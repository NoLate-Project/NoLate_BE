package com.noLate.eta.simulator

import com.noLate.calendar.application.CalendarCacheWriter
import com.noLate.calendar.infrastructure.KasiCalendarClient
import com.noLate.calendar.infrastructure.KasiHoliday
import com.noLate.eta.infrastructure.seoul.SeoulTimetableServiceDay
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.time.*

/** Synthetic source contracts, not evidence of actual KASI/E2E success. */
class RealCalendarEvidenceBootstrapTest {
    private val date = LocalDate.of(2026, 10, 12)
    private val now = Instant.parse("2026-10-12T01:20:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val serviceDay = mock<SeoulTimetableServiceDay>()
    private val client = mock<KasiCalendarClient>()
    private val writer = mock<CalendarCacheWriter>()

    @Test
    fun `검증된 운행일이면 추가 조회나 freshness 덮어쓰기가 없다`() {
        whenever(serviceDay.weekTag(date)).thenReturn("1")
        val result = RealCalendarEvidenceBootstrap(serviceDay, client, writer, clock).prepare(date)
        assertEquals(0, result["additionalKasiCalls"])
        verifyNoInteractions(client, writer)
    }

    @Test
    fun `미확인 운행일은 실제 클라이언트 반환 이후에만 H2 writer에 넣고 재검증한다`() {
        val holidays = listOf(KasiHoliday(date, "fixture holiday", "PUBLIC_HOLIDAY"))
        whenever(serviceDay.weekTag(date)).thenReturn(null, "3")
        whenever(client.isAvailable()).thenReturn(true)
        whenever(client.fetchHolidayMonth(YearMonth.from(date))).thenReturn(holidays)
        val result = RealCalendarEvidenceBootstrap(serviceDay, client, writer, clock).prepare(date)
        assertEquals("3", result["weekTag"])
        assertEquals(1, result["additionalKasiCalls"])
        inOrder(client, writer) {
            verify(client).fetchHolidayMonth(YearMonth.from(date))
            verify(writer).replaceHolidayMonth(YearMonth.from(date), holidays,
                LocalDateTime.ofInstant(now, ZoneId.of("Asia/Seoul")))
        }
    }

    @Test
    fun `키 누락이나 원천 오류이면 빈 공휴일 또는 평일로 조작하지 않는다`() {
        whenever(serviceDay.weekTag(date)).thenReturn(null)
        assertThrows(IllegalStateException::class.java) {
            RealCalendarEvidenceBootstrap(serviceDay, client, writer, clock).prepare(date)
        }
        whenever(client.isAvailable()).thenReturn(true)
        whenever(client.fetchHolidayMonth(any())).thenThrow(IllegalStateException("synthetic source failure"))
        assertThrows(IllegalStateException::class.java) {
            RealCalendarEvidenceBootstrap(serviceDay, client, writer, clock).prepare(date)
        }
        verifyNoInteractions(writer)
    }
}
