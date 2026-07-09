package com.noLate.transit.domain

data class TransitArrivalDto(
    val provider: String,
    val kind: String,
    val lineName: String? = null,
    val routeName: String? = null,
    val stationName: String? = null,
    val direction: String? = null,
    val destinationName: String? = null,
    val arrivalMessage: String? = null,
    val waitSeconds: Int? = null,
    val waitMinutes: Int? = null,
    val expectedAt: String? = null,
    val lastTrain: Boolean = false,
    val realtime: Boolean = true,
)
