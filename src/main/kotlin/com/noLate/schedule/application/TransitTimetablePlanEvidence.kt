package com.noLate.schedule.application

import java.time.Instant

/** 같은 평가의 별도 미래 시간표 후보. 현재 실시간 실패나 실제 승차를 지우지 않는다. */
data class TransitTimetablePlanEvidence(
    val route: EffectiveTransitRoute,
    val fetchedAt: Instant,
    val failureReason: String,
    val provenance: TransitRouteProvenance,
) {
    init {
        require(provenance == TransitRouteProvenance.SELECTED_ROUTE_PRESERVED)
        require(failureReason in setOf(TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED,
            TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE))
    }
}
