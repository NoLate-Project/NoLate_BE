package com.noLate.route.application

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.application.port.DatedTransitTimetableProvider
import com.noLate.eta.application.transit.TransitJourneyMatcher
import com.noLate.eta.application.transit.SeoulBusEta
import com.noLate.eta.application.transit.TransitTransferFeasibilityEvaluator
import com.noLate.eta.application.transit.TransitJourneyTimetableEnricher
import com.noLate.eta.application.transit.TransitTimetablePreview
import com.noLate.eta.application.transit.TransitTimetableEntranceWalk
import com.noLate.eta.application.transit.TransitWalkingTimeEnricher
import com.noLate.eta.application.transit.requiresDatedTimetableForAdjacentRingRide
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitWalkingTimingSource
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.eta.resilience.EtaCalculationDeadline
import com.noLate.route.domain.TransitRouteRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.CancellationException

data class TransitTimetablePreviewRequest(
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double,
    val targetArrivalAt: Instant? = null,
    val route: JsonNode,
    val nextStops: List<String>,
    val requestedDepartureAt: Instant? = null,
    val orderedStops: List<List<String>>? = null,
)

data class TransitTimetablePreviewResponse(
    val preview: TransitTimetablePreview? = null,
    val missingEntranceWalk: TransitTimetableEntranceWalk? = null,
)

data class TransitBusPreviewRequest(
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double,
    val targetArrivalAt: Instant? = null,
    val requestedDepartureAt: Instant? = null,
    val route: JsonNode,
    val alternateProviderRouteId: String? = null,
)

data class TransitBusPreview(
    val departureAt: Instant,
    val boardingAt: Instant,
    val alightingAt: Instant,
    val arrivalAt: Instant,
    val sourceUpdatedAt: Instant,
    val accessSeconds: Long,
    val egressSeconds: Long,
    val providerRouteId: String,
    val transfer: TransitBusTransferPreview? = null,
)

data class TransitBusTransferPreview(
    val firstAlightingAt: Instant,
    val secondBoardingAt: Instant,
    val secondProviderRouteId: String,
    val walkingSeconds: Long,
    val requiredMarginSeconds: Long,
)

data class TransitBusPreviewResponse(val preview: TransitBusPreview?)

@Service
class TransitTimetablePreviewService(
    providers: List<TransitJourneyProvider>,
    private val decoder: SelectedTransitRouteDecoder,
    private val matcher: TransitJourneyMatcher,
    private val planner: TransitJourneyTimetableEnricher,
    private val clock: Clock,
    private val deadline: EtaCalculationDeadline,
    private val walkingTimeEnricher: TransitWalkingTimeEnricher = TransitWalkingTimeEnricher(),
    private val datedTimetable: DatedTransitTimetableProvider? = null,
    private val seoulBusEta: SeoulBusEta? = null,
    private val transferFeasibilityEvaluator: TransitTransferFeasibilityEvaluator = TransitTransferFeasibilityEvaluator(),
) {
    private val odsay = providers.singleOrNull { it.providerId == "odsay" }

    fun previewBus(request: TransitBusPreviewRequest): TransitBusPreview? {
        val provider = odsay ?: return null
        val busEta = seoulBusEta ?: return null
        val now = clock.instant()
        val target = request.targetArrivalAt
        val departure = request.requestedDepartureAt
        if ((target == null) == (departure == null) || departure?.nano?.let { it != 0 } == true) return null
        val anchor = target ?: requireNotNull(departure)
        val routeJson = request.route.toString().takeIf { it.length <= 12_000 } ?: return null
        val selected = decoder.decode(routeJson)?.takeIf { route ->
            route.provider == provider.providerId && route.rides.size in 1..2 && route.rides.all {
                it.mode == TransitLegMode.BUS && !it.providerRouteId.isNullOrBlank() &&
                    it.fromIds.isNotEmpty() && it.toIds.isNotEmpty()
            }
        } ?: return null
        val horizon = if (target == null) 20 * 60L else if (selected.rides.size == 2) 90 * 60L else 30 * 60L
        if (anchor <= now || anchor > now.plusSeconds(horizon)) return null
        val endpoints = TransitRouteRequest(request.startX, request.startY, request.endX, request.endY).validated()
        return try {
            deadline.within {
                val initialDeparture = departure ?: now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
                // ODsay searches whole minutes. The lookup verifies the route while the
                // live forecast still uses the exact requested departure.
                val searchAt = maxOf(initialDeparture.truncatedTo(ChronoUnit.MINUTES),
                    now.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60))
                val candidates = provider.search(TransitJourneySearchRequest(
                    endpoints.startY, endpoints.startX, endpoints.endY, endpoints.endX,
                    searchAt, MAX_TRAVEL_MINUTES,
                ))
                val candidate = matcher.findSelected(selected, candidates) ?: run {
                    logger.debug("No matching ODsay bus preview route among {} candidates", candidates.size)
                    return@within null
                }
                val journey = walkingTimeEnricher.enrich(candidate).copy(departureAt = initialDeparture)
                val ride = journey.rideLegs.first()
                val primary = ride.line ?: return@within null
                val alternate = request.alternateProviderRouteId?.takeIf { it != primary.providerRouteId }?.let { id ->
                    ride.alternativeBusLines.singleOrNull { it.providerRouteId == id }
                }
                val alternateJourney = alternate?.let { line ->
                    journey.copy(legs = journey.legs.map { if (it === ride) it.copy(line = line) else it })
                }
                if (journey.rideLegs.size == 2) {
                    if (target != null && target > now.plusSeconds((journey.totalMinutes.toLong() + 20) * 60)) {
                        return@within null
                    }
                    val evaluation = busEta.evaluation()
                    return@within previewBusTransfer(journey, evaluation, now, target)
                        ?: alternateJourney?.let { route ->
                            evaluation.alternateFirstLine()?.let { previewBusTransfer(route, it, now, target) }
                        }
                }
                // A grouped ODsay path may advertise a different bus at the exact same stops.
                // Try at most one such line, and only when the app supplied its original ODsay ID.
                listOfNotNull(journey, alternateJourney).firstNotNullOfOrNull { route ->
                    val id = route.rideLegs.single().line?.providerRouteId ?: return@firstNotNullOfOrNull null
                    val evaluation = busEta.evaluation()
                    val first = evaluation.project(route, now, MAX_TRAVEL_MINUTES, target)
                        ?: return@firstNotNullOfOrNull null
                    val projected = if (target == null) first else {
                        val accessAndBuffer = java.time.Duration.between(first.observation.departureAt, first.observation.readyAt)
                        val latestDeparture = first.observation.expectedBoardingAt.minus(accessAndBuffer)
                        evaluation.project(route.copy(departureAt = latestDeparture), now, MAX_TRAVEL_MINUTES, target)
                            ?: return@firstNotNullOfOrNull null
                    }
                    if (projected.observation.departureAt <= clock.instant()) return@firstNotNullOfOrNull null
                    TransitBusPreview(projected.observation.departureAt, projected.observation.expectedBoardingAt,
                        projected.alightingAt, projected.destinationAt, projected.observation.sourceUpdatedAt,
                        projected.accessSeconds, projected.egressSeconds, id)
                }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is InterruptedException ||
                Thread.currentThread().isInterrupted) throw failure
            logger.debug("ODsay bus preview failed ({})", failure.javaClass.simpleName)
            null
        }
    }

    private fun previewBusTransfer(
        journey: com.noLate.eta.domain.TransitJourney,
        evaluation: SeoulBusEta.Evaluation,
        now: Instant,
        target: Instant?,
    ): TransitBusPreview? {
        val transferMargin = transferFeasibilityEvaluator.requiredTransferMarginSeconds
        val initial = evaluation.projectTransfer(journey, now, MAX_TRAVEL_MINUTES, transferMargin, target)
            ?.connection ?: return null
        val firstObservation = initial.first.observation
        val projected = if (target == null) initial else {
            val readyDuration = Duration.between(firstObservation.departureAt, firstObservation.readyAt)
            val latestDeparture = firstObservation.expectedBoardingAt.minus(readyDuration)
            evaluation.projectTransfer(journey.copy(departureAt = latestDeparture), now, MAX_TRAVEL_MINUTES,
                transferMargin, target)?.connection ?: return null
        }
        val first = projected.first.observation
        val onward = projected.second.observation
        if (first.departureAt <= clock.instant()) return null
        val ids = journey.rideLegs.map { it.line?.providerRouteId ?: return null }
        return TransitBusPreview(first.departureAt, first.expectedBoardingAt, projected.second.alightingAt,
            projected.second.destinationAt, minOf(first.sourceUpdatedAt, onward.sourceUpdatedAt),
            projected.first.accessSeconds, projected.second.egressSeconds, ids.first(),
            TransitBusTransferPreview(projected.first.alightingAt, onward.expectedBoardingAt, ids.last(),
                projected.second.accessSeconds, projected.requiredMarginSeconds),
        )
    }

    fun preview(request: TransitTimetablePreviewRequest): TransitTimetablePreviewResponse {
        val unavailable = TransitTimetablePreviewResponse()
        val provider = odsay ?: return unavailable
        val now = clock.instant()
        val target = request.targetArrivalAt
        val departure = request.requestedDepartureAt
        if ((target == null) == (departure == null)) return unavailable
        if (departure != null && departure.nano != 0) return unavailable
        val anchor = target ?: requireNotNull(departure)
        if (anchor <= now) return unavailable
        val laterDate = anchor.atZone(SEOUL).toLocalDate() != now.atZone(SEOUL).toLocalDate()
        if (laterDate && datedTimetable == null) return unavailable
        val orderedStops = request.orderedStops
        if (laterDate && (orderedStops == null ||
                orderedStops.size != request.nextStops.size || orderedStops.any { stops ->
                    stops.size !in 2..512 || stops.any { it.isBlank() || it.length > 80 }
                })) return unavailable
        val endpoints = TransitRouteRequest(request.startX, request.startY, request.endX, request.endY).validated()
        val routeJson = request.route.toString()
        if (routeJson.length > 12_000 || request.nextStops.size !in 1..3 ||
            request.nextStops.any { it.isBlank() || it.length > 80 }) return unavailable
        val selected = decoder.decode(routeJson)?.takeIf { route ->
            route.provider == provider.providerId && route.rides.size == request.nextStops.size &&
                route.rides.all { it.mode == TransitLegMode.SUBWAY && it.fromIds.isNotEmpty() && it.toIds.isNotEmpty() }
        } ?: return unavailable
        // The same next-stop sequence proves direction on the ring line, whose
        // ODsay wayCode is not a Seoul timetable direction code.
        val route = selected.copy(rides = selected.rides.map { ride ->
            if (ride.providerRouteId == "1000:2") ride.copy(directionCode = null, directionName = null) else ride
        })
        return try {
            deadline.within {
                // ODsay SearchTime and the returned route timestamp are minute-granular.
                // This lookup verifies only the route; the timetable keeps the exact anchor below.
                val routeLookupAt = if (departure != null || laterDate) anchor.truncatedTo(ChronoUnit.MINUTES)
                else now.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60)
                val candidates = provider.search(TransitJourneySearchRequest(
                    endpoints.startY, endpoints.startX, endpoints.endY, endpoints.endX,
                    routeLookupAt,
                    MAX_TRAVEL_MINUTES,
                ))
                val verified = matcher.findSelectedCandidates(route, candidates).firstOrNull { candidate ->
                    candidate.rideLegs.map { it.nextStopName } == request.nextStops &&
                        (!laterDate || candidate.rideLegs.map { it.orderedStopNames } == orderedStops)
                } ?: return@within unavailable
                // For adjacent stops on the ring, the station tables alone cannot prove direction.
                // The dated path verifies the exact ordered ODsay hop, including on the current day.
                val hasAdjacentRingRide = verified.requiresDatedTimetableForAdjacentRingRide()
                val timed = walkingTimeEnricher.enrich(verified)
                val missingEntranceWalk = timed.legs.firstOrNull()?.takeIf {
                    verified.legs.firstOrNull()?.isRide == true &&
                        it.walkingTiming?.source == TransitWalkingTimingSource.ODSAY_ENTRANCE_WALK_SECONDS
                }?.let { walk ->
                    walk.walkingGeometry?.distanceMeters?.let { TransitTimetableEntranceWalk(walk.movementDurationSeconds, it) }
                }
                val requiresDatedProvider = laterDate || hasAdjacentRingRide
                val datedProvider = datedTimetable.takeIf { requiresDatedProvider }
                val preview = try {
                    when {
                        requiresDatedProvider && datedProvider == null -> null
                        departure != null -> planner.planDepartingAt(timed, departure, now, MAX_TRAVEL_MINUTES, datedProvider)
                        else -> planner.planArrivalBy(timed, anchor, now, MAX_TRAVEL_MINUTES, datedProvider)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException || failure is InterruptedException ||
                        Thread.currentThread().isInterrupted) throw failure
                    null
                }
                TransitTimetablePreviewResponse(preview, missingEntranceWalk.takeIf { preview == null })
            }
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is InterruptedException ||
                Thread.currentThread().isInterrupted) throw failure
            unavailable
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(TransitTimetablePreviewService::class.java)
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        const val MAX_TRAVEL_MINUTES = 180
    }
}
