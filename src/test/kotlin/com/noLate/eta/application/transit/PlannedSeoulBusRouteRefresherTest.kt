package com.noLate.eta.application.transit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.schedule.application.DeferredSeoulBusTransfer
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
import com.noLate.schedule.application.service.policy.PlannedTransitRefreshCadencePolicy
import com.noLate.schedule.domain.PlannedJourneyArrivalBasis
import com.noLate.schedule.domain.TrafficSource
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.SeoulBusConnectionStops
import com.noLate.transit.domain.SeoulBusRideService
import com.noLate.transit.domain.SeoulBusRouteStop
import com.noLate.transit.domain.SeoulBusVehicleObservation
import com.noLate.transit.domain.SeoulBusVehicleObservationBatch
import com.noLate.transit.domain.SeoulBusVehicleObservationStatus
import com.noLate.transit.domain.SeoulBusVehicleQuery
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.domain.TransitArrivalStatus
import com.noLate.transit.infrastructure.SeoulBusAlightingForecast
import com.noLate.transit.infrastructure.SeoulBusPairedArrival
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
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

    @Test fun `different vehicle or position beyond the alighting stop cannot replace the saved estimate`() {
        currentBus(vehicleId = "101000099")
        assertNull(refresher.refresh(route, journey, time(150)))
        verify(provider, never()).getBusAlightingForecast(any(), any())
        val beyond = first.last().copy(sequence = first.last().sequence + 1, stationId = "101000009")
        currentBus(lastStationId = beyond.stationId, catalog = first + beyond)
        assertNull(refresher.refresh(route, journey, time(150)))
        verify(provider, never()).getBusAlightingForecast(any(), any())
    }

    @ParameterizedTest
    @CsvSource("45,true", "46,false")
    fun `a new vehicle position cannot renew the publication time of its alighting forecast`(age: Long, fresh: Boolean) {
        val checkedAt = time(460)
        whenever(provider.getBusRouteDiagnosticStops(oldSecondVehicle.routeId)).thenReturn(second)
        whenever(provider.getBusVehicleObservations(SeoulBusVehicleQuery(
            oldSecondVehicle.routeId, second.first().sequence, second.last().sequence,
        ))).thenReturn(SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.AVAILABLE, listOf(
            SeoulBusVehicleObservation("101000092", oldSecondVehicle.routeId, "103000001", 501, 300,
                false, second[1].stationId, time(458), checkedAt, time(503)),
        )))
        val posted = checkedAt.minusSeconds(age)
        whenever(provider.getBusAlightingForecast(second.last(), oldSecondVehicle))
            .thenReturn(SeoulBusAlightingForecast(time(700), posted, checkedAt))

        val refreshed = refresher.refresh(route, journey, checkedAt)

        assertEquals(time(760).takeIf { fresh }, refreshed?.update?.route?.arrivalAt)
        assertEquals(posted.takeIf { fresh }, refreshed?.update?.sourceUpdatedAt)
    }

    @Test fun `a unique upstream stop confirms the same bus even with a different road section ordinal`() {
        currentBus(positionOrder = 501)
        onwardBuses(listOf(pair(time(470), time(651), oldSecondVehicle), pair(time(480), time(760), newSecondVehicle)))
        val refreshed = requireNotNull(refresher.refresh(route, journey, time(150)))
        val updated = requireNotNull(refreshed.update.route)
        assertEquals(start, updated.departureAt)
        assertEquals(time(390), updated.segments[1].busRidePrediction?.alightingAt)
        assertEquals(time(480), updated.segments[3].busRidePrediction?.boardingAt)
        assertEquals(time(820), updated.arrivalAt)
        verify(provider).getBusRouteDiagnosticStops(firstVehicle.routeId)
        verify(provider).getBusAlightingForecast(first.last(), firstVehicle)
    }

    @Test fun `a repeated station on the same route cannot identify which occurrence this bus passed`() {
        val repeated = first[1].copy(sequence = first.last().sequence + 1)
        currentBus(catalog = first + repeated)
        onwardBuses(listOf(pair(time(480), time(760), newSecondVehicle)))
        assertNull(refresher.refresh(route, journey, time(150)))
        verify(provider, never()).getBusAlightingForecast(any(), any())
    }

    @Test fun `a fresh last bus forecast can recover after the previous destination estimate passes`() {
        val checkedAt = time(610)
        whenever(provider.getBusRouteDiagnosticStops(oldSecondVehicle.routeId)).thenReturn(second)
        whenever(provider.getBusVehicleObservations(SeoulBusVehicleQuery(
            oldSecondVehicle.routeId, second.first().sequence, second.last().sequence,
        ))).thenReturn(SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.AVAILABLE, listOf(
            SeoulBusVehicleObservation("101000092", oldSecondVehicle.routeId, "103000001", 501, 300,
                false, second[1].stationId, time(605), time(608), time(650)),
        )))
        whenever(provider.getBusAlightingForecast(second.last(), oldSecondVehicle))
            .thenReturn(SeoulBusAlightingForecast(time(700), time(607), time(612)))
        val prior = EffectiveTransitRouteSnapshot(inProgressPlannedRoute = route, plannedSeoulBusJourney = journey)
        assertNull(prior.plannedJourneyArrivalPrediction(checkedAt, 120))
        val recovered = requireNotNull(refresher.refresh(route, journey, checkedAt))
        val forecast = requireNotNull(prior.copy(plannedRouteUpdate = recovered.update)
            .plannedJourneyArrivalPrediction(time(615), 120))
        assertEquals(journey, recovered.journey)
        assertEquals(time(760), forecast.predictedArrivalAt)
        assertEquals(PlannedJourneyArrivalBasis.MATCHED_BUS_REALTIME, forecast.basis)
        assertEquals(start, forecast.effectiveTransitRoute?.departureAt)
        assertEquals(route.segments[3].transferTiming, forecast.effectiveTransitRoute?.segments?.get(3)?.transferTiming)
        assertEquals(time(607), forecast.effectiveTransitRoute?.segments?.get(3)?.busRidePrediction?.alightingObservationSourceUpdatedAt)
        verify(provider, never()).getBusRouteDiagnosticStops(firstVehicle.routeId)
        verify(provider, never()).getPairedBusArrivals(any())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a same bus stop report replaces the final forecast and keeps the remaining walking time`(withTrain: Boolean) {
        val finalBus = if (withTrain) route.segments[3] else bus(1, second, time(380), time(540))
        val segments = if (withTrain) {
            val table = TransitLegTimetable("seoul-timetable", SeoulSubwayLine.LINE_2, LocalDate.of(2026, 11, 27),
                "2012", "UP", TransitServiceClass.LOCAL, "0226", "0230", "성수", time(90), time(240), time(-5))
            listOf(walk(0, 30), EffectiveTransitRouteSegment(1, SUBWAY, 4, lineName = "2호선", fromName = "사당", toName = "신림",
                rideTiming = EffectiveTransitRideTiming(table, table.boardingAt, table.arrivalAt, EffectiveTransitRideTimingBasis.TIMETABLE)),
                walk(2, 30), finalBus, walk(4, 120))
        } else listOf(walk(0, 30), finalBus, walk(2, 120))
        val plan = route.copy(arrivalAt = time(660), totalMinutes = 11, segments = segments)
        val saved = journey.copy(rides = listOf(PlannedSeoulBusRide(finalBus.sequence, oldSecondVehicle)))
        finalBusAtStop()
        whenever(provider.getBusAlightingForecast(second.last(), oldSecondVehicle))
            .thenReturn(SeoulBusAlightingForecast(time(527), time(527), time(532), reportedAtStop = true))

        val refreshed = requireNotNull(refresher.refresh(plan, saved, time(531)))
        val corrected = requireNotNull(refreshed.update.route)
        assertEquals(time(647), corrected.arrivalAt)
        assertEquals(start, corrected.departureAt)
        assertEquals(time(527), refreshed.update.reportedBusAlighting?.reportedAt)
        assertEquals(plan.segments.last(), corrected.segments.last())
        val snapshot = EffectiveTransitRouteSnapshot(inProgressPlannedRoute = plan, plannedRouteUpdate = refreshed.update,
            plannedSeoulBusJourney = saved)
        val prediction = requireNotNull(snapshot.plannedJourneyArrivalPrediction(time(610), 120))
        assertEquals("FINAL_BUS_ALIGHTING_REPORTED", prediction.basis.name)
        assertEquals(time(527), prediction.sourceUpdatedAt)
        assertEquals(time(647), prediction.predictedArrivalAt)
        assertEquals(time(647), PlannedTransitRefreshCadencePolicy.nextRefreshAt(time(610), snapshot))
        assertNull(snapshot.plannedJourneyArrivalPrediction(time(647), 120))
        assertNull(PlannedTransitRefreshCadencePolicy.nextRefreshAt(time(647), snapshot))
        assertNull(refresher.refresh(corrected, saved, time(610), previousUpdate = refreshed.update))
        verify(provider).getBusAlightingForecast(second.last(), oldSecondVehicle)
    }

    @Test fun `a repeated target stop cannot confirm that the bus arrived at the selected occurrence`() {
        val repeated = second.last().copy(sequence = second.last().sequence + 1)
        finalBusAtStop(catalog = second + repeated)
        assertNull(refresher.refresh(route, journey, time(531)))
        verify(provider, never()).getBusAlightingForecast(any(), any())
    }

    private fun finalBusAtStop(catalog: List<SeoulBusRouteStop> = second) {
        whenever(provider.getBusRouteDiagnosticStops(oldSecondVehicle.routeId)).thenReturn(catalog)
        whenever(provider.getBusVehicleObservations(SeoulBusVehicleQuery(oldSecondVehicle.routeId, 20, 22)))
            .thenReturn(SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.AVAILABLE, listOf(
                SeoulBusVehicleObservation("101000092", oldSecondVehicle.routeId, "103000001", 501, 300,
                    true, second.last().stationId, time(526), time(530), time(571)),
            )))
    }

    @Test fun `an expired route with an unconfirmed transfer or outside the last stop window makes no new requests`() {
        assertNull(refresher.refresh(route, journey, time(610), pendingTransferFailure = true))
        assertNull(refresher.refresh(route, journey, time(660)))
        verifyNoInteractions(provider)
    }

    @Test fun `a bus to rail journey can recover the already selected final train after its old estimate expires`() {
        val table = TransitLegTimetable("seoul-timetable", SeoulSubwayLine.LINE_2, LocalDate.of(2026, 11, 27),
            "2012", "UP", TransitServiceClass.LOCAL, "0226", "0230", "성수", time(380), time(540), time(-5))
        val train = EffectiveTransitRouteSegment(3, SUBWAY, 5, lineName = "2호선", fromName = "사당", toName = "신림",
            transferTiming = route.segments[3].transferTiming, rideTiming = EffectiveTransitRideTiming(
                table, table.boardingAt, table.arrivalAt, EffectiveTransitRideTimingBasis.TIMETABLE))
        val planned = route.copy(segments = route.segments.map { if (it.sequence == train.sequence) train else it })
        val binding = journey.copy(rides = listOf(journey.rides.first()))
        val guard = mock<TransitTransferRealtimeGuard>()
        val mixed = PlannedSeoulBusRouteRefresher(provider, evaluator, 60, 120, 1440, railRealtime = guard)
        val at = time(610)
        whenever(guard.readLastPlannedRide(planned.copy(segments = listOf(train)), at, allowAlightingRecovery = true))
            .thenReturn(LastSubwayRideEvidence(forecast = PlannedJourneyArrivalObservation(planned.identity, time(760), time(605))))
        val update = requireNotNull(mixed.refresh(planned, binding, at)).update
        val fresh = requireNotNull(EffectiveTransitRouteSnapshot(inProgressPlannedRoute = planned,
            plannedSeoulBusJourney = binding, plannedRouteUpdate = update).plannedJourneyArrivalPrediction(time(615), 120))
        assertEquals(time(760), fresh.predictedArrivalAt)
        assertEquals(PlannedJourneyArrivalBasis.MATCHED_TRAIN_REALTIME, fresh.basis)
        assertEquals("2012", fresh.effectiveTransitRoute?.segments?.get(3)?.rideTiming?.timetable?.trainNumber)
        assertEquals(time(380), fresh.effectiveTransitRoute?.segments?.get(3)?.rideTiming?.predictedBoardingAt)
        assertEquals(train.transferTiming, fresh.effectiveTransitRoute?.segments?.get(3)?.transferTiming)
        verifyNoInteractions(provider)
    }

    @Test fun `keeps the original bus transfer and uses the final train's reported arrival plus egress`() {
        val table = TransitLegTimetable("seoul-timetable", SeoulSubwayLine.LINE_2, LocalDate.of(2026, 11, 27),
            "2012", "DOWN", TransitServiceClass.LOCAL, "0226", "0230", "성수", time(380), time(540), time(-5))
        val train = EffectiveTransitRouteSegment(3, SUBWAY, 5, lineName = "2호선", fromName = "사당", toName = "신림",
            transferTiming = route.segments[3].transferTiming, rideTiming = EffectiveTransitRideTiming(
                table, table.boardingAt, table.arrivalAt, EffectiveTransitRideTimingBasis.TIMETABLE),
            orderedStopNames = listOf("사당", "낙성대", "서울대입구", "봉천", "신림"))
        val planned = route.copy(segments = route.segments.map { if (it.sequence == train.sequence) train else it })
        val binding = journey.copy(rides = listOf(journey.rides.first()))
        val arrivals = mock<TransitArrivalService>()
        val mixed = PlannedSeoulBusRouteRefresher(provider, evaluator, 60, 120, 1440,
            railRealtime = TransitTransferRealtimeGuard(arrivals))
        val reported = TransitArrivalDto("seoul-openapi", "SUBWAY", stationName = "신림", direction = "하행",
            destinationName = "성수", express = false, subwayLineId = "1002", trainNumber = "2012", waitSeconds = 0,
            expectedAt = time(606).toString(), sourceUpdatedAt = time(606).toString(), observedAt = time(610).toString(),
            freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP, arrivalStatus = TransitArrivalStatus.ARRIVED)
        whenever(arrivals.getSubwayArrivalsForEta("신림", "2호선", null, "DOWN", 10)).thenReturn(listOf(reported))

        assertNull(mixed.refresh(planned, binding, time(610), pendingTransferFailure = true))
        val update = requireNotNull(mixed.refresh(planned, binding, time(610))).update
        val revised = requireNotNull(update.route)
        val snapshot = EffectiveTransitRouteSnapshot(inProgressPlannedRoute = planned,
            plannedSeoulBusJourney = binding, plannedRouteUpdate = update, lastUpdatedPlannedRoute = revised)
        val public = requireNotNull(snapshot.plannedJourneyArrivalPrediction(time(615), 120))
        assertEquals(PlannedJourneyArrivalBasis.FINAL_TRAIN_ALIGHTING_REPORTED, public.basis)
        assertEquals(time(666), public.predictedArrivalAt)
        assertEquals(start, revised.departureAt)
        assertEquals(train.transferTiming, revised.segments[3].transferTiming)
        assertEquals(time(606), revised.segments[3].rideTiming?.reportedAlightingAt)
        assertEquals(time(666), PlannedTransitRefreshCadencePolicy.nextRefreshAt(time(615), snapshot))
        assertNull(mixed.refresh(revised, binding, time(615), previousUpdate = update))
        verify(arrivals).getSubwayArrivalsForEta("신림", "2호선", null, "DOWN", 10)
        verifyNoInteractions(provider)
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

        whenever(guard.readLastPlannedRide(changedRoute.copy(segments = listOf(changedRoute.segments[3])), time(600), true))
            .thenReturn(LastSubwayRideEvidence(forecast = PlannedJourneyArrivalObservation(route.identity, time(850), time(598))))
        assertNull(mixedRefresher.refresh(changedRoute, binding, time(600), pendingTransferFailure = true))
        val afterTrain = requireNotNull(mixedRefresher.refresh(changedRoute, binding, time(600)))
        val followed = afterBusSnapshot.copy(plannedRouteUpdate = afterTrain.update)
        val public = requireNotNull(followed.plannedJourneyArrivalPrediction(time(650), 120))
        assertEquals(PlannedJourneyArrivalBasis.MATCHED_TRAIN_REALTIME, public.basis)
        assertEquals(time(850), public.predictedArrivalAt)
        assertEquals(time(790), public.effectiveTransitRoute!!.segments[3].rideTiming!!.predictedArrivalAt)
        assertNull(public.effectiveTransitRoute!!.segments[1].busRidePrediction!!.alightingObservationSourceUpdatedAt)
        assertEquals(time(148), afterTrain.update.route!!.segments[1].busRidePrediction!!.alightingObservationSourceUpdatedAt)

        val delayed = requireNotNull(afterTrain.update.route)
        val recoveredAt = time(826).plusMillis(750)
        whenever(guard.readLastPlannedRide(delayed.copy(segments = listOf(delayed.segments[3])), time(660), true))
            .thenReturn(LastSubwayRideEvidence(forecast = PlannedJourneyArrivalObservation(route.identity, recoveredAt, time(659))))
        val recovered = requireNotNull(mixedRefresher.refresh(delayed, binding, time(660)))
        val recoveredRoute = requireNotNull(recovered.update.route)
        assertEquals(recoveredAt, recoveredRoute.arrivalAt)
        assertEquals(recoveredAt.minusSeconds(60), recoveredRoute.segments[3].rideTiming?.predictedArrivalAt)
        assertEquals("2014", recoveredRoute.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(start, recoveredRoute.departureAt)
        assertEquals(changedRoute.segments[3].transferTiming, recoveredRoute.segments[3].transferTiming)
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

    @Test fun `a stored bus stop report connects a later train without treating past departures as boardable`() {
        fun train(number: String, board: Long, alight: Long) = TransitLegTimetable("seoul-timetable", SeoulSubwayLine.LINE_2,
            LocalDate.of(2026, 11, 27), number, "UP", TransitServiceClass.LOCAL, "0226", "0230", "성수",
            time(board), time(alight), time(480))
        val originalTrain = train("2012", 380, 540)
        val railRide = EffectiveTransitRouteSegment(3, SUBWAY, 5, lineName = "2호선", fromName = "사당", toName = "신림",
            transferTiming = evaluator.describeTransfer(time(240), 30, time(380)),
            rideTiming = EffectiveTransitRideTiming(originalTrain, time(380), time(540), EffectiveTransitRideTimingBasis.TIMETABLE))
        val original = route.copy(segments = route.segments.map { if (it.sequence == 3) railRide else it })
        val binding = journey.copy(rides = listOf(journey.rides.first()))
        val rail = mock<TransitTimetableProvider>()
        whenever(rail.findRides(any())).thenReturn(emptyList(), listOf(train("2013", 479, 640), train("2014", 490, 750)))
        val mixed = PlannedSeoulBusRouteRefresher(provider, evaluator, 60, 120, 1440,
            TransitJourneyTimetableEnricher(listOf(rail)))
        whenever(provider.getBusRouteDiagnosticStops(firstVehicle.routeId)).thenReturn(first)
        whenever(provider.getBusVehicleObservations(SeoulBusVehicleQuery(firstVehicle.routeId, first.first().sequence, first.last().sequence)))
            .thenReturn(SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.AVAILABLE, listOf(
                SeoulBusVehicleObservation("101000091", firstVehicle.routeId, "103000001", 333, 0,
                    true, first.last().stationId, time(330), time(345), time(375)),
            )))
        whenever(provider.getBusAlightingForecast(first.last(), firstVehicle))
            .thenReturn(SeoulBusAlightingForecast(time(320), time(320), time(349), reportedAtStop = true))

        val failed = requireNotNull(mixed.refresh(original, binding, time(350))).update
        assertEquals(PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE, failed.failure)
        assertEquals(time(320), failed.reportedBusAlighting?.reportedAt)
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val persisted = mapper.readValue(mapper.writeValueAsBytes(failed), PlannedTransitRouteUpdate::class.java)
        val recovery = requireNotNull(mixed.refresh(original, binding, time(480), pendingTransferFailure = true, previousUpdate = persisted))
        val updated = requireNotNull(recovery.update.route)
        assertEquals(start, updated.departureAt)
        assertEquals("2014", updated.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(time(320), updated.segments[1].busRidePrediction?.reportedAlightingAt)
        val transfer = requireNotNull(updated.segments[3].transferTiming)
        assertEquals(30L, transfer.walkingSeconds)
        assertEquals(60L, transfer.requiredMarginSeconds)
        assertEquals(80L, transfer.remainingSlackSeconds)
        assertEquals(time(810), updated.arrivalAt)
        val published = requireNotNull(EffectiveTransitRouteSnapshot(inProgressPlannedRoute = original,
            plannedRouteUpdate = recovery.update).plannedJourneyArrivalPrediction(time(485), 120))
        assertEquals(PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN, published.basis)
        assertNull(published.sourceUpdatedAt)
        assertEquals(time(320), published.effectiveTransitRoute?.segments?.get(1)?.busRidePrediction?.reportedAlightingAt)

        val couldHaveBoarded = train("2010", 440, 540)
        val priorStillFeasible = original.copy(segments = original.segments.map { segment ->
            if (segment.sequence == 3) segment.copy(rideTiming = EffectiveTransitRideTiming(couldHaveBoarded,
                time(440), time(540), EffectiveTransitRideTimingBasis.TIMETABLE)) else segment
        })
        val preserved = requireNotNull(mixed.refresh(priorStillFeasible, binding, time(480),
            pendingTransferFailure = true, previousUpdate = persisted))
        assertEquals("2010", preserved.update.route?.segments?.get(3)?.rideTiming?.timetable?.trainNumber)
        val queries = argumentCaptor<com.noLate.eta.application.port.TransitTimetableQuery>()
        verify(rail, times(2)).findRides(queries.capture())
        assertEquals(listOf(time(410), time(480)), queries.allValues.map { it.notBefore })
        verify(provider).getBusAlightingForecast(first.last(), firstVehicle)
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
        whenever(rail.refresh(eq(railPrefix), eq(time(150)), eq(railPrefix), isNull(), eq(true)))
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
        whenever(rail.refresh(prefix, evaluatedAt, prefix, previousMiss, endsAtTransfer = true)).thenReturn(
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

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `keeps a reported alighting for a later bus forecast without refreshing its timestamp`(deferred: Boolean) {
        val table = TransitLegTimetable("seoul-timetable", SeoulSubwayLine.LINE_4, LocalDate.of(2026, 11, 27),
            "4703", "DOWN", TransitServiceClass.LOCAL, "0431", "0433", "오이도", time(90), time(240), time(-5))
        val train = EffectiveTransitRouteSegment(1, SUBWAY, 4, lineName = "4호선", fromName = "동작", toName = "사당",
            rideTiming = EffectiveTransitRideTiming(table, time(90), time(240), EffectiveTransitRideTimingBasis.TIMETABLE),
            orderedStopNames = listOf("동작", "총신대입구", "사당"))
        val plan = route.copy(segments = route.segments.map { if (it.sequence == 1) train else it })
        val binding = journey.copy(rides = listOf(journey.rides.last()))
        val railPrefix = plan.copy(segments = plan.segments.take(2), arrivalAt = time(240), totalMinutes = 4)
        val pending = DeferredSeoulBusTransfer(railPrefix, listOf(plan.segments[2]), plan.segments[3].copy(
            busRidePrediction = null, transferTiming = null), listOf(plan.segments[4]),
            SeoulBusConnectionStops(oldSecondVehicle.routeId, second.first().stationId, second.last().stationId,
                second.first().arsId, second.last().arsId), time(1000))
        val arrivals = mock<TransitArrivalService>()
        val rail = PlannedTransitRouteRefresher(TransitTransferRealtimeGuard(arrivals),
            TransitJourneyTimetableEnricher(emptyList()), evaluator)
        val eta = mock<SeoulBusEta>()
        val mixed = PlannedSeoulBusRouteRefresher(provider, evaluator, 60, 120, 1440,
            railRouteRefresher = rail, busEta = eta)
        val reported = TransitArrivalDto("seoul-openapi", "SUBWAY", stationName = "사당", direction = "하행",
            destinationName = "오이도", express = false, subwayLineId = "1004", trainNumber = "4703", waitSeconds = 0,
            expectedAt = time(320).toString(), sourceUpdatedAt = time(320).toString(), observedAt = time(350).toString(),
            freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP, arrivalStatus = TransitArrivalStatus.ARRIVED)
        whenever(arrivals.getSubwayArrivalsForEta("사당", "4호선", null, "DOWN", 10)).thenReturn(listOf(reported), emptyList())
        onwardBuses(emptyList())
        whenever(eta.readPairedArrivals(pending.stops)).thenReturn(emptyList())

        val privateUpdate = if (deferred) {
            val result = requireNotNull(mixed.refreshDeferred(pending, time(350)))
            assertNull(result.connected)
            val waiting = EffectiveTransitRouteSnapshot(deferredSeoulBusTransfer = result.pending)
            assertNull(waiting.plannedJourneyArrivalPrediction(time(350), 120))
            val publicPrefix = requireNotNull(waiting.pendingTransferRailPlan(time(350)))
            assertEquals(time(350), publicPrefix.arrivalAt)
            assertEquals(time(320), publicPrefix.segments[1].rideTiming?.reportedAlightingAt)
            assertEquals(WALK, publicPrefix.segments.last().kind)
            requireNotNull(result.pending.railUpdate)
        } else {
            val result = requireNotNull(mixed.refresh(plan, binding, time(350)))
            assertEquals(PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE, result.update.failure)
            assertNull(result.update.route)
            result.update
        }
        assertEquals(time(320), privateUpdate.reportedAlightings.single().sourceUpdatedAt)

        val evaluatedAt = time(480)
        val nextBuses = listOf(
            pair(time(475), time(691), oldSecondVehicle).copy(sourceUpdatedAt = time(476), receivedAt = time(480)),
            pair(time(489), time(710), oldSecondVehicle).copy(sourceUpdatedAt = time(421), receivedAt = time(480)),
            pair(time(490), time(750), newSecondVehicle).copy(sourceUpdatedAt = time(477), receivedAt = time(481)),
        )
        onwardBuses(nextBuses)
        whenever(eta.readPairedArrivals(pending.stops)).thenReturn(nextBuses)
        val connected = requireNotNull(if (deferred) {
            mixed.refreshDeferred(pending.copy(railUpdate = privateUpdate), evaluatedAt)?.connected
        } else {
            mixed.refresh(plan, binding, evaluatedAt, pendingTransferFailure = true, previousUpdate = privateUpdate)
        })
        val updated = requireNotNull(connected.update.route)
        assertEquals(start, updated.departureAt)
        assertEquals(time(320), updated.segments[1].rideTiming?.reportedAlightingAt)
        assertNull(updated.segments[1].rideTiming?.observationSourceUpdatedAt)
        assertEquals(time(490), updated.segments[3].busRidePrediction?.boardingAt)
        assertEquals(30L, updated.segments[3].transferTiming?.walkingSeconds)
        assertEquals(60L, updated.segments[3].transferTiming?.requiredMarginSeconds)
        assertEquals(time(810), updated.arrivalAt)
        assertEquals(time(477), connected.update.sourceUpdatedAt)
        assertEquals(newSecondVehicle, connected.journey.rides.single().service)
        val snapshot = EffectiveTransitRouteSnapshot(inProgressPlannedRoute = plan, plannedSeoulBusJourney = connected.journey,
            plannedRouteUpdate = connected.update)
        assertEquals(PlannedJourneyArrivalBasis.MATCHED_TRAIN_AND_BUS_REALTIME,
            snapshot.plannedJourneyArrivalPrediction(time(500), 120)?.basis)
        assertEquals(PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN,
            snapshot.plannedJourneyArrivalPrediction(time(523), 120)?.basis)
        verify(arrivals, times(2)).getSubwayArrivalsForEta("사당", "4호선", null, "DOWN", 10)
        verify(provider, never()).getBusVehicleObservations(any())
    }

    private fun currentBus(vehicleId: String = "101000091", positionOrder: Int = first[1].sequence,
                           lastStationId: String = first[1].stationId, catalog: List<SeoulBusRouteStop> = first) {
        whenever(provider.getBusRouteDiagnosticStops(firstVehicle.routeId)).thenReturn(catalog)
        whenever(provider.getBusVehicleObservations(SeoulBusVehicleQuery(
            firstVehicle.routeId, first.first().sequence, first.last().sequence,
        ))).thenReturn(SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.AVAILABLE, listOf(
            SeoulBusVehicleObservation(vehicleId, firstVehicle.routeId, "103000001", positionOrder, 300,
                false, lastStationId, time(145), time(149), time(190)),
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
