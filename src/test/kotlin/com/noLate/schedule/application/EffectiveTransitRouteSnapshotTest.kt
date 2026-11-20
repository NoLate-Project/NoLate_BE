package com.noLate.schedule.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitFirstBoardingObservation
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.TrafficSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.time.temporal.ChronoUnit

class EffectiveTransitRouteSnapshotTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val fetchedAt = Instant.parse("2026-08-10T01:00:00Z")

    @Test
    fun `첫 승차 출처는 같은 편과 같은 예측을 수락한 계획에만 연결한다`() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/eta/odsay-early-first-boarding-20261118.json"))
            .use(objectMapper::readTree)
        val route = objectMapper.treeToValue(fixture["plan"], EffectiveTransitRoute::class.java)
        val observed = objectMapper.treeToValue(fixture["firstStationObservation"], TransitFirstBoardingObservation::class.java)
        val diagnostic = TrafficResult(route.totalMinutes, TrafficSource.SELECTED_ROUTE, stale = true)
        fun carry(row: TransitFirstBoardingObservation): EffectiveTransitRoute? {
            val previous = EffectiveTransitRouteSnapshot(accepted = true, effectiveTransitRoute = route, firstBoardingObservation = row)
            return EffectiveTransitRouteSnapshot.from(diagnostic, previous, 1, evaluatedAt = route.departureAt)?.inProgressPlannedRoute
        }
        for (other in listOf(observed.copy(trainNumber = null), observed.copy(trainNumber = "2276"),
            observed.copy(stationName = "사당"), observed.copy(lineName = "2호선"),
            observed.copy(expectedBoardingAt = observed.expectedBoardingAt.plusSeconds(1)),
            observed.copy(sourceUpdatedAt = observed.evaluatedAt.minusSeconds(121)))) {
            assertEquals(route, carry(other))
        }
        val carried = requireNotNull(carry(observed))
        assertEquals(observed.sourceUpdatedAt, carried.segments[1].rideTiming?.boardingSourceAt())
        assertNull(carried.segments[1].rideTiming?.alightingSourceAt())
        assertEquals(route.departureAt, carried.departureAt)
        assertEquals(route.arrivalAt, carried.arrivalAt)
    }

    @Test
    fun `구간별 갱신의 전체 경로를 반환하고 관측 만료 후에도 이전 환승편으로 되돌리지 않는다`() {
        val result = trafficResult("a".repeat(64))
        val plan = requireNotNull(result.effectiveTransitRoute)
        val now = plan.departureAt.plusSeconds(30)
        val updatedRoute = plan.copy(arrivalAt = plan.arrivalAt.plusSeconds(300), totalMinutes = plan.totalMinutes + 5)
        val snapshot = requireNotNull(EffectiveTransitRouteSnapshot.from(result, null, 0)).copy(
            inProgressPlannedRoute = plan,
            plannedRouteUpdate = PlannedTransitRouteUpdate(plan.identity, now, route = updatedRoute))
        val fresh = requireNotNull(snapshot.plannedJourneyArrivalPrediction(now, 120))
        assertEquals(com.noLate.schedule.domain.PlannedJourneyArrivalBasis.MATCHED_RIDE_AND_ONWARD_TIMETABLE, fresh.basis)
        assertEquals(updatedRoute, fresh.effectiveTransitRoute)
        assertEquals(plan.departureAt, fresh.effectiveTransitRoute?.departureAt)
        val duplicateSourceCheck = requireNotNull(EffectiveTransitRouteSnapshot.from(result.copy(stale = true),
            snapshot, 1, evaluatedAt = now.plusSeconds(60)))
        val stillFresh = requireNotNull(duplicateSourceCheck.plannedJourneyArrivalPrediction(now.plusSeconds(60), 120))
        assertEquals(fresh.basis, stillFresh.basis)
        assertEquals(now, stillFresh.sourceUpdatedAt)
        assertEquals(updatedRoute, stillFresh.effectiveTransitRoute)
        val next = requireNotNull(EffectiveTransitRouteSnapshot.from(result.copy(stale = true,
            failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE), snapshot, 1, evaluatedAt = now.plusSeconds(121)))
        val stale = requireNotNull(next.plannedJourneyArrivalPrediction(now.plusSeconds(121), 120))
        assertEquals(updatedRoute.arrivalAt, stale.predictedArrivalAt)
        assertEquals(com.noLate.schedule.domain.PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN, stale.basis)
        assertEquals(updatedRoute.arrivalAt, stale.effectiveTransitRoute?.arrivalAt)
        assertTrue(stale.effectiveTransitRoute?.segments?.all { it.rideTiming?.observationSourceUpdatedAt == null } == true)
        assertEquals(plan, next.inProgressPlannedRoute)
        val parsed = EffectiveTransitRouteSnapshot.parse(objectMapper, objectMapper.writeValueAsString(next))
        assertEquals(updatedRoute, parsed?.lastUpdatedPlannedRoute)
    }

    @Test
    fun `확인된 환승 연결 실패는 다음 미관측 회차에도 이전 도착 예측을 숨긴다`() {
        val result = trafficResult("a".repeat(64))
        val plan = requireNotNull(result.effectiveTransitRoute)
        val now = plan.departureAt.plusSeconds(30)
        val snapshot = requireNotNull(EffectiveTransitRouteSnapshot.from(result, null, 0)).copy(
            inProgressPlannedRoute = plan, plannedRouteUpdate = PlannedTransitRouteUpdate(plan.identity, now,
                failure = PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE))
        assertNull(snapshot.plannedJourneyArrivalPrediction(now, 120))
        val next = requireNotNull(EffectiveTransitRouteSnapshot.from(result.copy(stale = true), snapshot, 1, evaluatedAt = now))
        assertNull(next.plannedJourneyArrivalPrediction(now.plusSeconds(121), 120))
        assertEquals("TRANSFER_RECONNECTION_UNAVAILABLE", next.plannedJourneyTimingFailureReason())
        val recovered = next.copy(plannedRouteUpdate = PlannedTransitRouteUpdate(plan.identity, now.plusSeconds(121), route = plan))
        assertNull(recovered.plannedJourneyTimingFailureReason())
        assertEquals(plan.arrivalAt, recovered.plannedJourneyArrivalPrediction(now.plusSeconds(121), 120)?.predictedArrivalAt)
    }

    @Test
    fun `미수락 경로의 이른 환승역 도착만으로 목적지 실시간 예측을 덮어쓰지 않는다`() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/eta/odsay-early-first-boarding-20261118.json"))
            .use(objectMapper::readTree)
        val plan = objectMapper.treeToValue(fixture["plan"], EffectiveTransitRoute::class.java)
        val lastRide = plan.segments.last { it.rideTiming != null }
        val timing = requireNotNull(lastRide.rideTiming)
        val stationArrival = requireNotNull(timing.timetable.boardingStationArrivalAt).minusSeconds(20)
        val now = stationArrival.minusSeconds(70)
        val early = timing.copy(predictedBoardingAt = stationArrival,
            basis = EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_TIMETABLE_ALIGHTING,
            observationSourceUpdatedAt = now.minusSeconds(5))
        val candidate = plan.copy(segments = plan.segments.map { if (it === lastRide) it.copy(rideTiming = early) else it })
        val snapshot = EffectiveTransitRouteSnapshot(accepted = false, inProgressPlannedRoute = plan, effectiveTransitRoute = candidate)
        val previous = PlannedJourneyArrivalObservation(plan.identity, plan.arrivalAt.plusSeconds(120), now.minusSeconds(20))
        val live = requireNotNull(snapshot.copy(plannedArrivalObservation = previous).plannedJourneyArrivalPrediction(now, 120))
        assertEquals(previous.predictedArrivalAt, live.predictedArrivalAt)
        assertEquals(previous.sourceUpdatedAt, live.sourceUpdatedAt)
        val saved = requireNotNull(snapshot.plannedJourneyArrivalPrediction(now, 120))
        assertEquals(com.noLate.schedule.domain.PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN, saved.basis)
        assertEquals(plan.arrivalAt, saved.predictedArrivalAt)
        assertEquals(plan.departureAt, saved.effectiveTransitRoute?.departureAt)
        assertNull(saved.sourceUpdatedAt)
    }

    @Test
    fun `출발 후 같은 환승 열차의 신선한 관측만 잠정 도착에 반영한다`() {
        val firstTable = TransitLegTimetable("seoul-timetable", SeoulSubwayLine.LINE_4,
            java.time.LocalDate.parse("2026-08-10"), "4012", "DOWN", TransitServiceClass.LOCAL,
            "0426", "0433", "오이도", fetchedAt.plusSeconds(900), fetchedAt.plusSeconds(2100), fetchedAt)
        val transferTable = TransitLegTimetable("seoul-timetable", SeoulSubwayLine.LINE_2,
            java.time.LocalDate.parse("2026-08-10"), "2464", "DOWN", TransitServiceClass.LOCAL,
            "0226", "0230", "신도림", fetchedAt.plusSeconds(2400), fetchedAt.plusSeconds(3300), fetchedAt)
        val first = EffectiveTransitRouteSegment(0, EffectiveTransitRouteSegmentKind.SUBWAY, 20,
            rideTiming = EffectiveTransitRideTiming(firstTable, firstTable.boardingAt, firstTable.arrivalAt,
                EffectiveTransitRideTimingBasis.TIMETABLE))
        val transfer = EffectiveTransitRouteSegment(1, EffectiveTransitRouteSegmentKind.SUBWAY, 15,
            rideTiming = EffectiveTransitRideTiming(transferTable, transferTable.boardingAt, transferTable.arrivalAt,
                EffectiveTransitRideTimingBasis.TIMETABLE))
        val plan = effectiveRoute("f".repeat(64), listOf(first, transfer))
        val accepted = requireNotNull(EffectiveTransitRouteSnapshot.from(
            trafficResult(plan.identity).copy(effectiveTransitRoute = plan), null, 0))
        val readAt = fetchedAt.plusSeconds(2700)
        val delayedTransfer = transfer.copy(rideTiming = EffectiveTransitRideTiming(
            transferTable, transferTable.boardingAt, transferTable.arrivalAt.plusSeconds(180),
            EffectiveTransitRideTimingBasis.TRANSFER_ALIGHTING_REALTIME, readAt.minusSeconds(20)))
        val candidate = plan.copy(arrivalAt = plan.arrivalAt.plusSeconds(180),
            segments = listOf(first, delayedTransfer))
        val degraded = requireNotNull(EffectiveTransitRouteSnapshot.from(
            trafficResult(plan.identity).copy(stale = true,
                failureReason = TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN,
                effectiveTransitRoute = candidate), accepted, 1, evaluatedAt = readAt))

        assertEquals(plan, degraded.inProgressPlannedRoute)
        assertEquals(candidate.arrivalAt, degraded.plannedJourneyArrivalPrediction(readAt, 120)?.predictedArrivalAt)
        assertEquals(com.noLate.schedule.domain.PlannedJourneyArrivalBasis.MATCHED_TRAIN_REALTIME,
            degraded.plannedJourneyArrivalPrediction(readAt, 120)?.basis)
        assertEquals(plan.arrivalAt, degraded.plannedJourneyArrivalPrediction(readAt.plusSeconds(121), 120)?.predictedArrivalAt)
        assertEquals(com.noLate.schedule.domain.PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN,
            degraded.plannedJourneyArrivalPrediction(readAt.plusSeconds(121), 120)?.basis)
        val wrongTrain = candidate.copy(segments = listOf(first, delayedTransfer.copy(rideTiming =
            delayedTransfer.rideTiming!!.copy(timetable = transferTable.copy(trainNumber = "2465")))))
        assertEquals(plan.arrivalAt, degraded.copy(effectiveTransitRoute = wrongTrain)
            .plannedJourneyArrivalPrediction(readAt, 120)?.predictedArrivalAt)
        val latestDirect = PlannedJourneyArrivalObservation(plan.identity, plan.arrivalAt.plusSeconds(240), readAt)
        assertEquals(latestDirect.predictedArrivalAt, degraded.copy(plannedArrivalObservation = latestDirect)
            .plannedJourneyArrivalPrediction(readAt, 120)?.predictedArrivalAt)
        assertEquals(candidate.arrivalAt, degraded.copy(
            plannedArrivalObservation = latestDirect.copy(sourceUpdatedAt = readAt.minusSeconds(21)),
        ).plannedJourneyArrivalPrediction(readAt, 120)?.predictedArrivalAt)

        val finalTable = transferTable.copy(trainNumber = "2510", fromStationCode = "0230", toStationCode = "0232",
            boardingAt = fetchedAt.plusSeconds(3600), arrivalAt = fetchedAt.plusSeconds(3780))
        val finalRide = transfer.copy(sequence = 2, rideTiming = EffectiveTransitRideTiming(
            finalTable, finalTable.boardingAt, finalTable.arrivalAt, EffectiveTransitRideTimingBasis.TIMETABLE,
        ))
        val threeRidePlan = plan.copy(segments = listOf(first, transfer, finalRide))
        val intermediateObservation = degraded.copy(
            inProgressPlannedRoute = threeRidePlan,
            effectiveTransitRoute = threeRidePlan.copy(segments = listOf(first, delayedTransfer, finalRide)),
        )
        assertEquals(com.noLate.schedule.domain.PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN,
            intermediateObservation.plannedJourneyArrivalPrediction(readAt, 120)?.basis)
        assertEquals(latestDirect.predictedArrivalAt, intermediateObservation.copy(plannedArrivalObservation = latestDirect)
            .plannedJourneyArrivalPrediction(readAt, 120)?.predictedArrivalAt)
        val roundTrip = EffectiveTransitRouteSnapshot.parse(objectMapper, objectMapper.writeValueAsString(degraded))
        assertEquals(plan, roundTrip?.inProgressPlannedRoute)
        assertNull(accepted.plannedJourneyArrivalPrediction(readAt, 120))
    }

    @Test
    fun `도보 상세는 정상과 degraded 스냅샷에 보존하고 구버전 명목 좌표는 UNKNOWN이다`() {
        val geometry = com.noLate.eta.domain.TransitWalkingGeometry(
            endpointBasis = com.noLate.eta.domain.TransitWalkingEndpointBasis.PROVIDER_LEG_ENDPOINTS,
            pathEvidence = com.noLate.eta.domain.TransitWalkingPathEvidence("tmap",
                com.noLate.eta.domain.TransitWalkingPathStatus.PROVIDED,
                listOf(com.noLate.eta.domain.TransitWalkingStep(
                    listOf(com.noLate.eta.domain.TransitWalkingCoordinate(37.5,127.0),
                        com.noLate.eta.domain.TransitWalkingCoordinate(37.501,127.001)), 128.0, "출구 경유"))))
        val original = trafficResult(identity = "a".repeat(64))
        val route = requireNotNull(original.effectiveTransitRoute).copy(segments = listOf(
            EffectiveTransitRouteSegment(0, EffectiveTransitRouteSegmentKind.WALK, 2,
                durationSeconds = 111, walkingGeometry = geometry)))
        val accepted = requireNotNull(EffectiveTransitRouteSnapshot.from(original.copy(effectiveTransitRoute = route),null,0))
        val degraded = requireNotNull(EffectiveTransitRouteSnapshot.from(original.copy(stale = true,
            failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE, effectiveTransitRoute = route), accepted,1))
        for (snapshot in listOf(accepted,degraded)) {
            val parsed = requireNotNull(EffectiveTransitRouteSnapshot.parse(objectMapper,objectMapper.writeValueAsString(snapshot)))
            assertEquals(geometry, parsed.effectiveTransitRoute?.segments?.single()?.walkingGeometry)
            assertEquals(geometry, parsed.acceptedRoute()?.segments?.single()?.walkingGeometry)
        }
        val legacy = objectMapper.readValue("""{"from":{"lat":37.5,"lng":127.0},"distanceMeters":128}""",
            com.noLate.eta.domain.TransitWalkingGeometry::class.java)
        assertEquals(com.noLate.eta.domain.TransitWalkingEndpointBasis.UNKNOWN,legacy.endpointBasis)
        assertNull(legacy.pathEvidence)
    }

    @Test
    fun `열차 시각 근거를 스냅샷에 보존하고 기존 승차 JSON에는 시각을 만들지 않는다`() {
        val table = TransitLegTimetable("seoul-timetable", SeoulSubwayLine.LINE_4,
            java.time.LocalDate.parse("2026-08-10"), "4012", "DOWN", TransitServiceClass.LOCAL,
            "0426", "0433", "오이도", fetchedAt.plusSeconds(197), fetchedAt.plusSeconds(1397), fetchedAt)
        val timing = EffectiveTransitRideTiming(table, fetchedAt.plusSeconds(210), fetchedAt.plusSeconds(1410),
            EffectiveTransitRideTimingBasis.FIRST_BOARDING_REALTIME_ADJUSTED_TIMETABLE)
        val segment = EffectiveTransitRouteSegment(0, EffectiveTransitRouteSegmentKind.SUBWAY, 22, rideTiming = timing)
        val result = trafficResult(identity = "a".repeat(64))
        val route = requireNotNull(result.effectiveTransitRoute).copy(segments = listOf(segment))
        val snapshot = requireNotNull(EffectiveTransitRouteSnapshot.from(result.copy(effectiveTransitRoute = route), null, 0))
        val parsed = requireNotNull(EffectiveTransitRouteSnapshot.parse(objectMapper, objectMapper.writeValueAsString(snapshot)))
        assertEquals(timing, parsed.acceptedRoute()?.segments?.single()?.rideTiming)
        val legacy = objectMapper.readValue("""{"sequence":0,"kind":"SUBWAY","durationMinutes":22}""",
            EffectiveTransitRouteSegment::class.java)
        assertNull(legacy.rideTiming)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            timing.copy(predictedArrivalAt = timing.predictedArrivalAt.plusSeconds(1))
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            timing.copy(basis = EffectiveTransitRideTimingBasis.TIMETABLE)
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            segment.copy(kind = EffectiveTransitRouteSegmentKind.WALK)
        }
    }

    @Test
    fun `도보 메타데이터는 스냅샷에 왕복 보존하고 기존 JSON은 null로 읽는다`() {
        val segment = EffectiveTransitRouteSegment(0, EffectiveTransitRouteSegmentKind.WALK, 5,
            walkingScope = com.noLate.eta.domain.TransitWalkingScope.TRANSFER, durationSeconds = 243,
            walkingGeometry = com.noLate.eta.domain.TransitWalkingGeometry(
                from = com.noLate.eta.domain.TransitWalkingCoordinate(37.5, 127.0), distanceMeters = 155))
        assertEquals(segment, objectMapper.readValue(objectMapper.writeValueAsString(segment), EffectiveTransitRouteSegment::class.java))
        val legacy = objectMapper.readValue("""{"sequence":0,"kind":"WALK","durationMinutes":5}""",
            EffectiveTransitRouteSegment::class.java)
        assertNull(legacy.walkingScope)
        assertNull(legacy.durationSeconds)
        assertNull(legacy.walkingGeometry)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            segment.copy(durationSeconds = 301)
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            segment.copy(kind = EffectiveTransitRouteSegmentKind.SUBWAY)
        }
    }

    @Test
    fun `첫 차량 관측은 JSON에 보존하지만 다음 실패 회차로 승계하거나 ETA 채택으로 바꾸지 않는다`() {
        val observation = com.noLate.eta.domain.TransitFirstBoardingObservation(
            provider = "seoul-openapi", stationName = "서울역", lineName = "4호선",
            departureAt = fetchedAt, readyAt = fetchedAt.plusSeconds(180),
            expectedBoardingAt = fetchedAt.plusSeconds(360), sourceUpdatedAt = fetchedAt, evaluatedAt = fetchedAt,
        )
        val result = TrafficResult(
            travelMinutes = 65, source = TrafficSource.SELECTED_ROUTE, stale = true,
            failureReason = TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY,
            firstBoardingObservation = observation,
        )
        val snapshot = requireNotNull(EffectiveTransitRouteSnapshot.from(result, null, 0))
        val parsed = requireNotNull(EffectiveTransitRouteSnapshot.parse(objectMapper, objectMapper.writeValueAsString(snapshot)))

        assertEquals(observation, parsed.firstBoardingObservation)
        assertFalse(parsed.accepted)
        assertNull(parsed.acceptedRoute())
        assertNull(parsed.firstBoardingWaitMinutes)
        val failed = EffectiveTransitRouteSnapshot.from(result.copy(firstBoardingObservation = null), parsed, 1)
        assertNull(failed?.firstBoardingObservation)
    }

    @Test
    fun `first verified alternative is a route change and survives the same check retry`() {
        val result = trafficResult(identity = "a".repeat(64))

        val first = requireNotNull(
            EffectiveTransitRouteSnapshot.from(result, previous = null, evaluationCheckCount = 3)
        )
        val retry = requireNotNull(
            EffectiveTransitRouteSnapshot.from(result, previous = first, evaluationCheckCount = 3)
        )
        val nextCheck = requireNotNull(
            EffectiveTransitRouteSnapshot.from(result, previous = retry, evaluationCheckCount = 4)
        )

        assertTrue(first.routeChanged)
        assertTrue(first.routeTransitioned)
        assertTrue(retry.routeChanged)
        assertTrue(retry.routeTransitioned)
        assertTrue(nextCheck.routeChanged)
        assertFalse(nextCheck.routeTransitioned)
    }

    @Test
    fun `snooze generation change does not replay a check zero route transition`() {
        val job = SchedulePushJob.create(
            memberId = 1,
            scheduleId = 10,
            scheduleAt = fetchedAt.plus(2, ChronoUnit.HOURS),
            departureAt = fetchedAt.plus(1, ChronoUnit.HOURS),
            monitorStartAt = fetchedAt,
            intervalMinutes = 20,
        )
        val first = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult = trafficResult(identity = "a".repeat(64)),
                previous = null,
                evaluationCheckCount = job.checkCount,
                notificationGeneration = job.notificationGeneration,
            )
        )

        job.snoozeUntil(fetchedAt.plus(5, ChronoUnit.MINUTES))
        val afterSnooze = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult = trafficResult(identity = "a".repeat(64)),
                previous = first,
                evaluationCheckCount = job.checkCount,
                notificationGeneration = job.notificationGeneration,
            )
        )

        assertEquals(0, first.evaluationCheckCount)
        assertTrue(first.routeTransitioned)
        assertEquals(0, job.checkCount)
        assertEquals(1, job.notificationGeneration)
        assertTrue(afterSnooze.routeChanged)
        assertFalse(afterSnooze.routeTransitioned)
    }

    @Test
    fun `alternative returning to selected route clears state and emits one transition`() {
        val alternative = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult(
                    identity = "a".repeat(64),
                    provenance = TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
                ),
                previous = null,
                evaluationCheckCount = 1,
            )
        )
        val selected = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult(
                    identity = "b".repeat(64),
                    provenance = TransitRouteProvenance.SELECTED_ROUTE_PRESERVED,
                ),
                previous = alternative,
                evaluationCheckCount = 2,
            )
        )

        assertTrue(alternative.routeChanged)
        assertFalse(selected.routeChanged)
        assertTrue(selected.routeTransitioned)
        val retry = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult(
                    identity = "b".repeat(64),
                    provenance = TransitRouteProvenance.SELECTED_ROUTE_PRESERVED,
                ),
                previous = selected,
                evaluationCheckCount = 2,
            )
        )
        assertFalse(retry.routeChanged)
        assertTrue(retry.routeTransitioned)
    }

    @Test
    fun `degraded evaluation keeps accepted baseline and same route recovery does not notify again`() {
        val acceptedA = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult(identity = "a".repeat(64)),
                previous = null,
                evaluationCheckCount = 1,
            )
        )
        val degraded = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                TrafficResult(
                    travelMinutes = 55,
                    source = TrafficSource.SELECTED_ROUTE,
                    stale = true,
                    failureReason = TrafficFailureReasons.PROVIDER_TIMEOUT,
                ),
                previous = acceptedA,
                evaluationCheckCount = 2,
            )
        )
        val recoveredA = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult(identity = "a".repeat(64)),
                previous = degraded,
                evaluationCheckCount = 3,
            )
        )
        val recoveredB = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult(identity = "b".repeat(64)),
                previous = degraded,
                evaluationCheckCount = 3,
            )
        )

        assertFalse(degraded.accepted)
        assertEquals("a".repeat(64), degraded.lastAcceptedRouteIdentity)
        assertEquals(
            TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
            degraded.lastAcceptedRouteProvenance,
        )
        assertTrue(recoveredA.routeChanged)
        assertFalse(recoveredA.routeTransitioned)
        assertTrue(recoveredB.routeChanged)
        assertTrue(recoveredB.routeTransitioned)
    }

    @Test
    fun `snapshot round trip is fail soft and keeps exact route contract`() {
        val snapshot = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult(identity = "b".repeat(64)),
                previous = null,
                evaluationCheckCount = 0,
            )
        )

        val restored = EffectiveTransitRouteSnapshot.parse(
            objectMapper,
            objectMapper.writeValueAsString(snapshot),
        )

        assertEquals("b".repeat(64), restored?.effectiveTransitRoute?.identity)
        assertEquals(0, restored?.evaluationNotificationGeneration)
        assertEquals(TransitTimingBasis.TIMETABLE_ONLY, restored?.transitTimingBasis)
        assertEquals(6, restored?.firstBoardingWaitMinutes)
        assertEquals("b".repeat(64), restored?.lastAcceptedRouteIdentity)
        assertNull(EffectiveTransitRouteSnapshot.parse(objectMapper, "{not-json"))
        assertNull(EffectiveTransitRouteSnapshot.parse(objectMapper, "{\"version\":99}"))
    }

    @ParameterizedTest
    @EnumSource(TransitTimingBasis::class, names = ["FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE", "ROUTE_ESTIMATE_ONLY"])
    fun `추정 기반 스냅샷은 JSON 왕복 후에도 미채택 상태와 근거를 보존한다`(basis: TransitTimingBasis) {
        val result = TrafficResult(
            travelMinutes = 55,
            source = TrafficSource.SELECTED_ROUTE,
            stale = true,
            failureReason = TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY,
            transitTimingBasis = basis,
            effectiveTransitRoute = effectiveRoute("d".repeat(64)),
        )
        val snapshot = requireNotNull(
            EffectiveTransitRouteSnapshot.from(result, previous = null, evaluationCheckCount = 0)
        )

        val restored = requireNotNull(
            EffectiveTransitRouteSnapshot.parse(objectMapper, objectMapper.writeValueAsString(snapshot))
        )

        assertEquals(basis, restored.transitTimingBasis)
        assertFalse(restored.accepted)
        assertNull(restored.lastAcceptedRouteIdentity)
        assertNull(restored.acceptedRoute())
    }

    @Test
    fun `live activity route bar preserves both ends when itinerary is long`() {
        val segments = (0..6).map { sequence ->
            EffectiveTransitRouteSegment(
                sequence = sequence,
                kind = if (sequence % 2 == 0) {
                    EffectiveTransitRouteSegmentKind.BUS
                } else {
                    EffectiveTransitRouteSegmentKind.SUBWAY
                },
                durationMinutes = 5,
                lineName = "L$sequence",
            )
        }
        val route = effectiveRoute("c".repeat(64), segments)

        val display = route.toRouteDisplaySegments()

        assertEquals(5, display.size)
        assertEquals(listOf("L0", "L1", "3구간 생략", "L5", "L6"), display.map { it.label })
    }

    private fun trafficResult(
        identity: String,
        provenance: TransitRouteProvenance = TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
    ) = TrafficResult(
        travelMinutes = 55,
        source = TrafficSource.TIMETABLE_PROVIDER,
        fetchedAt = fetchedAt,
        stale = false,
        recommendedDepartureAt = Instant.parse("2026-08-10T01:10:00Z"),
        predictedArrivalAt = Instant.parse("2026-08-10T02:05:00Z"),
        transitRouteProvenance = provenance,
        transitTimingBasis = TransitTimingBasis.TIMETABLE_ONLY,
        firstBoardingWaitMinutes = 6,
        effectiveTransitRoute = effectiveRoute(identity),
    )

    private fun effectiveRoute(
        identity: String,
        segments: List<EffectiveTransitRouteSegment> = listOf(
            EffectiveTransitRouteSegment(
                sequence = 0,
                kind = EffectiveTransitRouteSegmentKind.WALK,
                durationMinutes = 4,
                fromName = "집",
                toName = "정류장",
            ),
            EffectiveTransitRouteSegment(
                sequence = 1,
                kind = EffectiveTransitRouteSegmentKind.BUS,
                durationMinutes = 45,
                waitingMinutes = 6,
                lineName = "341",
                fromName = "강남역",
                toName = "회사",
            ),
        ),
    ) = EffectiveTransitRoute(
        provider = "ODSAY",
        identity = identity,
        departureAt = Instant.parse("2026-08-10T01:10:00Z"),
        arrivalAt = Instant.parse("2026-08-10T02:05:00Z"),
        totalMinutes = 55,
        segments = segments,
    )
}
