package com.noLate.eta.application.transit

import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitLegMode
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Same verified trains, less waiting before boarding. No HTTP or timetable lookup. */
object ObservedFirstBoardingDepartureProjector {
    data class Projection(val journey: TransitJourney, val resolution: TransitRealtimeOverlayResolution)

    fun project(journey: TransitJourney, resolution: TransitRealtimeOverlayResolution,
                evaluatedAt: Instant, secondPrecision: Boolean): Projection? {
        val overlay = resolution.overlay ?: return null
        val observation = resolution.firstBoardingObservation ?: return null
        val firstIndex = journey.legs.indexOfFirst { it.isRide }
        if (firstIndex < 0 || journey.rideLegs.any { it.mode != TransitLegMode.SUBWAY || it.timetable == null }) return null
        val first = journey.legs[firstIndex]
        val timetable = first.timetable ?: return null
        val access = journey.legs.take(firstIndex)
        if (access.any { it.mode != TransitLegMode.WALK }) return null
        val accessSeconds = access.sumOf { it.movementDurationSeconds }
        val readySpan = Duration.between(observation.departureAt, observation.readyAt)
        if (observation.provider != "seoul-openapi" || timetable.provider != "seoul-timetable" ||
            observation.stationName != first.from?.name || observation.lineName != timetable.line.displayName ||
            observation.trainNumber.isNullOrBlank() || observation.trainNumber !in setOfNotNull(timetable.trainNumber, timetable.realtimeTrainNumber) ||
            observation.departureAt != journey.departureAt || observation.evaluatedAt != evaluatedAt ||
            observation.expectedBoardingAt != overlay.boardingAt || readySpan.seconds < accessSeconds) return null
        // The span includes the configured boarding buffer. Keep it exactly,
        // floor the anchor and re-evaluate the whole journey before adoption.
        val departure = observation.expectedBoardingAt.minus(readySpan)
            .truncatedTo(if (secondPrecision) ChronoUnit.SECONDS else ChronoUnit.MINUTES)
        if (departure <= journey.departureAt || departure < evaluatedAt) return null
        val shift = Duration.between(journey.departureAt, departure)
        val updatedObservation = observation.copy(departureAt = departure, readyAt = departure.plus(readySpan))
        val embedded = updatedObservation.takeIf { it.expectedBoardingAt >= timetable.boardingAt }
        val oldEmbeddedDelay = first.firstBoardingObservation?.let { Duration.between(timetable.boardingAt, it.expectedBoardingAt) } ?: Duration.ZERO
        val newEmbeddedDelay = embedded?.let { Duration.between(timetable.boardingAt, it.expectedBoardingAt) } ?: Duration.ZERO
        val legs = journey.legs.mapIndexed { index, leg ->
            when {
                index < firstIndex -> leg.copy(scheduledDepartureAt = leg.scheduledDepartureAt?.plus(shift),
                    scheduledArrivalAt = leg.scheduledArrivalAt?.plus(shift))
                index == firstIndex -> {
                    val start = departure.plusSeconds(accessSeconds)
                    val arrival = TransitRideArrivalPredictor.baseline(timetable,
                        timetable.boardingAt.plus(newEmbeddedDelay)).arrivalAt
                    val seconds = Duration.between(start, arrival).seconds
                    if (seconds <= 0) return null
                    leg.copy(durationSeconds = seconds, durationMinutes = ceilMinutes(seconds),
                        waitingMinutes = ceilMinutes(Duration.between(start, embedded?.expectedBoardingAt ?: timetable.boardingAt).seconds),
                        scheduledDepartureAt = start, scheduledArrivalAt = arrival, firstBoardingObservation = embedded)
                }
                else -> leg // Never change a transfer, destination walk or train.
            }
        }
        // A single ride stores its embedded delay in the final arrival; for
        // transfers the already-verified later train absorbs the first delay.
        val arrival = if (journey.rideLegs.size == 1) journey.arrivalAt.plus(newEmbeddedDelay).minus(oldEmbeddedDelay)
            else journey.arrivalAt
        val updated = journey.copy(departureAt = departure, arrivalAt = arrival,
            totalMinutes = ceilMinutes(Duration.between(departure, arrival).seconds), legs = legs)
        return Projection(updated, resolution.copy(firstBoardingObservation = updatedObservation,
            overlay = overlay.copy(travelMinutes = ceilMinutes(Duration.between(departure, overlay.predictedArrivalAt).seconds),
                firstBoardingWaitMinutes = ceilMinutes(Duration.between(departure.plusSeconds(accessSeconds), overlay.boardingAt).seconds))))
    }

    private fun ceilMinutes(seconds: Long) = ((seconds + 59) / 60).toInt()
}
