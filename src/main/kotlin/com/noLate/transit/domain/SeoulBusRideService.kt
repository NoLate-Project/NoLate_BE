package com.noLate.transit.domain

import java.security.MessageDigest

/** A catalog occurrence, checked again at refresh. The same stop ID can occur twice on one route. */
data class SeoulBusStopOccurrence(val stationId: String, val arsId: String, val order: Int) {
    init {
        require(isSeoulBusIdentifier(stationId) && arsId.matches(Regex("[0-9]{5}")) && arsId != "00000")
        require(order in 1..999)
    }

    fun matches(stop: SeoulBusRouteStop): Boolean =
        !stop.publicStopNumberInvalid && stationId == stop.stationId && arsId == stop.arsId && order == stop.sequence
}

/** Internal correlation for one planned vehicle. It is never part of the schedule display DTO. */
data class SeoulBusRideService(
    val routeId: String,
    val direction: String,
    val boarding: SeoulBusStopOccurrence,
    val alighting: SeoulBusStopOccurrence,
    val vehicleKey: String,
) {
    init {
        require(isSeoulBusIdentifier(routeId) && direction.isNotBlank() && direction.length <= 128)
        require(boarding.order < alighting.order && boarding.stationId != alighting.stationId)
        require(vehicleKey.matches(Regex("[0-9a-f]{64}")))
    }

    fun matchesVehicle(vehicleId: String): Boolean = isSeoulBusIdentifier(vehicleId) &&
        vehicleKey == keyFor(routeId, vehicleId)

    override fun toString() = "SeoulBusRideService(REDACTED)"

    companion object {
        internal fun fromVehicle(boarding: SeoulBusRouteStop, alighting: SeoulBusRouteStop, vehicleId: String) =
            SeoulBusRideService(
                boarding.routeId, boarding.direction, boarding.toOccurrence(), alighting.toOccurrence(),
                keyFor(boarding.routeId, vehicleId),
            )

        private fun keyFor(routeId: String, vehicleId: String): String =
            java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("seoul-bus/$routeId/$vehicleId".toByteArray(Charsets.UTF_8)))
    }
}

private fun SeoulBusRouteStop.toOccurrence() =
    SeoulBusStopOccurrence(stationId, requireNotNull(arsId), sequence)

private fun isSeoulBusIdentifier(value: String): Boolean = value.matches(Regex("[1-9][0-9]{8}"))
