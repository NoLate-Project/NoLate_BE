package com.noLate.schedule.application

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
}
