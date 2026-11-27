package com.noLate.eta.application.transit

import com.noLate.eta.domain.TransitFirstBoardingObservation
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitLegMode
import com.noLate.transit.domain.SeoulBusRideService
import com.noLate.transit.infrastructure.SeoulBusPairedArrival
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlin.math.ceil

@Component
class SeoulBusEta(
    private val client: SeoulTransitArrivalClient,
    @Value("\${eta.transit.boarding-buffer-seconds:60}") private val boardingBufferSeconds: Long,
    @Value("\${eta.transit.arrival-source-max-age-seconds:120}") private val sourceMaxAgeSeconds: Long,
) {
    internal fun evaluation(): Evaluation = Evaluation()

    /** Safe-departure search reuses the same response and never polls another route in this evaluation. */
    internal inner class Evaluation(private val sharedOnward: (() -> Evaluation)? = null) {
        private var queried: StopPair? = null
        private var arrivals = emptyList<SeoulBusPairedArrival>()
        private var providerFailed = false
        private val onward by lazy { sharedOnward?.invoke() ?: Evaluation() }

        fun alternateFirstLine(): Evaluation? =
            if (providerFailed || onward.providerFailed) null else Evaluation { onward }

        fun projectTransfer(
            journey: TransitJourney,
            evaluatedAt: Instant,
            maxTravelMinutes: Int,
            transferMarginSeconds: Long,
            latestArrivalAt: Instant? = null,
        ): SeoulBusTransferAttempt? {
            if (journey.legs.any { it.mode != TransitLegMode.BUS && it.mode != TransitLegMode.WALK }) return null
            val rides = journey.legs.indices.filter { journey.legs[it].mode == TransitLegMode.BUS }
            if (rides.size != 2) return null
            val (firstIndex, secondIndex) = rides
            val transferWalk = journey.legs.subList(firstIndex + 1, secondIndex)
                .sumOf(TransitJourneyLeg::movementDurationSeconds)
            val firstAlightingId = journey.legs[firstIndex].to?.localStopId
            val secondBoardingId = journey.legs[secondIndex].from?.localStopId
            if (transferWalk == 0L && (firstAlightingId == null || firstAlightingId != secondBoardingId)) return null
            val firstLegs = journey.copy(legs = journey.legs.take(firstIndex + 1))
            val secondLegs = journey.copy(legs = journey.legs.drop(firstIndex + 1))
            val candidates = projectRides(firstLegs, evaluatedAt, maxTravelMinutes)
            val firstAvailable = candidates.firstOrNull() ?: return null
            val departureOrder = if (latestArrivalAt == null) candidates else candidates.asReversed()
            val connection = departureOrder.firstNotNullOfOrNull { first ->
                connect(first, secondLegs, secondIndex, evaluatedAt, maxTravelMinutes,
                    transferMarginSeconds, latestArrivalAt)
            }
            return SeoulBusTransferAttempt(firstAvailable, connection)
        }

        private fun connect(
            first: SeoulSingleBusProjection,
            secondLegs: TransitJourney,
            secondIndex: Int,
            evaluatedAt: Instant,
            maxTravelMinutes: Int,
            transferMarginSeconds: Long,
            latestArrivalAt: Instant?,
        ): SeoulBusTransferProjection? {
            val additionalMargin = (transferMarginSeconds - boardingBufferSeconds).coerceAtLeast(0)
            val connectionStart = first.alightingAt.plusSeconds(additionalMargin)
            val second = onward.projectRides(secondLegs.copy(departureAt = connectionStart), evaluatedAt, maxTravelMinutes)
                .filter { latestArrivalAt == null || it.destinationAt <= latestArrivalAt }
                .minByOrNull(SeoulSingleBusProjection::destinationAt) ?: return null
            val receivedAt = maxOf(first.receivedAt, second.receivedAt)
            val firstObservation = first.observation
            val firstBoardingAt = firstObservation.expectedBoardingAt
            val readyDuration = Duration.between(firstObservation.departureAt, firstObservation.readyAt)
            if (firstBoardingAt < receivedAt.plus(readyDuration) ||
                listOf(firstObservation, second.observation).any {
                    it.sourceUpdatedAt < receivedAt.minusSeconds(sourceMaxAgeSeconds)
                }) return null
            val totalMinutes = minutes(firstObservation.departureAt, second.destinationAt)
                .takeIf { it in 1..maxTravelMinutes } ?: return null
            val physicalReadyAt = first.alightingAt.plusSeconds(second.accessSeconds)
            return SeoulBusTransferProjection(first, second.copy(
                rideIndex = secondIndex,
                waitingMinutes = minutes(physicalReadyAt, second.observation.expectedBoardingAt),
                rideWithWaitMinutes = minutes(physicalReadyAt, second.alightingAt),
            ), receivedAt, totalMinutes, maxOf(transferMarginSeconds, boardingBufferSeconds))
        }

        fun project(
            journey: TransitJourney,
            evaluatedAt: Instant,
            maxTravelMinutes: Int,
            latestArrivalAt: Instant? = null,
        ): SeoulSingleBusProjection? {
            val projections = projectRides(journey, evaluatedAt, maxTravelMinutes)
            return if (latestArrivalAt == null) projections.firstOrNull()
            else projections.lastOrNull { it.destinationAt <= latestArrivalAt }
        }

        fun firstBusBeforeSubway(journey: TransitJourney, evaluatedAt: Instant,
                                 maxTravelMinutes: Int): SeoulSingleBusProjection? {
            val rides = journey.rideLegs
            if (journey.provider != "odsay" || rides.size !in 2..3 || rides.first().mode != TransitLegMode.BUS ||
                rides.drop(1).any { it.mode != TransitLegMode.SUBWAY }) return null
            val prefix = journey.copy(legs = journey.legs.take(journey.legs.indexOf(rides.first()) + 1))
            return projectRides(prefix, evaluatedAt, maxTravelMinutes).minByOrNull { it.alightingAt }
        }

        fun canStillBoard(bus: SeoulSingleBusProjection, at: Instant): Boolean =
            bus.observation.isFreshAt(maxOf(at, bus.receivedAt), sourceMaxAgeSeconds)

        private fun projectRides(
            journey: TransitJourney,
            evaluatedAt: Instant,
            maxTravelMinutes: Int,
        ): List<SeoulSingleBusProjection> {
            if (journey.provider != "odsay" || journey.departureAt < evaluatedAt) return emptyList()
            val ride = journey.rideLegs.singleOrNull()?.takeIf { it.mode == TransitLegMode.BUS } ?: return emptyList()
            if (journey.legs.any { it !== ride && it.mode != TransitLegMode.WALK }) return emptyList()
            val index = journey.legs.indexOf(ride)
            val accessSeconds = journey.legs.take(index).sumOf(TransitJourneyLeg::movementDurationSeconds)
            val egressSeconds = journey.legs.drop(index + 1).sumOf(TransitJourneyLeg::movementDurationSeconds)
            val departure = journey.departureAt
            val accessAt = departure.plusSeconds(accessSeconds)
            val readyAt = accessAt.plusSeconds(boardingBufferSeconds)
            val horizon = evaluatedAt.plusSeconds(20 * 60)
            if (readyAt > horizon) return emptyList()

            val routeId = ride.line?.localRouteId?.takeIf(providerId::matches) ?: return emptyList()
            val lineName = ride.line.name?.takeIf(String::isNotBlank) ?: return emptyList()
            val from = ride.from ?: return emptyList()
            val to = ride.to ?: return emptyList()
            val stationName = from.name?.takeIf(String::isNotBlank) ?: return emptyList()
            val fromId = from.localStopId?.takeIf(providerId::matches) ?: return emptyList()
            val toId = to.localStopId?.takeIf { it != fromId && providerId.matches(it) } ?: return emptyList()
            val key = StopPair(routeId, fromId, toId, from.arsId, to.arsId)
            if (queried == null) {
                queried = key
                arrivals = try {
                    fetch(key)
                } catch (failure: Exception) {
                    if (failure is CancellationException || failure is InterruptedException ||
                        Thread.currentThread().isInterrupted) throw failure
                    providerFailed = true
                    logger.debug("Seoul bus ETA provider lookup failed ({})", failure.javaClass.simpleName)
                    emptyList()
                }
                logger.debug("Seoul bus ETA paired predictions: {}", arrivals.size)
            }
            if (queried != key) return emptyList()
            val current = arrivals.filter { candidate ->
                val now = maxOf(evaluatedAt, candidate.receivedAt)
                candidate.sourceUpdatedAt >= now.minusSeconds(sourceMaxAgeSeconds)
            }
            val (withinHorizon, outsideHorizon) = current.partition { it.boardingAt <= horizon }
            val (boardable, beforeReady) = withinHorizon.partition { candidate ->
                val actuallyReadyAt = maxOf(departure, evaluatedAt, candidate.receivedAt)
                    .plusSeconds(accessSeconds + boardingBufferSeconds)
                candidate.boardingAt >= actuallyReadyAt
            }
            if (boardable.isEmpty()) {
                logger.debug("Seoul bus ETA unavailable: {} fresh, {} before ready, {} beyond horizon",
                    current.size, beforeReady.size, outsideHorizon.size)
            }
            return boardable.mapNotNull { candidate ->
                val arrival = candidate.alightingAt.plusSeconds(egressSeconds)
                val totalMinutes = minutes(departure, arrival).takeIf { it in 1..maxTravelMinutes }
                    ?: return@mapNotNull null
                val observation = TransitFirstBoardingObservation(
                    provider = "seoul-openapi",
                    stationName = stationName,
                    lineName = lineName,
                    departureAt = departure,
                    readyAt = readyAt,
                    expectedBoardingAt = candidate.boardingAt,
                    sourceUpdatedAt = candidate.sourceUpdatedAt,
                    evaluatedAt = evaluatedAt,
                )
                SeoulSingleBusProjection(index, candidate.alightingAt, candidate.receivedAt, observation, arrival,
                    totalMinutes, minutes(accessAt, candidate.boardingAt), minutes(accessAt, candidate.alightingAt),
                    accessSeconds, egressSeconds, candidate.service)
            }
        }
    }

    private fun fetch(pair: StopPair): List<SeoulBusPairedArrival> {
        val stops = client.getBusRouteDiagnosticStops(pair.routeId)
        val board = stops.singleOrNull { it.stationId == pair.from && !it.publicStopNumberInvalid &&
            it.arsId != null && (pair.fromArs == null || it.arsId == pair.fromArs) } ?: return emptyList()
        val alight = stops.singleOrNull { it.stationId == pair.to && !it.publicStopNumberInvalid &&
            it.arsId != null && (pair.toArs == null || it.arsId == pair.toArs) } ?: return emptyList()
        if (alight.sequence <= board.sequence) return emptyList()
        return client.getPairedBusArrivals(stops.filter { it.sequence in board.sequence..alight.sequence })
    }

    private data class StopPair(
        val routeId: String, val from: String, val to: String, val fromArs: String?, val toArs: String?,
    )

    private companion object {
        val logger = LoggerFactory.getLogger(SeoulBusEta::class.java)
        val providerId = Regex("[1-9][0-9]{8}")
        fun minutes(from: Instant, to: Instant) = ceil(Duration.between(from, to).toMillis() / 60_000.0).toInt()
    }
}

internal data class SeoulSingleBusProjection(
    val rideIndex: Int,
    val alightingAt: Instant,
    val receivedAt: Instant,
    val observation: TransitFirstBoardingObservation,
    val destinationAt: Instant,
    val travelMinutes: Int,
    val waitingMinutes: Int,
    val rideWithWaitMinutes: Int,
    val accessSeconds: Long,
    val egressSeconds: Long,
    val service: SeoulBusRideService?,
)

internal data class SeoulBusTransferProjection(
    val first: SeoulSingleBusProjection,
    val second: SeoulSingleBusProjection,
    val receivedAt: Instant,
    val travelMinutes: Int,
    val requiredMarginSeconds: Long,
)

internal data class SeoulBusTransferAttempt(
    val firstAvailable: SeoulSingleBusProjection,
    val connection: SeoulBusTransferProjection?,
)
