package com.noLate.transit.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant

/** Stop occurrence ordinals in the request are NOT road-section ordinals in the response. */
data class SeoulBusVehicleQuery(val routeId: String, val firstStopOrder: Int, val lastStopOrder: Int) {
    init {
        require(routeId.matches(Regex("[1-9][0-9]{8}"))) { "Invalid Seoul route identifier" }
        require(firstStopOrder in 1..999 && lastStopOrder in firstStopOrder..999 &&
            lastStopOrder - firstStopOrder < 100) { "Invalid Seoul stop query window" }
    }
}

enum class SeoulBusVehicleObservationStatus {
    AVAILABLE, NO_VEHICLES, CREDENTIAL_UNAVAILABLE, NO_USABLE_OBSERVATIONS,
}

enum class SeoulBusVehicleRejection {
    VEHICLE_ID_INVALID, ROUTE_MISMATCH, SECTION_INVALID, STOP_INVALID, COORDINATE_INVALID,
    TIMESTAMP_UNSUPPORTED, TIMESTAMP_INVALID, TIMESTAMP_FUTURE, TIMESTAMP_STALE, DUPLICATE_VEHICLE,
}

/**
 * Internal provider evidence, NOT passenger boarding evidence. tmX/tmY have an official WGS84
 * contract; parsing that contract does not verify the route-section crosswalk or a passenger ride.
 * Vehicle identifiers stay out of serialization and toString; plates are never retained.
 */
class SeoulBusVehicleObservation internal constructor(
    @get:JsonIgnore val vehicleId: String,
    val routeId: String,
    val roadSectionId: String,
    val roadSectionOrder: Int,
    val sectionOffset: Int,
    val atStop: Boolean,
    val lastStationId: String,
    val sourceUpdatedAt: Instant,
    val receivedAt: Instant,
    val validUntil: Instant,
    @get:JsonIgnore val wgs84Coordinate: SeoulBusVehicleCoordinate? = null,
) {
    override fun toString() = "SeoulBusVehicleObservation(vehicleId=REDACTED, sourceUpdatedAt=$sourceUpdatedAt)"
}

/** Internal only; do not log source vehicle position or substitute GRS80 posX/posY. */
class SeoulBusVehicleCoordinate internal constructor(val latitude: Double, val longitude: Double) {
    override fun toString() = "SeoulBusVehicleCoordinate(REDACTED)"
}

/** Aggregate diagnostics only; contains neither raw source timestamps nor vehicle identity. */
data class SeoulBusVehicleSourceAgeSummary(
    val count: Int,
    val minAgeMillis: Long,
    val maxAgeMillis: Long,
)

data class SeoulBusVehicleObservationBatch(
    val status: SeoulBusVehicleObservationStatus,
    val observations: List<SeoulBusVehicleObservation>,
    val rejectedCount: Int = 0,
    val rejectionCounts: Map<SeoulBusVehicleRejection, Int> = emptyMap(),
    val sourceAgeSummary: SeoulBusVehicleSourceAgeSummary? = null,
) {
    // Explicit capability boundaries: consumers cannot promote this result to a riding snapshot.
    val passengerVehicleAssociation: Boolean = false
    val stopOrderCrosswalkVerified: Boolean = false
    val geographicCoordinatesVerified: Boolean = false
    // Contract-level parsing only; diagnostics expose a count, never raw coordinates.
    val wgs84CoordinateCount: Int get() = observations.count { it.wgs84Coordinate != null }
}
