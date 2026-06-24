package com.noLate.schedule.application

import com.noLate.schedule.domain.ScheduleTravelMode

data class TrafficRequest(
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val travelMode: ScheduleTravelMode,
    val fallbackTravelMinutes: Int,
    val selectedRouteJson: String? = null,
    val selectedRouteTravelMinutes: Int? = null,
)

interface TrafficClient {
    fun getTravelMinutes(request: TrafficRequest): Int
}
