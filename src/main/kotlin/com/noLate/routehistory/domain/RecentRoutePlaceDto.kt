package com.noLate.routehistory.domain

data class RecentRoutePlaceDto(
    val id: Long? = null,
    val label: String,
    val placeName: String? = null,
    val address: String? = null,
    val lat: Double,
    val lng: Double,
    val provider: String? = null,
    val providerPlaceId: String? = null,
    val lastUsedAt: String? = null,
    val updatedAt: String? = null,
)
