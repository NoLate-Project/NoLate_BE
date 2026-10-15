package com.noLate.eta.simulator

import com.noLate.eta.application.TransitEtaCalculationService
import com.noLate.eta.application.transit.FirstBoardingRealtimeOverlay
import com.noLate.eta.domain.*
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.test.util.ReflectionTestUtils
import java.time.*

class RealFirstBoardingSelectionTraceTest {
    private val now = Instant.parse("2026-10-09T06:00:00Z")

    @Test
    fun `관측 프록시는 실제 반환 객체와 예외 및 호출수를 보존하고 종료시 원복한다`() {
        val service = mock<TransitArrivalService>()
        val arrivals = listOf(TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY", stationName = "노원",
            subwayLineId = "1004", trainNumber = "4012", destinationName = "오이도", direction = "하행", express = false,
            expectedAt = now.plusSeconds(120).toString(), sourceUpdatedAt = now.toString(),
            freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP))
        whenever(service.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenReturn(arrivals)
        val overlay = FirstBoardingRealtimeOverlay(service, Clock.fixed(now, ZoneOffset.UTC))
        val calculator = TransitEtaCalculationService(mock(), emptyList(), mock(), overlay, mock(), mock())
        val baseline = overlay.resolve(journey(), now, 180)
        clearInvocations(service)
        val trace = RealFirstBoardingSelectionTrace(calculator, overlay)
        val wrapped = ReflectionTestUtils.getField(calculator, "firstBoardingRealtimeOverlay") as FirstBoardingRealtimeOverlay
        val observedService = ReflectionTestUtils.getField(overlay, "transitArrivalService") as TransitArrivalService
        assertSame(arrivals, observedService.getSubwayArrivalsForEta("노원", "4호선", null, "DOWN", 10))
        assertEquals(baseline, wrapped.resolve(journey(), now, 180)) // 원본 캐시도 그대로 유지한다.
        verify(service, times(1)).getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())
        @Suppress("UNCHECKED_CAST")
        val events = trace.snapshot()["events"] as List<Map<String, Any?>>
        assertEquals(listOf("ARRIVALS", "SELECTION"), events.map { it["kind"] })
        assertEquals("4012", events.last()["expectedTrainNumber"])
        assertEquals(true, events.last()["observationAccepted"])
        assertEquals(now.plusSeconds(60).toString(), events.last()["readyAt"])
        val failure = IllegalStateException("private failure must not be recorded")
        whenever(service.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenThrow(failure)
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            observedService.getSubwayArrivalsForEta("노원", "4호선", null, "DOWN", 10)
        })
        assertFalse(trace.snapshot().toString().contains("private failure"))
        trace.close()
        assertSame(overlay, ReflectionTestUtils.getField(calculator, "firstBoardingRealtimeOverlay"))
        assertSame(service, ReflectionTestUtils.getField(overlay, "transitArrivalService"))
    }

    private fun journey(): TransitJourney {
        val table = TransitLegTimetable("fixture", SeoulSubwayLine.LINE_4, LocalDate.of(2026, 10, 9), "4012",
            "DOWN", TransitServiceClass.LOCAL, "0411", "0433", "오이도", now.plusSeconds(120),
            now.plusSeconds(600), now)
        return TransitJourney("fixture", now, now, now.plusSeconds(600), 10, listOf(
            TransitJourneyLeg(sequence = 0, mode = TransitLegMode.SUBWAY, durationMinutes = 10, durationSeconds = 600,
                from = TransitStop(name = "노원"), to = TransitStop(name = "사당"),
                line = TransitLine(seoulSubwayLine = SeoulSubwayLine.LINE_4, serviceClass = TransitServiceClass.LOCAL),
                directionCode = "DOWN", timingBasis = TransitLegTimingBasis.TIMETABLE,
                scheduledDepartureAt = now, scheduledArrivalAt = now.plusSeconds(600), timetable = table)), now)
    }
}
