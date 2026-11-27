package com.noLate.schedule.application

import com.noLate.transit.domain.SeoulBusRideService
import java.time.Instant

/** Stored with the Worker snapshot, separate from the API's display route and boarding claims. */
data class PlannedSeoulBusJourney(
    val routeIdentity: String,
    val departureAt: Instant,
    val rides: List<PlannedSeoulBusRide>,
) {
    init {
        require(rides.size in 1..2 && rides.zipWithNext().all { (first, next) -> first.sequence < next.sequence })
    }

    fun matches(plan: EffectiveTransitRoute?): Boolean {
        if (plan == null || plan.provider != "odsay" || plan.identity != routeIdentity ||
            plan.departureAt != departureAt) return false
        val moving = plan.segments.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }.sortedBy { it.sequence }
        val busSegments = moving.filter { it.kind == EffectiveTransitRouteSegmentKind.BUS }
        if (busSegments.map { it.sequence } != rides.map { it.sequence } || busSegments.any { it.busRidePrediction == null }) return false
        val rail = moving.filterNot { it.kind == EffectiveTransitRouteSegmentKind.BUS }
        return rail.isEmpty() || (busSegments.size == 1 && rail.size <= 2 &&
            (moving.first() == busSegments.first() || moving.last() == busSegments.first()) &&
            rail.all { it.kind == EffectiveTransitRouteSegmentKind.SUBWAY && it.rideTiming != null })
    }

    /** The extra lookup is for the last vehicle, so measure the window at its stop, before egress walking. */
    internal fun finalArrivalObservationDeadline(plan: EffectiveTransitRoute): Instant? {
        val lastRide = plan.segments.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }.maxByOrNull { it.sequence }
        val alightingAt = lastRide?.busRidePrediction?.alightingAt ?: lastRide?.rideTiming?.predictedArrivalAt
        // Keep the same two-minute lookback as the final rail station observation.
        return alightingAt?.plusSeconds(120)
    }
}

data class PlannedSeoulBusRide(val sequence: Int, val service: SeoulBusRideService) {
    init { require(sequence >= 0) }
}
