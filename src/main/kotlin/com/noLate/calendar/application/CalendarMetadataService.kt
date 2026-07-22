package com.noLate.calendar.application

import com.noLate.calendar.domain.CalendarDayCache
import com.noLate.calendar.domain.CalendarDayDto
import com.noLate.calendar.domain.CalendarHolidayDto
import com.noLate.calendar.infrastructure.CalendarDayCacheRepository
import com.noLate.calendar.infrastructure.KasiCalendarClient
import com.noLate.calendar.infrastructure.KasiHoliday
import com.noLate.calendar.infrastructure.KasiLunarDay
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    fun getDays(startDate: LocalDate, endDate: LocalDate): List<CalendarDayDto> {
        validateRange(startDate, endDate)
        refreshCacheWhenNeeded(startDate, endDate)

        val dayCaches = calendarDayCacheRepository
            .findAllByDateBetweenOrderByDateAsc(startDate, endDate)
            .associateBy { it.date }
        val holidaysByDate = publicHolidayRepository
            .findAllByHolidayDateBetweenOrderByHolidayDateAscIdAsc(startDate, endDate)
            .groupBy { it.holidayDate }

        return startDate.datesUntil(endDate.plusDays(1)).map { date ->
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
            )
        }.toList()
    }

    private fun refreshCacheWhenNeeded(startDate: LocalDate, endDate: LocalDate) {
        if (!kasiCalendarClient.isAvailable()) return

        val now = LocalDateTime.now(clock.withZone(SEOUL_ZONE))
        val staleBefore = now.minusHours(cacheTtlHours)
        val cachedByDate = calendarDayCacheRepository
            .findAllByDateBetweenOrderByDateAsc(startDate, endDate)
            .associateBy { it.date }

        val refreshPlans = monthsBetween(startDate, endDate).map { month ->
            val requestedDates = requestedDatesInMonth(month, startDate, endDate)
            MonthRefreshPlan(
                month = month,
                lunar = requestedDates.any { date ->
                    cachedByDate[date].isLunarStale(staleBefore)
                },
                holidays = requestedDates.any { date ->
                    cachedByDate[date].isHolidayStale(staleBefore)
                },
            )
        }

        // 월간 달력은 앞뒤 주 때문에 보통 2~3개 월에 걸친다. KASI 호출을 순차 실행하면
        // 최초 조회가 앱의 10초 HTTP timeout을 넘을 수 있으므로 네트워크 I/O만 병렬화하고,
        // 같은 캐시 row를 갱신하는 DB 쓰기는 아래에서 순차 실행한다.
        val fetches = refreshPlans.map { plan ->
            MonthRefreshFetch(
                month = plan.month,
                lunar = plan.lunar.takeIf { it }?.let {
                    CompletableFuture.supplyAsync(
                        { runCatching { kasiCalendarClient.fetchLunarMonth(plan.month) } },
                        refreshExecutor,
                    )
                },
                holidays = plan.holidays.takeIf { it }?.let {
                    CompletableFuture.supplyAsync(
                        { runCatching { kasiCalendarClient.fetchHolidayMonth(plan.month) } },
                        refreshExecutor,
                    )
                },
            )
        }

        fetches.forEach { fetch ->
            fetch.lunar?.join()?.fold(
                onSuccess = { lunarDays ->
                    runCatching {
                        calendarCacheWriter.storeLunarMonth(lunarDays, now)
                    }.onFailure { exception -> logRefreshFailure("lunar", fetch.month, exception) }
                },
                onFailure = { exception -> logRefreshFailure("lunar", fetch.month, exception) },
            )
            fetch.holidays?.join()?.fold(
                onSuccess = { holidays ->
                    runCatching {
                        calendarCacheWriter.replaceHolidayMonth(fetch.month, holidays, now)
                    }.onFailure { exception -> logRefreshFailure("holiday", fetch.month, exception) }
                },
                onFailure = { exception -> logRefreshFailure("holiday", fetch.month, exception) },
            )
        }
    }

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

    companion object {
        const val MAX_RANGE_DAYS = 93L
        private val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }

    private data class MonthRefreshPlan(
        val month: YearMonth,
        val lunar: Boolean,
        val holidays: Boolean,
    )

    private data class MonthRefreshFetch(
        val month: YearMonth,
        val lunar: CompletableFuture<Result<List<KasiLunarDay>>>?,
        val holidays: CompletableFuture<Result<List<KasiHoliday>>>?,
    )
}
