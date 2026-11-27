package com.noLate.schedule.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.application.DeferredSeoulBusTransfer
import com.noLate.schedule.application.EffectiveBusRidePrediction
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.EffectiveTransitRouteSegment
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind
import com.noLate.schedule.application.EffectiveTransitRouteSnapshot
import com.noLate.schedule.application.EffectiveTransitRideTiming
import com.noLate.schedule.application.EffectiveTransitRideTimingBasis
import com.noLate.schedule.application.PlannedTransitRouteUpdate
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TransitRouteProvenance
import com.noLate.schedule.application.TransitTimingBasis
import com.noLate.schedule.application.savedOdsayTimetableFixture
import com.noLate.schedule.domain.PlannedJourneyArrivalBasis
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleEtaConfidence
import com.noLate.schedule.domain.ScheduleEtaRouteFingerprint
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@ExtendWith(MockitoExtension::class)
class ScheduleDepartureEtaServiceTest {
    private val queryAt = Instant.parse("2026-07-24T03:00:00Z")
    private val clock = Clock.fixed(queryAt, ZoneOffset.UTC)
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `재조회 TTL 만료가 보호한 출발시각과 시간표 계획을 옛 저장 시각으로 되돌리지 않는다`(wrongRoute: Boolean) {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val checked = queryAt.minusSeconds(8 * 60)
        val departure = queryAt.minusSeconds(5 * 60)
        val arrival = queryAt.plusSeconds(40 * 60)
        val table = com.noLate.eta.domain.TransitLegTimetable("fixture", com.noLate.eta.domain.SeoulSubwayLine.LINE_4,
            java.time.LocalDate.of(2026, 7, 24), "4012", "DOWN", com.noLate.eta.domain.TransitServiceClass.LOCAL,
            "FROM", "TO", "오이도", departure.plusSeconds(120), arrival.minusSeconds(180), checked)
        val plan = EffectiveTransitRoute("fixture", "f".repeat(64), departure, arrival, 45,
            listOf(EffectiveTransitRouteSegment(0, EffectiveTransitRouteSegmentKind.SUBWAY, 42,
                rideTiming = EffectiveTransitRideTiming(table, table.boardingAt, table.arrivalAt, EffectiveTransitRideTimingBasis.TIMETABLE)),
                EffectiveTransitRouteSegment(1, EffectiveTransitRouteSegmentKind.WALK, 3, durationSeconds = 180)))
        val snapshot = EffectiveTransitRouteSnapshot(evaluationCheckCount = job.checkCount,
            evaluationNotificationGeneration = job.notificationGeneration, lastTimetablePlannedRoute = plan)
        val json = objectMapper.writeValueAsString(snapshot)
        finish(job, 45, TrafficSource.TIMETABLE_PROVIDER, checked, liveFetchedAt = checked.minusSeconds(1),
            failureReason = TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED, etaStale = true,
            snapshotSchedule = schedule, recommendedDepartureAt = departure, effectiveTransitRouteJson = json,
            etaRefreshDueAt = checked.plusSeconds(60))
        if (wrongRoute) requireNotNull(schedule.route).destinationLat = 38.1
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertTrue(result.stale)
        assertNull(result.liveFetchedAt)
        assertNull(result.predictedArrivalAt)
        assertNull(result.effectiveTransitRoute)
        assertNull(result.firstBoardingObservation)
        if (wrongRoute) {
            assertNull(result.plannedJourneyArrivalPrediction)
            assertTrue(requireNotNull(result.recommendedDepartureAt) > departure)
        } else {
            assertEquals(departure, result.recommendedDepartureAt)
            assertEquals(arrival, result.plannedJourneyArrivalPrediction?.predictedArrivalAt)
            assertEquals(com.noLate.schedule.domain.PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN,
                result.plannedJourneyArrivalPrediction?.basis)
            assertNull(result.plannedJourneyArrivalPrediction?.sourceUpdatedAt)
            assertNull(result.source)
            assertNull(result.travelMinutes)
            assertTrue(result.originReplanDiagnostic?.failureReason?.startsWith("ETA_SNAPSHOT_EXPIRED:") == true)
        }
        assertEquals(json, job.lastEffectiveTransitRouteJson)
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `갱신한 지연 여정은 이전 도착이 지나도 유지하고 최신 도착 이후에는 종료한다`(expired: Boolean) {
        val checkedAt = queryAt.minusSeconds(if (expired) 480 else 30)
        val departureAt = queryAt.minusSeconds(45 * 60)
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val table = com.noLate.eta.domain.TransitLegTimetable(
            "fixture", com.noLate.eta.domain.SeoulSubwayLine.LINE_4, java.time.LocalDate.of(2026, 7, 24),
            "4012", "DOWN", com.noLate.eta.domain.TransitServiceClass.LOCAL, "FROM", "TO", "오이도",
            departureAt.plusSeconds(120), queryAt.minusSeconds(60), checkedAt,
        )
        val originalRide = EffectiveTransitRouteSegment(0, EffectiveTransitRouteSegmentKind.SUBWAY, 44,
            rideTiming = EffectiveTransitRideTiming(table, table.boardingAt, table.arrivalAt,
                EffectiveTransitRideTimingBasis.TIMETABLE))
        val original = EffectiveTransitRoute("fixture", "f".repeat(64), departureAt, table.arrivalAt, 44,
            listOf(originalRide))
        val latest = original.copy(arrivalAt = queryAt.plusSeconds(180), totalMinutes = 48,
            segments = listOf(originalRide.copy(durationMinutes = 48, rideTiming = EffectiveTransitRideTiming(
                table, table.boardingAt, queryAt.plusSeconds(180), EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME,
                observationSourceUpdatedAt = checkedAt))))
        val update = com.noLate.schedule.application.PlannedTransitRouteUpdate(
            original.identity, checkedAt, route = latest, onlyLastRideObserved = true,
        )
        val snapshot = EffectiveTransitRouteSnapshot(evaluationCheckCount = job.checkCount,
            evaluationNotificationGeneration = job.notificationGeneration, inProgressPlannedRoute = original,
            lastUpdatedPlannedRoute = latest, plannedRouteUpdate = update)
        finish(job, 62, TrafficSource.TIMETABLE_PROVIDER, checkedAt, liveFetchedAt = checkedAt.minusSeconds(1),
            failureReason = TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED, etaStale = true,
            snapshotSchedule = schedule, etaRefreshDueAt = checkedAt.plusSeconds(60), recommendedDepartureAt = departureAt,
            effectiveTransitRouteJson = objectMapper.writeValueAsString(snapshot))
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val showingPlan = service().getDepartureStatus(1L, 10L)
        assertEquals(latest.arrivalAt, showingPlan.plannedJourneyArrivalPrediction?.predictedArrivalAt)
        assertEquals(departureAt, showingPlan.recommendedDepartureAt)
        assertNull(showingPlan.source)
        assertNull(showingPlan.travelMinutes)

        val readAtLatestArrival = ScheduleDepartureEtaService(
            scheduleRepository, travelPlanRepository, pushJobRepository, scheduleAccessPolicy, objectMapper,
            clock = Clock.fixed(latest.arrivalAt, ZoneOffset.UTC),
        ).getDepartureStatus(1L, 10L)
        assertNull(readAtLatestArrival.plannedJourneyArrivalPrediction)
        assertEquals(departureAt, readAtLatestArrival.recommendedDepartureAt)
        assertEquals(if (expired) TrafficSource.SAVED_FALLBACK else TrafficSource.TIMETABLE_PROVIDER, readAtLatestArrival.source)
    }

    @Test
    fun `출발한 계획 경로의 잠정 도착은 정상 ETA와 알림 출발시각으로 승격하지 않는다`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val checkedAt = queryAt.minusSeconds(30)
        val departureAt = queryAt.minus(10, ChronoUnit.MINUTES)
        val arrivalAt = queryAt.plus(40, ChronoUnit.MINUTES)
        val table = com.noLate.eta.domain.TransitLegTimetable("fixture", com.noLate.eta.domain.SeoulSubwayLine.LINE_4,
            java.time.LocalDate.of(2026, 7, 24), "4012", "DOWN", com.noLate.eta.domain.TransitServiceClass.LOCAL,
            "FROM", "TO", "오이도", departureAt.plusSeconds(120), arrivalAt.minusSeconds(180), checkedAt)
        val plan = EffectiveTransitRoute("fixture", "f".repeat(64), departureAt, arrivalAt, 50,
            listOf(EffectiveTransitRouteSegment(0, EffectiveTransitRouteSegmentKind.SUBWAY, 45,
                rideTiming = EffectiveTransitRideTiming(table, table.boardingAt, table.arrivalAt,
                    EffectiveTransitRideTimingBasis.TIMETABLE))))
        val snapshot = EffectiveTransitRouteSnapshot(
            evaluationCheckCount = job.checkCount,
            evaluationNotificationGeneration = job.notificationGeneration,
            accepted = false, inProgressPlannedRoute = plan)
        finish(job, 50, TrafficSource.SELECTED_ROUTE, checkedAt,
            failureReason = TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
            snapshotSchedule = schedule, recommendedDepartureAt = departureAt,
            effectiveTransitRouteJson = objectMapper.writeValueAsString(snapshot))
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(arrivalAt, result.plannedJourneyArrivalPrediction?.predictedArrivalAt)
        assertEquals(com.noLate.schedule.domain.PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN,
            result.plannedJourneyArrivalPrediction?.basis)
        assertEquals(departureAt, result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
        assertNull(result.effectiveTransitRoute)
        assertNull(result.travelMinutes)
        assertNull(result.source)
        assertNull(result.failureReason)
        assertEquals(50, result.originReplanDiagnostic?.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.originReplanDiagnostic?.source)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
            result.originReplanDiagnostic?.failureReason)
        assertTrue(result.stale)
        assertEquals(departureAt, job.lastRecommendedDepartureAt)
        assertNull(job.lastPredictedArrivalAt)
    }

    @Test
    fun `출발 후 새 출발지 재검색이 늦은 경로를 찾아도 저장된 여정과 혼합하지 않는다`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val checkedAt = queryAt.minusSeconds(30)
        val departureAt = queryAt.minus(10, ChronoUnit.MINUTES)
        val plannedArrivalAt = queryAt.plus(40, ChronoUnit.MINUTES)
        val replanArrivalAt = schedule.startAt.plus(5, ChronoUnit.MINUTES)
        val plan = EffectiveTransitRoute("fixture", "f".repeat(64), departureAt,
            plannedArrivalAt, 50, listOf(EffectiveTransitRouteSegment(
                0, EffectiveTransitRouteSegmentKind.SUBWAY, 50)))
        val snapshot = EffectiveTransitRouteSnapshot(
            evaluationCheckCount = job.checkCount,
            evaluationNotificationGeneration = job.notificationGeneration,
            accepted = true,
            inProgressPlannedRoute = plan,
            effectiveTransitRoute = EffectiveTransitRoute("fixture", "a".repeat(64), checkedAt,
                replanArrivalAt, 60, listOf(EffectiveTransitRouteSegment(
                    0, EffectiveTransitRouteSegmentKind.SUBWAY, 60))),
        )
        finish(job, 60, TrafficSource.LIVE_PROVIDER, checkedAt,
            liveFetchedAt = checkedAt, snapshotSchedule = schedule, etaStale = true,
            failureReason = TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE,
            recommendedDepartureAt = departureAt, predictedArrivalAt = replanArrivalAt,
            effectiveTransitRouteJson = objectMapper.writeValueAsString(snapshot))
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(plannedArrivalAt, result.plannedJourneyArrivalPrediction?.predictedArrivalAt)
        assertEquals(departureAt, result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
        assertNull(result.travelMinutes)
        assertNull(result.source)
        assertNull(result.failureReason)
        assertEquals(60, result.originReplanDiagnostic?.travelMinutes)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.originReplanDiagnostic?.source)
        assertEquals(TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE,
            result.originReplanDiagnostic?.failureReason)
        assertEquals(replanArrivalAt, job.lastPredictedArrivalAt)
    }

    @ParameterizedTest
    @CsvSource("FRESH,true", "AGE_120,true", "AGE_121,false", "UNCATCHABLE,false", "MISSING,false", "WRONG_CHECK,false", "WRONG_GENERATION,false")
    fun `실시간 여정은 API 조회 시점에도 첫 차량 관측이 유효해야 정상으로 제공한다`(boundary: String, fresh: Boolean) {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val checkedAt = queryAt.minusSeconds(60)
        val observation = com.noLate.eta.domain.TransitFirstBoardingObservation(
            provider = "seoul-openapi", stationName = "노원", lineName = "4호선",
            departureAt = checkedAt, readyAt = checkedAt.plusSeconds(120),
            expectedBoardingAt = queryAt.plusSeconds(if (boundary == "UNCATCHABLE") 119 else 180),
            sourceUpdatedAt = queryAt.minusSeconds(when (boundary) {
                "AGE_120" -> 120
                "AGE_121" -> 121
                else -> 60
            }),
            evaluatedAt = checkedAt, trainNumber = "4695",
        )
        val route = EffectiveTransitRoute("odsay", "a".repeat(64), checkedAt,
            queryAt.plusSeconds(600), 11, listOf(
                EffectiveTransitRouteSegment(0, EffectiveTransitRouteSegmentKind.SUBWAY, 11)))
        val snapshot = EffectiveTransitRouteSnapshot(
            evaluationCheckCount = if (boundary == "WRONG_CHECK") 2 else job.checkCount,
            evaluationNotificationGeneration = if (boundary == "WRONG_GENERATION") 1 else job.notificationGeneration,
            accepted = true, transitTimingBasis = TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE,
            effectiveTransitRoute = route,
            firstBoardingObservation = observation.takeUnless { boundary == "MISSING" },
        )
        finish(job, 11, TrafficSource.LIVE_PROVIDER, checkedAt, liveFetchedAt = checkedAt,
            snapshotSchedule = schedule, recommendedDepartureAt = checkedAt, predictedArrivalAt = route.arrivalAt,
            effectiveTransitRouteJson = objectMapper.writeValueAsString(snapshot))
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(!fresh, result.stale)
        // 첫 열차의 관측이 신선해도 환승편은 미래 시간표이므로 전체 도착 ETA는 중간 신뢰도다.
        assertEquals(if (fresh) ScheduleEtaConfidence.MEDIUM else ScheduleEtaConfidence.LOW, result.confidence)
        assertEquals(route.takeIf { fresh }, result.effectiveTransitRoute)
        assertEquals(observation.takeIf { fresh }, result.firstBoardingObservation)
        assertEquals(route.arrivalAt.takeIf { fresh }, result.predictedArrivalAt)
        assertEquals(true.takeIf { fresh }, result.onTimeArrivalPossible)
        assertEquals(if (fresh) null else if (boundary == "AGE_121")
            TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE else TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE,
            result.failureReason)
        // API 읽기는 알람 추천시각과 원본 평가 이력을 변경하지 않는다.
        assertEquals(checkedAt, result.recommendedDepartureAt)
        assertFalse(requireNotNull(job.lastEtaStale))
        assertNull(job.lastEtaFailureReason)
        assertEquals(route.arrivalAt, job.lastPredictedArrivalAt)
        assertEquals(objectMapper.writeValueAsString(snapshot), job.lastEffectiveTransitRouteJson)
    }

    @ParameterizedTest
    @CsvSource(
        "60,true,TRANSFER_ALIGHTING_REALTIME,false", "121,false,TRANSFER_ALIGHTING_REALTIME,false",
        "60,true,FIRST_RIDE_BOARDING_AND_ALIGHTING_REALTIME,false", "121,false,FIRST_RIDE_BOARDING_AND_ALIGHTING_REALTIME,false",
        "60,true,FIRST_RIDE_BOARDING_AND_ALIGHTING_REALTIME,true", "121,false,FIRST_RIDE_BOARDING_AND_ALIGHTING_REALTIME,true",
    )
    fun `하차역 관측이 만료되면 계산된 도착 ETA도 정상 응답에서 제외한다`(
        ageSeconds: Long, fresh: Boolean, basis: EffectiveTransitRideTimingBasis, direct: Boolean,
    ) {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val checkedAt = queryAt.minusSeconds(60)
        val observation = com.noLate.eta.domain.TransitFirstBoardingObservation(
            provider = "seoul-openapi", stationName = "노원", lineName = "4호선",
            departureAt = checkedAt, readyAt = checkedAt.plusSeconds(120),
            expectedBoardingAt = queryAt.plusSeconds(180),
            sourceUpdatedAt = checkedAt, evaluatedAt = checkedAt, trainNumber = "4695",
        )
        val table = com.noLate.eta.domain.TransitLegTimetable(
            "fixture", com.noLate.eta.domain.SeoulSubwayLine.LINE_2,
            java.time.LocalDate.of(2026, 7, 24), "2480", "DOWN",
            com.noLate.eta.domain.TransitServiceClass.LOCAL,
            "사당", "신림", "신도림", queryAt.plusSeconds(240), queryAt.plusSeconds(480), checkedAt,
        )
        val route = EffectiveTransitRoute("fixture", "a".repeat(64), checkedAt,
            queryAt.plusSeconds(600), 11, listOf(
                EffectiveTransitRouteSegment(0, EffectiveTransitRouteSegmentKind.SUBWAY, 11,
                    rideTiming = EffectiveTransitRideTiming(
                        table, table.boardingAt, queryAt.plusSeconds(600),
                        basis,
                        observationSourceUpdatedAt = queryAt.minusSeconds(ageSeconds),
                    )),
            ))
        val snapshot = EffectiveTransitRouteSnapshot(
            evaluationCheckCount = job.checkCount,
            evaluationNotificationGeneration = job.notificationGeneration,
            accepted = true,
            transitTimingBasis = if (direct) TransitTimingBasis.SAME_SUBWAY_BOARDING_AND_ALIGHTING_REALTIME
                else TransitTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_OBSERVED,
            effectiveTransitRoute = route,
            firstBoardingObservation = if (direct) observation.copy(stationName = "사당", lineName = "2호선",
                trainNumber = table.trainNumber, expectedBoardingAt = table.boardingAt) else observation,
        )
        finish(job, 11, TrafficSource.LIVE_PROVIDER, checkedAt, liveFetchedAt = checkedAt,
            snapshotSchedule = schedule, recommendedDepartureAt = checkedAt,
            predictedArrivalAt = route.arrivalAt,
            effectiveTransitRouteJson = objectMapper.writeValueAsString(snapshot))
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(route.takeIf { fresh }, result.effectiveTransitRoute)
        assertEquals(route.arrivalAt.takeIf { fresh }, result.predictedArrivalAt)
        assertEquals(!fresh, result.stale)
        assertEquals(if (fresh) ScheduleEtaConfidence.MEDIUM else ScheduleEtaConfidence.LOW, result.confidence)
        assertEquals(if (fresh) null else TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE,
            result.failureReason)
        // 읽기에서 오래된 저장 결과나 이미 계획된 알람 시각을 변경하지 않는다.
        assertEquals(route.arrivalAt, job.lastPredictedArrivalAt)
        assertEquals(checkedAt, job.lastRecommendedDepartureAt)
    }

    @ParameterizedTest
    @CsvSource("CURRENT,true", "STALE_SOURCE,true", "WRONG_CHECK,false", "WRONG_GENERATION,false",
        "ALTERNATIVE,true", "LEGACY,true", "INCOMPLETE,false", "PAST,false", "API_ERROR,false", "LATE,false", "STALE_SOURCE_LATE,false",
        "VERIFIED_HORIZON,true", "HORIZON_NO_PLAN,false", "API_ERROR_WITH_PLAN,false")
    fun `미래 시간표 미리보기는 정상 ETA 및 알람 시각과 분리하고 현재 완전한 평가만 제공한다`(boundary: String, expose: Boolean) {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val checkedAt = queryAt.minusSeconds(60)
        val departure = if (boundary == "PAST") queryAt.minusSeconds(1) else queryAt.plusSeconds(600)
        val arrival = if (boundary.endsWith("LATE")) schedule.startAt.plusSeconds(60) else departure.plusSeconds(1200)
        val table = com.noLate.eta.domain.TransitLegTimetable("fixture", com.noLate.eta.domain.SeoulSubwayLine.LINE_4,
            java.time.LocalDate.of(2026, 7, 24), "4012", "DOWN", com.noLate.eta.domain.TransitServiceClass.LOCAL,
            "FROM", "TO", "오이도", departure.plusSeconds(120), arrival, checkedAt)
        val route = EffectiveTransitRoute("fixture", "e".repeat(64), departure, arrival, 20, listOf(
            EffectiveTransitRouteSegment(0, EffectiveTransitRouteSegmentKind.SUBWAY, 20,
                rideTiming = if (boundary == "INCOMPLETE") null else com.noLate.schedule.application.EffectiveTransitRideTiming(
                    table, table.boardingAt, table.arrivalAt, com.noLate.schedule.application.EffectiveTransitRideTimingBasis.TIMETABLE))))
        val provenance = when (boundary) {
            "ALTERNATIVE" -> TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE
            "LEGACY" -> null
            else -> TransitRouteProvenance.SELECTED_ROUTE_PRESERVED
        }
        val snapshot = EffectiveTransitRouteSnapshot(
            evaluationCheckCount = if (boundary == "WRONG_CHECK") 2 else job.checkCount,
            evaluationNotificationGeneration = if (boundary == "WRONG_GENERATION") 1 else job.notificationGeneration,
            accepted = false, transitRouteProvenance = provenance, transitTimingBasis = TransitTimingBasis.TIMETABLE_ONLY,
            effectiveTransitRoute = route, lastTimetablePlannedRoute = route.takeIf { boundary in setOf("VERIFIED_HORIZON", "API_ERROR_WITH_PLAN") })
        finish(job, 40, TrafficSource.TIMETABLE_PROVIDER, checkedAt, liveFetchedAt = checkedAt,
            failureReason = when {
                boundary.startsWith("API_ERROR") -> TrafficFailureReasons.TRANSIT_ARRIVAL_API_ERROR
                "HORIZON" in boundary -> TrafficFailureReasons.TRANSIT_ARRIVAL_OUT_OF_HORIZON
                boundary.startsWith("STALE_SOURCE") -> TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE
                else -> TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED
            },
            snapshotSchedule = schedule, etaStale = true,
            effectiveTransitRouteJson = objectMapper.writeValueAsString(snapshot))
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)
        val result = service().getDepartureStatus(1L, 10L)
        assertEquals(route.takeIf { expose }, result.futureTimetablePrediction)
        assertEquals(provenance.takeIf { expose }, result.futureTimetableRouteProvenance)
        assertNull(result.transitRouteProvenance)
        assertTrue(result.stale)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertNull(result.effectiveTransitRoute)
        assertNull(result.predictedArrivalAt)
        assertNull(result.onTimeArrivalPossible)
        assertEquals(job.lastRecommendedDepartureAt, result.recommendedDepartureAt)
    }

    @ParameterizedTest
    @CsvSource("CURRENT,true", "STALE,false", "UNCATCHABLE,false", "FUTURE_SOURCE,false", "WRONG_CHECK,false", "WRONG_GENERATION,false")
    fun `별도 첫 차량 관측을 노출해도 전체 ETA는 LOW이며 오래되거나 다른 평가의 관측은 숨긴다`(boundary: String, expose: Boolean) {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val checkedAt = queryAt.minusSeconds(60)
        val observation = com.noLate.eta.domain.TransitFirstBoardingObservation(
            provider = "seoul-openapi", stationName = "서울역", lineName = "4호선",
            departureAt = checkedAt, readyAt = checkedAt.plusSeconds(180),
            expectedBoardingAt = checkedAt.plusSeconds(if (boundary == "UNCATCHABLE") 200 else 360),
            sourceUpdatedAt = when (boundary) {
                "STALE" -> queryAt.minusSeconds(121)
                "FUTURE_SOURCE" -> queryAt.plusSeconds(61)
                else -> checkedAt
            },
            evaluatedAt = checkedAt,
        )
        val snapshot = EffectiveTransitRouteSnapshot(
            evaluationCheckCount = if (boundary == "WRONG_CHECK") 2 else job.checkCount,
            evaluationNotificationGeneration = if (boundary == "WRONG_GENERATION") 1 else job.notificationGeneration,
            firstBoardingObservation = observation,
        )
        finish(
            job = job, travelMinutes = 40, source = TrafficSource.SELECTED_ROUTE,
            checkedAt = checkedAt, failureReason = TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY,
            snapshotSchedule = schedule, effectiveTransitRouteJson = objectMapper.writeValueAsString(snapshot),
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val status = service().getDepartureStatus(1L, 10L)

        assertEquals(observation.takeIf { expose }, status.firstBoardingObservation)
        assertEquals(ScheduleEtaConfidence.LOW, status.confidence)
        assertTrue(status.stale)
        assertNull(status.predictedArrivalAt)
        assertNull(status.onTimeArrivalPossible)
        assertNull(status.firstBoardingWaitMinutes)
    }

    @Mock
    lateinit var scheduleRepository: ScheduleRepository

    @Mock
    lateinit var travelPlanRepository: ScheduleTravelPlanRepository

    @Mock
    lateinit var pushJobRepository: SchedulePushJobRepository

    @Mock
    lateinit var scheduleAccessPolicy: ScheduleAccessPolicy

    @Test
    fun `저장된 LIVE job snapshot을 provider 재호출 없이 그대로 반환한다`() {
        val schedule = schedule()
        val firstCheckedAt = queryAt.minus(20, ChronoUnit.MINUTES)
        val secondCheckedAt = queryAt.minus(10, ChronoUnit.MINUTES)
        val liveFetchedAt = secondCheckedAt.minusSeconds(2)
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 35,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = firstCheckedAt,
            liveFetchedAt = firstCheckedAt.minusSeconds(2),
        )
        finish(
            job = job,
            travelMinutes = 40,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = secondCheckedAt,
            liveFetchedAt = liveFetchedAt,
        )
        job.startProcessing("in-flight-worker")
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(40, result.travelMinutes)
        assertEquals(schedule.startAt.minus(40, ChronoUnit.MINUTES), result.recommendedDepartureAt)
        assertEquals(secondCheckedAt, result.evaluatedAt)
        assertEquals(liveFetchedAt, result.liveFetchedAt)
        assertFalse(result.evaluatedAt.isBefore(result.liveFetchedAt))
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(ScheduleEtaConfidence.HIGH, result.confidence)
        assertFalse(result.stale)
        assertEquals(5, result.lastTrafficChangeMinutes)
        assertEquals(secondCheckedAt, result.lastChangedAt)
        assertEquals(job.nextCheckAt, result.nextCheckAt)
        assertNull(result.preparationMinutes)
        assertNull(result.preparationStartAt)
        assertNull(result.safetyBufferMinutes)
        assertEquals("Asia/Seoul", result.timeZone)
    }

    @Test
    fun `다음 재조회 시각의 freshness grace 경계까지 worker snapshot을 허용한다`() {
        val schedule = schedule()
        val checkedAt = queryAt.minus(25, ChronoUnit.MINUTES)
        val liveFetchedAt = checkedAt.minusSeconds(2)
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 40,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = checkedAt,
            liveFetchedAt = liveFetchedAt,
            snapshotSchedule = schedule,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(queryAt.minus(5, ChronoUnit.MINUTES), job.nextCheckAt)
        assertEquals(40, result.travelMinutes)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(liveFetchedAt, result.liveFetchedAt)
        assertFalse(result.stale)
    }

    @Test
    fun `다음 재조회 freshness grace를 초과한 worker snapshot은 저장 경로로 내린다`() {
        val schedule = schedule()
        val checkedAt = queryAt.minus(25, ChronoUnit.MINUTES).minusSeconds(1)
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 40,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = checkedAt,
            liveFetchedAt = checkedAt.minusSeconds(2),
            snapshotSchedule = schedule,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(queryAt.minus(5, ChronoUnit.MINUTES).minusSeconds(1), job.nextCheckAt)
        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(queryAt, result.evaluatedAt)
        assertNull(result.liveFetchedAt)
        assertTrue(result.stale)
        assertNull(result.effectiveTransitRoute)
    }

    @Test
    fun `retry 예약이 미래로 이동해도 만료된 provider snapshot을 되살리지 않는다`() {
        val schedule = schedule()
        val checkedAt = queryAt.minus(26, ChronoUnit.MINUTES)
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 40,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = checkedAt,
            liveFetchedAt = checkedAt.minusSeconds(2),
            snapshotSchedule = schedule,
        )
        job.retryLater("push retry", queryAt.plus(30, ChronoUnit.MINUTES))
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(queryAt.plus(30, ChronoUnit.MINUTES), job.nextCheckAt)
        assertEquals(checkedAt.plus(20, ChronoUnit.MINUTES), job.lastEtaRefreshDueAt)
        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertTrue(result.stale)
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `processing timeout 복구 예약도 만료된 provider snapshot을 되살리지 않는다`() {
        val schedule = schedule()
        val checkedAt = queryAt.minus(26, ChronoUnit.MINUTES)
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 40,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = checkedAt,
            liveFetchedAt = checkedAt.minusSeconds(2),
            snapshotSchedule = schedule,
        )
        job.startProcessing("timed-out-worker", checkedAt.plus(20, ChronoUnit.MINUTES))
        job.recoverProcessingTimeout(
            reason = "processing timeout",
            nextCheckAt = queryAt.plus(30, ChronoUnit.MINUTES),
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(checkedAt.plus(20, ChronoUnit.MINUTES), job.lastEtaRefreshDueAt)
        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertTrue(result.stale)
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `설정한 60분 평가 주기는 grace까지 provider snapshot을 유지한다`() {
        val schedule = schedule()
        val checkedAt = queryAt.minus(64, ChronoUnit.MINUTES)
        val job = job(schedule, intervalMinutes = 60)
        finish(
            job = job,
            travelMinutes = 40,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = checkedAt,
            liveFetchedAt = checkedAt.minusSeconds(2),
            snapshotSchedule = schedule,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(checkedAt.plus(60, ChronoUnit.MINUTES), job.nextCheckAt)
        assertEquals(40, result.travelMinutes)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertFalse(result.stale)
        assertEquals(checkedAt.plus(60, ChronoUnit.MINUTES), result.etaRefreshDueAt)
    }

    @Test
    fun `조기 reminder ETA 경계를 retry 시각이 덮어써도 snapshot은 부활하지 않는다`() {
        val schedule = schedule()
        val checkedAt = queryAt.minus(11, ChronoUnit.MINUTES)
        val originalEtaDueAt = checkedAt.plus(5, ChronoUnit.MINUTES)
        val job = job(schedule, intervalMinutes = 60)
        finish(
            job = job,
            travelMinutes = 40,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = checkedAt,
            liveFetchedAt = checkedAt.minusSeconds(2),
            snapshotSchedule = schedule,
            etaRefreshDueAt = originalEtaDueAt,
        )
        job.retryLater("push retry", queryAt.plus(30, ChronoUnit.MINUTES))
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(originalEtaDueAt, job.lastEtaRefreshDueAt)
        assertEquals(queryAt.plus(30, ChronoUnit.MINUTES), job.nextCheckAt)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertTrue(result.stale)
    }

    @ParameterizedTest
    @CsvSource("45,true", "46,false")
    fun `같은 버스 도착 예보의 상태 API는 원본 만료 후에도 계획 시각을 유지한다`(sourceAgeSeconds: Long, fresh: Boolean) {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val departureAt = queryAt.minusSeconds(150)
        val sourceAt = queryAt.minusSeconds(sourceAgeSeconds)
        val initialAlighting = queryAt.plusSeconds(90)
        val updatedAlighting = queryAt.plusSeconds(240)
        val bus = EffectiveTransitRouteSegment(0, EffectiveTransitRouteSegmentKind.BUS, 4, lineName = "101",
            fromName = "승차 정류장", toName = "하차 정류장", busRidePrediction = EffectiveBusRidePrediction(
                departureAt.plusSeconds(90), initialAlighting, departureAt.minusSeconds(1)))
        val walk = EffectiveTransitRouteSegment(1, EffectiveTransitRouteSegmentKind.WALK, 1, durationSeconds = 60)
        val original = EffectiveTransitRoute("odsay", "b".repeat(64), departureAt, initialAlighting.plusSeconds(60), 5, listOf(bus, walk))
        val revised = original.copy(arrivalAt = updatedAlighting.plusSeconds(60), totalMinutes = 8,
            segments = listOf(bus.copy(durationMinutes = 7, busRidePrediction = bus.busRidePrediction!!.copy(
                alightingAt = updatedAlighting, alightingObservationSourceUpdatedAt = sourceAt)), walk))
        val snapshot = EffectiveTransitRouteSnapshot(evaluationCheckCount = job.checkCount,
            evaluationNotificationGeneration = job.notificationGeneration, inProgressPlannedRoute = original,
            plannedRouteUpdate = PlannedTransitRouteUpdate(original.identity, sourceAt, route = revised))
        finish(job, 5, TrafficSource.SELECTED_ROUTE, queryAt.minusSeconds(1), snapshotSchedule = schedule,
            failureReason = TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY,
            recommendedDepartureAt = departureAt, effectiveTransitRouteJson = objectMapper.writeValueAsString(snapshot))
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val response = service().getDepartureStatus(1L, 10L)

        val provisional = requireNotNull(response.plannedJourneyArrivalPrediction)
        assertEquals(departureAt, response.recommendedDepartureAt)
        assertNull(response.predictedArrivalAt)
        assertNull(response.effectiveTransitRoute)
        assertEquals(revised.arrivalAt, provisional.predictedArrivalAt)
        assertEquals(if (fresh) PlannedJourneyArrivalBasis.MATCHED_BUS_REALTIME else PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN,
            provisional.basis)
        assertEquals(sourceAt.takeIf { fresh }, provisional.sourceUpdatedAt)
        assertEquals(sourceAt.takeIf { fresh }, provisional.effectiveTransitRoute
            ?.segments?.first()?.busRidePrediction?.alightingObservationSourceUpdatedAt)
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `버스 관측 대기 중 보호한 출발과 열차 구간만 제공한다`(statusExpired: Boolean) {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val departure = queryAt.minusSeconds(1500)
        val arrival = queryAt.plusSeconds(480)
        val newerArrival = arrival.plusSeconds(15)
        val table = com.noLate.eta.domain.TransitLegTimetable("seoul-timetable", com.noLate.eta.domain.SeoulSubwayLine.LINE_4,
            java.time.LocalDate.of(2026, 7, 24), "4703", "DOWN", com.noLate.eta.domain.TransitServiceClass.LOCAL,
            "0431", "0433", "오이도", departure.plusSeconds(120), arrival, departure.minusSeconds(60))
        val walk = EffectiveTransitRouteSegment(0, EffectiveTransitRouteSegmentKind.WALK, 1, durationSeconds = 30)
        val train = EffectiveTransitRouteSegment(1, EffectiveTransitRouteSegmentKind.SUBWAY, 33, lineName = "4호선",
            fromName = "동작", toName = "사당", rideTiming = EffectiveTransitRideTiming(table, table.boardingAt,
                arrival, EffectiveTransitRideTimingBasis.TIMETABLE))
        val rail = EffectiveTransitRoute("odsay", "b".repeat(64), departure, arrival, 33, listOf(walk, train))
        val sourceAt = queryAt.minusSeconds(180)
        val observed = rail.copy(arrivalAt = newerArrival, totalMinutes = 34, segments = listOf(walk,
            train.copy(rideTiming = train.rideTiming!!.copy(predictedArrivalAt = newerArrival,
                basis = EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME, observationSourceUpdatedAt = sourceAt))))
        val pending = DeferredSeoulBusTransfer(rail,
            listOf(walk.copy(sequence = 2, walkingScope = com.noLate.eta.domain.TransitWalkingScope.TRANSFER)),
            EffectiveTransitRouteSegment(3, EffectiveTransitRouteSegmentKind.BUS, 5),
            listOf(walk.copy(sequence = 4, durationSeconds = 60)),
            com.noLate.transit.domain.SeoulBusConnectionStops("100100101", "101000001", "101000002", "01001", "01002"),
            schedule.startAt, PlannedTransitRouteUpdate(rail.identity, sourceAt, route = observed))
        val snapshot = EffectiveTransitRouteSnapshot(evaluationCheckCount = job.checkCount,
            evaluationNotificationGeneration = job.notificationGeneration, deferredSeoulBusTransfer = pending)
        finish(job, 5, TrafficSource.SELECTED_ROUTE, queryAt.minusSeconds(if (statusExpired) 1500 else 1), snapshotSchedule = schedule,
            failureReason = TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN, recommendedDepartureAt = departure,
            effectiveTransitRouteJson = objectMapper.writeValueAsString(snapshot))
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val response = service().getDepartureStatus(1L, 10L)

        assertEquals("TRANSFER_FORECAST_PENDING", response.plannedJourneyTimingFailureReason)
        assertEquals(departure, response.recommendedDepartureAt)
        assertNull(response.travelMinutes)
        assertNull(response.predictedArrivalAt)
        assertNull(response.plannedJourneyArrivalPrediction)
        val prefix = requireNotNull(response.pendingTransferRailPlan)
        assertEquals(listOf(EffectiveTransitRouteSegmentKind.WALK, EffectiveTransitRouteSegmentKind.SUBWAY,
            EffectiveTransitRouteSegmentKind.WALK), prefix.segments.map { it.kind })
        assertEquals(newerArrival.plusSeconds(30), prefix.arrivalAt)
        assertEquals(30L, prefix.segments.last().durationSeconds)
        assertEquals(newerArrival, prefix.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals(EffectiveTransitRideTimingBasis.PLANNED_RIDE_BASELINE, prefix.segments[1].rideTiming?.basis)
        assertNull(prefix.segments[1].rideTiming?.observationSourceUpdatedAt)
    }

    @Test
    fun `legacy null ETA refresh due snapshot은 현재 상태로 채택하지 않는다`() {
        val schedule = schedule()
        val checkedAt = queryAt.minus(5, ChronoUnit.MINUTES)
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 40,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = checkedAt,
            liveFetchedAt = checkedAt.minusSeconds(2),
            snapshotSchedule = schedule,
        )
        setField(job, "lastEtaRefreshDueAt", null)
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertTrue(result.stale)
        assertNull(result.etaRefreshDueAt)
    }

    @Test
    fun `설정 주기를 넘어선 손상 ETA refresh due는 미래여도 거부한다`() {
        val schedule = schedule()
        val checkedAt = queryAt.minus(5, ChronoUnit.MINUTES)
        val job = job(schedule, intervalMinutes = 20)
        finish(
            job = job,
            travelMinutes = 40,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = checkedAt,
            liveFetchedAt = checkedAt.minusSeconds(2),
            snapshotSchedule = schedule,
            etaRefreshDueAt = checkedAt.plus(21, ChronoUnit.MINUTES),
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertTrue(result.stale)
        assertNull(result.etaRefreshDueAt)
    }

    @Test
    fun `ODsay 실제 출발시각이 ETA 역산값보다 이르더라도 시간표 snapshot을 그대로 노출한다`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val checkedAt = queryAt.minus(5, ChronoUnit.MINUTES)
        val providerFetchedAt = checkedAt.minusSeconds(2)
        val providerRecommendedDepartureAt =
            schedule.startAt.minus(50, ChronoUnit.MINUTES)
        val route = requireNotNull(schedule.route)
        job.startProcessing("odsay-worker")
        job.finishCheck(
            travelMinutes = 35,
            recommendedDepartureAt = providerRecommendedDepartureAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = checkedAt.plus(job.intervalMinutes.toLong(), ChronoUnit.MINUTES),
            completeAfterCheck = false,
            etaSource = TrafficSource.TIMETABLE_PROVIDER,
            liveFetchedAt = providerFetchedAt,
            etaStale = false,
            etaRouteFingerprint = ScheduleEtaRouteFingerprint.calculate(
                schedule = schedule,
                travelMinutes = route.travelMinutes,
                travelMode = route.travelMode,
                originLat = route.originLat,
                originLng = route.originLng,
                routeJson = route.routeJson,
            ),
            now = checkedAt,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(35, result.travelMinutes)
        assertEquals(providerRecommendedDepartureAt, result.recommendedDepartureAt)
        assertEquals(TrafficSource.TIMETABLE_PROVIDER, result.source)
        assertEquals(ScheduleEtaConfidence.MEDIUM, result.confidence)
        assertFalse(result.stale)
        assertEquals(checkedAt, result.evaluatedAt)
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `departure status exposes the accepted effective transit route and timing basis`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val checkedAt = queryAt.minus(5, ChronoUnit.MINUTES)
        val providerFetchedAt = checkedAt.minusSeconds(2)
        val recommendedDepartureAt = schedule.startAt.minus(60, ChronoUnit.MINUTES)
        val effectiveRoute = EffectiveTransitRoute(
            provider = "ODSAY",
            identity = "d".repeat(64),
            departureAt = recommendedDepartureAt,
            arrivalAt = schedule.startAt.minus(10, ChronoUnit.MINUTES),
            totalMinutes = 50,
            segments = listOf(
                EffectiveTransitRouteSegment(
                    sequence = 0,
                    kind = EffectiveTransitRouteSegmentKind.WALK,
                    durationMinutes = 4,
                    fromName = "집",
                    toName = "강남역",
                ),
                EffectiveTransitRouteSegment(
                    sequence = 1,
                    kind = EffectiveTransitRouteSegmentKind.SUBWAY,
                    durationMinutes = 40,
                    waitingMinutes = 6,
                    lineName = "2호선",
                    fromName = "강남역",
                    toName = "시청역",
                ),
            ),
        )
        val snapshotJson = objectMapper.writeValueAsString(
            EffectiveTransitRouteSnapshot(
                evaluationCheckCount = 0,
                accepted = true,
                transitRouteProvenance = TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
                transitTimingBasis = TransitTimingBasis.TIMETABLE_ONLY,
                firstBoardingWaitMinutes = 6,
                routeChanged = true,
                routeTransitioned = true,
                effectiveTransitRoute = effectiveRoute,
            )
        )
        val route = requireNotNull(schedule.route)
        job.startProcessing("effective-route-worker")
        job.finishCheck(
            travelMinutes = 50,
            recommendedDepartureAt = recommendedDepartureAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = checkedAt.plus(job.intervalMinutes.toLong(), ChronoUnit.MINUTES),
            completeAfterCheck = false,
            etaSource = TrafficSource.TIMETABLE_PROVIDER,
            liveFetchedAt = providerFetchedAt,
            etaStale = false,
            predictedArrivalAt = effectiveRoute.arrivalAt,
            etaTravelMode = ScheduleTravelMode.TRANSIT,
            etaRouteFingerprint = ScheduleEtaRouteFingerprint.calculate(
                schedule = schedule,
                travelMinutes = route.travelMinutes,
                travelMode = route.travelMode,
                originLat = route.originLat,
                originLng = route.originLng,
                routeJson = route.routeJson,
            ),
            effectiveTransitRouteJson = snapshotJson,
            now = checkedAt,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE, result.transitRouteProvenance)
        assertEquals(TransitTimingBasis.TIMETABLE_ONLY, result.transitTimingBasis)
        assertEquals(6, result.firstBoardingWaitMinutes)
        assertTrue(result.routeChanged)
        assertEquals("d".repeat(64), result.effectiveTransitRoute?.identity)
        assertEquals("2호선", result.effectiveTransitRoute?.segments?.last()?.lineName)
    }

    @Test
    fun `corrupt effective route json does not invalidate existing departure status fields`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val checkedAt = queryAt.minus(10, ChronoUnit.MINUTES)
        val fetchedAt = checkedAt.minusSeconds(2)
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 35,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = checkedAt,
            liveFetchedAt = fetchedAt,
            snapshotSchedule = schedule,
            effectiveTransitRouteJson = "{broken-json",
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(35, result.travelMinutes)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(fetchedAt, result.liveFetchedAt)
        assertFalse(result.stale)
        assertNull(result.transitRouteProvenance)
        assertNull(result.transitTimingBasis)
        assertNull(result.firstBoardingWaitMinutes)
        assertFalse(result.routeChanged)
        assertNull(result.effectiveTransitRoute)
    }

    @Test
    fun `diagnostic snapshot without an accepted route keeps new departure fields empty`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val checkedAt = queryAt.minus(10, ChronoUnit.MINUTES)
        val fetchedAt = checkedAt.minusSeconds(2)
        val diagnosticSnapshot = EffectiveTransitRouteSnapshot(
            evaluationCheckCount = 0,
            accepted = false,
            transitRouteProvenance = TransitRouteProvenance.SELECTED_ROUTE_PRESERVED,
            transitTimingBasis = TransitTimingBasis.TIMETABLE_TRANSFER_UNKNOWN,
            firstBoardingWaitMinutes = 0,
            routeChanged = false,
            routeTransitioned = false,
            effectiveTransitRoute = null,
        )
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 35,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = checkedAt,
            liveFetchedAt = fetchedAt,
            snapshotSchedule = schedule,
            effectiveTransitRouteJson = objectMapper.writeValueAsString(diagnosticSnapshot),
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(35, result.travelMinutes)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertFalse(result.stale)
        assertNull(result.transitRouteProvenance)
        assertNull(result.transitTimingBasis)
        assertNull(result.firstBoardingWaitMinutes)
        assertFalse(result.routeChanged)
        assertNull(result.effectiveTransitRoute)
    }

    @Test
    fun `정시 도착 불가 진단은 늦은 예측 도착을 LOW confidence 상태로 노출한다`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val checkedAt = queryAt.minus(5, ChronoUnit.MINUTES)
        val liveFetchedAt = checkedAt.minusSeconds(2)
        val predictedArrivalAt = schedule.startAt.plus(5, ChronoUnit.MINUTES)
        val travelMinutes = ChronoUnit.MINUTES.between(checkedAt, predictedArrivalAt).toInt()
        val route = requireNotNull(schedule.route)
        job.startProcessing("odsay-live-worker")
        job.finishCheck(
            travelMinutes = travelMinutes,
            recommendedDepartureAt = checkedAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = checkedAt.plus(20, ChronoUnit.MINUTES),
            completeAfterCheck = false,
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = liveFetchedAt,
            etaStale = true,
            etaFailureReason = TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE,
            predictedArrivalAt = predictedArrivalAt,
            etaRouteFingerprint = ScheduleEtaRouteFingerprint.calculate(
                schedule = schedule,
                travelMinutes = route.travelMinutes,
                travelMode = route.travelMode,
                originLat = route.originLat,
                originLng = route.originLng,
                routeJson = route.routeJson,
            ),
            now = checkedAt,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(travelMinutes, result.travelMinutes)
        assertEquals(checkedAt, result.recommendedDepartureAt)
        assertEquals(predictedArrivalAt, result.predictedArrivalAt)
        assertEquals(false, result.onTimeArrivalPossible)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertTrue(result.stale)
        assertEquals(
            TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE,
            result.failureReason,
        )
        assertEquals(liveFetchedAt, result.liveFetchedAt)
    }

    @Test
    fun `환승 실패 fallback snapshot은 선택 경로여도 LOW confidence로 노출한다`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val checkedAt = queryAt.minus(5, ChronoUnit.MINUTES)
        val route = requireNotNull(schedule.route)
        job.startProcessing("transfer-fallback-worker")
        job.finishCheck(
            travelMinutes = 45,
            recommendedDepartureAt = schedule.startAt.minus(45, ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = checkedAt.plus(20, ChronoUnit.MINUTES),
            completeAfterCheck = false,
            etaSource = TrafficSource.SELECTED_ROUTE,
            etaStale = true,
            etaFailureReason = TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
            etaTravelMode = ScheduleTravelMode.TRANSIT,
            etaRouteFingerprint = ScheduleEtaRouteFingerprint.calculate(
                schedule = schedule,
                travelMinutes = route.travelMinutes,
                travelMode = route.travelMode,
                originLat = route.originLat,
                originLng = route.originLng,
                routeJson = route.routeJson,
            ),
            now = checkedAt,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_MISSED, result.failureReason)
    }

    @ParameterizedTest
    @CsvSource(
        "SELECTED_ROUTE, 3841, 65",
        "SELECTED_ROUTE, 3888, 65",
        "SELECTED_ROUTE, 3899, 65",
        "SELECTED_ROUTE, 3900, 65",
        "SELECTED_ROUTE, 3901, 66",
        "SAVED_FALLBACK, 3888, 65",
    )
    fun `초 단위 출발시각을 보존한 추정 snapshot은 최신 진단과 재조회 시각을 전달한다`(
        source: TrafficSource,
        durationSeconds: Long,
        travelMinutes: Int,
    ) {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT, travelMinutes = travelMinutes)
        val checkedAt = queryAt.minus(1, ChronoUnit.MINUTES)
        val departureAt = schedule.startAt.minusSeconds(durationSeconds)
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = travelMinutes,
            source = source,
            checkedAt = checkedAt,
            failureReason = TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY,
            snapshotSchedule = schedule,
            recommendedDepartureAt = departureAt,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(source, result.source)
        assertEquals(departureAt, result.recommendedDepartureAt)
        assertEquals(checkedAt, result.evaluatedAt)
        assertEquals(job.nextCheckAt, result.nextCheckAt)
        assertEquals(job.lastEtaRefreshDueAt, result.etaRefreshDueAt)
        assertEquals(TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY, result.failureReason)
        assertTrue(result.stale)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertNull(result.predictedArrivalAt)
        assertNull(result.onTimeArrivalPossible)
        assertNull(result.effectiveTransitRoute)
    }

    @ParameterizedTest
    @CsvSource("TIMETABLE_PROVIDER,ARRIVAL", "TIMETABLE_PROVIDER,HORIZON", "LIVE_PROVIDER,ARRIVAL")
    fun `채택하지 못한 provider 진단도 보존한 출발시각과 LOW 상태로 전달한다`(
        source: TrafficSource,
        reason: String,
    ) {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT, travelMinutes = 65)
        val checkedAt = queryAt.minusSeconds(60)
        val preservedDeparture = schedule.startAt.minusSeconds(3888)
        val job = job(schedule)
        val failure = if (reason == "HORIZON") TrafficFailureReasons.TRANSIT_ARRIVAL_OUT_OF_HORIZON
            else TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE
        finish(
            job = job, travelMinutes = 71, source = source, checkedAt = checkedAt,
            liveFetchedAt = checkedAt.minusSeconds(2), failureReason = failure,
            snapshotSchedule = schedule, recommendedDepartureAt = preservedDeparture, etaStale = true,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(source, result.source)
        assertEquals(failure, result.failureReason)
        assertEquals(preservedDeparture, result.recommendedDepartureAt)
        assertEquals(schedule.startAt.minusSeconds(65 * 60L), result.savedRouteEstimatedDepartureAt)
        assertEquals(71, result.travelMinutes)
        assertEquals(checkedAt, result.evaluatedAt)
        assertEquals(job.nextCheckAt, result.nextCheckAt)
        assertEquals(job.lastEtaRefreshDueAt, result.etaRefreshDueAt)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertTrue(result.stale)
        assertNull(result.predictedArrivalAt)
        assertNull(result.onTimeArrivalPossible)
        assertNull(result.effectiveTransitRoute)
    }

    @ParameterizedTest
    @CsvSource("MISSING_FETCH", "FUTURE_FETCH", "EXPIRED", "WRONG_ROUTE", "DISABLED", "LATE_ARRIVAL", "NO_REASON")
    fun `provider 진단 전달도 취득시각과 경로 및 만료 안전 경계를 지킨다`(boundary: String) {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT, travelMinutes = 65)
        val checkedAt = if (boundary == "EXPIRED") queryAt.minusSeconds(1501) else queryAt.minusSeconds(60)
        val job = job(schedule)
        finish(
            job = job, travelMinutes = 71, source = TrafficSource.TIMETABLE_PROVIDER,
            checkedAt = checkedAt, liveFetchedAt = checkedAt.minusSeconds(2),
            failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE,
            snapshotSchedule = schedule, recommendedDepartureAt = schedule.startAt.minusSeconds(3888), etaStale = true,
        )
        when (boundary) {
            "MISSING_FETCH" -> setField(job, "lastEtaProviderFetchedAt", null)
            "FUTURE_FETCH" -> setField(job, "lastEtaProviderFetchedAt", checkedAt.plusSeconds(1))
            "WRONG_ROUTE" -> requireNotNull(schedule.route).destinationLat = 38.1
            "DISABLED" -> requireNotNull(schedule.route).notificationEnabled = false
            "LATE_ARRIVAL" -> setField(job, "lastPredictedArrivalAt", schedule.startAt.plusSeconds(1))
            "NO_REASON" -> setField(job, "lastEtaFailureReason", null)
        }
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertTrue(result.source != TrafficSource.TIMETABLE_PROVIDER)
        assertNull(result.nextCheckAt)
        assertNull(result.predictedArrivalAt)
        assertNull(result.effectiveTransitRoute)
    }

    @ParameterizedTest
    @CsvSource(
        "LIVE_PROVIDER, 3841, 65",
        "LIVE_PROVIDER, 3888, 65",
        "LIVE_PROVIDER, 3899, 65",
        "LIVE_PROVIDER, 3900, 65",
        "LIVE_PROVIDER, 3901, 66",
        "TIMETABLE_PROVIDER, 3888, 65",
    )
    fun `정상 snapshot의 정시 도착은 올림 분수가 아닌 계산된 도착시각으로 검증한다`(
        source: TrafficSource,
        durationSeconds: Long,
        travelMinutes: Int,
    ) {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT, travelMinutes = travelMinutes)
        val checkedAt = queryAt.minus(1, ChronoUnit.MINUTES)
        val departureAt = schedule.startAt.minusSeconds(durationSeconds)
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = travelMinutes,
            source = source,
            checkedAt = checkedAt,
            liveFetchedAt = checkedAt.minusSeconds(2),
            snapshotSchedule = schedule,
            recommendedDepartureAt = departureAt,
            predictedArrivalAt = schedule.startAt,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(source, result.source)
        assertEquals(travelMinutes, result.travelMinutes)
        assertEquals(departureAt, result.recommendedDepartureAt)
        assertEquals(schedule.startAt, result.predictedArrivalAt)
        assertEquals(true, result.onTimeArrivalPossible)
        assertEquals(checkedAt, result.evaluatedAt)
        assertFalse(result.stale)
        assertNull(result.failureReason)
    }

    @ParameterizedTest
    @CsvSource("LIVE_PROVIDER", "TIMETABLE_PROVIDER", "SELECTED_ROUTE")
    fun `손상된 snapshot의 출발 이전 도착시각은 거부한다`(source: TrafficSource) {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val checkedAt = queryAt.minus(1, ChronoUnit.MINUTES)
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = source,
            checkedAt = checkedAt,
            liveFetchedAt = checkedAt.minusSeconds(2).takeIf { source != TrafficSource.SELECTED_ROUTE },
            failureReason = TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY
                .takeIf { source == TrafficSource.SELECTED_ROUTE },
            snapshotSchedule = schedule,
        )
        setField(job, "lastPredictedArrivalAt", requireNotNull(job.lastRecommendedDepartureAt).minusSeconds(1))
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertNull(result.predictedArrivalAt)
        assertNull(result.onTimeArrivalPossible)
        assertNull(result.nextCheckAt)
    }

    @ParameterizedTest
    @CsvSource("LIVE_PROVIDER", "TIMETABLE_PROVIDER")
    fun `도착시각 없는 정상 snapshot에는 기존 분 단위 도착 검증을 유지한다`(source: TrafficSource) {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT, travelMinutes = 65)
        val checkedAt = queryAt.minus(1, ChronoUnit.MINUTES)
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 65,
            source = source,
            checkedAt = checkedAt,
            liveFetchedAt = checkedAt.minusSeconds(2),
            snapshotSchedule = schedule,
            recommendedDepartureAt = schedule.startAt.minusSeconds(3888),
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertNull(result.predictedArrivalAt)
        assertNull(result.nextCheckAt)
    }

    @ParameterizedTest
    @CsvSource("EXPIRED", "FINGERPRINT", "SCHEDULE", "NOTIFICATIONS_DISABLED", "LATE_PREDICTION")
    fun `초 단위 추정 snapshot도 만료와 경로 및 도착 안전 경계를 지킨다`(invalidBoundary: String) {
        val schedule = schedule(
            travelMode = ScheduleTravelMode.TRANSIT,
            travelMinutes = 65,
            routeJson = """{"minutes":65,"totalDurationSeconds":3888}""",
        )
        val checkedAt = if (invalidBoundary == "EXPIRED") {
            queryAt.minus(25, ChronoUnit.MINUTES).minusSeconds(1)
        } else {
            queryAt.minus(1, ChronoUnit.MINUTES)
        }
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 65,
            source = TrafficSource.SELECTED_ROUTE,
            checkedAt = checkedAt,
            failureReason = TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY,
            snapshotSchedule = schedule,
            recommendedDepartureAt = schedule.startAt.minusSeconds(3888),
        )
        when (invalidBoundary) {
            "FINGERPRINT" -> requireNotNull(schedule.route).destinationLat = 38.1
            "SCHEDULE" -> schedule.startAt = schedule.startAt.plus(1, ChronoUnit.HOURS)
            "NOTIFICATIONS_DISABLED" -> requireNotNull(schedule.route).notificationEnabled = false
            "LATE_PREDICTION" -> setField(job, "lastPredictedArrivalAt", schedule.startAt.plusSeconds(1))
        }
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(queryAt, result.evaluatedAt)
        assertNull(result.nextCheckAt)
        assertNull(result.etaRefreshDueAt)
        assertNull(result.predictedArrivalAt)
        assertNull(result.onTimeArrivalPossible)
        assertTrue(result.failureReason != TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY)
        assertTrue(result.stale)
    }

    @ParameterizedTest
    @CsvSource("LIVE_PROVIDER, 1", "TIMETABLE_PROVIDER, 1", "LIVE_PROVIDER, 300")
    fun `정상 provider snapshot의 늦은 예측 도착은 계속 거부한다`(
        source: TrafficSource,
        latenessSeconds: Long,
    ) {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = source,
            checkedAt = queryAt.minus(5, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(5, ChronoUnit.MINUTES).minusSeconds(2),
            snapshotSchedule = schedule,
        )
        // 영속 계층에서 유입될 수 있는 legacy/손상 snapshot을 재현한다. 새 도메인 쓰기 경로는
        // 정시 도착 불가 진단이 아닌 late predictedArrivalAt 자체를 이미 차단한다.
        setField(
            job,
            "lastPredictedArrivalAt",
            schedule.startAt.plusSeconds(latenessSeconds),
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertNull(result.predictedArrivalAt)
        assertNull(result.onTimeArrivalPossible)
    }

    @Test
    fun `departure status 조회 서비스는 provider client를 의존하지 않는다`() {
        val constructorParameterTypes = ScheduleDepartureEtaService::class.java.declaredConstructors
            .flatMap { it.parameterTypes.toList() }

        assertFalse(constructorParameterTypes.contains(TrafficClient::class.java))
    }

    @Test
    fun `global sharing off selects owner detail before ETA status lookup`() {
        whenever(scheduleAccessPolicy.isSharingDisabled()).thenReturn(true)
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 2L)).thenReturn(null)

        val error = assertThrows(BusinessException::class.java) {
            service().getDepartureStatus(memberId = 2L, scheduleId = 10L)
        }

        assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, error.errorCode)
        verify(scheduleRepository).findOwnedScheduleDetail(10L, 2L)
        verify(scheduleRepository, never()).findScheduleDetail(10L, 2L)
        verify(pushJobRepository, never()).findByScheduleIdAndMemberId(any(), any())
        verify(travelPlanRepository, never()).findByScheduleIdAndMemberIdAndDeletedFalse(any(), any())
    }

    @Test
    fun `global sharing off preserves owner saved ETA status`() {
        val schedule = schedule(memberId = 1L)
        whenever(scheduleAccessPolicy.isSharingDisabled()).thenReturn(true)
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleAccessPolicy.resolve(1L, schedule)).thenReturn(
            ScheduleAccessDecision(
                canView = true,
                canEdit = true,
                travelEnabled = true,
                canViewAllTravelPlans = true,
            )
        )

        val result = service().getDepartureStatus(memberId = 1L, scheduleId = 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(schedule.startAt.minus(30, ChronoUnit.MINUTES), result.recommendedDepartureAt)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        verify(scheduleRepository).findOwnedScheduleDetail(10L, 1L)
        verify(scheduleRepository, never()).findScheduleDetail(10L, 1L)
    }

    @Test
    fun `저장된 timeout fallback snapshot은 이전 live 시각과 낮은 신뢰도를 노출한다`() {
        val schedule = schedule()
        val liveCheckedAt = queryAt.minus(20, ChronoUnit.MINUTES)
        val fallbackCheckedAt = queryAt.minus(5, ChronoUnit.MINUTES)
        val liveFetchedAt = liveCheckedAt.minusSeconds(2)
        val job = job(schedule)
        finish(job, 30, TrafficSource.LIVE_PROVIDER, liveCheckedAt, liveFetchedAt)
        finish(
            job = job,
            travelMinutes = 45,
            source = TrafficSource.SAVED_FALLBACK,
            checkedAt = fallbackCheckedAt,
            failureReason = "PROVIDER_TIMEOUT: 실시간 ETA 공급자 응답 시간이 초과되었습니다.",
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(45, result.travelMinutes)
        assertEquals(fallbackCheckedAt, result.evaluatedAt)
        assertEquals(liveFetchedAt, result.liveFetchedAt)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertTrue(result.stale)
        assertTrue(result.failureReason.orEmpty().startsWith("PROVIDER_TIMEOUT:"))
        assertNull(result.lastTrafficChangeMinutes)
        assertNull(result.lastChangedAt)
    }

    @Test
    fun `저장된 provider 원문은 departure status에서 안정 코드로 sanitize한다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 45,
            source = TrafficSource.SAVED_FALLBACK,
            checkedAt = queryAt.minus(5, ChronoUnit.MINUTES),
            failureReason =
                "GET http://provider.internal/routes?startX=127.1&startY=37.1 failed",
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertTrue(result.failureReason.orEmpty().startsWith("ETA_FALLBACK:"))
        assertFalse(result.failureReason.orEmpty().contains("provider.internal"))
        assertFalse(result.failureReason.orEmpty().contains("127.1"))
    }

    @Test
    fun `invalid provider 응답 snapshot은 saved fallback과 낮은 신뢰도로 노출한다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = TrafficSource.SAVED_FALLBACK,
            checkedAt = queryAt.minus(5, ChronoUnit.MINUTES),
            failureReason =
                "PROVIDER_INVALID_RESPONSE: internal parser at http://provider.internal",
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertTrue(result.stale)
        assertEquals(
            "PROVIDER_INVALID_RESPONSE: 실시간 ETA 공급자 응답을 해석할 수 없습니다.",
            result.failureReason,
        )
    }

    @Test
    fun `legacy job의 source와 stale이 null이면 ETA source null 대신 현재 saved fallback을 반환한다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 35,
            source = TrafficSource.SAVED_FALLBACK,
            checkedAt = queryAt.minus(5, ChronoUnit.MINUTES),
            failureReason = "PROVIDER_DISABLED: safe",
            snapshotSchedule = schedule,
        )
        setField(job, "lastEtaSource", null)
        setField(job, "lastEtaStale", null)
        setField(job, "lastEtaRouteFingerprint", null)
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertTrue(result.stale)
    }

    @Test
    fun `제품 상한 초과 또는 불완전 LIVE provenance job은 현재 route fallback으로 내린다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(5, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(5, ChronoUnit.MINUTES),
            snapshotSchedule = schedule,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        setField(job, "lastTravelMinutes", 2_000)
        val oversized = service().getDepartureStatus(1L, 10L)

        assertEquals(30, oversized.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, oversized.source)
        assertNull(oversized.liveFetchedAt)

        setField(job, "lastTravelMinutes", 30)
        setField(
            job,
            "lastRecommendedDepartureAt",
            schedule.startAt.minus(30, ChronoUnit.MINUTES),
        )
        setField(job, "lastLiveFetchedAt", null)
        val incompleteLive = service().getDepartureStatus(1L, 10L)

        assertEquals(30, incompleteLive.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, incompleteLive.source)
        assertNull(incompleteLive.liveFetchedAt)
    }

    @Test
    fun `job이 없으면 routeInfo 전체 시간으로 선택 경로 snapshot을 구성한다`() {
        val schedule = schedule(
            routeJson = """
                {
                  "routeInfo": {
                    "totalDurationMinutes": 40,
                    "steps": [{"durationMinutes": 5}]
                  }
                }
            """.trimIndent(),
            travelMinutes = 40,
        )
        stubVisible(schedule)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(40, result.travelMinutes)
        assertEquals(queryAt, result.evaluatedAt)
        assertNull(result.liveFetchedAt)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertEquals(ScheduleEtaConfidence.MEDIUM, result.confidence)
        assertTrue(result.stale)
        assertTrue(result.failureReason.orEmpty().startsWith("SELECTED_ROUTE_SNAPSHOT:"))
    }

    @ParameterizedTest
    @CsvSource("45,45", "20,30", "-5,30")
    fun `job 없는 저장 대중교통의 출발 안내는 캘린더와 같은 안전 시각을 유지한다`(
        savedMinutesBeforeTarget: Long,
        expectedMinutesBeforeTarget: Long,
    ) {
        val target = queryAt.plus(2, ChronoUnit.HOURS)
        val schedule = schedule(
            travelMode = ScheduleTravelMode.TRANSIT,
            departureAt = target.minus(savedMinutesBeforeTarget, ChronoUnit.MINUTES),
        )
        stubVisible(schedule)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(target.minus(expectedMinutesBeforeTarget, ChronoUnit.MINUTES), result.recommendedDepartureAt)
        assertEquals(30, result.travelMinutes)
        assertTrue(result.stale)
        assertNull(result.predictedArrivalAt)
    }

    @Test
    fun `재조회 전 ODsay 출발 안내에 일치하는 초 단위 추정을 유지한다`() {
        val target = queryAt.plus(2, ChronoUnit.HOURS)
        val expectedDeparture = target.minusSeconds(877)
        val schedule = schedule(
            travelMode = ScheduleTravelMode.TRANSIT,
            travelMinutes = 15,
            departureAt = expectedDeparture,
            routeJson = """{"provider":"odsay","routeInfo":{"provider":"odsay","timeBasis":"estimated",
                "providerTimeBasis":"route_estimate","totalDurationMinutes":15,"totalDurationSeconds":877,
                "departureTime":"$expectedDeparture","arrivalTime":"$target","steps":[]}}""",
        )
        stubVisible(schedule)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(expectedDeparture, result.recommendedDepartureAt)
        assertEquals(15, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertTrue(result.stale)
        assertNull(result.predictedArrivalAt)
    }

    @ParameterizedTest
    @CsvSource("true", "false")
    fun `저장된 ODsay 버스 예측은 같은 일정에서 초 단위 출발을 유지한다`(hasStoredDeparture: Boolean) {
        val target = queryAt.plus(12, ChronoUnit.MINUTES)
        val arrival = target.minusSeconds(5)
        val departure = arrival.minusSeconds(663)
        val observedAt = departure.minusSeconds(95)
        val routeJson = """
            {"provider":"odsay","minutes":15,"transitLegs":[{"kind":"WALK"},{"kind":"BUS"},{"kind":"WALK"}],
             "routeInfo":{"provider":"odsay","timeBasis":"estimated","providerTimeBasis":"route_estimate",
               "totalDurationMinutes":12,"totalDurationSeconds":663,"departureTime":"$departure","arrivalTime":"$arrival",
               "busPreviewObservation":{"targetArrivalAt":"$target","sourceUpdatedAt":"$observedAt"}}}
        """.trimIndent()
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT, travelMinutes = 12,
            departureAt = departure.takeIf { hasStoredDeparture }, startAt = target, routeJson = routeJson)
        stubVisible(schedule)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(departure, result.recommendedDepartureAt)
        assertEquals(12, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertTrue(result.stale)
        assertNull(result.liveFetchedAt)
        assertNull(result.predictedArrivalAt)
    }

    @Test
    fun `route-level 시간이 없으면 step 시간이 아니라 저장 travelMinutes를 사용한다`() {
        val schedule = schedule(
            routeJson = """{"routeInfo":{"steps":[{"durationMinutes":5}]}}""",
        )
        stubVisible(schedule)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertTrue(result.failureReason.orEmpty().startsWith("SAVED_ROUTE_SNAPSHOT:"))
    }

    @ParameterizedTest
    @CsvSource("true", "false")
    fun `저장된 ODsay 시간표는 출발 상태에서 분 단위 추정으로 돌아가지 않는다`(hasStoredDeparture: Boolean) {
        val target = queryAt.plus(2, ChronoUnit.HOURS)
        val arrival = target.minusSeconds(360)
        val departure = arrival.minusSeconds(1771)
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT,
            departureAt = departure.takeIf { hasStoredDeparture },
            routeJson = savedOdsayTimetableFixture(departure, arrival))
        stubVisible(schedule)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(departure, result.recommendedDepartureAt)
        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `과대 selected JSON ETA는 canonical 저장 시간을 덮어쓰지 않는다`() {
        val schedule = schedule(routeJson = """{"minutes":2000}""", travelMinutes = 30)
        stubVisible(schedule)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
    }

    @Test
    fun `fraction routeInfo ETA가 canonical과 일치하면 선택 경로 snapshot으로 사용한다`() {
        val schedule = schedule(
            routeJson = """{"routeInfo":{"totalDurationMinutes":29.2}}""",
            travelMinutes = 30,
        )
        stubVisible(schedule)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertEquals(ScheduleEtaConfidence.MEDIUM, result.confidence)
    }

    @Test
    fun `job이 없는 개인 대중교통 계획은 선택 itinerary snapshot만 반환한다`() {
        val schedule = schedule()
        val routeJson = """
            {
              "minutes": 44,
              "selectedItinerary": {
                "transferCount": 1,
                "legs": [{"mode": "BUS", "route": "간선 100"}]
              }
            }
        """.trimIndent()
        val plan = ScheduleTravelPlan(scheduleId = 10L, memberId = 1L).apply {
            replace(
                command = ScheduleTravelPlanUpsertCommand(
                    travelMinutes = 44,
                    travelMode = ScheduleTravelMode.TRANSIT,
                    originLat = 37.3,
                    originLng = 127.3,
                    routeJson = routeJson,
                ),
                scheduleFingerprint = ScheduleTravelPlanFingerprint.calculate(schedule),
                departAt = null,
                routeJson = routeJson,
                notificationLeadMinutes = null,
                notificationIntervalMinutes = null,
            )
        }
        stubVisible(schedule)
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 1L))
            .thenReturn(plan)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(44, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertEquals(ScheduleEtaConfidence.MEDIUM, result.confidence)
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `owner 경로가 바뀌고 job이 취소되면 이전 live snapshot을 사용하지 않는다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            snapshotSchedule = schedule,
        )
        schedule.route?.apply {
            travelMinutes = 25
            destinationName = "새 회사"
            destinationLat = 37.8
        }
        job.cancel()
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(25, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(queryAt, result.evaluatedAt)
        assertNull(result.liveFetchedAt)
        assertNull(result.nextCheckAt)
    }

    @Test
    fun `owner 목적지가 바뀌면 active job의 이전 route fingerprint snapshot도 거부한다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            snapshotSchedule = schedule,
        )
        schedule.route?.apply {
            destinationName = "새 목적지"
            destinationLat = 38.1
        }
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(queryAt, result.evaluatedAt)
        assertNull(result.liveFetchedAt)
        assertNull(result.lastTrafficChangeMinutes)
    }

    @Test
    fun `일정 시작 시각이 바뀌면 이전 scheduleAt job snapshot을 거부한다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            snapshotSchedule = schedule,
        )
        schedule.startAt = schedule.startAt.plus(1, ChronoUnit.HOURS)
        schedule.endAt = schedule.endAt.plus(1, ChronoUnit.HOURS)
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(schedule.startAt.minus(30, ChronoUnit.MINUTES), result.recommendedDepartureAt)
        assertEquals(queryAt, result.evaluatedAt)
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `FAILED job은 현재 저장 경로보다 우선하지 않는다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 35,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            snapshotSchedule = schedule,
        )
        job.fail("terminal")
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(queryAt, result.evaluatedAt)
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `알림이 비활성화되면 active job snapshot도 거부한다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            snapshotSchedule = schedule,
        )
        schedule.route?.notificationEnabled = false
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertTrue(result.failureReason.orEmpty().startsWith("NOTIFICATION_DISABLED:"))
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `shared participant 계획 fingerprint가 stale이면 이전 job snapshot을 거부한다`() {
        val schedule = schedule(memberId = 1L)
        val plan = ScheduleTravelPlan(scheduleId = 10L, memberId = 2L).apply {
            replace(
                command = ScheduleTravelPlanUpsertCommand(
                    travelMinutes = 30,
                    travelMode = ScheduleTravelMode.CAR,
                    originLat = 37.1,
                    originLng = 127.1,
                    notificationEnabled = true,
                ),
                scheduleFingerprint = ScheduleTravelPlanFingerprint.calculate(schedule),
                departAt = null,
                routeJson = null,
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = 20,
            )
        }
        val participantJob = SchedulePushJob.create(
            memberId = 2L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = queryAt.minus(30, ChronoUnit.MINUTES),
            intervalMinutes = 20,
        )
        finish(
            job = participantJob,
            travelMinutes = 30,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            snapshotSchedule = schedule,
        )
        schedule.route?.apply {
            destinationName = "변경된 목적지"
            destinationLat = 38.0
        }
        stubVisible(schedule, 2L)
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 2L))
            .thenReturn(plan)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 2L))
            .thenReturn(participantJob)

        val result = service().getDepartureStatus(2L, 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertTrue(result.failureReason.orEmpty().startsWith("ROUTE_STALE:"))
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `다른 회원에게 보이지 않는 일정은 snapshot 조회 전에 차단한다`() {
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(null)

        val exception = assertThrows(BusinessException::class.java) {
            service().getDepartureStatus(2L, 10L)
        }

        assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, exception.errorCode)
        verify(scheduleAccessPolicy, never()).resolve(any(), any())
        verify(pushJobRepository, never()).findByScheduleIdAndMemberId(any(), any())
    }

    @Test
    fun `일정만 공유되고 이동 권한이 없는 회원은 departure status를 조회할 수 없다`() {
        val schedule = schedule(memberId = 1L)
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(schedule)
        whenever(scheduleAccessPolicy.resolve(2L, schedule)).thenReturn(
            ScheduleAccessDecision(
                canView = true,
                canEdit = false,
                travelEnabled = false,
                canViewAllTravelPlans = false,
            )
        )

        val exception = assertThrows(BusinessException::class.java) {
            service().getDepartureStatus(2L, 10L)
        }

        assertEquals(ErrorCode.FORBIDDEN, exception.errorCode)
        verify(pushJobRepository, never()).findByScheduleIdAndMemberId(any(), any())
    }

    @Test
    fun `이동 계획이 없으면 존재하지 않는 ETA와 준비시간을 만들지 않는다`() {
        val schedule = Schedule(
            id = 10L,
            memberId = 1L,
            title = "경로 미설정",
            startAt = queryAt.plus(2, ChronoUnit.HOURS),
            endAt = queryAt.plus(3, ChronoUnit.HOURS),
        )
        stubVisible(schedule)

        val result = service().getDepartureStatus(1L, 10L)

        assertNull(result.travelMinutes)
        assertNull(result.recommendedDepartureAt)
        assertNull(result.source)
        assertNull(result.confidence)
        assertTrue(result.failureReason.orEmpty().startsWith("ROUTE_NOT_CONFIGURED:"))
        assertNull(result.preparationMinutes)
        assertNull(result.preparationStartAt)
        assertNull(result.safetyBufferMinutes)
    }

    private fun finish(
        job: SchedulePushJob,
        travelMinutes: Int,
        source: TrafficSource,
        checkedAt: Instant,
        liveFetchedAt: Instant? = null,
        failureReason: String? = null,
        snapshotSchedule: Schedule = schedule(),
        etaRefreshDueAt: Instant = checkedAt.plus(
            job.intervalMinutes.toLong(),
            ChronoUnit.MINUTES,
        ),
        effectiveTransitRouteJson: String? = null,
        recommendedDepartureAt: Instant = job.scheduleAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES),
        predictedArrivalAt: Instant? = null,
        etaStale: Boolean = source != TrafficSource.LIVE_PROVIDER && source != TrafficSource.TIMETABLE_PROVIDER,
    ) {
        val route = requireNotNull(snapshotSchedule.route)
        job.startProcessing("eta-test")
        job.finishCheck(
            travelMinutes = travelMinutes,
            recommendedDepartureAt = recommendedDepartureAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = etaRefreshDueAt,
            completeAfterCheck = false,
            etaSource = source,
            liveFetchedAt = liveFetchedAt,
            etaStale = etaStale,
            etaFailureReason = failureReason,
            etaTravelMode = route.travelMode,
            etaRouteFingerprint = ScheduleEtaRouteFingerprint.calculate(
                schedule = snapshotSchedule,
                travelMinutes = route.travelMinutes,
                travelMode = route.travelMode,
                originLat = route.originLat,
                originLng = route.originLng,
                routeJson = route.routeJson,
            ),
            effectiveTransitRouteJson = effectiveTransitRouteJson,
            predictedArrivalAt = predictedArrivalAt,
            now = checkedAt,
        )
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun stubVisible(schedule: Schedule) {
        stubVisible(schedule, 1L)
    }

    private fun stubVisible(schedule: Schedule, viewerId: Long) {
        whenever(scheduleRepository.findScheduleDetail(10L, viewerId)).thenReturn(schedule)
        whenever(scheduleAccessPolicy.resolve(viewerId, schedule)).thenReturn(
            ScheduleAccessDecision(
                canView = true,
                canEdit = viewerId == schedule.memberId,
                travelEnabled = true,
                canViewAllTravelPlans = viewerId == schedule.memberId,
            )
        )
    }

    private fun service() = ScheduleDepartureEtaService(
        scheduleRepository = scheduleRepository,
        travelPlanRepository = travelPlanRepository,
        pushJobRepository = pushJobRepository,
        scheduleAccessPolicy = scheduleAccessPolicy,
        objectMapper = objectMapper,
        clock = clock,
    )

    private fun job(
        schedule: Schedule,
        intervalMinutes: Int = 20,
    ) = SchedulePushJob.create(
        memberId = 1L,
        scheduleId = 10L,
        scheduleAt = schedule.startAt,
        departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
        monitorStartAt = queryAt.minus(30, ChronoUnit.MINUTES),
        intervalMinutes = intervalMinutes,
    )

    @Test
    fun `fixed intercity departure is preserved when arrival deadline is later than ticket arrival`() {
        val fixed = queryAt.plusSeconds(600)
        val journey = objectMapper.createObjectNode().put("version", 1).put("departureAt", fixed.toString()).put("arrivalAt", queryAt.plusSeconds(4200).toString())
        journey.set<com.fasterxml.jackson.databind.node.ObjectNode>("service", objectMapper.createObjectNode().put("kind", "INTERCITY_BUS").put("timingBasis", "TICKET"))
        val root = objectMapper.createObjectNode().put("schemaVersion", 3).put("minutes", 60)
        root.set<com.fasterxml.jackson.databind.node.ObjectNode>("intercityJourney", journey)
        root.set<com.fasterxml.jackson.databind.node.ObjectNode>("routeInfo", objectMapper.createObjectNode().put("timeBasis", "anchored_journey").put("departureTime", fixed.toString()).set("intercityJourney", journey.deepCopy()))
        val schedule = schedule(routeJson = root.toString(), travelMinutes = 60, travelMode = ScheduleTravelMode.TRANSIT)
        stubVisible(schedule)
        val result = service().getDepartureStatus(1L, 10L)
        assertEquals(fixed, result.recommendedDepartureAt)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_INTERCITY_TIMETABLE_UNAVAILABLE, result.failureReason)
        assertNull(result.effectiveTransitRoute)
    }

    private fun schedule(
        memberId: Long = 1L,
        routeJson: String? = null,
        travelMinutes: Int? = 30,
        destinationName: String = "회사",
        destinationLat: Double = 37.2,
        notificationEnabled: Boolean = true,
        travelMode: ScheduleTravelMode = ScheduleTravelMode.CAR,
        departureAt: Instant? = null,
        startAt: Instant = queryAt.plus(2, ChronoUnit.HOURS),
    ): Schedule =
        Schedule(
            id = 10L,
            memberId = memberId,
            title = "회의",
            startAt = startAt,
            endAt = startAt.plus(1, ChronoUnit.HOURS),
        ).apply {
            updateRoute(
                travelMinutes = travelMinutes,
                departAt = departureAt,
                departedAt = null,
                travelMode = travelMode,
                locationName = "회사",
                originName = "집",
                originAddress = null,
                originLat = 37.1,
                originLng = 127.1,
                destinationName = destinationName,
                destinationAddress = null,
                destinationLat = destinationLat,
                destinationLng = 127.2,
                routeJson = routeJson,
                notificationEnabled = notificationEnabled,
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = 20,
            )
        }
}
