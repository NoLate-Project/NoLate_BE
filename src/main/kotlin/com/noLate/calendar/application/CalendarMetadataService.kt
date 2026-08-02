package com.noLate.calendar.application

import com.noLate.calendar.domain.CalendarDayCache
import com.noLate.calendar.domain.CalendarDayDto
import com.noLate.calendar.domain.CalendarHolidayDto
import com.noLate.calendar.infrastructure.CalendarDayCacheRepository
import com.noLate.calendar.infrastructure.KasiCalendarClient
import com.noLate.calendar.infrastructure.PublicHolidayRepository
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class CalendarMetadataSnapshot(
    val days: List<CalendarDayDto>,
    val cacheableMonths: Set<YearMonth>,
    val refreshPlans: List<CalendarMetadataRefreshPlan>,
)

data class CalendarMetadataRefreshPlan(
    val month: YearMonth,
    val lunar: Boolean,
    val holidays: Boolean,
)

@Service
class CalendarMetadataService(
    private val calendarDayCacheRepository: CalendarDayCacheRepository,
    private val publicHolidayRepository: PublicHolidayRepository,
    private val kasiCalendarClient: KasiCalendarClient,
    private val calendarCacheWriter: CalendarCacheWriter,
    private val clock: Clock,
    @Value("\${calendar.kasi.cache-ttl-hours:168}") cacheTtlHours: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cacheTtlHours = cacheTtlHours.coerceAtLeast(1)
    private val refreshExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val inFlightRefreshes =
        ConcurrentHashMap<RefreshKey, CompletableFuture<Boolean>>()
    private val monthWriteLocks = Array(MONTH_WRITE_LOCK_STRIPES) { ReentrantLock() }

    /**
     * Direct callers get the current DB snapshot immediately. KASI is only scheduled after the
     * snapshot has been built, and is never joined on the request thread.
     *
     * The HTTP query path uses [loadCurrentSnapshot] and schedules refresh only after its Redis
     * read/fill has completed, which prevents a cold placeholder from racing a completed refill.
     */
    fun getDays(startDate: LocalDate, endDate: LocalDate): List<CalendarDayDto> {
        val snapshot = loadCurrentSnapshot(startDate, endDate)
        refreshCacheAsync(snapshot.refreshPlans)
        return snapshot.days
    }

    fun loadCurrentSnapshot(
        startDate: LocalDate,
        endDate: LocalDate,
    ): CalendarMetadataSnapshot {
        validateRange(startDate, endDate)

        val dayCaches = calendarDayCacheRepository
            .findAllByDateBetweenOrderByDateAsc(startDate, endDate)
            .associateBy { it.date }
        val holidaysByDate = publicHolidayRepository
            .findAllByHolidayDateBetweenOrderByHolidayDateAscIdAsc(startDate, endDate)
            .groupBy { it.holidayDate }
        val months = monthsBetween(startDate, endDate)
        val refreshPlans = buildRefreshPlans(
            months = months,
            startDate = startDate,
            endDate = endDate,
            cachedByDate = dayCaches,
        )

        val days = startDate.datesUntil(endDate.plusDays(1)).map { date ->
            val cache = dayCaches[date]
            CalendarDayDto(
                date = date.toString(),
                lunarYear = cache?.lunarYear,
                lunarMonth = cache?.lunarMonth,
                lunarDay = cache?.lunarDay,
                leapMonth = cache?.leapMonth,
                holidays = holidaysByDate[date]
                    .orEmpty()
                    .map { holiday ->
                        CalendarHolidayDto(
                            name = holiday.name,
                            type = holiday.type,
                        )
                    },
                metadataComplete = cache.isMetadataComplete(),
            )
        }.toList()

        return CalendarMetadataSnapshot(
            days = days,
            cacheableMonths = months
                .filterTo(mutableSetOf()) { month ->
                    isFullyLoadedMonth(
                        month = month,
                        startDate = startDate,
                        endDate = endDate,
                        cachedByDate = dayCaches,
                    )
                },
            refreshPlans = refreshPlans,
        )
    }

    /**
     * Starts one refresh per month/kind for this application instance. Concurrent callers reuse
     * the same future, so a swipe burst cannot fan out duplicate KASI requests.
     */
    fun refreshCacheAsync(
        plans: Collection<CalendarMetadataRefreshPlan>,
        onMonthsRefreshed: (Set<YearMonth>) -> Unit = {},
    ) {
        if (!kasiCalendarClient.isAvailable()) return

        val combinedPlans = plans
            .groupBy(CalendarMetadataRefreshPlan::month)
            .map { (month, monthPlans) ->
                CalendarMetadataRefreshPlan(
                    month = month,
                    lunar = monthPlans.any(CalendarMetadataRefreshPlan::lunar),
                    holidays = monthPlans.any(CalendarMetadataRefreshPlan::holidays),
                )
            }
            .filter { it.lunar || it.holidays }
        if (combinedPlans.isEmpty()) return

        val refreshesByMonth = combinedPlans.associate { plan ->
            plan.month to buildList {
                if (plan.lunar) add(refreshSingleFlight(RefreshKey(plan.month, RefreshKind.LUNAR)))
                if (plan.holidays) {
                    add(refreshSingleFlight(RefreshKey(plan.month, RefreshKind.HOLIDAYS)))
                }
            }
        }
        val allRefreshes = refreshesByMonth.values.flatten()

        CompletableFuture.allOf(*allRefreshes.toTypedArray()).whenCompleteAsync(
            { _, _ ->
                val refreshedMonths = refreshesByMonth
                    .filterValues { refreshes ->
                        refreshes.any { refresh ->
                            runCatching { refresh.getNow(false) }.getOrDefault(false)
                        }
                    }
                    .keys
                if (refreshedMonths.isNotEmpty()) {
                    runCatching { onMonthsRefreshed(refreshedMonths) }
                        .onFailure { exception ->
                            log.warn(
                                "Calendar metadata Redis refill failed ({})",
                                exception.javaClass.simpleName,
                            )
                        }
                }
            },
            refreshExecutor,
        )
    }

    private fun buildRefreshPlans(
        months: List<YearMonth>,
        startDate: LocalDate,
        endDate: LocalDate,
        cachedByDate: Map<LocalDate, CalendarDayCache>,
    ): List<CalendarMetadataRefreshPlan> {
        if (!kasiCalendarClient.isAvailable()) return emptyList()

        val now = LocalDateTime.now(clock.withZone(SEOUL_ZONE))
        val staleBefore = now.minusHours(cacheTtlHours)
        return months.map { month ->
            val requestedDates = requestedDatesInMonth(month, startDate, endDate)
            CalendarMetadataRefreshPlan(
                month = month,
                lunar = requestedDates.any { date ->
                    cachedByDate[date].isLunarStale(staleBefore)
                },
                holidays = requestedDates.any { date ->
                    cachedByDate[date].isHolidayStale(staleBefore)
                },
            )
        }
    }

    private fun refreshSingleFlight(key: RefreshKey): CompletableFuture<Boolean> {
        val promise = CompletableFuture<Boolean>()
        val existing = inFlightRefreshes.putIfAbsent(key, promise)
        if (existing != null) return existing

        promise.whenComplete { _, _ -> inFlightRefreshes.remove(key, promise) }
        runCatching {
            refreshExecutor.submit {
                val refreshed = runCatching { refreshMonthKind(key) }
                    .onFailure { exception ->
                        logRefreshFailure(key.kind.logName, key.month, exception)
                    }
                    .isSuccess
                promise.complete(refreshed)
            }
        }.onFailure { exception ->
            logRefreshFailure(key.kind.logName, key.month, exception)
            promise.complete(false)
        }
        return promise
    }

    private fun refreshMonthKind(key: RefreshKey) {
        when (key.kind) {
            RefreshKind.LUNAR -> {
                val lunarDays = kasiCalendarClient.fetchLunarMonth(key.month)
                val syncedAt = LocalDateTime.now(clock.withZone(SEOUL_ZONE))
                monthWriteLock(key.month).withLock {
                    calendarCacheWriter.storeLunarMonth(lunarDays, syncedAt)
                }
            }
            RefreshKind.HOLIDAYS -> {
                val holidays = kasiCalendarClient.fetchHolidayMonth(key.month)
                val syncedAt = LocalDateTime.now(clock.withZone(SEOUL_ZONE))
                monthWriteLock(key.month).withLock {
                    calendarCacheWriter.replaceHolidayMonth(key.month, holidays, syncedAt)
                }
            }
        }
    }

    private fun isFullyLoadedMonth(
        month: YearMonth,
        startDate: LocalDate,
        endDate: LocalDate,
        cachedByDate: Map<LocalDate, CalendarDayCache>,
    ): Boolean {
        if (startDate > month.atDay(1) || endDate < month.atEndOfMonth()) return false

        return (1..month.lengthOfMonth()).all { dayOfMonth ->
            val cached = cachedByDate[month.atDay(dayOfMonth)] ?: return@all false
            cached.lunarYear != null &&
                cached.lunarMonth != null &&
                cached.lunarDay != null &&
                cached.leapMonth != null &&
                cached.lunarSyncedAt != null &&
                cached.holidaysSyncedAt != null
        }
    }

    private fun monthWriteLock(month: YearMonth): ReentrantLock =
        monthWriteLocks[Math.floorMod(month.hashCode(), monthWriteLocks.size)]

    private fun logRefreshFailure(kind: String, month: YearMonth, exception: Throwable) {
        // 외부 요청 예외에는 인증키가 포함될 수 있으므로 메시지/스택을 남기지 않는다.
        log.warn(
            "KASI {} month refresh failed for {} ({})",
            kind,
            month,
            exception.javaClass.simpleName,
        )
    }

    @PreDestroy
    fun closeRefreshExecutor() {
        refreshExecutor.shutdownNow()
    }

    private fun validateRange(startDate: LocalDate, endDate: LocalDate) {
        if (endDate.isBefore(startDate)) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "endDate는 startDate와 같거나 이후여야 합니다.",
            )
        }
        val inclusiveDayCount = ChronoUnit.DAYS.between(startDate, endDate) + 1
        if (inclusiveDayCount > MAX_RANGE_DAYS) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "캘린더 조회 범위는 최대 ${MAX_RANGE_DAYS}일입니다.",
            )
        }
    }

    private fun monthsBetween(startDate: LocalDate, endDate: LocalDate): List<YearMonth> {
        val first = YearMonth.from(startDate)
        val last = YearMonth.from(endDate)
        return generateSequence(first) { month -> month.plusMonths(1) }
            .takeWhile { month -> month <= last }
            .toList()
    }

    private fun requestedDatesInMonth(
        month: YearMonth,
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<LocalDate> {
        val first = maxOf(month.atDay(1), startDate)
        val last = minOf(month.atEndOfMonth(), endDate)
        return first.datesUntil(last.plusDays(1)).toList()
    }

    private fun CalendarDayCache?.isLunarStale(staleBefore: LocalDateTime): Boolean =
        this?.lunarSyncedAt?.isBefore(staleBefore) != false

    private fun CalendarDayCache?.isHolidayStale(staleBefore: LocalDateTime): Boolean =
        this?.holidaysSyncedAt?.isBefore(staleBefore) != false

    private fun CalendarDayCache?.isMetadataComplete(): Boolean =
        this?.lunarYear != null &&
            lunarMonth != null &&
            lunarDay != null &&
            leapMonth != null &&
            lunarSyncedAt != null &&
            holidaysSyncedAt != null

    companion object {
        // FE가 이전·현재·다음 달과 각 월의 바깥 주를 한 번에 요청할 때의 최대 범위다.
        const val MAX_RANGE_DAYS = 98L
        private val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private const val MONTH_WRITE_LOCK_STRIPES = 64
    }

    private data class RefreshKey(
        val month: YearMonth,
        val kind: RefreshKind,
    )

    private enum class RefreshKind(val logName: String) {
        LUNAR("lunar"),
        HOLIDAYS("holiday"),
    }
}
