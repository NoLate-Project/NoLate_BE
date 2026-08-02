package com.noLate.schedule.application.cache

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.schedule.domain.ScheduleDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.CompletableFuture

@Service
class ScheduleCalendarCacheService(
    private val store: ScheduleCalendarCacheStore,
    private val revisionService: ScheduleCalendarCacheRevisionService,
    private val objectMapper: ObjectMapper,
    private val properties: ScheduleCalendarCacheProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val seoulZone = ZoneId.of("Asia/Seoul")
    private val listType = object : TypeReference<List<ScheduleDto>>() {}
    private val fillClaimMonitor = Any()
    private val inFlightByMonth = mutableMapOf<MonthFillKey, CompletableFuture<MonthFillOutcome>>()

    fun getOrLoad(
        memberId: Long,
        scope: ScheduleCalendarCacheScope,
        rangeStart: Instant,
        rangeEnd: Instant,
        loader: (Instant, Instant) -> List<ScheduleDto>,
    ): List<ScheduleDto> {
        if (!properties.enabled) return loader(rangeStart, rangeEnd)

        return getOrLoad(memberId, scope, rangeStart, rangeEnd, loader, retry = true)
    }

    private fun loadWithoutCache(
        memberId: Long,
        scope: ScheduleCalendarCacheScope,
        rangeStart: Instant,
        rangeEnd: Instant,
        loader: (Instant, Instant) -> List<ScheduleDto>,
        error: Exception,
    ): List<ScheduleDto> {
        log.warn(
            "Schedule calendar cache unavailable; falling back to DB. memberId={}, scope={}, error={}",
            memberId,
            scope,
            error.javaClass.simpleName,
        )
        return loader(rangeStart, rangeEnd)
    }

    fun currentRevision(memberId: Long, scope: ScheduleCalendarCacheScope): Long =
        scope.clientRevision(revisionService.currentRevision(memberId))

    private fun getOrLoad(
        memberId: Long,
        scope: ScheduleCalendarCacheScope,
        rangeStart: Instant,
        rangeEnd: Instant,
        loader: (Instant, Instant) -> List<ScheduleDto>,
        retry: Boolean,
    ): List<ScheduleDto> {
        val revision = try {
            revisionService.currentRevision(memberId)
        } catch (error: Exception) {
            return loadWithoutCache(memberId, scope, rangeStart, rangeEnd, loader, error)
        }
        val months = monthRanges(rangeStart, rangeEnd)
        val keys = months.associateWith { cacheKey(memberId, scope, revision, it.yearMonth) }
        val cachedJson = try {
            store.getAll(keys.values.toList())
        } catch (error: Exception) {
            log.warn(
                "Schedule calendar cache read failed; treating requested months as misses. " +
                    "memberId={}, scope={}, revision={}, error={}",
                memberId,
                scope,
                revision,
                error.javaClass.simpleName,
            )
            emptyMap()
        }
        val cached = months.mapNotNull { month ->
            val json = cachedJson[keys.getValue(month)] ?: return@mapNotNull null
            val items = try {
                objectMapper.readValue(json, listType)
            } catch (error: Exception) {
                log.warn(
                    "Schedule calendar cache entry is unreadable; reloading month. " +
                        "memberId={}, scope={}, revision={}, month={}, error={}",
                    memberId,
                    scope,
                    revision,
                    month.yearMonth,
                    error.javaClass.simpleName,
                )
                null
            }
            items?.let { month to it }
        }.toMap()
        val missingGroups = contiguousGroups(months.filterNot(cached::containsKey))

        if (missingGroups.isEmpty()) {
            log.info(
                "Schedule calendar cache HIT memberId={}, scope={}, revision={}, months={}",
                memberId,
                scope,
                revision,
                months.joinToString(",") { it.yearMonth.toString() },
            )
            return mergeAndFilter(months.flatMap { cached.getValue(it) }, rangeStart, rangeEnd)
        }

        log.info(
            "Schedule calendar cache MISS memberId={}, scope={}, revision={}, missingMonths={}",
            memberId,
            scope,
            revision,
            missingGroups.flatten().joinToString(",") { it.yearMonth.toString() },
        )

        val loadedByMonth = missingGroups
            .flatMap { group ->
                loadMissingGroupSingleFlight(
                    memberId = memberId,
                    scope = scope,
                    revision = revision,
                    group = group,
                    loader = loader,
                ).entries
            }
            .associate { it.toPair() }

        val revisionAfterLoad = try {
            revisionService.currentRevision(memberId)
        } catch (error: Exception) {
            log.warn(
                "Schedule calendar cache revision recheck failed after DB load; " +
                    "returning loaded data without another DB query. memberId={}, scope={}, error={}",
                memberId,
                scope,
                error.javaClass.simpleName,
            )
            return mergeAndFilter(
                months.flatMap { month -> cached[month] ?: loadedByMonth[month].orEmpty() },
                rangeStart,
                rangeEnd,
            )
        }
        if (revisionAfterLoad != revision) {
            log.info(
                "Schedule calendar cache fill skipped after revision change. memberId={}, scope={}, before={}, after={}",
                memberId,
                scope,
                revision,
                revisionAfterLoad,
            )
            return if (retry) {
                getOrLoad(memberId, scope, rangeStart, rangeEnd, loader, retry = false)
            } else {
                loader(rangeStart, rangeEnd)
            }
        }

        return mergeAndFilter(
            months.flatMap { month -> cached[month] ?: loadedByMonth[month].orEmpty() },
            rangeStart,
            rangeEnd,
        )
    }

    /**
     * Claims every missing month under one monitor so identical multi-month requests elect a single
     * loader. A future is kept per month so partially overlapping ranges can share the common months
     * while each leader still batches its own contiguous months into one DB call.
     *
     * Cache serialization and writes are best-effort after the DB result exists. Followers receive
     * that same result even when Redis is unavailable, and every claim is removed on both success and
     * failure so a stuck entry cannot leak after the leader completes.
     */
    private fun loadMissingGroupSingleFlight(
        memberId: Long,
        scope: ScheduleCalendarCacheScope,
        revision: Long,
        group: List<MonthRange>,
        loader: (Instant, Instant) -> List<ScheduleDto>,
    ): Map<MonthRange, List<ScheduleDto>> {
        val claims = synchronized(fillClaimMonitor) {
            group.map { month ->
                val key = MonthFillKey(memberId, scope, revision, month.yearMonth)
                val existing = inFlightByMonth[key]
                if (existing != null) {
                    MonthFillClaim(month, key, existing, leader = false)
                } else {
                    val created = CompletableFuture<MonthFillOutcome>()
                    inFlightByMonth[key] = created
                    MonthFillClaim(month, key, created, leader = true)
                }
            }
        }
        val leaderClaims = claims.filter(MonthFillClaim::leader)
        val loadedByOwnedMonth = mutableMapOf<MonthRange, List<ScheduleDto>>()

        if (leaderClaims.isNotEmpty()) {
            try {
                var loadFailure: Exception? = null
                try {
                    contiguousGroups(leaderClaims.map(MonthFillClaim::month)).forEach { ownedGroup ->
                        val loaded = loader(ownedGroup.first().start, ownedGroup.last().end)
                        ownedGroup.forEach { month ->
                            loadedByOwnedMonth[month] = loaded.filter {
                                overlaps(it, month.start, month.end)
                            }
                        }
                    }
                    storeLoadedMonthsBestEffort(
                        memberId = memberId,
                        scope = scope,
                        revision = revision,
                        loadedByMonth = loadedByOwnedMonth,
                    )
                } catch (error: Exception) {
                    loadFailure = error
                }
                leaderClaims.forEach { claim ->
                    val outcome = loadFailure?.let(MonthFillOutcome::Failed)
                        ?: MonthFillOutcome.Loaded(loadedByOwnedMonth[claim.month].orEmpty())
                    claim.future.complete(outcome)
                }
                loadFailure?.let { throw it }
            } catch (fatal: Error) {
                leaderClaims.forEach { claim -> claim.future.completeExceptionally(fatal) }
                throw fatal
            } finally {
                synchronized(fillClaimMonitor) {
                    leaderClaims.forEach { claim ->
                        inFlightByMonth.remove(claim.key, claim.future)
                    }
                }
            }
        }

        return claims.associate { claim ->
            val outcome = if (claim.leader) {
                MonthFillOutcome.Loaded(loadedByOwnedMonth[claim.month].orEmpty())
            } else {
                claim.future.join()
            }
            val items = when (outcome) {
                is MonthFillOutcome.Loaded -> outcome.items
                is MonthFillOutcome.Failed -> throw outcome.error
            }
            claim.month to items
        }
    }

    private fun storeLoadedMonthsBestEffort(
        memberId: Long,
        scope: ScheduleCalendarCacheScope,
        revision: Long,
        loadedByMonth: Map<MonthRange, List<ScheduleDto>>,
    ) {
        if (loadedByMonth.isEmpty()) return

        try {
            val values = loadedByMonth.map { (month, items) ->
                cacheKey(memberId, scope, revision, month.yearMonth) to
                    objectMapper.writeValueAsString(items)
            }.toMap()
            store.putAll(values, properties.ttl)
            log.info(
                "Schedule calendar cache STORE memberId={}, scope={}, revision={}, months={}",
                memberId,
                scope,
                revision,
                loadedByMonth.keys.joinToString(",") { it.yearMonth.toString() },
            )
        } catch (error: Exception) {
            log.warn(
                "Schedule calendar cache store failed; returning the single loaded DB result. " +
                    "memberId={}, scope={}, revision={}, months={}, error={}",
                memberId,
                scope,
                revision,
                loadedByMonth.keys.joinToString(",") { it.yearMonth.toString() },
                error.javaClass.simpleName,
            )
        }
    }

    private fun monthRanges(rangeStart: Instant, rangeEnd: Instant): List<MonthRange> {
        val first = YearMonth.from(rangeStart.atZone(seoulZone))
        val last = YearMonth.from(rangeEnd.atZone(seoulZone))
        return generateSequence(first) { current ->
            current.plusMonths(1).takeUnless { it > last }
        }.map { yearMonth ->
            val start = yearMonth.atDay(1).atStartOfDay(seoulZone).toInstant()
            val end = yearMonth.plusMonths(1).atDay(1).atStartOfDay(seoulZone).toInstant().minusNanos(1)
            MonthRange(yearMonth, start, end)
        }.toList()
    }

    private fun contiguousGroups(months: List<MonthRange>): List<List<MonthRange>> {
        if (months.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<MonthRange>>()
        months.forEach { month ->
            val current = groups.lastOrNull()
            if (current == null || current.last().yearMonth.plusMonths(1) != month.yearMonth) {
                groups += mutableListOf(month)
            } else {
                current += month
            }
        }
        return groups
    }

    private fun mergeAndFilter(
        schedules: List<ScheduleDto>,
        rangeStart: Instant,
        rangeEnd: Instant,
    ): List<ScheduleDto> = schedules
        .filter { overlaps(it, rangeStart, rangeEnd) }
        .distinctBy { it.id }
        .sortedBy { parseInstant(it.startAt) }

    private fun overlaps(schedule: ScheduleDto, rangeStart: Instant, rangeEnd: Instant): Boolean {
        val start = parseInstant(schedule.startAt)
        val end = schedule.endAt?.let(::parseInstant) ?: start
        return !start.isAfter(rangeEnd) && !end.isBefore(rangeStart)
    }

    private fun parseInstant(value: String): Instant = Instant.parse(value)

    private fun cacheKey(
        memberId: Long,
        scope: ScheduleCalendarCacheScope,
        revision: Long,
        month: YearMonth,
    ): String =
        "nolate:schedules:v2:member:$memberId:scope:${scope.keySegment}:rev:$revision:month:$month"

    private data class MonthRange(
        val yearMonth: YearMonth,
        val start: Instant,
        val end: Instant,
    )

    private data class MonthFillKey(
        val memberId: Long,
        val scope: ScheduleCalendarCacheScope,
        val revision: Long,
        val yearMonth: YearMonth,
    )

    private data class MonthFillClaim(
        val month: MonthRange,
        val key: MonthFillKey,
        val future: CompletableFuture<MonthFillOutcome>,
        val leader: Boolean,
    )

    private sealed interface MonthFillOutcome {
        data class Loaded(val items: List<ScheduleDto>) : MonthFillOutcome
        data class Failed(val error: Exception) : MonthFillOutcome
    }
}
