package com.noLate.route.quickshare

import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.ScheduleTravelMode
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

data class QuickSharePlace(
    val name: String,
    val address: String? = null,
    val lat: Double,
    val lng: Double,
    val provider: String = "tmap",
    val providerPlaceId: String? = null,
)

data class QuickShareRouteRequest(
    val origin: SchedulePlaceDto,
    val destination: SchedulePlaceDto,
    val mode: ScheduleTravelMode,
    val departureAt: String? = null,
) {
    fun validated(): QuickShareRouteRequest {
        val originLat = origin.lat
        val originLng = origin.lng
        val destinationLat = destination.lat
        val destinationLng = destination.lng
        if (
            originLat == null || originLng == null || destinationLat == null || destinationLng == null ||
            originLat !in -90.0..90.0 || destinationLat !in -90.0..90.0 ||
            originLng !in -180.0..180.0 || destinationLng !in -180.0..180.0
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "출발지 또는 도착지 좌표가 올바르지 않습니다.")
        }
        if (mode !in setOf(ScheduleTravelMode.CAR, ScheduleTravelMode.TRANSIT, ScheduleTravelMode.WALK)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "공유 일정에서 지원하지 않는 이동수단입니다.")
        }
        return this
    }
}

data class QuickShareRouteOption(
    val id: String,
    val mode: ScheduleTravelMode,
    val minutes: Int,
    val distanceMeters: Int? = null,
    val transferCount: Int? = null,
    val walkMeters: Int? = null,
    val fareWon: Int? = null,
    val summary: String,
    val provider: String = "tmap",
    val providerRouteOption: String? = null,
)

