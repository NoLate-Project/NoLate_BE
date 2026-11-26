package com.noLate.eta.application.transit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.schedule.application.EffectiveBusRidePrediction
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.EffectiveTransitRouteSegment
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind.BUS
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind.WALK
import com.noLate.schedule.application.EffectiveTransitRouteSnapshot
import com.noLate.schedule.application.PlannedSeoulBusJourney
import com.noLate.schedule.application.PlannedSeoulBusRide
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
        assertEquals(30, result.segments[3].transferTiming!!.walkingSeconds)
        assertEquals(60, result.segments[3].transferTiming!!.requiredMarginSeconds)
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

    @Test fun `a known downstream miss does not publish a speculative final arrival`() {
        currentBus()
        onwardBuses(listOf(pair(time(470), time(651), oldSecondVehicle)))
        val refreshed = requireNotNull(refresher.refresh(route, journey, time(150)))
        assertEquals(PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE, refreshed.update.failure)
        assertNull(refreshed.update.route)
        assertEquals(journey, refreshed.journey)
    }

    @Test fun `worker snapshot retains correlation across departure without publishing it in the route DTO`() {
        val mapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
        val accepted = TrafficResult(10, TrafficSource.LIVE_PROVIDER, fetchedAt = time(-4),
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
