package com.noLate.calendar.controller

import com.noLate.calendar.application.CalendarMetadataService
import com.noLate.calendar.domain.CalendarDayDto
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate

@ExtendWith(MockitoExtension::class)
class CalendarControllerTest {
    @Mock
    lateinit var calendarMetadataService: CalendarMetadataService

    private val principal = MemberPrincipal(
        id = 7L,
        email = "member@nolate.test",
        name = "tester",
    )

    @Test
    fun `인증 사용자의 날짜 범위를 서비스에 전달한다`() {
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
        whenever(calendarMetadataService.getDays(startDate, endDate)).thenReturn(expected)

        val response = CalendarController(calendarMetadataService)
            .getDays(principal, startDate, endDate)

        assertTrue(response.success)
        assertSame(expected, response.data)
        verify(calendarMetadataService).getDays(startDate, endDate)
    }

    @Test
    fun `인증 주체가 없으면 조회를 거부한다`() {
        val exception = assertThrows<BusinessException> {
            CalendarController(calendarMetadataService).getDays(
                principal = null,
                startDate = LocalDate.of(2026, 9, 1),
                endDate = LocalDate.of(2026, 9, 30),
            )
        }

        assertEquals(ErrorCode.UNAUTHORIZED, exception.errorCode)
    }
}
