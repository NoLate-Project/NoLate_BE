package com.noLate.favorite.application.service

import com.noLate.favorite.domain.FavoritePlace
import com.noLate.favorite.domain.FavoritePlaceCategory
import com.noLate.favorite.domain.FavoritePlaceCategoryDto
import com.noLate.favorite.domain.FavoritePlaceDto
import com.noLate.favorite.infrastructure.FavoritePlaceCategoryRepository
import com.noLate.favorite.infrastructure.FavoritePlaceRepository
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import kotlin.math.abs

@Service
class FavoritePlaceService(
    private val categoryRepository: FavoritePlaceCategoryRepository,
    private val placeRepository: FavoritePlaceRepository,
) {
    private val defaultCategoryColor = "#5A96FF"
    private val samePlaceCoordinateTolerance = 0.000001

    @Transactional
    fun getCategories(memberId: Long): List<FavoritePlaceCategoryDto> {
        return categoryRepository.findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(memberId)
            .map { it.toDto() }
    }

    @Transactional
    fun createCategory(
        memberId: Long,
        name: String?,
        color: String?,
        iconKey: String?,
        sortOrder: Int?,
    ): FavoritePlaceCategoryDto {
        val entity = FavoritePlaceCategory(
            memberId = memberId,
            name = normalizeRequiredText(name, "name", maxLength = 80),
            color = normalizeOptionalText(color, maxLength = 32) ?: defaultCategoryColor,
            iconKey = normalizeOptionalText(iconKey, maxLength = 40),
            sortOrder = sortOrder ?: nextCategorySortOrder(memberId),
        )

        return categoryRepository.save(entity).toDto()
    }

    @Transactional
    fun updateCategory(
        memberId: Long,
        categoryId: Long,
        name: String?,
        color: String?,
        iconKey: String?,
        sortOrder: Int?,
    ): FavoritePlaceCategoryDto {
        val entity = findCategory(memberId, categoryId)
        entity.update(
            name = name?.let { normalizeRequiredText(it, "name", maxLength = 80) } ?: entity.name,
            color = normalizeOptionalText(color, maxLength = 32) ?: entity.color,
            iconKey = if (iconKey != null) normalizeOptionalText(iconKey, maxLength = 40) else entity.iconKey,
            sortOrder = sortOrder ?: entity.sortOrder,
        )

        return categoryRepository.save(entity).toDto()
    }

    @Transactional
    fun deleteCategory(memberId: Long, categoryId: Long) {
        val entity = findCategory(memberId, categoryId)
        placeRepository.clearCategory(memberId, categoryId)
        entity.softDelete()
        categoryRepository.save(entity)
    }

    @Transactional
    fun reorderCategories(memberId: Long, items: List<FavoritePlaceReorderItem>): List<FavoritePlaceCategoryDto> {
        items.forEach { item ->
            val entity = findCategory(memberId, item.id)
            entity.sortOrder = item.sortOrder
            categoryRepository.save(entity)
        }

        return getCategories(memberId)
    }

    @Transactional
    fun getPlaces(memberId: Long): List<FavoritePlaceDto> {
        val categoriesById = categoryRepository.findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(memberId)
            .associateBy { it.id }

        return placeRepository.findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(memberId)
            .map { it.toDto(categoriesById[it.categoryId]) }
    }

    @Transactional
    fun getDefaultOrigin(memberId: Long): FavoritePlaceDto? {
        val entity = placeRepository
            .findFirstByMemberIdAndDeletedFalseAndDefaultOriginTrueOrderByIdAsc(memberId)
            ?: return null
        val category = entity.categoryId?.let { findCategory(memberId, it) }
        return entity.toDto(category)
    }

    @Transactional
    fun saveDefaultOrigin(
        memberId: Long,
        label: String?,
        placeName: String?,
        address: String?,
        lat: Double?,
        lng: Double?,
        provider: String?,
        providerPlaceId: String?,
    ): FavoritePlaceDto {
        val normalizedLat = normalizeLat(lat)
        val normalizedLng = normalizeLng(lng)
        val normalizedPlaceName = normalizeOptionalText(placeName, maxLength = 255)
        val normalizedAddress = normalizeOptionalText(address, maxLength = 500)
        val normalizedLabel = normalizeOptionalText(label, maxLength = 120)
            ?: normalizedPlaceName
            ?: normalizedAddress
            ?: "기본 출발지"
        val normalizedProvider = normalizeOptionalText(provider, maxLength = 30)?.uppercase()
        val normalizedProviderPlaceId = normalizeOptionalText(providerPlaceId, maxLength = 128)
        val existing = findMatchingPlace(
            memberId = memberId,
            lat = normalizedLat,
            lng = normalizedLng,
            provider = normalizedProvider,
            providerPlaceId = normalizedProviderPlaceId,
        )

        if (existing == null) {
            return createPlace(
                memberId = memberId,
                categoryId = null,
                label = normalizedLabel,
                placeName = normalizedPlaceName,
                address = normalizedAddress,
                lat = normalizedLat,
                lng = normalizedLng,
                provider = normalizedProvider,
                providerPlaceId = normalizedProviderPlaceId,
                defaultOrigin = true,
                sortOrder = null,
            )
        }

        val categoryId = existing.categoryId
        placeRepository.clearDefaultOrigin(memberId)
        existing.update(
            categoryId = categoryId,
            label = normalizedLabel,
            placeName = normalizedPlaceName,
            address = normalizedAddress,
            lat = normalizedLat,
            lng = normalizedLng,
            provider = normalizedProvider,
            providerPlaceId = normalizedProviderPlaceId,
            defaultOrigin = true,
            sortOrder = existing.sortOrder,
        )
        val category = categoryId?.let { findCategory(memberId, it) }
        return placeRepository.save(existing).toDto(category)
    }

    @Transactional
    fun clearDefaultOrigin(memberId: Long) {
        placeRepository.clearDefaultOrigin(memberId)
    }

    @Transactional
    fun createPlace(
        memberId: Long,
        categoryId: Long?,
        label: String?,
        placeName: String?,
        address: String?,
        lat: Double?,
        lng: Double?,
        provider: String?,
        providerPlaceId: String?,
        defaultOrigin: Boolean?,
        sortOrder: Int?,
    ): FavoritePlaceDto {
        val normalizedLat = normalizeLat(lat)
        val normalizedLng = normalizeLng(lng)
        val normalizedPlaceName = normalizeOptionalText(placeName, maxLength = 255)
        val normalizedAddress = normalizeOptionalText(address, maxLength = 500)
        val normalizedLabel = normalizeOptionalText(label, maxLength = 120)
            ?: normalizedPlaceName
            ?: normalizedAddress
            ?: "즐겨찾기 장소"
        val category = categoryId?.let { findCategory(memberId, it) }
        val shouldBeDefaultOrigin = defaultOrigin == true

        if (shouldBeDefaultOrigin) {
            placeRepository.clearDefaultOrigin(memberId)
        }

        val entity = FavoritePlace(
            memberId = memberId,
            categoryId = category?.id,
            label = normalizedLabel,
            placeName = normalizedPlaceName,
            address = normalizedAddress,
            lat = normalizedLat,
            lng = normalizedLng,
            provider = normalizeOptionalText(provider, maxLength = 30)?.uppercase(),
            providerPlaceId = normalizeOptionalText(providerPlaceId, maxLength = 128),
            defaultOrigin = shouldBeDefaultOrigin,
            sortOrder = sortOrder ?: nextPlaceSortOrder(memberId),
        )

        return placeRepository.save(entity).toDto(category)
    }

    @Transactional
    fun updatePlace(
        memberId: Long,
        placeId: Long,
        categoryId: Long?,
        clearCategory: Boolean,
        label: String?,
        placeName: String?,
        address: String?,
        lat: Double?,
        lng: Double?,
        provider: String?,
        providerPlaceId: String?,
        defaultOrigin: Boolean?,
        sortOrder: Int?,
    ): FavoritePlaceDto {
        val entity = findPlace(memberId, placeId)
        val category = when {
            clearCategory -> null
            categoryId != null -> findCategory(memberId, categoryId)
            else -> entity.categoryId?.let { findCategory(memberId, it) }
        }
        val nextDefaultOrigin = defaultOrigin ?: entity.defaultOrigin

        if (nextDefaultOrigin) {
            placeRepository.clearDefaultOrigin(memberId)
        }

        entity.update(
            categoryId = category?.id,
            label = label?.let { normalizeRequiredText(it, "label", maxLength = 120) } ?: entity.label,
            placeName = if (placeName != null) normalizeOptionalText(placeName, maxLength = 255) else entity.placeName,
            address = if (address != null) normalizeOptionalText(address, maxLength = 500) else entity.address,
            lat = lat?.let { normalizeLat(it) } ?: entity.lat,
            lng = lng?.let { normalizeLng(it) } ?: entity.lng,
            provider = if (provider != null) normalizeOptionalText(provider, maxLength = 30)?.uppercase() else entity.provider,
            providerPlaceId = if (providerPlaceId != null) {
                normalizeOptionalText(providerPlaceId, maxLength = 128)
            } else {
                entity.providerPlaceId
            },
            defaultOrigin = nextDefaultOrigin,
            sortOrder = sortOrder ?: entity.sortOrder,
        )

        return placeRepository.save(entity).toDto(category)
    }

    @Transactional
    fun deletePlace(memberId: Long, placeId: Long) {
        val entity = findPlace(memberId, placeId)
        entity.softDelete()
        placeRepository.save(entity)
    }

    @Transactional
    fun setDefaultOrigin(memberId: Long, placeId: Long): FavoritePlaceDto {
        val entity = findPlace(memberId, placeId)
        val category = entity.categoryId?.let { findCategory(memberId, it) }
        placeRepository.clearDefaultOrigin(memberId)
        entity.defaultOrigin = true
        return placeRepository.save(entity).toDto(category)
    }

    @Transactional
    fun reorderPlaces(memberId: Long, items: List<FavoritePlaceReorderItem>): List<FavoritePlaceDto> {
        items.forEach { item ->
            val entity = findPlace(memberId, item.id)
            entity.sortOrder = item.sortOrder
            placeRepository.save(entity)
        }

        return getPlaces(memberId)
    }

    private fun findCategory(memberId: Long, categoryId: Long): FavoritePlaceCategory {
        return categoryRepository.findByIdAndMemberIdAndDeletedFalse(categoryId, memberId)
            ?: throw BusinessException(ErrorCode.FAVORITE_PLACE_CATEGORY_NOT_FOUND)
    }

    private fun findPlace(memberId: Long, placeId: Long): FavoritePlace {
        return placeRepository.findByIdAndMemberIdAndDeletedFalse(placeId, memberId)
            ?: throw BusinessException(ErrorCode.FAVORITE_PLACE_NOT_FOUND)
    }

    private fun nextCategorySortOrder(memberId: Long): Int {
        return categoryRepository.findMaxSortOrder(memberId) + 1
    }

    private fun nextPlaceSortOrder(memberId: Long): Int {
        return placeRepository.findMaxSortOrder(memberId) + 1
    }

    private fun findMatchingPlace(
        memberId: Long,
        lat: Double,
        lng: Double,
        provider: String?,
        providerPlaceId: String?,
    ): FavoritePlace? {
        val places = placeRepository.findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(memberId)
        val providerMatch = if (provider != null && providerPlaceId != null) {
            places.firstOrNull {
                it.provider.equals(provider, ignoreCase = true) && it.providerPlaceId == providerPlaceId
            }
        } else {
            null
        }

        return providerMatch ?: places.firstOrNull {
            abs(it.lat - lat) <= samePlaceCoordinateTolerance &&
                abs(it.lng - lng) <= samePlaceCoordinateTolerance
        }
    }

    private fun normalizeRequiredText(value: String?, field: String, maxLength: Int): String {
        val normalized = value?.trim()
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "$field is required.")

        if (normalized.isBlank()) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "$field is required.")
        }
        if (normalized.length > maxLength) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "$field must be $maxLength characters or less.")
        }

        return normalized
    }

    private fun normalizeOptionalText(value: String?, maxLength: Int): String? {
        val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (normalized.length > maxLength) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "text must be $maxLength characters or less.")
        }
        return normalized
    }

    private fun normalizeLat(value: Double?): Double {
        val lat = value ?: throw BusinessException(ErrorCode.INVALID_INPUT, "lat is required.")
        if (!lat.isFinite() || lat < -90.0 || lat > 90.0) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "lat must be between -90 and 90.")
        }
        return lat
    }

    private fun normalizeLng(value: Double?): Double {
        val lng = value ?: throw BusinessException(ErrorCode.INVALID_INPUT, "lng is required.")
        if (!lng.isFinite() || lng < -180.0 || lng > 180.0) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "lng must be between -180 and 180.")
        }
        return lng
    }
}

data class FavoritePlaceReorderItem(
    val id: Long,
    val sortOrder: Int,
)
