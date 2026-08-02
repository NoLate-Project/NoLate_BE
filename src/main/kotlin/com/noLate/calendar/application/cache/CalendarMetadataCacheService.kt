package com.noLate.calendar.application.cache

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.calendar.application.CalendarMetadataService
import com.noLate.calendar.domain.CalendarDayDto
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock

data class CalendarMetadataCacheLoad(
    val days: List<CalendarDayDto>,
    val cacheableMonths: Set<YearMonth>,
)

@Service
class CalendarMetadataCacheService(
    private val store: CalendarMetadataCacheStore,
    private val objectMapper: ObjectMapper,
    private val properties: CalendarMetadataCacheProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val listType = object : TypeReference<List<CalendarDayDto>>() {}
    private val fillLocks = Array(FILL_LOCK_STRIPES) { ReentrantLock() }

    fun getOrLoad(
        startDate: LocalDate,
        endDate: LocalDate,
        loader: (LocalDate, LocalDate) -> List<CalendarDayDto>,
    ): List<CalendarDayDto> = getOrLoadSnapshot(startDate, endDate) { loadStart, loadEnd ->
        CalendarMetadataCacheLoad(
            days = loader(loadStart, loadEnd),
            cacheableMonths = monthsBetween(loadStart, loadEnd).toSet(),
        )
    }

    fun getOrLoadSnapshot(
        startDate: LocalDate,
        endDate: LocalDate,
        loader: (LocalDate, LocalDate) -> CalendarMetadataCacheLoad,
    ): List<CalendarDayDto> {
        validateRange(startDate, endDate)
        if (!properties.enabled) return loader(startDate, endDate).days

        val months = monthsBetween(startDate, endDate)
        val keys = months.associateWith(::cacheKey)
        val firstRead = readCachedMonthsOrNull(months, keys)
            ?: return loader(startDate, endDate).days
        if (firstRead.size == months.size) {
            log.info(
                "Calendar metadata cache HIT months={}",
                months.joinToString(","),
            )
            return filterRange(months.flatMap(firstRead::getValue), startDate, endDate)
        }
        val initiallyMissingMonths = months.filterNot(firstRead::containsKey)

        return withMonthLocks(initiallyMissingMonths) {
            // 같은 인스턴스의 선행 요청이 적재했을 수 있으므로 lock 획득 후 다시 확인한다.
            val cachedByMonth = readCachedMonthsOrNull(months, keys)
                ?: return@withMonthLocks loader(startDate, endDate).days
            val missingMonths = months.filterNot(cachedByMonth::containsKey)
            if (missingMonths.isEmpty()) {
                log.info(
                    "Calendar metadata cache HIT months={}",
                    months.joinToString(","),
                )
                return@withMonthLocks filterRange(
                    months.flatMap(cachedByMonth::getValue),
                    startDate,
                    endDate,
                )
            }

            log.info(
                "Calendar metadata cache MISS missingMonths={}",
                missingMonths.joinToString(","),
            )
            val loaded = loadMonths(missingMonths, loader)
            storeLoadedMonths(
                loadedByMonth = loaded.daysByMonth,
                cacheableMonths = loaded.cacheableMonths,
                keys = keys,
            )

            filterRange(
                months.flatMap { month ->
                    cachedByMonth[month] ?: loaded.daysByMonth[month].orEmpty()
                },
                startDate,
                endDate,
            )
        }
    }

    /**
     * Rebuilds only months whose DB refresh completed. Ready snapshots overwrite the old value in
     * one Redis SET; an incomplete placeholder is deleted rather than being retained for the TTL.
     */
    fun refillMonths(
        months: Collection<YearMonth>,
        loader: (LocalDate, LocalDate) -> CalendarMetadataCacheLoad,
    ) {
        if (!properties.enabled) return
        val targetMonths = months.distinct().sorted()
        if (targetMonths.isEmpty()) return

        withMonthLocks(targetMonths) {
            val keys = targetMonths.associateWith(::cacheKey)
            val loaded = loadMonths(targetMonths, loader)
            val incompleteMonths = targetMonths.minus(loaded.cacheableMonths)

            if (incompleteMonths.isNotEmpty()) {
                runCatching {
                    store.deleteAll(incompleteMonths.map(keys::getValue))
                }.onFailure { error ->
                    log.warn(
                        "Calendar metadata cache invalidation failed. error={}",
                        error.javaClass.simpleName,
                    )
                }
            }
            storeLoadedMonths(
                loadedByMonth = loaded.daysByMonth,
                cacheableMonths = loaded.cacheableMonths,
                keys = keys,
            )
        }
    }

    private fun <T> withMonthLocks(
        months: List<YearMonth>,
        block: () -> T,
    ): T {
        val locks = months
            .map { month -> Math.floorMod(month.hashCode(), fillLocks.size) }
            .distinct()
            .sorted()
            .map(fillLocks::get)
        locks.forEach(ReentrantLock::lock)
        return try {
            block()
        } finally {
            locks.asReversed().forEach(ReentrantLock::unlock)
        }
    }

    private fun readCachedMonthsOrNull(
        months: List<YearMonth>,
        keys: Map<YearMonth, String>,
    ): Map<YearMonth, List<CalendarDayDto>>? = runCatching {
        val cachedJson = store.getAll(keys.values.toList())
        val corruptedKeys = mutableListOf<String>()
        val cachedByMonth = months.mapNotNull { month ->
            val key = keys.getValue(month)
            val json = cachedJson[key] ?: return@mapNotNull null
            runCatching { deserializeMonth(month, json) }
                .onFailure { corruptedKeys += key }
                .getOrNull()
                ?.let { month to it }
        }.toMap()
        if (corruptedKeys.isNotEmpty()) {
            runCatching { store.deleteAll(corruptedKeys) }
        }
        cachedByMonth
    }.getOrElse { error ->
        log.warn(
            "Calendar metadata cache unavailable; falling back to DB. error={}",
            error.javaClass.simpleName,
        )
        null
    }

    private fun loadMonths(
        months: List<YearMonth>,
        loader: (LocalDate, LocalDate) -> CalendarMetadataCacheLoad,
    ): LoadedMonths {
        val daysByMonth = mutableMapOf<YearMonth, List<CalendarDayDto>>()
        val cacheableMonths = mutableSetOf<YearMonth>()
        groupLoadableMonths(months).forEach { group ->
            val loaded = loader(group.first().atDay(1), group.last().atEndOfMonth())
            group.forEach { month ->
                daysByMonth[month] = loaded.days.filter { day ->
                    runCatching {
                        YearMonth.from(LocalDate.parse(day.date)) == month
                    }.getOrDefault(false)
                }
                if (month in loaded.cacheableMonths) cacheableMonths += month
            }
        }
        return LoadedMonths(
            daysByMonth = daysByMonth,
            cacheableMonths = cacheableMonths,
        )
    }

    private fun groupLoadableMonths(months: List<YearMonth>): List<List<YearMonth>> {
        val groups = mutableListOf<MutableList<YearMonth>>()
        months.sorted().forEach { month ->
            val current = groups.lastOrNull()
            if (
                current != null
                && current.last().plusMonths(1) == month
                && ChronoUnit.DAYS.between(
                    current.first().atDay(1),
                    month.atEndOfMonth(),
                ) + 1 <= CalendarMetadataService.MAX_RANGE_DAYS
            ) {
                current.add(month)
            } else {
                groups += mutableListOf(month)
            }
        }
        return groups
    }

    private fun storeLoadedMonths(
        loadedByMonth: Map<YearMonth, List<CalendarDayDto>>,
        cacheableMonths: Set<YearMonth>,
        keys: Map<YearMonth, String>,
    ) {
        val completeMonths = loadedByMonth.filter { (month, days) ->
            month in cacheableMonths && isCacheReadyMonth(month, days)
        }
        if (completeMonths.size != loadedByMonth.size) {
            log.warn(
                "Calendar metadata cache skipped incomplete months. months={}",
                loadedByMonth.keys.minus(completeMonths.keys).joinToString(","),
            )
        }
        if (completeMonths.isEmpty()) return

        runCatching {
            val serialized = completeMonths.map { (month, days) ->
                keys.getValue(month) to objectMapper.writeValueAsString(days)
            }.toMap()
            store.putAll(serialized, properties.ttl)
        }.onSuccess {
            log.info(
                "Calendar metadata cache STORE months={}",
                completeMonths.keys.joinToString(","),
            )
        }.onFailure { error ->
            log.warn(
                "Calendar metadata cache store failed; returning loaded data. error={}",
                error.javaClass.simpleName,
            )
        }
    }

    private fun deserializeMonth(month: YearMonth, json: String): List<CalendarDayDto> {
        val days = objectMapper.readValue(json, listType)
        require(isCacheReadyMonth(month, days))
        return days
    }

    private fun isCacheReadyMonth(month: YearMonth, days: List<CalendarDayDto>): Boolean {
        if (days.size != month.lengthOfMonth()) return false
        val dates = runCatching { days.map { LocalDate.parse(it.date) } }.getOrNull()
            ?: return false
        return dates.toSet().size == month.lengthOfMonth()
            && dates.all { YearMonth.from(it) == month }
            && days.all { day ->
                day.metadataComplete &&
                day.lunarYear != null &&
                    day.lunarMonth != null &&
                    day.lunarDay != null &&
                    day.leapMonth != null
            }
    }

    private fun validateRange(startDate: LocalDate, endDate: LocalDate) {
        if (endDate.isBefore(startDate)) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "endDate는 startDate와 같거나 이후여야 합니다.",
            )
        }
        val inclusiveDayCount = ChronoUnit.DAYS.between(startDate, endDate) + 1
        if (inclusiveDayCount > CalendarMetadataService.MAX_RANGE_DAYS) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "캘린더 조회 범위는 최대 ${CalendarMetadataService.MAX_RANGE_DAYS}일입니다.",
            )
        }
    }

    private fun monthsBetween(startDate: LocalDate, endDate: LocalDate): List<YearMonth> {
        val first = YearMonth.from(startDate)
        val last = YearMonth.from(endDate)
        return generateSequence(first) { current ->
            current.plusMonths(1).takeUnless { it > last }
        }.toList()
    }

    private fun filterRange(
        days: List<CalendarDayDto>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<CalendarDayDto> = days
        .filter { day ->
            val date = LocalDate.parse(day.date)
            !date.isBefore(startDate) && !date.isAfter(endDate)
        }
        .distinctBy(CalendarDayDto::date)
        .sortedBy(CalendarDayDto::date)

    private fun cacheKey(month: YearMonth): String =
        "nolate:calendar:metadata:v2:month:$month"

    private data class LoadedMonths(
        val daysByMonth: Map<YearMonth, List<CalendarDayDto>>,
        val cacheableMonths: Set<YearMonth>,
    )

    private companion object {
        const val FILL_LOCK_STRIPES = 64
    }
}
