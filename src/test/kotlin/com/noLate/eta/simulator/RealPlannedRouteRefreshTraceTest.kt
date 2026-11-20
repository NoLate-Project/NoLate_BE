package com.noLate.eta.simulator

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.transit.*
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.service.SchedulePushJobWorker
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

class RealPlannedRouteRefreshTraceTest {
    @Test
    fun `같은 역과 시간표 응답으로 환승 재선택을 재생하고 누락된 조회는 비교에서 제외한다`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val input = requireNotNull(javaClass.getResourceAsStream("/eta/planned-transfer-recovery-synthetic.json")).use(mapper::readTree)
        val plan = mapper.treeToValue(input["plan"], EffectiveTransitRoute::class.java)
        val late = mapper.treeToValue(input["stationRows"][0][0], TransitArrivalDto::class.java)
        val laterTrain = mapper.treeToValue(input["alternateTrain"], TransitLegTimetable::class.java)
        val table = mock<TransitTimetableProvider>()
        whenever(table.findRides(any())).thenReturn(listOf(laterTrain))
        val arrivals = mock<TransitArrivalService>()
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenAnswer {
            if (it.arguments[0] == late.stationName && it.arguments[1] == "4호선") listOf(late) else emptyList<TransitArrivalDto>()
        }
        val guard = TransitTransferRealtimeGuard(arrivals)
        val enricher = TransitJourneyTimetableEnricher(listOf(table))
        val service = PlannedTransitRouteRefresher(guard, enricher, TransitTransferFeasibilityEvaluator())
        val worker = mock<SchedulePushJobWorker>()
        ReflectionTestUtils.setField(worker, "plannedTransitRouteRefresher", service)
        RealPlannedRouteRefreshTrace(worker).use { trace ->
            RealPlannedRideObservationTrace(guard, trace).use {
                RealTimetableSelectionTrace(enricher, trace).use {
                    val wrapped = ReflectionTestUtils.getField(worker, "plannedTransitRouteRefresher") as PlannedTransitRouteRefresher
                    val now = Instant.parse("2026-11-19T12:07:30.123456789Z")
                    val result = wrapped.refresh(plan, now, plan, null)
                    assertEquals(laterTrain.trainNumber, result?.route?.segments?.get(3)?.rideTiming?.timetable?.trainNumber)
                    val frame = trace.snapshot().single()
                    assertEquals(now, frame.evaluatedAt)
                    assertSame(plan, frame.plan)
                    assertSame(result, frame.result)
                    assertEquals(listOf(laterTrain), frame.timetables.single().rides)
                    assertTrue(frame.stations.any { it.rows == listOf(late) })
                    // An unrelated route read after the call returns must not enter the frame.
                    val count = frame.stations.size
                    guard.observePlannedRideChanges(plan, now)
                    assertEquals(count, frame.stations.size)

                    val json = mapper.valueToTree<ObjectNode>(frame)
                    val config = mapper.createObjectNode().put("timetableProviderCount", 1).put("boardingBufferSeconds", 60)
                        .put("transferConfidenceMarginSeconds", 60).put("transferBufferSeconds", 0).put("maxTravelMinutes", 1440)
                    assertEquals(result, ReplayRecordedPlannedRefresh.replay(config, json, true)["update"])
                    val wrongStation = json.deepCopy()
                    (wrongStation["stations"][0] as ObjectNode).put("directionCode", "UP")
                    assertEquals("DIFFERENT_STATION_QUERY", ReplayRecordedPlannedRefresh.replay(config, wrongStation, false)["reason"])
                    val unknownInput = json.deepCopy()
                    (unknownInput["plan"] as ObjectNode).put("unrepresentedTimingEvidence", "new-version-only")
                    assertEquals("INPUT_SCHEMA_NOT_SUPPORTED", ReplayRecordedPlannedRefresh.replay(config, unknownInput, false)["reason"])
                    val missingTable = json.deepCopy().apply { putArray("timetables") }
                    assertEquals("UNRECORDED_TIMETABLE_QUERY", ReplayRecordedPlannedRefresh.replay(config, missingTable, false)["reason"])
                    assertEquals("INCOMPLETE_CAPTURE", ReplayRecordedPlannedRefresh.replay(config, json.put("incomplete", true), false)["reason"])
                }
            }
        }
        assertSame(service, ReflectionTestUtils.getField(worker, "plannedTransitRouteRefresher"))
    }
}
