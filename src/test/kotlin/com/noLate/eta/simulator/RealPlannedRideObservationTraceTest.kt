package com.noLate.eta.simulator

import com.noLate.eta.application.transit.TransitTransferRealtimeGuard
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils

class RealPlannedRideObservationTraceTest {
    @Test
    fun `환승편 조회 결과를 추가 호출 없이 요약하고 예외 원문은 남기지 않는다`() {
        val service = mock<TransitArrivalService>()
        val rows = listOf(TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY",
            stationName = "신림", subwayLineId = "1002", trainNumber = "2042",
            destinationName = "성수", direction = "내선", express = false,
            expectedAt = "2026-10-22T00:00:00Z", sourceUpdatedAt = "2026-10-21T23:58:00Z",
            freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP))
        whenever(service.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenReturn(rows)
        val guard = TransitTransferRealtimeGuard(service)
        val trace = RealPlannedRideObservationTrace(guard)
        val wrapped = ReflectionTestUtils.getField(guard, "arrivals") as TransitArrivalService

        assertSame(rows, wrapped.getSubwayArrivalsForEta("신림", "2호선", null, "UP", 10))
        verify(service, times(1)).getSubwayArrivalsForEta("신림", "2호선", null, "UP", 10)
        @Suppress("UNCHECKED_CAST")
        val events = trace.snapshot()["events"] as List<Map<String, Any?>>
        assertEquals(1, events.size)
        assertEquals("신림", events.single()["stationName"])
        assertEquals(1, events.single()["resultCount"])
        @Suppress("UNCHECKED_CAST")
        val recordedRows = events.single()["rows"] as List<Map<String, Any?>>
        assertEquals("2042", recordedRows.single()["trainNumber"])
        assertEquals(0, trace.snapshot()["additionalHttpCalls"])

        whenever(service.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenThrow(IllegalStateException("private provider body"))
        assertThrows(IllegalStateException::class.java) {
            wrapped.getSubwayArrivalsForEta("사당", "2호선", null, "UP", 10)
        }
        assertFalse(trace.snapshot().toString().contains("private provider body"))
        trace.close()
        assertSame(service, ReflectionTestUtils.getField(guard, "arrivals"))
    }
}
