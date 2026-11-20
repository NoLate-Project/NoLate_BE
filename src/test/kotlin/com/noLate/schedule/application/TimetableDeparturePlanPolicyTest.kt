package com.noLate.schedule.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.schedule.domain.TrafficSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class TimetableDeparturePlanPolicyTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val input = mapper.readTree(requireNotNull(javaClass.getResourceAsStream("/eta/real-timetable-unobserved-20261119.json")))
    private val result = mapper.treeToValue(input["trafficResult"], TrafficResult::class.java)
    private val now = Instant.parse(input["evaluatedAt"].asText())
    private val target = Instant.parse(input["targetArrivalAt"].asText())

    @Test
    fun `실제 미관측 응답의 완전한 시간표를 계획으로 보존하고 13분 늦은 출발을 보호한다`() {
        val plan = requireNotNull(TimetableDeparturePlanPolicy.verifiedRoute(result, now, target, 60))
        assertFalse(result.accepted)
        assertEquals(TrafficSource.TIMETABLE_PROVIDER, result.source)
        assertEquals(Instant.parse("2026-11-19T13:34:00Z"), plan.departureAt)
        assertTrue(plan.departureAt < Instant.parse(input["previousRecommendedDepartureAt"].asText()))
        assertEquals(Instant.parse("2026-11-19T14:44:13Z"), plan.arrivalAt)
        val first = requireNotNull(EffectiveTransitRouteSnapshot.from(result, null, 0, evaluatedAt = now, verifiedTimetablePlan = plan))
        assertFalse(first.accepted)
        val next = requireNotNull(EffectiveTransitRouteSnapshot.from(TrafficResult(60, TrafficSource.SELECTED_ROUTE,
            stale = true, failureReason = TrafficFailureReasons.PROVIDER_TIMEOUT), first, 1,
            evaluatedAt = plan.departureAt.plusSeconds(60)))
        assertEquals(plan, next.inProgressPlannedRoute)
        assertEquals(plan.arrivalAt, next.plannedJourneyArrivalPrediction(plan.departureAt.plusSeconds(60), 120)?.predictedArrivalAt)
        assertEquals(com.noLate.schedule.domain.PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN,
            first.plannedJourneyArrivalPrediction(plan.departureAt.plusSeconds(1), 120)?.basis)
        // 오래된 차량 응답은 실시간 근거가 아니지만, 독립적으로 검증한 시간표를 무효화하지 않는다.
        assertEquals(plan, TimetableDeparturePlanPolicy.verifiedRoute(result.copy(
            failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE), now, target, 60))
    }

    @Test
    fun `별도 후보도 같은 경로와 엄격한 시간표 도착 및 여유 검증을 거친다`() {
        val route = requireNotNull(result.effectiveTransitRoute)
        val evidence = TransitTimetablePlanEvidence(route, requireNotNull(result.fetchedAt),
            TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED, TransitRouteProvenance.SELECTED_ROUTE_PRESERVED)
        val diagnostic = result.copy(failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_OUT_OF_HORIZON,
            futureTimetablePlanEvidence = evidence)
        assertEquals(route, TimetableDeparturePlanPolicy.verifiedRoute(diagnostic, now, target, 60))
        assertEquals(TrafficFailureReasons.TRANSIT_ARRIVAL_OUT_OF_HORIZON, diagnostic.failureReason)
        assertFalse(diagnostic.accepted)
        assertNull(TimetableDeparturePlanPolicy.verifiedRoute(diagnostic.copy(futureTimetablePlanEvidence = null), now, target, 60))
        assertNull(TimetableDeparturePlanPolicy.verifiedRoute(diagnostic, now, now.plusSeconds(60), 60))
        assertNull(TimetableDeparturePlanPolicy.verifiedRoute(diagnostic.copy(futureTimetablePlanEvidence =
            evidence.copy(fetchedAt = now.plusSeconds(1))), now, target, 60))
        assertNull(TimetableDeparturePlanPolicy.verifiedRoute(diagnostic.copy(futureTimetablePlanEvidence =
            evidence.copy(route = route.copy(identity = "b".repeat(64)))), now, target, 60))
        assertNull(TimetableDeparturePlanPolicy.verifiedRoute(diagnostic.copy(source = TrafficSource.LIVE_PROVIDER,
            stale = false, failureReason = null), now, target, 60))
        assertThrows(IllegalArgumentException::class.java) { evidence.copy(failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_API_ERROR) }
    }

    @Test
    fun `시간표 부족 또는 장애 또는 도착 지각은 더 이른 알림의 근거로 승격하지 않는다`() {
        for (failure in listOf(TrafficFailureReasons.PROVIDER_TIMEOUT, TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN,
            TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE, TrafficFailureReasons.TRANSIT_ARRIVAL_API_TIMEOUT)) {
            assertNull(TimetableDeparturePlanPolicy.verifiedRoute(result.copy(failureReason = failure), now, target, 60))
        }
        assertNull(TimetableDeparturePlanPolicy.verifiedRoute(result, now, now.plusSeconds(60), 60))
        assertNull(TimetableDeparturePlanPolicy.verifiedRoute(result, target, target, 60))
        val route = requireNotNull(result.effectiveTransitRoute)
        assertNull(TimetableDeparturePlanPolicy.verifiedRoute(result.copy(effectiveTransitRoute = route.copy(totalMinutes = 1)), now, target, 60))
        val impossibleTransfer = route.copy(segments = route.segments.map { segment ->
            if (segment.sequence != 3) segment else segment.copy(rideTiming = requireNotNull(segment.rideTiming).let { timing ->
                val board = Instant.parse("2026-11-19T14:31:36Z")
                timing.copy(timetable = timing.timetable.copy(boardingAt = board, boardingStationArrivalAt = null),
                    predictedBoardingAt = board)
            })
        })
        assertNull(TimetableDeparturePlanPolicy.verifiedRoute(result.copy(effectiveTransitRoute = impossibleTransfer), now, target, 60))
    }
}
