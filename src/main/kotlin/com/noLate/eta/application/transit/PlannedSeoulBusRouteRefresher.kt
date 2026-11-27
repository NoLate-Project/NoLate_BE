package com.noLate.eta.application.transit

import com.noLate.schedule.application.DeferredSeoulBusTransfer
import com.noLate.schedule.application.EffectiveBusRidePrediction
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.EffectiveTransitRouteSegment
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind
import com.noLate.schedule.application.EffectiveTransitRideTiming
import com.noLate.schedule.application.EffectiveTransitRideTimingBasis
import com.noLate.schedule.application.PlannedSeoulBusJourney
import com.noLate.schedule.application.PlannedSeoulBusRide
import com.noLate.schedule.application.PlannedTransitRouteUpdate
import com.noLate.schedule.application.PlannedTransitTimingFailure
import com.noLate.schedule.application.ReportedBusAlightingEvidence
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLegTimingBasis
import com.noLate.eta.domain.TransitLine
import com.noLate.eta.domain.TransitStop
import com.noLate.transit.domain.SeoulBusRideService
import com.noLate.transit.domain.SeoulBusRouteStop
import com.noLate.transit.domain.SeoulBusVehicleQuery
import com.noLate.transit.domain.SeoulBusVehicleFreshness
import com.noLate.transit.infrastructure.SeoulBusRouteCatalogParser
import com.noLate.transit.infrastructure.SeoulBusPairedArrival
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlin.math.ceil

/** Refresh the planned vehicle, without inferring that the passenger boarded it. */
@Component
class PlannedSeoulBusRouteRefresher(
    private val client: SeoulTransitArrivalClient,
    private val transferEvaluator: TransitTransferFeasibilityEvaluator,
    @Value("\${eta.transit.boarding-buffer-seconds:60}") private val boardingBufferSeconds: Long,
    @Value("\${eta.transit.arrival-source-max-age-seconds:120}") private val sourceMaxAgeSeconds: Long,
    @Value("\${schedule.traffic.max-travel-minutes:1440}") private val maxTravelMinutes: Int,
    private val timetableEnricher: TransitJourneyTimetableEnricher? = null,
    private val railRealtime: TransitTransferRealtimeGuard? = null,
    private val railRouteRefresher: PlannedTransitRouteRefresher? = null,
    private val busEta: SeoulBusEta? = null,
) {
    internal data class Refresh(val update: PlannedTransitRouteUpdate, val journey: PlannedSeoulBusJourney)
    internal data class DeferredRefresh(val pending: DeferredSeoulBusTransfer, val connected: Refresh? = null)

    internal fun refreshDeferred(pending: DeferredSeoulBusTransfer, at: Instant): DeferredRefresh? {
        val eta = busEta ?: return null
        val original = pending.railPlan
        if (at < original.departureAt || at >= pending.trackingUntil) return null
        val rail = pending.railUpdate?.route ?: original
        val walk = walkingSeconds(pending.transferWalk)
        val margin = timetableEnricher?.busTransferMarginSeconds(transferEvaluator.requiredTransferMarginSeconds)
            ?: maxOf(boardingBufferSeconds, transferEvaluator.requiredTransferMarginSeconds)
        if (eta.isOutsideConnectionHorizon(rail.arrivalAt.plusSeconds(walk + margin), at)) return null
        var latest = pending
        return try {
            val read = railRouteRefresher?.refresh(rail, at, original, pending.railUpdate, endsAtTransfer = true)
                ?: pending.railUpdate ?: return null
            latest = pending.copy(railUpdate = read)
            val inbound = railTransferArrival(rail, read, at) ?: return DeferredRefresh(latest)
            val updatedRail = inbound.route
            // The pending result exposes only the confirmed rail prefix until a bus can be linked.
            if (inbound.forecastSourceAt == null) latest = pending.copy(railUpdate = read.copy(route = updatedRail))
            val railArrival = updatedRail.arrivalAt
            val ready = railArrival.plusSeconds(walk)
            if (eta.isOutsideConnectionHorizon(ready.plusSeconds(margin), at)) return DeferredRefresh(latest)

            val arrivals = eta.readPairedArrivals(pending.stops).filter { !eta.isOutsideConnectionHorizon(it.boardingAt, at) }
            val pair = chooseConnection(arrivals, ready.plusSeconds(margin), railArrival,
                inbound.forecastSourceAt, at) ?: return DeferredRefresh(latest)
            val bus = pending.bus.copy(busRidePrediction = pair.prediction,
                waitingMinutes = minutes(ready, pair.prediction.boardingAt), durationMinutes = minutes(ready, pair.prediction.alightingAt),
                transferTiming = transferEvaluator.describeTransfer(railArrival, walk, pair.prediction.boardingAt)
                    .copy(confidenceMarginSeconds = margin))
            val arrival = pair.prediction.alightingAt.plusSeconds(walkingSeconds(pending.egressWalk))
            val totalMinutes = minutes(original.departureAt, arrival).takeIf { it in 1..maxTravelMinutes }
                ?: return DeferredRefresh(latest)
            val whole = updatedRail.copy(segments = updatedRail.segments + pending.transferWalk + bus + pending.egressWalk,
                arrivalAt = arrival, totalMinutes = totalMinutes)
            val binding = PlannedSeoulBusJourney(whole.identity, whole.departureAt, listOf(PlannedSeoulBusRide(bus.sequence, pair.service)))
            DeferredRefresh(latest, Refresh(PlannedTransitRouteUpdate(whole.identity, inbound.combinedSourceAt(pair.sourceAt),
                route = whole, reportedBoardings = read.reportedBoardings, railToBusConnectionObserved = true,
                reportedAlightings = read.reportedAlightings), binding))
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is InterruptedException || Thread.currentThread().isInterrupted) throw failure
            logger.debug("Seoul deferred bus transfer unavailable ({})", failure.javaClass.simpleName)
            DeferredRefresh(latest)
        }
    }

    internal fun refresh(route: EffectiveTransitRoute, journey: PlannedSeoulBusJourney, at: Instant,
                         pendingTransferFailure: Boolean = false, departurePlan: EffectiveTransitRoute = route,
                         previousUpdate: PlannedTransitRouteUpdate? = null): Refresh? {
        if (!journey.matches(route) || at < route.departureAt) return null
        return try {
            val last = route.segments.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }.maxBy { it.sequence }
            if (last.busRidePrediction?.reportedAlightingAt != null || last.rideTiming?.reportedAlightingAt != null) return null
            if (at >= route.arrivalAt) {
                val deadline = journey.finalArrivalObservationDeadline(route) ?: return null
                if (pendingTransferFailure || at >= deadline) return null
                // After a previous arrival estimate expires, only direct evidence for the final vehicle can recover it.
                val segments = route.segments.sortedBy { it.sequence }
                return if (last.kind == EffectiveTransitRouteSegmentKind.BUS) refreshFinalBus(route, journey, segments, at)
                    else refreshLastSubway(route, journey, segments, at)
            }
            val firstRide = route.segments.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }.minBy { it.sequence }
            if (firstRide.kind == EffectiveTransitRouteSegmentKind.SUBWAY) {
                refreshBusAfterRail(route, journey, at, departurePlan, previousUpdate, pendingTransferFailure)
            } else refreshAcceptedPlan(route, journey, at, pendingTransferFailure, previousUpdate)
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is InterruptedException ||
                Thread.currentThread().isInterrupted) throw failure
            logger.debug("Seoul planned bus refresh unavailable ({})", failure.javaClass.simpleName)
            null
        }
    }

    private fun refreshAcceptedPlan(route: EffectiveTransitRoute, journey: PlannedSeoulBusJourney, at: Instant,
                                    pendingTransferFailure: Boolean, previousUpdate: PlannedTransitRouteUpdate?): Refresh? {
        val segments = route.segments.sortedBy { it.sequence }
        val rides = journey.rides.map { binding -> segments.single { it.sequence == binding.sequence } }
        val trainFollows = segments.any { it.kind == EffectiveTransitRouteSegmentKind.SUBWAY }
        val firstBus = requireNotNull(rides.first().busRidePrediction)
        // An accepted connection continues to follow the selected train after the bus report.
        if (trainFollows && firstBus.reportedAlightingAt != null && !pendingTransferFailure) {
            return refreshLastSubway(route, journey, segments, at)
        }
        val storedReport = previousUpdate?.reportedBusAlighting?.takeIf {
            trainFollows && it.matches(route, journey.rides.first(), firstBus.boardingAt, at)
        }
        val latest = rides.indices.firstNotNullOfOrNull { index ->
            val observed = if (index == 0 && storedReport != null) {
                MatchedAlighting(firstBus.copy(alightingAt = storedReport.reportedAt,
                    alightingObservationSourceUpdatedAt = null, reportedAlightingAt = storedReport.reportedAt),
                    storedReport.reportedAt, at, storedReport.validUntil())
            } else observeAlighting(requireNotNull(rides[index].busRidePrediction), journey.rides[index].service, at,
                allowArrivalReport = trainFollows || (index == rides.lastIndex && !pendingTransferFailure))
            observed?.let {
                index to it
            }
        } ?: return if (pendingTransferFailure) null else refreshLastSubway(route, journey, segments, at)
        val (index, current) = latest
        val active = rides[index]
        val activeIndex = segments.indexOf(active)
        val observed = active.copy(busRidePrediction = current.prediction,
            durationMinutes = minutes(accessEndsAt(route, segments, rides, index), current.prediction.alightingAt))
        val suffix = segments.drop(activeIndex + 1)
        val reported = storedReport ?: current.reportFor(route, journey.rides[index])
        if (suffix.any { it.kind == EffectiveTransitRouteSegmentKind.SUBWAY }) {
            return reconnectSubway(route, journey, segments.take(activeIndex) + observed, suffix, current, at, reported)
        }
        val next = rides.getOrNull(index + 1)
        if (next == null) {
            return updated(route, journey, segments.map {
                if (it === active) observed else it.copy(
                    busRidePrediction = it.busRidePrediction?.copy(alightingObservationSourceUpdatedAt = null))
            },
                current.prediction.alightingAt.plusSeconds(walkingSeconds(suffix)), current.sourceAt,
                busReport = reported)
        }

        val walkingSeconds = walkingSeconds(segments.subList(activeIndex + 1, segments.indexOf(next)))
        val readyAt = current.prediction.alightingAt.plusSeconds(walkingSeconds)
        val margin = maxOf(boardingBufferSeconds, transferEvaluator.requiredTransferMarginSeconds,
            next.transferTiming?.requiredMarginSeconds ?: 0L)
        val nextService = journey.rides[index + 1].service
        val nextPair = findConnection(nextService, readyAt.plusSeconds(margin), current.prediction.alightingAt,
            current.sourceAt, maxOf(at, current.receivedAt))
        if (nextPair == null) {
            return if (requireNotNull(next.busRidePrediction).boardingAt < readyAt.plusSeconds(margin)) {
                Refresh(PlannedTransitRouteUpdate(route.identity, current.sourceAt,
                    failure = PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE), journey)
            } else null
        }
        val (connectedRide, connectedService, connectedSourceAt) = nextPair
        val reconnected = next.copy(
            durationMinutes = minutes(readyAt, connectedRide.alightingAt),
            waitingMinutes = minutes(readyAt, connectedRide.boardingAt),
            busRidePrediction = connectedRide,
            transferTiming = transferEvaluator.describeTransfer(current.prediction.alightingAt, walkingSeconds,
                connectedRide.boardingAt).copy(confidenceMarginSeconds = margin),
        )
        val arrivalAt = connectedRide.alightingAt.plusSeconds(walkingSeconds(segments.drop(segments.indexOf(next) + 1)))
        return updated(route, journey.copy(rides = journey.rides.map {
            if (it.sequence == next.sequence) PlannedSeoulBusRide(it.sequence, connectedService) else it
        }), segments.map {
            when (it.sequence) {
                active.sequence -> observed
                next.sequence -> reconnected
                else -> it
            }
        }, arrivalAt, minOf(current.sourceAt, connectedSourceAt))
    }

    private fun refreshBusAfterRail(route: EffectiveTransitRoute, journey: PlannedSeoulBusJourney, at: Instant,
                                    departurePlan: EffectiveTransitRoute, previousUpdate: PlannedTransitRouteUpdate?,
                                    pendingTransferFailure: Boolean): Refresh? {
        val segments = route.segments.sortedBy { it.sequence }
        val binding = journey.rides.single()
        val busIndex = segments.indexOfFirst { it.sequence == binding.sequence }
        val bus = segments[busIndex]
        val busTiming = requireNotNull(bus.busRidePrediction)
        val egress = segments.drop(busIndex + 1)
        val retryMissedBus = pendingTransferFailure && previousUpdate?.routeIdentity == route.identity &&
            previousUpdate.failure == PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE
        // 기존 버스를 놓쳤다면 그 승차시각 이후에도 열차 하차와 다음 버스의 예보를 대조한다.
        if (at >= busTiming.boardingAt && !retryMissedBus) {
            if (pendingTransferFailure) return null
            return refreshFinalBus(route, journey, segments, at)
        }

        fun railPrefix(plan: EffectiveTransitRoute): EffectiveTransitRoute {
            val prefix = plan.segments.sortedBy { it.sequence }.takeWhile { it.sequence < bus.sequence }
                .dropLastWhile { it.kind == EffectiveTransitRouteSegmentKind.WALK }
            val arrival = requireNotNull(prefix.last().rideTiming).predictedArrivalAt
            return plan.copy(segments = prefix, arrivalAt = arrival, totalMinutes = minutes(plan.departureAt, arrival))
        }
        if (!journey.matches(departurePlan)) return null
        val rail = railPrefix(route)
        val refreshed = railRouteRefresher?.refresh(rail, at, railPrefix(departurePlan), previousUpdate,
            endsAtTransfer = true) ?: return null
        if (refreshed.failure != null) return Refresh(refreshed, journey)
        fun preserveRailRecords(): Refresh = Refresh(previousUpdate?.copy(reportedBoardings = refreshed.reportedBoardings,
            reportedAlightings = refreshed.reportedAlightings) ?: refreshed, journey)
        val inbound = railTransferArrival(rail, refreshed, at)
            ?: return if (refreshed.route == null) preserveRailRecords() else null
        val updatedRail = inbound.route
        val railArrival = updatedRail.arrivalAt
        val transfer = segments.subList(rail.segments.size, busIndex)
        val walk = walkingSeconds(transfer).takeIf { it > 0 } ?: return null
        val ready = railArrival.plusSeconds(walk)
        val margin = maxOf(boardingBufferSeconds, transferEvaluator.requiredTransferMarginSeconds,
            bus.transferTiming?.requiredMarginSeconds ?: 0L)
        val connection = findConnection(binding.service, ready.plusSeconds(margin), railArrival, inbound.forecastSourceAt, at)
        if (connection == null) {
            return when {
                busTiming.boardingAt < ready.plusSeconds(margin) -> Refresh(PlannedTransitRouteUpdate(route.identity,
                    refreshed.sourceUpdatedAt, failure = PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE,
                    reportedBoardings = refreshed.reportedBoardings, reportedAlightings = refreshed.reportedAlightings), journey)
                inbound.forecastSourceAt == null -> preserveRailRecords()
                else -> null
            }
        }

        val revisedBus = bus.copy(busRidePrediction = connection.prediction,
            waitingMinutes = minutes(ready, connection.prediction.boardingAt),
            durationMinutes = minutes(ready, connection.prediction.alightingAt),
            transferTiming = transferEvaluator.describeTransfer(railArrival, walk, connection.prediction.boardingAt)
                .copy(confidenceMarginSeconds = margin))
        val nextJourney = journey.copy(rides = listOf(binding.copy(service = connection.service)))
        val next = updated(route, nextJourney, updatedRail.segments + transfer + revisedBus + egress,
            connection.prediction.alightingAt.plusSeconds(walkingSeconds(egress)), inbound.combinedSourceAt(connection.sourceAt))
            ?: return null
        return next.copy(update = next.update.copy(railToBusConnectionObserved = true,
            reportedBoardings = refreshed.reportedBoardings, reportedAlightings = refreshed.reportedAlightings))
    }

    private fun refreshFinalBus(route: EffectiveTransitRoute, journey: PlannedSeoulBusJourney,
                                segments: List<EffectiveTransitRouteSegment>, at: Instant): Refresh? {
        val index = segments.indexOfLast { it.kind != EffectiveTransitRouteSegmentKind.WALK }
        val last = segments[index]
        val timing = last.busRidePrediction ?: return null
        val prior = segments.take(index)
        val ready = last.transferTiming?.physicalReadyAt ?: run {
            if (prior.any { it.kind == EffectiveTransitRouteSegmentKind.SUBWAY }) return null
            val buses = segments.filter { it.kind == EffectiveTransitRouteSegmentKind.BUS }
            accessEndsAt(route, segments, buses, buses.lastIndex)
        }
        val binding = journey.rides.last()
        val observed = observeAlighting(timing, binding.service, at, allowArrivalReport = true) ?: return null
        val alightingAt = observed.prediction.alightingAt
        val refreshed = segments.mapIndexed { i, segment ->
            if (index == i) segment.copy(busRidePrediction = observed.prediction, durationMinutes = minutes(ready, alightingAt))
            else segment.copy(busRidePrediction = segment.busRidePrediction?.copy(alightingObservationSourceUpdatedAt = null))
        }
        return updated(route, journey, refreshed, alightingAt.plusSeconds(walkingSeconds(segments.drop(index + 1))),
            observed.sourceAt, busReport = observed.reportFor(route, binding))
    }

    private data class RailTransferArrival(val route: EffectiveTransitRoute, val forecastSourceAt: Instant?) {
        fun combinedSourceAt(busSourceAt: Instant): Instant = forecastSourceAt?.let { minOf(it, busSourceAt) } ?: busSourceAt
    }

    private fun railTransferArrival(plan: EffectiveTransitRoute, update: PlannedTransitRouteUpdate,
                                    at: Instant): RailTransferArrival? {
        if (update.failure != null) return null
        val refreshed = update.route?.takeIf { it.segments.map(EffectiveTransitRouteSegment::sequence) ==
            plan.segments.map(EffectiveTransitRouteSegment::sequence) }
        val rail = refreshed ?: plan
        val last = rail.segments.last()
        val report = update.reportedAlightings.singleOrNull { it.matches(rail, last, at) }
            ?: return refreshed?.takeIf { it.arrivalAt > at &&
                update.sourceUpdatedAt in at.minusSeconds(sourceMaxAgeSeconds)..at.plusSeconds(5) }
                ?.let { RailTransferArrival(it, update.sourceUpdatedAt) }

        // The provider's past report establishes transfer readiness only. A fresh bus forecast must still follow.
        val prior = rail.segments.dropLast(1)
        val accessWalk = prior.takeLastWhile { it.kind == EffectiveTransitRouteSegmentKind.WALK }
        val priorArrival = prior.lastOrNull { it.rideTiming != null }?.rideTiming?.predictedArrivalAt ?: rail.departureAt
        val readyAt = priorArrival.plusSeconds(walkingSeconds(accessWalk))
        val timing = requireNotNull(last.rideTiming).asPlannedBaseline().copy(predictedArrivalAt = report.sourceUpdatedAt,
            reportedAlightingAt = report.sourceUpdatedAt)
        if (timing.predictedBoardingAt < readyAt) return null
        val projected = prior.map { segment -> segment.copy(rideTiming = segment.rideTiming?.asPlannedBaseline()) } +
            last.copy(rideTiming = timing, durationMinutes = minutes(readyAt, report.sourceUpdatedAt))
        return RailTransferArrival(rail.copy(segments = projected, arrivalAt = report.sourceUpdatedAt,
            totalMinutes = minutes(rail.departureAt, report.sourceUpdatedAt)), null)
    }

    private fun reconnectSubway(
        route: EffectiveTransitRoute, journey: PlannedSeoulBusJourney, prefix: List<EffectiveTransitRouteSegment>,
        suffix: List<EffectiveTransitRouteSegment>, bus: MatchedAlighting, at: Instant, report: ReportedBusAlightingEvidence?,
    ): Refresh? {
        val enricher = timetableEnricher ?: return null
        val firstIndex = suffix.indexOfFirst { it.kind == EffectiveTransitRouteSegmentKind.SUBWAY }
        val first = suffix[firstIndex]
        val transferWalk = walkingSeconds(suffix.take(firstIndex))
        if (transferWalk <= 0) return null
        val alightingAt = bus.prediction.alightingAt
        val ready = alightingAt.plusSeconds(transferWalk)
        val requiredMargin = enricher.busTransferMarginSeconds(maxOf(boardingBufferSeconds,
            transferEvaluator.requiredTransferMarginSeconds, first.transferTiming?.requiredMarginSeconds ?: 0))
        val firstTiming = requireNotNull(first.rideTiming)
        val checkedAt = if (report != null) maxOf(at, bus.receivedAt) else at
        fun preserveReport(result: Refresh?): Refresh? =
            if (report == null) result else result?.copy(update = result.update.copy(reportedBusAlighting = report))
        fun unavailable(): Refresh = Refresh(PlannedTransitRouteUpdate(route.identity, bus.sourceAt,
            failure = PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE, reportedBusAlighting = report), journey)
        // A physically feasible planned train may already have boarded. Only search for a new train after a confirmed miss.
        if (firstTiming.predictedBoardingAt >= ready.plusSeconds(requiredMargin)) {
            val transfer = transferEvaluator.describeTransfer(alightingAt, transferWalk, firstTiming.predictedBoardingAt)
                .copy(confidenceMarginSeconds = requiredMargin)
            val refreshed = suffix.mapIndexed { index, segment -> if (index != firstIndex) segment else segment.copy(
                transferTiming = transfer, waitingMinutes = minutes(ready, firstTiming.predictedBoardingAt),
                durationMinutes = minutes(ready, firstTiming.predictedArrivalAt)) }
            return preserveReport(updated(route, journey, prefix + refreshed, route.arrivalAt, bus.sourceAt))
        }

        val legs = suffix.map { segment ->
            val table = segment.rideTiming?.timetable
            TransitJourneyLeg(segment.sequence, if (table == null) TransitLegMode.WALK else TransitLegMode.SUBWAY,
                segment.durationMinutes, durationSeconds = segment.durationSeconds, walkingTiming = segment.walkingTiming,
                walkingGeometry = segment.walkingGeometry,
                from = table?.let { TransitStop(name = segment.fromName, providerStopId = it.fromStationCode) },
                to = table?.let { TransitStop(name = segment.toName, providerStopId = it.toStationCode) },
                line = table?.let { TransitLine(name = segment.lineName, serviceClass = it.serviceClass, seoulSubwayLine = it.line) },
                directionCode = table?.directionCode, timingBasis = if (table == null) TransitLegTimingBasis.UNKNOWN else TransitLegTimingBasis.TIMETABLE,
                scheduledDepartureAt = table?.boardingAt, scheduledArrivalAt = table?.arrivalAt, timetable = table,
                orderedStopNames = segment.orderedStopNames, nextStopName = segment.orderedStopNames.getOrNull(1))
        }
        val continuation = TransitJourney(route.provider, route.departureAt, alightingAt, route.arrivalAt, route.totalMinutes,
            legs, bus.sourceAt)
        val connected = enricher.connectAfterBus(continuation, checkedAt, maxTravelMinutes, requiredMargin, preserveRailScope = true,
            reportedBusAlighting = report != null) ?: return unavailable()
        val validUntil = report?.validUntil() ?: bus.validUntil
        if (connected.rideLegs.any { requireNotNull(it.timetable).fetchedAt >= validUntil })
            return if (report == null) null else unavailable()
        var priorArrival = alightingAt
        val revised = suffix.zip(connected.legs).map { (segment, leg) ->
            val table = leg.timetable ?: return@map segment
            val walk = Duration.between(priorArrival, requireNotNull(leg.scheduledDepartureAt)).seconds
            val timing = transferEvaluator.describeTransfer(priorArrival, walk, table.boardingAt)
            priorArrival = table.arrivalAt
            segment.copy(durationMinutes = leg.durationMinutes, waitingMinutes = leg.waitingMinutes,
                directionName = null, rideTiming = EffectiveTransitRideTiming(table, table.boardingAt, table.arrivalAt,
                    EffectiveTransitRideTimingBasis.TIMETABLE),
                transferTiming = if (segment.sequence == first.sequence) timing.copy(confidenceMarginSeconds = requiredMargin) else timing)
        }
        return preserveReport(updated(route, journey, prefix + revised, connected.arrivalAt, bus.sourceAt))
    }

    private fun refreshLastSubway(route: EffectiveTransitRoute, journey: PlannedSeoulBusJourney,
                                  segments: List<EffectiveTransitRouteSegment>, at: Instant): Refresh? {
        val index = segments.indexOfLast { it.kind != EffectiveTransitRouteSegmentKind.WALK }
        val last = segments.getOrNull(index)?.takeIf { it.kind == EffectiveTransitRouteSegmentKind.SUBWAY } ?: return null
        val timing = requireNotNull(last.rideTiming)
        // Reuse the direct-ride check to take only this train's alighting forecast after its planned boarding.
        val evidence = railRealtime?.readLastPlannedRide(route.copy(segments = listOf(last)), at,
            allowAlightingRecovery = true) ?: return null
        evidence.report?.let { report ->
            val revised = report.applyToFinalRide(route, at, maxTravelMinutes) ?: return null
            return Refresh(PlannedTransitRouteUpdate(route.identity, report.sourceUpdatedAt, route = revised,
                reportedAlightings = listOf(report)), journey)
        }
        val observation = evidence.forecast ?: return null
        val previousArrival = last.transferTiming?.physicalReadyAt ?: return null
        val latestAlighting = observation.predictedArrivalAt.minusSeconds(walkingSeconds(segments.drop(index + 1)))
        if (observation.routeIdentity != route.identity || latestAlighting <= maxOf(at, timing.predictedBoardingAt) ||
            timing.observationSourceUpdatedAt?.let { observation.sourceUpdatedAt <= it } == true ||
            latestAlighting < timing.timetable.arrivalAt) return null
        val refreshed = segments.mapIndexed { segmentIndex, segment ->
            if (segmentIndex == index) segment.copy(durationMinutes = minutes(previousArrival, latestAlighting),
                rideTiming = timing.copy(predictedArrivalAt = latestAlighting,
                    basis = EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME,
                    observationSourceUpdatedAt = observation.sourceUpdatedAt))
            else segment
        }
        return updated(route, journey, refreshed, observation.predictedArrivalAt, observation.sourceUpdatedAt,
            onlyLastRideObserved = true)
    }

    private data class MatchedAlighting(val prediction: EffectiveBusRidePrediction, val sourceAt: Instant,
                                       val receivedAt: Instant, val validUntil: Instant) {
        fun reportFor(route: EffectiveTransitRoute, ride: PlannedSeoulBusRide): ReportedBusAlightingEvidence? =
            prediction.reportedAlightingAt?.let { reportedAt ->
                ReportedBusAlightingEvidence(route.identity, route.departureAt, ride.sequence, ride.service, reportedAt, receivedAt)
            }
    }

    private fun observeAlighting(
        plan: EffectiveBusRidePrediction, service: SeoulBusRideService, at: Instant, allowArrivalReport: Boolean = false,
    ): MatchedAlighting? {
        if (at < plan.boardingAt || service.alighting.order - service.boarding.order >= 100) return null
        val stops = client.getBusRouteDiagnosticStops(service.routeId)
        val path = path(service, stops) ?: return null
        val position = client.getBusVehicleObservations(
            SeoulBusVehicleQuery(service.routeId, service.boarding.order, service.alighting.order),
        ).observations.singleOrNull { service.matchesVehicle(it.vehicleId) } ?: return null
        if (position.routeId != service.routeId || position.sourceUpdatedAt < plan.boardingAt ||
            position.sourceUpdatedAt > position.receivedAt ||
            position.validUntil <= maxOf(at, position.receivedAt)) return null
        // The position feed identifies the last stop but numbers road sections separately.
        // Check the full catalog because the same stop can appear again outside this ride.
        val lastStop = stops.singleOrNull { it.stationId == position.lastStationId }
        val approaching = lastStop != null && lastStop in path && lastStop.sequence < service.alighting.order
        val atAlightingStop = allowArrivalReport && position.atStop && lastStop == path.last()
        if (!approaching && !atAlightingStop) return null
        val forecast = client.getBusAlightingForecast(path.last(), service) ?: return null
        val checkedAt = maxOf(at, position.receivedAt, forecast.receivedAt)
        if (position.validUntil <= checkedAt || forecast.sourceUpdatedAt < checkedAt.minusSeconds(sourceMaxAgeSeconds) ||
            forecast.sourceUpdatedAt < plan.boardingAt ||
            forecast.sourceUpdatedAt <= (plan.alightingObservationSourceUpdatedAt ?: plan.sourceUpdatedAt) ||
            forecast.sourceUpdatedAt > forecast.receivedAt) return null
        if (forecast.reportedAtStop) {
            // The stop-scoped row supplies its occurrence; the location feed's road-section ordinal is independent.
            if (!atAlightingStop || forecast.alightingAt != forecast.sourceUpdatedAt || forecast.sourceUpdatedAt <= plan.boardingAt ||
                forecast.sourceUpdatedAt < checkedAt.minusSeconds(SeoulBusVehicleFreshness.MAX_SOURCE_AGE_SECONDS)) return null
            return MatchedAlighting(plan.copy(alightingAt = forecast.alightingAt,
                alightingObservationSourceUpdatedAt = null, reportedAlightingAt = forecast.sourceUpdatedAt),
                forecast.sourceUpdatedAt, checkedAt, position.validUntil)
        }
        if (!approaching || forecast.alightingAt <= checkedAt) return null
        return MatchedAlighting(plan.copy(alightingAt = forecast.alightingAt,
            alightingObservationSourceUpdatedAt = forecast.sourceUpdatedAt),
            minOf(position.sourceUpdatedAt, forecast.sourceUpdatedAt), checkedAt, position.validUntil)
    }

    private data class Connection(
        val prediction: EffectiveBusRidePrediction, val service: SeoulBusRideService, val sourceAt: Instant,
    )

    private fun findConnection(
        service: SeoulBusRideService, readyAt: Instant, precedingAlightingAt: Instant,
        precedingSourceAt: Instant?, checkedAfter: Instant,
    ): Connection? {
        val path = path(service) ?: return null
        return chooseConnection(client.getPairedBusArrivals(path), readyAt, precedingAlightingAt, precedingSourceAt, checkedAfter)
    }

    private fun chooseConnection(
        arrivals: List<SeoulBusPairedArrival>, readyAt: Instant, precedingAlightingAt: Instant,
        precedingSourceAt: Instant?, checkedAfter: Instant,
    ): Connection? {
        val paired = arrivals.filter { candidate ->
            val checkedAt = maxOf(checkedAfter, candidate.receivedAt)
            val oldest = checkedAt.minusSeconds(sourceMaxAgeSeconds)
            val precedingTimingSupported = if (precedingSourceAt != null) {
                precedingSourceAt in oldest..checkedAt && precedingAlightingAt > checkedAt
            } else {
                precedingAlightingAt <= checkedAfter &&
                    candidate.sourceUpdatedAt >= checkedAt.minusSeconds(SeoulBusVehicleFreshness.MAX_SOURCE_AGE_SECONDS)
            }
            candidate.service != null && candidate.boardingAt >= maxOf(readyAt, checkedAt) &&
                candidate.sourceUpdatedAt in oldest..candidate.receivedAt && precedingTimingSupported
        }.minByOrNull { it.alightingAt } ?: return null
        return Connection(EffectiveBusRidePrediction(paired.boardingAt, paired.alightingAt, paired.sourceUpdatedAt),
            requireNotNull(paired.service), paired.sourceUpdatedAt)
    }

    private fun path(service: SeoulBusRideService,
                     stops: List<SeoulBusRouteStop> = client.getBusRouteDiagnosticStops(service.routeId)): List<SeoulBusRouteStop>? {
        // A duplicated stop elsewhere in the same loop does not identify the selected occurrence.
        val path = stops.filter { it.sequence in service.boarding.order..service.alighting.order }
        return path.takeIf {
            SeoulBusRouteCatalogParser.isForwardBusPath(it) &&
                it.first().direction == service.direction && it.first().routeId == service.routeId &&
                service.boarding.matches(it.first()) && service.alighting.matches(it.last())
        }
    }

    private fun updated(
        route: EffectiveTransitRoute, journey: PlannedSeoulBusJourney, segments: List<EffectiveTransitRouteSegment>,
        arrivalAt: Instant, sourceAt: Instant, onlyLastRideObserved: Boolean = false,
        busReport: ReportedBusAlightingEvidence? = null,
    ): Refresh? {
        val remaining = minutes(route.departureAt, arrivalAt).takeIf { it in 1..maxTravelMinutes } ?: return null
        return Refresh(PlannedTransitRouteUpdate(route.identity, sourceAt,
            route = route.copy(segments = segments, arrivalAt = arrivalAt, totalMinutes = remaining),
            onlyLastRideObserved = onlyLastRideObserved, reportedBusAlighting = busReport), journey)
    }

    private fun accessEndsAt(
        route: EffectiveTransitRoute, segments: List<EffectiveTransitRouteSegment>,
        rides: List<EffectiveTransitRouteSegment>, index: Int,
    ): Instant {
        val previous = rides.getOrNull(index - 1)
        val priorArrival = previous?.busRidePrediction?.alightingAt ?: route.departureAt
        val start = if (previous == null) 0 else segments.indexOf(previous) + 1
        return priorArrival.plusSeconds(walkingSeconds(segments.subList(start, segments.indexOf(rides[index]))))
    }

    private fun walkingSeconds(segments: List<EffectiveTransitRouteSegment>) =
        segments.sumOf { it.durationSeconds ?: it.durationMinutes * 60L }

    private fun minutes(start: Instant, end: Instant) =
        ceil(Duration.between(start, end).toMillis() / 60_000.0).toInt().coerceAtLeast(0)

    private companion object { val logger = LoggerFactory.getLogger(PlannedSeoulBusRouteRefresher::class.java) }
}
