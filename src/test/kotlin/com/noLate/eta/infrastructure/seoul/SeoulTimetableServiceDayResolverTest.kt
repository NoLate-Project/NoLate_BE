package com.noLate.eta.infrastructure.seoul

import com.noLate.calendar.domain.CalendarDayCache
import com.noLate.calendar.domain.PublicHoliday
import com.noLate.calendar.infrastructure.CalendarDayCacheRepository
import com.noLate.calendar.infrastructure.PublicHolidayRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.time.*
import java.util.Optional

class SeoulTimetableServiceDayResolverTest {
    private val now = Instant.parse("2026-10-04T06:00:00Z")
    private val cache = mock<CalendarDayCacheRepository>()
    private val holidays = mock<PublicHolidayRepository>()
    private val service = SeoulTimetableServiceDayResolver(cache, holidays, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `일요일은 항상 휴일 시간표이며 캐시가 없는 평일은 추정하지 않는다`() {
        assertEquals("3", service.weekTag(LocalDate.of(2026, 10, 4)))
        verifyNoInteractions(cache, holidays)
        val date = LocalDate.of(2026, 10, 5)
        whenever(cache.findById(date)).thenReturn(Optional.empty())
        assertNull(service.weekTag(date))
        verifyNoInteractions(holidays)
    }

    @Test
    fun `동기화된 평일 토요일 공휴일을 구분한다`() {
        val synced = LocalDateTime.of(2026, 10, 4, 15, 0)
        val weekday = LocalDate.of(2026, 10, 5)
        val saturday = LocalDate.of(2026, 10, 10)
        val holiday = LocalDate.of(2026, 10, 9)
        listOf(weekday, saturday, holiday).forEach { date ->
            whenever(cache.findById(date)).thenReturn(Optional.of(CalendarDayCache(date, holidaysSyncedAt = synced)))
            whenever(holidays.findAllByHolidayDateBetweenOrderByHolidayDateAscIdAsc(date, date))
                .thenReturn(if (date == holiday) listOf(PublicHoliday(holidayDate = date)) else emptyList())
        }
        assertEquals("1", service.weekTag(weekday))
        assertEquals("2", service.weekTag(saturday))
        assertEquals("3", service.weekTag(holiday))
    }

    @Test
    fun `만료되거나 미래인 동기화 시각은 휴일 확인으로 사용하지 않는다`() {
        val date = LocalDate.of(2026, 10, 5)
        listOf(LocalDateTime.of(2026, 9, 26, 15, 0), LocalDateTime.of(2026, 10, 4, 15, 2)).forEach { timestamp ->
            whenever(cache.findById(date)).thenReturn(Optional.of(CalendarDayCache(date, holidaysSyncedAt = timestamp)))
            assertNull(service.weekTag(date))
        }
        verifyNoInteractions(holidays)
    }
}
