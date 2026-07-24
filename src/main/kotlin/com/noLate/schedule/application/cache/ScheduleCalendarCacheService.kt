package com.noLate.schedule.application.cache

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.schedule.domain.ScheduleDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

@Service
class ScheduleCalendarCacheService(
    private val store: ScheduleCalendarCacheStore,
    private val objectMapper: ObjectMapper,
    private val properties: ScheduleCalendarCacheProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val seoulZone = ZoneId.of("Asia/Seoul")
    private val listType = object : TypeReference<List<ScheduleDto>>() {}

    fun getOrLoad(
        memberId: Long,
        rangeStart: Instant,
        rangeEnd: Instant,
        loader: (Instant, Instant) -> List<ScheduleDto>,
    ): List<ScheduleDto> {
        if (!properties.enabled) return loader(rangeStart, rangeEnd)

        return runCatching {
            getOrLoad(memberId, rangeStart, rangeEnd, loader, retry = true)
        }.getOrElse { error ->
            log.warn(
                "Schedule calendar cache unavailable; falling back to DB. memberId={}, error={}",
                memberId,
                error.javaClass.simpleName,
            )
            loader(rangeStart, rangeEnd)
        }
    }

    fun currentRevision(memberId: Long): Long {
        if (!properties.enabled) return 0L
        return runCatching { store.getRevision(memberId) }
            .getOrElse { error ->
                log.warn(
                    "Schedule calendar cache revision unavailable. memberId={}, error={}",
                    memberId,
                    error.javaClass.simpleName,
                )
                0L
            }
    }

    fun invalidateMembers(memberIds: Collection<Long>, reason: String) {
        if (!properties.enabled) return
        memberIds.distinct().forEach { memberId ->
            runCatching {
                store.incrementRevision(memberId, properties.revisionTtl)
            }.onSuccess { revision ->
                log.info(
                    "Schedule calendar cache INVALIDATE memberId={}, revision={}, reason={}",
                    memberId,
                    revision,
                    reason,
                )
            }.onFailure { error ->
                // 변경 트랜잭션은 이미 커밋됐다. Redis 장애가 API 성공을 뒤집지 않도록 로그만 남긴다.
                log.error(
                    "Schedule calendar cache invalidation failed. memberId={}, reason={}",
                    memberId,
                    reason,
                    error,
                )
            }
        }
    }

    private fun getOrLoad(
        memberId: Long,
        rangeStart: Instant,
        rangeEnd: Instant,
        loader: (Instant, Instant) -> List<ScheduleDto>,
        retry: Boolean,
    ): List<ScheduleDto> {
        val revision = store.getRevision(memberId)
        val months = monthRanges(rangeStart, rangeEnd)
        val keys = months.associateWith { cacheKey(memberId, revision, it.yearMonth) }
        val cachedJson = store.getAll(keys.values.toList())
        val cached = months.mapNotNull { month ->
            cachedJson[keys.getValue(month)]?.let { month to objectMapper.readValue(it, listType) }
        }.toMap()
        val missingGroups = contiguousGroups(months.filterNot(cached::containsKey))

        if (missingGroups.isEmpty()) {
            log.info(
                "Schedule calendar cache HIT memberId={}, revision={}, months={}",
                memberId,
                revision,
                months.joinToString(",") { it.yearMonth.toString() },
            )
            return mergeAndFilter(months.flatMap { cached.getValue(it) }, rangeStart, rangeEnd)
        }

        log.info(
            "Schedule calendar cache MISS memberId={}, revision={}, missingMonths={}",
            memberId,
            revision,
            missingGroups.flatten().joinToString(",") { it.yearMonth.toString() },
        )

        val loadedByMonth = mutableMapOf<MonthRange, List<ScheduleDto>>()
        missingGroups.forEach { group ->
            val loaded = loader(group.first().start, group.last().end)
            group.forEach { month ->
                loadedByMonth[month] = loaded.filter { overlaps(it, month.start, month.end) }
            }
        }

        val revisionAfterLoad = store.getRevision(memberId)
        if (revisionAfterLoad != revision) {
            log.info(
                "Schedule calendar cache fill skipped after revision change. memberId={}, before={}, after={}",
                memberId,
                revision,
                revisionAfterLoad,
            )
            return if (retry) {
                getOrLoad(memberId, rangeStart, rangeEnd, loader, retry = false)
            } else {
                loader(rangeStart, rangeEnd)
            }
        }

        val values = loadedByMonth.map { (month, items) ->
            keys.getValue(month) to objectMapper.writeValueAsString(items)
        }.toMap()
        store.putAll(values, properties.ttl)
        log.info(
            "Schedule calendar cache STORE memberId={}, revision={}, months={}",
            memberId,
            revision,
            loadedByMonth.keys.joinToString(",") { it.yearMonth.toString() },
        )

        return mergeAndFilter(
            months.flatMap { month -> cached[month] ?: loadedByMonth[month].orEmpty() },
            rangeStart,
            rangeEnd,
        )
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

    private fun cacheKey(memberId: Long, revision: Long, month: YearMonth): String =
        "nolate:schedules:v1:member:$memberId:rev:$revision:month:$month"

    private data class MonthRange(
        val yearMonth: YearMonth,
        val start: Instant,
        val end: Instant,
    )
}
