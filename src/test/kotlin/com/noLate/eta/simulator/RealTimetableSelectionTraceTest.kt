package com.noLate.eta.simulator

import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.application.transit.TransitJourneyTimetableEnricher
import com.noLate.eta.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import java.time.LocalDate

class RealTimetableSelectionTraceTest {
    @Test
    fun `추적은 원본 호출과 반환 객체를 보존하고 종료하면 공급자 목록을 복원한다`() {
        val now = Instant.parse("2026-10-11T03:00:00Z")
        val rides = listOf(TransitLegTimetable("fixture", SeoulSubwayLine.LINE_4, LocalDate.parse("2026-10-11"),
            "4012", "DOWN", TransitServiceClass.LOCAL, "0411", "0433", "사당", now, now.plusSeconds(600), now))
        var calls = 0
        val original = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> { calls++; return rides }
        }
        val originals = listOf(original)
        val enricher = TransitJourneyTimetableEnricher(originals)
        RealTimetableSelectionTrace(enricher).use { trace ->
            @Suppress("UNCHECKED_CAST")
            val provider = (ReflectionTestUtils.getField(enricher, "providers") as List<TransitTimetableProvider>).single()
            assertSame(rides, provider.findRides(TransitTimetableQuery(SeoulSubwayLine.LINE_4, "노원", "사당", "창동",
                TransitServiceClass.LOCAL, now, "DOWN")))
            assertEquals(1, calls)
            assertEquals(0, trace.snapshot()["additionalHttpCalls"])
            assertEquals(false, trace.snapshot()["responseModified"])
            assertEquals(1, (trace.snapshot()["events"] as List<*>).size)
        }
        assertSame(originals, ReflectionTestUtils.getField(enricher, "providers"))
    }
}
