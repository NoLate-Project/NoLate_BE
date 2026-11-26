package com.noLate.eta.application.transit

import com.noLate.schedule.application.EffectiveBusRidePrediction
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.EffectiveTransitRouteSegment
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind
import com.noLate.schedule.application.PlannedSeoulBusJourney
import com.noLate.schedule.application.PlannedSeoulBusRide
import com.noLate.schedule.application.PlannedTransitRouteUpdate
import com.noLate.schedule.application.PlannedTransitTimingFailure
import com.noLate.transit.domain.SeoulBusRideService
import com.noLate.transit.domain.SeoulBusRouteStop
import com.noLate.transit.domain.SeoulBusVehicleQuery
import com.noLate.transit.infrastructure.SeoulBusRouteCatalogParser
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
) {
    internal data class Refresh(val update: PlannedTransitRouteUpdate, val journey: PlannedSeoulBusJourney)

    internal fun refresh(route: EffectiveTransitRoute, journey: PlannedSeoulBusJourney, at: Instant): Refresh? {
        if (!journey.matches(route) || at < route.departureAt || at >= route.arrivalAt) return null
        return try {
            refreshAcceptedPlan(route, journey, at)
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is InterruptedException ||
                Thread.currentThread().isInterrupted) throw failure
            logger.debug("Seoul planned bus refresh unavailable ({})", failure.javaClass.simpleName)
            null
        }
    }

    private fun refreshAcceptedPlan(route: EffectiveTransitRoute, journey: PlannedSeoulBusJourney, at: Instant): Refresh? {
        val segments = route.segments.sortedBy { it.sequence }
        val rides = journey.rides.map { binding -> segments.single { it.sequence == binding.sequence } }
        val latest = rides.indices.firstNotNullOfOrNull { index ->
            observeAlighting(requireNotNull(rides[index].busRidePrediction), journey.rides[index].service, at)?.let {
                index to it
            }
        } ?: return null
        val (index, current) = latest
        val active = rides[index]
        val activeIndex = segments.indexOf(active)
        val observed = active.copy(busRidePrediction = current.prediction,
            durationMinutes = minutes(accessEndsAt(route, segments, rides, index), current.prediction.alightingAt))
        val next = rides.getOrNull(index + 1)
        if (next == null) {
            return updated(route, journey, segments.map {
                if (it === active) observed else it.copy(
                    busRidePrediction = it.busRidePrediction?.copy(alightingObservationSourceUpdatedAt = null))
            },
                current.prediction.alightingAt.plusSeconds(walkingSeconds(segments.drop(activeIndex + 1))), current.sourceAt)
        }

        val walkingSeconds = walkingSeconds(segments.subList(activeIndex + 1, segments.indexOf(next)))
        val readyAt = current.prediction.alightingAt.plusSeconds(walkingSeconds)
        val margin = maxOf(boardingBufferSeconds, transferEvaluator.requiredTransferMarginSeconds,
            next.transferTiming?.requiredMarginSeconds ?: 0L)
        val nextService = journey.rides[index + 1].service
        val nextPair = findConnection(nextService, readyAt.plusSeconds(margin), at, current)
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

    private data class MatchedAlighting(val prediction: EffectiveBusRidePrediction, val sourceAt: Instant, val receivedAt: Instant)

    private fun observeAlighting(
        plan: EffectiveBusRidePrediction, service: SeoulBusRideService, at: Instant,
    ): MatchedAlighting? {
        if (at < plan.boardingAt || service.alighting.order - service.boarding.order >= 100) return null
        val path = path(service) ?: return null
        val position = client.getBusVehicleObservations(
            SeoulBusVehicleQuery(service.routeId, service.boarding.order, service.alighting.order),
        ).observations.singleOrNull { service.matchesVehicle(it.vehicleId) } ?: return null
        if (position.routeId != service.routeId || position.sourceUpdatedAt < plan.boardingAt ||
            position.sourceUpdatedAt > position.receivedAt ||
            path.dropLast(1).none { it.stationId == position.lastStationId && it.sequence == position.roadSectionOrder }) return null
        val forecast = client.getBusAlightingForecast(path.last(), service) ?: return null
        val checkedAt = maxOf(at, position.receivedAt, forecast.receivedAt)
        if (position.validUntil <= checkedAt || forecast.sourceUpdatedAt < checkedAt.minusSeconds(sourceMaxAgeSeconds) ||
            forecast.sourceUpdatedAt < plan.boardingAt ||
            forecast.sourceUpdatedAt <= (plan.alightingObservationSourceUpdatedAt ?: plan.sourceUpdatedAt) ||
            forecast.sourceUpdatedAt > forecast.receivedAt || forecast.alightingAt <= checkedAt) return null
        return MatchedAlighting(plan.copy(alightingAt = forecast.alightingAt,
            alightingObservationSourceUpdatedAt = forecast.sourceUpdatedAt),
            minOf(position.sourceUpdatedAt, forecast.sourceUpdatedAt), checkedAt)
    }

    private data class Connection(
        val prediction: EffectiveBusRidePrediction, val service: SeoulBusRideService, val sourceAt: Instant,
    )

    private fun findConnection(
        service: SeoulBusRideService, readyAt: Instant, at: Instant, current: MatchedAlighting,
    ): Connection? {
        val path = path(service) ?: return null
        val paired = client.getPairedBusArrivals(path).filter { candidate ->
            val checkedAt = maxOf(at, current.receivedAt, candidate.receivedAt)
            val oldest = checkedAt.minusSeconds(sourceMaxAgeSeconds)
            candidate.service != null && candidate.boardingAt >= maxOf(readyAt, checkedAt) &&
                candidate.sourceUpdatedAt in oldest..candidate.receivedAt &&
                current.sourceAt >= oldest && current.prediction.alightingAt > checkedAt
        }.minByOrNull { it.alightingAt } ?: return null
        return Connection(EffectiveBusRidePrediction(paired.boardingAt, paired.alightingAt, paired.sourceUpdatedAt),
            requireNotNull(paired.service), paired.sourceUpdatedAt)
    }

    private fun path(service: SeoulBusRideService): List<SeoulBusRouteStop>? {
        val stops = client.getBusRouteDiagnosticStops(service.routeId)
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
        arrivalAt: Instant, sourceAt: Instant,
    ): Refresh? {
        val remaining = minutes(route.departureAt, arrivalAt).takeIf { it in 1..maxTravelMinutes } ?: return null
        return Refresh(PlannedTransitRouteUpdate(route.identity, sourceAt,
            route = route.copy(segments = segments, arrivalAt = arrivalAt, totalMinutes = remaining)), journey)
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
