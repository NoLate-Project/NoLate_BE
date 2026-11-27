package com.noLate.eta.application.transit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.schedule.application.EffectiveBusRidePrediction
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.EffectiveTransitRouteSegment
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind.BUS
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind.SUBWAY
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind.WALK
import com.noLate.schedule.application.EffectiveTransitRouteSnapshot
import com.noLate.schedule.application.EffectiveTransitRideTiming
import com.noLate.schedule.application.EffectiveTransitRideTimingBasis
import com.noLate.schedule.application.PlannedJourneyArrivalObservation
import com.noLate.schedule.application.PlannedSeoulBusJourney
import com.noLate.schedule.application.PlannedSeoulBusRide
import com.noLate.schedule.application.PlannedTransitRouteUpdate
import com.noLate.schedule.application.PlannedTransitTimingFailure
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.domain.PlannedJourneyArrivalBasis
import com.noLate.schedule.domain.TrafficSource
import com.noLate.transit.domain.SeoulBusRideService
import com.noLate.transit.domain.SeoulBusRouteStop
import com.noLate.transit.domain.SeoulBusVehicleObservation
import com.noLate.transit.domain.SeoulBusVehicleObservationBatch
import com.noLate.transit.domain.SeoulBusVehicleObservationStatus
import com.noLate.transit.domain.SeoulBusVehicleQuery
import com.noLate.transit.infrastructure.SeoulBusAlightingForecast
import com.noLate.transit.infrastructure.SeoulBusPairedArrival
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.time.Instant
import java.time.LocalDate

class PlannedSeoulBusRouteRefresherTest {
    private val start = Instant.parse("2026-11-27T00:00:00Z")
    private fun time(seconds: Long) = start.plusSeconds(seconds)

    private val provider = mock<SeoulTransitArrivalClient>()
    private val evaluator = TransitTransferFeasibilityEvaluator(60)
    private val refresher = PlannedSeoulBusRouteRefresher(provider, evaluator, 60, 120, 1440)
    private val first = stops("100100101", "10100000", 10)
    private val second = stops("100100102", "10200000", 20)
    private val firstVehicle = SeoulBusRideService.fromVehicle(first.first(), first.last(), "101000091")
    private val oldSecondVehicle = SeoulBusRideService.fromVehicle(second.first(), second.last(), "101000092")
    private val newSecondVehicle = SeoulBusRideService.fromVehicle(second.first(), second.last(), "101000093")
    private val route = EffectiveTransitRoute("odsay", "b".repeat(64), start, time(600), 10, listOf(
        walk(0, 30),
        bus(1, first, time(90), time(240)),
        walk(2, 30),
        bus(3, second, time(380), time(540)).copy(
            transferTiming = evaluator.describeTransfer(time(240), 30, time(380))),
        walk(4, 60),
    ))
    private val journey = PlannedSeoulBusJourney(route.identity, start,
        listOf(PlannedSeoulBusRide(1, firstVehicle), PlannedSeoulBusRide(3, oldSecondVehicle)))

    @Test fun `same bus delay reconnects the onward bus and uses its directly predicted alighting`() {
        currentBus()
        // The old bus arrives before the passenger can complete the same walk and buffer.
        onwardBuses(listOf(pair(time(470), time(651), oldSecondVehicle), pair(time(480), time(760), newSecondVehicle)))
        val refreshed = requireNotNull(refresher.refresh(route, journey, time(150)))
        val result = requireNotNull(refreshed.update.route)
        assertEquals(start, result.departureAt)
        assertEquals(route.identity, result.identity)
        assertEquals(time(820), result.arrivalAt)
        assertEquals(14, result.totalMinutes)
        assertEquals(time(390), result.segments[1].busRidePrediction!!.alightingAt)
        assertEquals(time(480), result.segments[3].busRidePrediction!!.boardingAt)
        assertEquals(30L, result.segments[3].transferTiming!!.walkingSeconds)
        assertEquals(60L, result.segments[3].transferTiming!!.requiredMarginSeconds)
        assertEquals(newSecondVehicle, refreshed.journey.rides[1].service)
        assertEquals(time(145), refreshed.update.sourceUpdatedAt)

        val snapshot = EffectiveTransitRouteSnapshot(inProgressPlannedRoute = route,
            plannedSeoulBusJourney = refreshed.journey, plannedRouteUpdate = refreshed.update)
        val fresh = snapshot.plannedJourneyArrivalPrediction(time(170), 120)!!
        assertEquals(PlannedJourneyArrivalBasis.MATCHED_BUS_REALTIME, fresh.basis)
        assertEquals(time(820), fresh.predictedArrivalAt)
        val expired = snapshot.plannedJourneyArrivalPrediction(time(191), 120)!!
        assertEquals(PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN, expired.basis)
        assertNull(expired.effectiveTransitRoute!!.segments[1].busRidePrediction!!.alightingObservationSourceUpdatedAt)
        assertEquals(time(820), expired.predictedArrivalAt)
    }

    @Test fun `same route with a different bus or a reused stop position cannot replace the saved estimate`() {
        currentBus(vehicleId = "101000099")
        assertNull(refresher.refresh(route, journey, time(150)))
        verify(provider, never()).getBusAlightingForecast(any(), any())
        currentBus(positionOrder = first.last().sequence)
        assertNull(refresher.refresh(route, journey, time(150)))
        verify(provider, never()).getBusAlightingForecast(any(), any())
    }

    @Test fun `same bus changes the planned train then a fresh observation of that train updates the final arrival`() {
        fun train(number: String, board: Long, alight: Long, destinationCode: String = "0230") = TransitLegTimetable(
            "seoul-timetable", SeoulSubwayLine.LINE_2, LocalDate.of(2026, 11, 27), number, "UP", TransitServiceClass.LOCAL,
            "0226", destinationCode, "성수", time(board), time(alight), time(153),
        )
        val originalTrain = train("2012", 380, 540)
        val originalRail = EffectiveTransitRouteSegment(3, SUBWAY, 5, lineName = "2호선", fromName = "사당", toName = "신림",
            transferTiming = evaluator.describeTransfer(time(240), 30, time(380)),
            rideTiming = EffectiveTransitRideTiming(originalTrain, originalTrain.boardingAt, originalTrain.arrivalAt,
                EffectiveTransitRideTimingBasis.TIMETABLE))
        val original = route.copy(segments = listOf(route.segments[0], route.segments[1], route.segments[2], originalRail, route.segments[4]))
        val binding = journey.copy(rides = listOf(journey.rides.first()))
        val rail = mock<TransitTimetableProvider>()
        whenever(rail.findRides(any())).thenReturn(listOf(train("2013", 470, 640), train("2100", 480, 680, "0229"),
            train("2014", 500, 760)))
        val guard = mock<TransitTransferRealtimeGuard>()
        val mixedRefresher = PlannedSeoulBusRouteRefresher(provider, evaluator, 60, 120, 1440,
            TransitJourneyTimetableEnricher(listOf(rail)), guard)
        currentBus()

        val afterBus = requireNotNull(mixedRefresher.refresh(original, binding, time(150)))
        val changedRoute = requireNotNull(afterBus.update.route)
        assertEquals(start, changedRoute.departureAt)
        assertEquals(time(820), changedRoute.arrivalAt)
        assertEquals("2014", changedRoute.segments[3].rideTiming!!.timetable.trainNumber)
        assertEquals(time(390), changedRoute.segments[3].transferTiming!!.previousArrivalAt)
        assertEquals(20L, changedRoute.segments[3].transferTiming!!.remainingSlackSeconds)
        verify(rail).findRides(check { assertEquals(time(480), it.notBefore) })
        val afterBusSnapshot = EffectiveTransitRouteSnapshot(inProgressPlannedRoute = original, plannedSeoulBusJourney = binding,
            plannedRouteUpdate = afterBus.update)
        assertEquals(PlannedJourneyArrivalBasis.MATCHED_BUS_REALTIME,
            afterBusSnapshot.plannedJourneyArrivalPrediction(time(170), 120)?.basis)

        whenever(guard.observeLastPlannedRide(changedRoute.copy(segments = listOf(changedRoute.segments[3])), time(600)))
            .thenReturn(PlannedJourneyArrivalObservation(route.identity, time(850), time(598)))
        assertNull(mixedRefresher.refresh(changedRoute, binding, time(600), pendingTransferFailure = true))
        val afterTrain = requireNotNull(mixedRefresher.refresh(changedRoute, binding, time(600)))
        val followed = afterBusSnapshot.copy(plannedRouteUpdate = afterTrain.update)
        val public = requireNotNull(followed.plannedJourneyArrivalPrediction(time(650), 120))
        assertEquals(PlannedJourneyArrivalBasis.MATCHED_TRAIN_REALTIME, public.basis)
        assertEquals(time(850), public.predictedArrivalAt)
        assertEquals(time(790), public.effectiveTransitRoute!!.segments[3].rideTiming!!.predictedArrivalAt)
        assertNull(public.effectiveTransitRoute!!.segments[1].busRidePrediction!!.alightingObservationSourceUpdatedAt)
        assertEquals(time(148), afterTrain.update.route!!.segments[1].busRidePrediction!!.alightingObservationSourceUpdatedAt)
        verify(provider).getBusAlightingForecast(first.last(), firstVehicle)
    }

    @Test fun `a known downstream miss does not publish a speculative final arrival`() {
        currentBus()
        onwardBuses(listOf(pair(time(470), time(651), oldSecondVehicle)))
        val refreshed = requireNotNull(refresher.refresh(route, journey, time(150)))
        assertEquals(PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE, refreshed.update.failure)
        assertNull(refreshed.update.route)
        assertEquals(journey, refreshed.journey)
    }

    @Test fun `train delay reconnects the bus and a later direct bus forecast keeps only its live source`() {
        val table = TransitLegTimetable("seoul-timetable", SeoulSubwayLine.LINE_4, LocalDate.of(2026, 11, 27), "4703", "DOWN",
            TransitServiceClass.LOCAL, "0431", "0433", "오이도", time(90), time(240), time(-5))
        val trainTiming = EffectiveTransitRideTiming(table, time(90), time(240), EffectiveTransitRideTimingBasis.TIMETABLE)
        val train = EffectiveTransitRouteSegment(1, SUBWAY, 4, lineName = "4호선", fromName = "동작", toName = "사당",
            rideTiming = trainTiming)
        val plan = route.copy(segments = route.segments.map { if (it.sequence == 1) train else it })
        val binding = journey.copy(rides = listOf(journey.rides.last()))
        assertTrue(binding.matches(plan))
        val rail = mock<PlannedTransitRouteRefresher>()
        val mixed = PlannedSeoulBusRouteRefresher(provider, evaluator, 60, 120, 1440, railRouteRefresher = rail)
        val railPrefix = plan.copy(segments = plan.segments.take(2), arrivalAt = time(240), totalMinutes = 4)
        val observedTrain = train.copy(rideTiming = trainTiming.copy(predictedArrivalAt = time(390),
            basis = EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME, observationSourceUpdatedAt = time(145)))
        whenever(rail.refresh(eq(railPrefix), eq(time(150)), eq(railPrefix), isNull()))
            .thenReturn(PlannedTransitRouteUpdate(plan.identity, time(145), onlyLastRideObserved = true,
                route = railPrefix.copy(segments = listOf(plan.segments.first(), observedTrain), arrivalAt = time(390), totalMinutes = 7)))
        onwardBuses(listOf(pair(time(470), time(651), oldSecondVehicle)))
        val missed = requireNotNull(mixed.refresh(plan, binding, time(150)))
        assertEquals(PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE, missed.update.failure)
        assertNull(mixed.refresh(plan, binding, time(510), pendingTransferFailure = true))

        onwardBuses(listOf(pair(time(470), time(651), oldSecondVehicle), pair(time(480), time(760), newSecondVehicle)))
        val reconnected = requireNotNull(mixed.refresh(plan, binding, time(150)))
        val updated = requireNotNull(reconnected.update.route)
        assertEquals(start, updated.departureAt)
        assertEquals(time(820), updated.arrivalAt)
        assertEquals(time(420), updated.segments[3].transferTiming?.physicalReadyAt)
        assertEquals(time(480), updated.segments[3].busRidePrediction?.boardingAt)
        assertEquals(newSecondVehicle, reconnected.journey.rides.single().service)
        val snapshot = EffectiveTransitRouteSnapshot(inProgressPlannedRoute = plan,
            plannedSeoulBusJourney = reconnected.journey, plannedRouteUpdate = reconnected.update)
        assertEquals(PlannedJourneyArrivalBasis.MATCHED_TRAIN_AND_BUS_REALTIME,
            snapshot.plannedJourneyArrivalPrediction(time(170), 120)?.basis)
        assertEquals(PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN, snapshot.plannedJourneyArrivalPrediction(time(191), 120)?.basis)

        whenever(provider.getBusVehicleObservations(SeoulBusVehicleQuery(
            newSecondVehicle.routeId, second.first().sequence, second.last().sequence,
        ))).thenReturn(SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.AVAILABLE, listOf(
            SeoulBusVehicleObservation("101000093", newSecondVehicle.routeId, "103000001", second[1].sequence, 300,
                false, second[1].stationId, time(508), time(510), time(550)),
        )))
        whenever(provider.getBusAlightingForecast(second.last(), newSecondVehicle))
            .thenReturn(SeoulBusAlightingForecast(time(790), time(509), time(512)))
        val afterBus = requireNotNull(mixed.refresh(updated, reconnected.journey, time(510), departurePlan = plan))
        val busPrediction = requireNotNull(snapshot.copy(plannedRouteUpdate = afterBus.update).plannedJourneyArrivalPrediction(time(520), 120))
        assertEquals(time(850), busPrediction.predictedArrivalAt)
        assertEquals(PlannedJourneyArrivalBasis.MATCHED_BUS_REALTIME, busPrediction.basis)
        val publicRoute = requireNotNull(busPrediction.effectiveTransitRoute)
        assertEquals(EffectiveTransitRideTimingBasis.PLANNED_RIDE_BASELINE, publicRoute.segments[1].rideTiming?.basis)
        assertEquals(time(509), publicRoute.segments[3].busRidePrediction?.alightingObservationSourceUpdatedAt)
        assertEquals(time(145), afterBus.update.route?.segments?.get(1)?.rideTiming?.observationSourceUpdatedAt)
    }

    @Test fun `a missed bus is reconnected after its old boarding time using fresh train and bus forecasts`() {
        val table = TransitLegTimetable("seoul-timetable", SeoulSubwayLine.LINE_4, LocalDate.of(2026, 11, 27), "4703", "DOWN",
            TransitServiceClass.LOCAL, "0431", "0433", "오이도", time(90), time(240), time(-5))
        val originalTiming = EffectiveTransitRideTiming(table, time(90), time(240), EffectiveTransitRideTimingBasis.TIMETABLE)
        val train = EffectiveTransitRouteSegment(1, SUBWAY, 4, lineName = "4호선", fromName = "동작", toName = "사당",
            rideTiming = originalTiming)
        val plan = route.copy(segments = route.segments.map { if (it.sequence == 1) train else it })
        val binding = journey.copy(rides = listOf(journey.rides.last()))
        val previousMiss = PlannedTransitRouteUpdate(plan.identity, time(145),
            failure = PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE)
        val baseline = EffectiveTransitRouteSnapshot(inProgressPlannedRoute = plan, plannedSeoulBusJourney = binding,
            plannedRouteUpdate = previousMiss)
        val rail = mock<PlannedTransitRouteRefresher>()
        val mixed = PlannedSeoulBusRouteRefresher(provider, evaluator, 60, 120, 1440, railRouteRefresher = rail)

        val evaluatedAt = time(385)
        val prefix = plan.copy(segments = plan.segments.take(2), arrivalAt = time(240), totalMinutes = 4)
        val observed = train.copy(rideTiming = originalTiming.copy(predictedArrivalAt = time(435),
            basis = EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME, observationSourceUpdatedAt = time(381)))
        whenever(rail.refresh(prefix, evaluatedAt, prefix, previousMiss)).thenReturn(
            PlannedTransitRouteUpdate(plan.identity, time(381), onlyLastRideObserved = true,
                route = prefix.copy(segments = listOf(plan.segments.first(), observed), arrivalAt = time(435), totalMinutes = 8)))
        onwardBuses(listOf(
            pair(time(524), time(720), oldSecondVehicle).copy(sourceUpdatedAt = time(382), receivedAt = time(386)),
            pair(time(530), time(750), newSecondVehicle).copy(sourceUpdatedAt = time(382), receivedAt = time(386)),
        ))

        assertNull(baseline.plannedJourneyArrivalPrediction(evaluatedAt, 120))
        val recovered = mixed.refresh(plan, binding, evaluatedAt, pendingTransferFailure = true, previousUpdate = previousMiss)
        assertNotNull(recovered, "The old bus boarding boundary must not freeze a known missed transfer.")
        val refreshed = requireNotNull(recovered)
        val result = requireNotNull(refreshed.update.route)
        assertEquals(start, result.departureAt)
        assertEquals(time(810), result.arrivalAt)
        assertEquals(time(435), result.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals(time(530), result.segments[3].busRidePrediction?.boardingAt)
        val transfer = requireNotNull(result.segments[3].transferTiming)
        assertEquals(30L, transfer.walkingSeconds)
        assertEquals(60L, transfer.requiredMarginSeconds)
        assertEquals(5L, transfer.remainingSlackSeconds)
        assertEquals(newSecondVehicle, refreshed.journey.rides.single().service)
        val public = baseline.copy(plannedSeoulBusJourney = refreshed.journey, plannedRouteUpdate = refreshed.update)
            .plannedJourneyArrivalPrediction(time(390), 120)
        assertEquals(PlannedJourneyArrivalBasis.MATCHED_TRAIN_AND_BUS_REALTIME, public?.basis)
        assertEquals(time(810), public?.predictedArrivalAt)
        verify(provider, never()).getBusVehicleObservations(any())
    }

    @Test fun `worker snapshot retains correlation across departure without publishing it in the route DTO`() {
        val mapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
        val accepted = TrafficResult(10, TrafficSource.LIVE_PROVIDER, fetchedAt = time(-4), stale = false,
            effectiveTransitRoute = route, seoulBusJourney = journey)
        val previous = requireNotNull(EffectiveTransitRouteSnapshot.from(accepted, null, 1, evaluatedAt = time(-4)))
        val json = mapper.writeValueAsString(previous)
        val persisted = EffectiveTransitRouteSnapshot.parse(mapper, json)
        val fallback = TrafficResult(10, TrafficSource.SELECTED_ROUTE, stale = true)
        val after = requireNotNull(EffectiveTransitRouteSnapshot.from(fallback, persisted, 2, evaluatedAt = time(150)))
        assertEquals(journey, after.plannedSeoulBusJourney)
        assertFalse(mapper.writeValueAsString(route).contains("vehicleKey"))
        assertFalse(mapper.writeValueAsString(accepted).contains("vehicleKey"))
        assertFalse(json.contains("101000091"))
    }

    private fun currentBus(vehicleId: String = "101000091", positionOrder: Int = first[1].sequence) {
        whenever(provider.getBusRouteDiagnosticStops(firstVehicle.routeId)).thenReturn(first)
        whenever(provider.getBusVehicleObservations(SeoulBusVehicleQuery(
            firstVehicle.routeId, first.first().sequence, first.last().sequence,
        ))).thenReturn(SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.AVAILABLE, listOf(
            SeoulBusVehicleObservation(vehicleId, firstVehicle.routeId, "103000001", positionOrder, 300,
                false, first[1].stationId, time(145), time(149), time(190)),
        )))
        whenever(provider.getBusAlightingForecast(first.last(), firstVehicle))
            .thenReturn(SeoulBusAlightingForecast(time(390), time(148), time(151)))
    }

    private fun onwardBuses(arrivals: List<SeoulBusPairedArrival>) {
        whenever(provider.getBusRouteDiagnosticStops(oldSecondVehicle.routeId)).thenReturn(second)
        whenever(provider.getPairedBusArrivals(second)).thenReturn(arrivals)
    }

    private fun pair(board: Instant, alight: Instant, service: SeoulBusRideService) =
        SeoulBusPairedArrival(board, alight, time(151), time(153), service)

    private fun stops(routeId: String, prefix: String, firstSequence: Int) = (0..2).map { index ->
        SeoulBusRouteStop(routeId, firstSequence + index, prefix + index, "0120" + index,
            "정류장 $firstSequence/$index", "방면", null, 37.5, 127.0)
    }
    private fun walk(sequence: Int, seconds: Long) = EffectiveTransitRouteSegment(
        sequence, WALK, ((seconds + 59) / 60).toInt(), durationSeconds = seconds)
    private fun bus(sequence: Int, path: List<SeoulBusRouteStop>, boardingAt: Instant, alightingAt: Instant) =
        EffectiveTransitRouteSegment(sequence, BUS, 5, lineName = "버스 $sequence",
            fromName = path.first().name, toName = path.last().name,
            busRidePrediction = EffectiveBusRidePrediction(boardingAt, alightingAt, time(-5)))
}
