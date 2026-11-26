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
        return moving.map { it.sequence } == rides.map { it.sequence } &&
            moving.all { it.kind == EffectiveTransitRouteSegmentKind.BUS && it.busRidePrediction != null }
    }
}

data class PlannedSeoulBusRide(val sequence: Int, val service: SeoulBusRideService) {
    init { require(sequence >= 0) }
}
