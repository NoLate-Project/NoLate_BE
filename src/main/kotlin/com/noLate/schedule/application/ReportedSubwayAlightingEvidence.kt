package com.noLate.schedule.application

import java.time.Duration
import java.time.Instant

/** The provider reported this planned train at the alighting station. It does not establish passenger arrival. */
data class ReportedSubwayAlightingEvidence(
    val routeIdentity: String,
    val routeDepartureAt: Instant,
    val segmentSequence: Int,
    val service: ObservedTransitSubwayService,
    val sourceUpdatedAt: Instant,
    val checkedAt: Instant,
    val recordedAt: Instant,
) {
    init {
        require(routeIdentity.matches(Regex("[0-9a-f]{64}")) && segmentSequence >= 0)
        require(sourceUpdatedAt >= routeDepartureAt && sourceUpdatedAt in checkedAt.minusSeconds(120)..checkedAt)
        require(recordedAt in sourceUpdatedAt..minOf(sourceUpdatedAt.plusSeconds(120), checkedAt.plusSeconds(5)))
    }

    fun matches(route: EffectiveTransitRoute, segment: EffectiveTransitRouteSegment, evaluatedAt: Instant): Boolean {
        val timing = segment.rideTiming ?: return false
        val table = timing.timetable
        val sameTrain = timing.observedService?.let { it == service }
            ?: (service.trainNumber in setOfNotNull(table.trainNumber, table.realtimeTrainNumber))
        return routeIdentity == route.identity && routeDepartureAt == route.departureAt &&
            segmentSequence == segment.sequence && segment.kind == EffectiveTransitRouteSegmentKind.SUBWAY &&
            sameTrain && service.matchesReference(table) &&
            service.matchesOrderedRide(segment.fromName, segment.toName, segment.orderedStopNames) &&
            sourceUpdatedAt > timing.predictedBoardingAt && evaluatedAt in checkedAt..sourceUpdatedAt.plusSeconds(1200) &&
            (recordedAt <= evaluatedAt || checkedAt == evaluatedAt) &&
            table.isObservableServiceDay(route.departureAt, recordedAt) &&
            table.isObservableServiceDay(route.departureAt, evaluatedAt)
    }

    internal fun applyToFinalRide(route: EffectiveTransitRoute, evaluatedAt: Instant,
                                 maxTravelMinutes: Int): EffectiveTransitRoute? {
        val segments = route.segments.sortedBy { it.sequence }
        val finalIndex = segments.indexOfLast { it.kind != EffectiveTransitRouteSegmentKind.WALK }
        val finalRide = segments.getOrNull(finalIndex) ?: return null
        val timing = finalRide.rideTiming ?: return null
        if (!matches(route, finalRide, evaluatedAt)) return null
        val previousIndex = (finalIndex - 1 downTo 0).firstOrNull { segments[it].kind != EffectiveTransitRouteSegmentKind.WALK }
        val earlier = previousIndex?.let(segments::get)
        val earlierArrival = if (earlier == null) route.departureAt
            else earlier.rideTiming?.predictedArrivalAt ?: earlier.busRidePrediction?.alightingAt ?: return null
        val readyAt = earlierArrival.plusSeconds(segments.subList((previousIndex ?: -1) + 1, finalIndex)
            .sumOf { it.durationSeconds ?: it.durationMinutes * 60L })
        if (readyAt > timing.predictedBoardingAt) return null
        val arrivalAt = sourceUpdatedAt.plusSeconds(segments.drop(finalIndex + 1).sumOf { it.durationSeconds ?: it.durationMinutes * 60L })
        val duration = Duration.between(route.departureAt, arrivalAt).seconds
        if (duration !in 1..maxTravelMinutes * 60L) return null
        val finalDuration = Duration.between(readyAt, sourceUpdatedAt).seconds
        val revised = finalRide.copy(durationMinutes = ((finalDuration + 59) / 60).toInt(),
            rideTiming = timing.asPlannedBaseline().copy(predictedArrivalAt = sourceUpdatedAt, reportedAlightingAt = sourceUpdatedAt))
        return route.copy(segments = segments.map { if (it.sequence == segmentSequence) revised else it },
            arrivalAt = arrivalAt, totalMinutes = ((duration + 59) / 60).toInt())
    }
}
