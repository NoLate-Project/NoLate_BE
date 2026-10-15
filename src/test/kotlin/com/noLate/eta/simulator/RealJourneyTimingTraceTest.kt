package com.noLate.eta.simulator

import com.noLate.eta.application.TransitEtaCalculationService
import com.noLate.eta.application.transit.TransitJourneyTimetableEnricher
import com.noLate.eta.application.transit.TransitRealtimeOverlay
import com.noLate.eta.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

class RealJourneyTimingTraceTest {
    @Test
    fun `조기 환승 관찰도 추가 호출 없이 원래 반환 객체를 보존한다`() {
        val original = TransitJourneyTimetableEnricher(emptyList())
        val calculator = TransitEtaCalculationService(mock(), emptyList(), mock(), mock(), mock(), mock(), original)
        val now = Instant.parse("2026-10-12T13:30:00Z")
        val input = TransitJourney("odsay", now, now, now.plusSeconds(600), 10,
            listOf(TransitJourneyLeg(0, TransitLegMode.WALK, 1)), now)
        RealJourneyTimingTrace(calculator).use { trace ->
            val proxy = ReflectionTestUtils.getField(calculator, "timetableEnricher") as TransitJourneyTimetableEnricher
            assertSame(input, proxy.reconnectEarlierTransfers(input,
                TransitRealtimeOverlay(10, input.arrivalAt, now, now.plusSeconds(120)), null, now, 180))
            val events = trace.snapshot()["events"] as List<*>
            assertEquals("reconnectEarlierTransfers", (events.single() as Map<*, *>)["method"])
            assertEquals(0, trace.snapshot()["additionalHttpCalls"])
            assertEquals(false, trace.snapshot()["responseModified"])
        }
        assertSame(original, ReflectionTestUtils.getField(calculator, "timetableEnricher"))
    }

    @Test
    fun `관찰은 원본 반환과 검색 시각을 보존하고 크기를 제한하며 종료시 복원한다`() {
        val original = TransitJourneyTimetableEnricher(emptyList())
        val calculator = TransitEtaCalculationService(mock(), emptyList(), mock(), mock(), mock(), mock(), original)
        val departure = Instant.parse("2026-10-12T13:30:00Z")
        val input = TransitJourney("odsay", departure, departure, departure.plusSeconds(600), 10,
            listOf(TransitJourneyLeg(0, TransitLegMode.WALK, 1),
                TransitJourneyLeg(1, TransitLegMode.SUBWAY, 8), TransitJourneyLeg(2, TransitLegMode.WALK, 1)), departure)
        RealJourneyTimingTrace(calculator).use { trace ->
            val proxy = ReflectionTestUtils.getField(calculator, "timetableEnricher") as TransitJourneyTimetableEnricher
            repeat(60) { assertSame(input, proxy.enrich(input, departure, 180)) }
            val snapshot = trace.snapshot()
            assertEquals(0, snapshot["additionalHttpCalls"])
            assertEquals(false, snapshot["responseModified"])
            @Suppress("UNCHECKED_CAST")
            val events = snapshot["events"] as List<Map<String, Any?>>
            assertEquals(48, events.size)
            assertEquals(departure, events.first()["requestedDepartureAt"])
            assertEquals(input.arrivalAt, events.first()["arrivalAt"])
            @Suppress("UNCHECKED_CAST")
            val legs = events.first()["legs"] as List<Map<String, Any?>>
            assertEquals(listOf(60L, null, 60L), legs.map { it["movementSeconds"] })
            assertEquals(listOf(TransitWalkingScope.ACCESS, null, TransitWalkingScope.EGRESS), legs.map { it["walkingScope"] })
            assertFalse(events.first().containsKey("accepted")) // A candidate trace is not an accepted ETA.
        }
        assertSame(original, ReflectionTestUtils.getField(calculator, "timetableEnricher"))
    }
}
