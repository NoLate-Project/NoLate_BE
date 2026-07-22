package com.noLate.calendar.application

import com.noLate.calendar.domain.CalendarDayCache
import com.noLate.calendar.domain.PublicHoliday
import com.noLate.calendar.infrastructure.CalendarDayCacheRepository
import com.noLate.calendar.infrastructure.KasiCalendarClient
import com.noLate.calendar.infrastructure.KasiCalendarException
import com.noLate.calendar.infrastructure.PublicHolidayRepository
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class CalendarMetadataServiceTest {
    @Mock
    lateinit var calendarDayCacheRepository: CalendarDayCacheRepository

    @Mock
    lateinit var publicHolidayRepository: PublicHolidayRepository

    @Mock
    lateinit var kasiCalendarClient: KasiCalendarClient

    @Mock
    lateinit var calendarCacheWriter: CalendarCacheWriter

    private val startDate = LocalDate.of(2026, 9, 25)
    private val endDate = LocalDate.of(2026, 9, 27)

    @Test
    fun `연동이 비활성화되어도 캐시와 빈 날짜를 범위 전체에 반환한다`() {
        whenever(kasiCalendarClient.isAvailable()).thenReturn(false)
        whenever(calendarDayCacheRepository.findAllByDateBetweenOrderByDateAsc(startDate, endDate))
            .thenReturn(listOf(cachedDay(startDate)))
        whenever(publicHolidayRepository.findAllByHolidayDateBetweenOrderByHolidayDateAscIdAsc(startDate, endDate))
            .thenReturn(listOf(holiday(startDate, "추석")))

        val result = service().getDays(startDate, endDate)

        assertEquals(3, result.size)
        assertEquals("2026-09-25", result[0].date)
        assertEquals(2026, result[0].lunarYear)
        assertEquals("추석", result[0].holidays.single().name)
        assertEquals("2026-09-26", result[1].date)
        assertNull(result[1].lunarYear)
        assertEquals(emptyList<Any>(), result[1].holidays)
        verify(kasiCalendarClient, never()).fetchLunarMonth(any())
        verify(kasiCalendarClient, never()).fetchHolidayMonth(any())
    }

    @Test
    fun `외부 API 장애 시 오래된 캐시를 그대로 반환한다`() {
        val stale = cachedDay(startDate).apply {
            lunarSyncedAt = LocalDateTime.of(2026, 1, 1, 0, 0)
            holidaysSyncedAt = LocalDateTime.of(2026, 1, 1, 0, 0)
        }
        whenever(kasiCalendarClient.isAvailable()).thenReturn(true)
        whenever(calendarDayCacheRepository.findAllByDateBetweenOrderByDateAsc(startDate, endDate))
            .thenReturn(listOf(stale))
        whenever(kasiCalendarClient.fetchLunarMonth(any()))
            .thenThrow(KasiCalendarException("unavailable"))
        whenever(kasiCalendarClient.fetchHolidayMonth(any()))
            .thenThrow(KasiCalendarException("unavailable"))
        whenever(publicHolidayRepository.findAllByHolidayDateBetweenOrderByHolidayDateAscIdAsc(startDate, endDate))
            .thenReturn(listOf(holiday(startDate, "추석")))

        val result = service().getDays(startDate, endDate)

        assertEquals(2026, result.single { it.date == startDate.toString() }.lunarYear)
        assertEquals("추석", result.single { it.date == startDate.toString() }.holidays.single().name)
        verify(kasiCalendarClient).fetchLunarMonth(any())
        verify(kasiCalendarClient).fetchHolidayMonth(any())
    }

    @Test
    fun `역전되거나 93일을 초과한 범위를 거부한다`() {
        val reversed = assertThrows<BusinessException> {
            service().getDays(endDate, startDate)
        }
        assertEquals(ErrorCode.INVALID_INPUT, reversed.errorCode)

        val tooLong = assertThrows<BusinessException> {
            service().getDays(startDate, startDate.plusDays(CalendarMetadataService.MAX_RANGE_DAYS))
        }
        assertEquals(ErrorCode.INVALID_INPUT, tooLong.errorCode)
    }

    private fun service() = CalendarMetadataService(
        calendarDayCacheRepository = calendarDayCacheRepository,
        publicHolidayRepository = publicHolidayRepository,
        kasiCalendarClient = kasiCalendarClient,
        calendarCacheWriter = calendarCacheWriter,
        clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC),
        cacheTtlHours = 168,
    )

    private fun cachedDay(date: LocalDate) = CalendarDayCache(
        date = date,
        lunarYear = 2026,
        lunarMonth = 8,
        lunarDay = 15,
        leapMonth = false,
        lunarSyncedAt = LocalDateTime.of(2026, 7, 20, 9, 0),
        holidaysSyncedAt = LocalDateTime.of(2026, 7, 20, 9, 0),
        updatedAt = LocalDateTime.of(2026, 7, 20, 9, 0),
    )

    private fun holiday(date: LocalDate, name: String) = PublicHoliday(
        id = 1L,
        holidayDate = date,
        name = name,
        type = "PUBLIC_HOLIDAY",
        source = "KASI",
        updatedAt = LocalDateTime.of(2026, 7, 20, 9, 0),
    )
}
