package com.noLate.schedule.application

import com.noLate.transit.domain.SeoulBusConnectionStops
import java.time.Instant

/** Private rail plan with a bus transfer that was beyond the provider's forecast window. */
data class DeferredSeoulBusTransfer(
    val railPlan: EffectiveTransitRoute,
    val transferWalk: List<EffectiveTransitRouteSegment>,
    val bus: EffectiveTransitRouteSegment,
    val egressWalk: List<EffectiveTransitRouteSegment>,
    val stops: SeoulBusConnectionStops,
    val trackingUntil: Instant,
    val railUpdate: PlannedTransitRouteUpdate? = null,
) {
    init {
        require(railPlan.provider == "odsay" && railPlan.arrivalAt < trackingUntil)
        require(railPlan.segments.lastOrNull()?.rideTiming?.predictedArrivalAt == railPlan.arrivalAt)
        require(railPlan.segments.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }.all { it.rideTiming != null })
        require(bus.kind == EffectiveTransitRouteSegmentKind.BUS && bus.busRidePrediction == null)
        require(transferWalk.isNotEmpty() && (transferWalk + egressWalk).all { it.kind == EffectiveTransitRouteSegmentKind.WALK })
        require((railPlan.segments + transferWalk + bus + egressWalk).map { it.sequence }.let { it == it.indices.toList() })
        require(railUpdate == null || railUpdate.routeIdentity == railPlan.identity)
    }
}
