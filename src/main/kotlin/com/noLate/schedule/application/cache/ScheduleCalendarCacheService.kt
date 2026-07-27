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
    private val revisionService: ScheduleCalendarCacheRevisionService,
    private val objectMapper: ObjectMapper,
    private val properties: ScheduleCalendarCacheProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val seoulZone = ZoneId.of("Asia/Seoul")
    private val listType = object : TypeReference<List<ScheduleDto>>() {}

    fun getOrLoad(
        memberId: Long,
        scope: ScheduleCalendarCacheScope,
        rangeStart: Instant,
        rangeEnd: Instant,
        loader: (Instant, Instant) -> List<ScheduleDto>,
    ): List<ScheduleDto> {
        if (!properties.enabled) return loader(rangeStart, rangeEnd)

        return runCatching {
            getOrLoad(memberId, scope, rangeStart, rangeEnd, loader, retry = true)
        }.getOrElse { error ->
            log.warn(
                "Schedule calendar cache unavailable; falling back to DB. memberId={}, scope={}, error={}",
                memberId,
                scope,
                error.javaClass.simpleName,
            )
            loader(rangeStart, rangeEnd)
        }
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
        val revision = revisionService.currentRevision(memberId)
        val months = monthRanges(rangeStart, rangeEnd)
        val keys = months.associateWith { cacheKey(memberId, scope, revision, it.yearMonth) }
        val cachedJson = store.getAll(keys.values.toList())
        val cached = months.mapNotNull { month ->
            cachedJson[keys.getValue(month)]?.let { month to objectMapper.readValue(it, listType) }
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

        val loadedByMonth = mutableMapOf<MonthRange, List<ScheduleDto>>()
        missingGroups.forEach { group ->
            val loaded = loader(group.first().start, group.last().end)
            group.forEach { month ->
                loadedByMonth[month] = loaded.filter { overlaps(it, month.start, month.end) }
            }
        }

        val revisionAfterLoad = revisionService.currentRevision(memberId)
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

        val values = loadedByMonth.map { (month, items) ->
            keys.getValue(month) to objectMapper.writeValueAsString(items)
        }.toMap()
        store.putAll(values, properties.ttl)
        log.info(
            "Schedule calendar cache STORE memberId={}, scope={}, revision={}, months={}",
            memberId,
            scope,
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
}
