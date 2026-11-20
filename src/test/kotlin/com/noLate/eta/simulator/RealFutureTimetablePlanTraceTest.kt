package com.noLate.eta.simulator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.schedule.application.*
import com.noLate.schedule.application.service.SchedulePushJobWorker
import com.noLate.schedule.domain.ScheduleTravelMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

class RealFutureTimetablePlanTraceTest {
    @Test fun `현재 실패와 별도 시간표 후보를 수정 없이 기록하고 원래 클라이언트를 복원한다`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val input = requireNotNull(javaClass.getResourceAsStream("/eta/real-timetable-unobserved-20261119.json")).use(mapper::readTree)
        val base = mapper.treeToValue(input["trafficResult"], TrafficResult::class.java)
        val evidence = TransitTimetablePlanEvidence(requireNotNull(base.effectiveTransitRoute), requireNotNull(base.fetchedAt),
            TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED, TransitRouteProvenance.SELECTED_ROUTE_PRESERVED)
        val result = base.copy(failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_OUT_OF_HORIZON, futureTimetablePlanEvidence = evidence)
        val client = mock<TrafficClient>()
        val request = TrafficRequest(37.0, 127.0, 37.1, 127.1, ScheduleTravelMode.TRANSIT, 60,
            evaluatedAt = Instant.parse(input["evaluatedAt"].asText()))
        whenever(client.getTravelMinutes(request)).thenReturn(result)
        val worker = mock<SchedulePushJobWorker>()
        ReflectionTestUtils.setField(worker, "trafficClient", client)
        RealFutureTimetablePlanTrace(worker).use { trace ->
            val wrapped = ReflectionTestUtils.getField(worker, "trafficClient") as TrafficClient
            assertSame(result, wrapped.getTravelMinutes(request))
            @Suppress("UNCHECKED_CAST")
            val events = trace.snapshot()["events"] as List<Map<String, Any?>>
            assertEquals(1, events.size)
            assertSame(evidence, events.single()["futureTimetablePlanEvidence"])
            assertEquals(result.failureReason, events.single()["failureReason"])
            assertEquals(request.evaluatedAt, events.single()["evaluatedAt"])
        }
        verify(client, times(1)).getTravelMinutes(request)
        verifyNoMoreInteractions(client)
        assertSame(client, ReflectionTestUtils.getField(worker, "trafficClient"))
    }
}
