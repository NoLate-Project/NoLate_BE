package com.noLate.favorite.domain

data class FavoritePlaceCategoryDto(
    val id: Long? = null,
    val name: String,
    val color: String,
    val iconKey: String? = null,
    val sortOrder: Int,
    val updatedAt: String? = null,
)

data class FavoritePlaceDto(
    val id: Long? = null,
    val categoryId: Long? = null,
    val category: FavoritePlaceCategoryDto? = null,
    val label: String,
    val placeName: String? = null,
    val address: String? = null,
    val lat: Double,
    val lng: Double,
    val provider: String? = null,
    val providerPlaceId: String? = null,
    val defaultOrigin: Boolean,
    val sortOrder: Int,
    val updatedAt: String? = null,
)
