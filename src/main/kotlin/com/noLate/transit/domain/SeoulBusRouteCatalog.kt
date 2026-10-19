package com.noLate.transit.domain

/** Public static provider metadata, not a passenger location or a boarding decision. */
data class SeoulBusRouteStop(
    val routeId: String,
    val sequence: Int,
    val stationId: String,
    /** Missing/blank/00000 public number is unavailable, never inferred from stationId/sequence. */
    val arsId: String?,
    val name: String,
    val direction: String,
    val sectionId: String?,
    val latitude: Double,
    val longitude: Double,
    /** Diagnostic catalogs may retain this row, but must not use it as a boarding/arrival mapping. */
    val publicStopNumberInvalid: Boolean = false,
)

data class SeoulBusRoutePathPoint(val order: Int, val latitude: Double, val longitude: Double)
