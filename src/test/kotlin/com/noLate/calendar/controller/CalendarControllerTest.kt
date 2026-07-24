package com.noLate.calendar.controller

import com.noLate.calendar.application.CalendarMetadataQueryService
import com.noLate.calendar.domain.CalendarDayDto
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate

@ExtendWith(MockitoExtension::class)
class CalendarControllerTest {
    @Mock
    lateinit var calendarMetadataQueryService: CalendarMetadataQueryService

    @Test
    fun `공개 메타데이터 날짜 범위를 캐시 조회 서비스에 전달한다`() {
        val startDate = LocalDate.of(2026, 9, 1)
        val endDate = LocalDate.of(2026, 9, 30)
        val expected = listOf(
            CalendarDayDto(
                date = "2026-09-25",
                lunarYear = 2026,
                lunarMonth = 8,
                lunarDay = 15,
                leapMonth = false,
                holidays = emptyList(),
            )
        )
        whenever(calendarMetadataQueryService.getDays(startDate, endDate)).thenReturn(expected)

        val response = CalendarController(calendarMetadataQueryService)
            .getDays(startDate, endDate)

        assertTrue(response.success)
        assertSame(expected, response.data)
        verify(calendarMetadataQueryService).getDays(startDate, endDate)
    }
}
