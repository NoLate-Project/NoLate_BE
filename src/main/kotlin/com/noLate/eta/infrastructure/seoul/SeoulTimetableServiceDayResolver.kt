package com.noLate.eta.infrastructure.seoul

import com.noLate.calendar.domain.PublicHoliday
import com.noLate.calendar.infrastructure.CalendarDayCacheRepository
import com.noLate.calendar.infrastructure.PublicHolidayRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

fun interface SeoulTimetableServiceDay {
    fun weekTag(date: LocalDate): String?
}

/** 휴일 캐시 미확인을 평일로 간주하지 않는다. ETA 조회에서 달력 데이터를 쓰거나 동기화하지 않는다. */
@Component
class SeoulTimetableServiceDayResolver(
    private val dayCache: CalendarDayCacheRepository,
    private val holidays: PublicHolidayRepository,
    private val clock: Clock = Clock.systemUTC(),
) : SeoulTimetableServiceDay {
    @Transactional(readOnly = true)
    override fun weekTag(date: LocalDate): String? {
        if (date.dayOfWeek == DayOfWeek.SUNDAY) return "3"
        val syncedAt = dayCache.findById(date).orElse(null)?.holidaysSyncedAt ?: return null
        val now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"))
        if (syncedAt.isBefore(now.minusDays(7)) || syncedAt.isAfter(now.plusSeconds(60))) return null
        val isHoliday = holidays.findAllByHolidayDateBetweenOrderByHolidayDateAscIdAsc(date, date)
            .any { it.source == PublicHoliday.KASI_SOURCE && it.type == PublicHoliday.PUBLIC_HOLIDAY_TYPE }
        return when {
            isHoliday -> "3"
            date.dayOfWeek == DayOfWeek.SATURDAY -> "2"
            else -> "1"
        }
    }
}
