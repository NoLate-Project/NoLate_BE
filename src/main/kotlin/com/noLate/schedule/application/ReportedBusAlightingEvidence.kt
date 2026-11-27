package com.noLate.schedule.application

import com.noLate.transit.domain.SeoulBusRideService
import com.noLate.transit.domain.SeoulBusVehicleFreshness
import java.time.Instant

/** A same-vehicle stop report used as a past transfer boundary, independent of passenger presence. */
data class ReportedBusAlightingEvidence(
    val routeIdentity: String,
    val departureAt: Instant,
    val sequence: Int,
    val service: SeoulBusRideService,
    val reportedAt: Instant,
    val checkedAt: Instant,
) {
    init {
        require(routeIdentity.matches(Regex("[0-9a-f]{64}")) && sequence >= 0)
        require(reportedAt >= departureAt && reportedAt in
            checkedAt.minusSeconds(SeoulBusVehicleFreshness.MAX_SOURCE_AGE_SECONDS)..checkedAt)
    }

    fun matches(route: EffectiveTransitRoute, ride: PlannedSeoulBusRide, boardingAt: Instant, at: Instant): Boolean =
        routeIdentity == route.identity && departureAt == route.departureAt && sequence == ride.sequence &&
            service == ride.service && reportedAt > boardingAt && at in checkedAt..validUntil()

    fun validUntil(): Instant = reportedAt.plusSeconds(20 * 60)
}
