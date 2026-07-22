package com.noLate.calendar.application

import com.noLate.calendar.domain.CalendarDayCache
import com.noLate.calendar.domain.PublicHoliday
import com.noLate.calendar.infrastructure.CalendarDayCacheRepository
import com.noLate.calendar.infrastructure.KasiHoliday
import com.noLate.calendar.infrastructure.KasiLunarDay
import com.noLate.calendar.infrastructure.PublicHolidayRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

@Service
class CalendarCacheWriter(
    private val calendarDayCacheRepository: CalendarDayCacheRepository,
    private val publicHolidayRepository: PublicHolidayRepository,
) {
    @Transactional
    fun storeLunarMonth(
        lunarDays: List<KasiLunarDay>,
        syncedAt: LocalDateTime,
    ) {
        if (lunarDays.isEmpty()) return

        val startDate = lunarDays.minOf { it.date }
        val endDate = lunarDays.maxOf { it.date }
        val existing = calendarDayCacheRepository
            .findAllByDateBetweenOrderByDateAsc(startDate, endDate)
            .associateBy { it.date }

        val entities = lunarDays.map { lunarDay ->
            (existing[lunarDay.date] ?: newCalendarDay(lunarDay.date, syncedAt)).apply {
                updateLunar(
                    year = lunarDay.lunarYear,
                    month = lunarDay.lunarMonth,
                    day = lunarDay.lunarDay,
                    leapMonth = lunarDay.leapMonth,
                    syncedAt = syncedAt,
                )
            }
        }
        calendarDayCacheRepository.saveAll(entities)
    }

    @Transactional
    fun replaceHolidayMonth(
        month: YearMonth,
        holidays: List<KasiHoliday>,
        syncedAt: LocalDateTime,
    ) {
        val startDate = month.atDay(1)
        val endDate = month.atEndOfMonth()

        publicHolidayRepository.deleteAllInRange(startDate, endDate)
        if (holidays.isNotEmpty()) {
            publicHolidayRepository.saveAll(
                holidays.map { holiday ->
                    PublicHoliday(
                        holidayDate = holiday.date,
                        name = holiday.name,
                        type = holiday.type,
                        source = PublicHoliday.KASI_SOURCE,
                        updatedAt = syncedAt,
                    )
                }
            )
        }

        val existing = calendarDayCacheRepository
            .findAllByDateBetweenOrderByDateAsc(startDate, endDate)
            .associateBy { it.date }
        val monthDays = startDate.datesUntil(endDate.plusDays(1)).map { date ->
            (existing[date] ?: newCalendarDay(date, syncedAt)).apply {
                markHolidaysSynced(syncedAt)
            }
        }.toList()
        calendarDayCacheRepository.saveAll(monthDays)
    }

    private fun newCalendarDay(date: LocalDate, now: LocalDateTime): CalendarDayCache =
        CalendarDayCache(date = date, updatedAt = now)
}
