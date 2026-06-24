package com.noLate.routehistory.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.routehistory.domain.RecentRoutePlace
import com.noLate.routehistory.domain.RecentRoutePlaceDto
import com.noLate.routehistory.infrastructure.RecentRoutePlaceRepository
import jakarta.transaction.Transactional
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import kotlin.math.abs

@Service
class RecentRoutePlaceService(
    private val recentRoutePlaceRepository: RecentRoutePlaceRepository,
) {
    private val maxRecentPlaceCount = 20
    private val coordinateEpsilon = 0.000001

    @Transactional
    fun getRecentPlaces(memberId: Long, limit: Int?): List<RecentRoutePlaceDto> {
        val normalizedLimit = limit?.coerceIn(1, maxRecentPlaceCount) ?: maxRecentPlaceCount

        return recentRoutePlaceRepository
            .findByMemberIdAndDeletedFalseOrderByLastUsedAtDescIdDesc(
                memberId,
                PageRequest.of(0, normalizedLimit),
            )
            .map { it.toDto() }
    }

    @Transactional
    fun saveRecentPlace(
        memberId: Long,
        label: String?,
        placeName: String?,
        address: String?,
        lat: Double?,
        lng: Double?,
        provider: String?,
        providerPlaceId: String?,
    ): RecentRoutePlaceDto {
        val normalizedLat = normalizeLat(lat)
        val normalizedLng = normalizeLng(lng)
        val normalizedPlaceName = normalizeOptionalText(placeName, maxLength = 255)
        val normalizedAddress = normalizeOptionalText(address, maxLength = 500)
        val normalizedLabel = normalizeOptionalText(label, maxLength = 120)
            ?: normalizedPlaceName
            ?: normalizedAddress
            ?: "최근 검색 장소"
        val normalizedProvider = normalizeOptionalText(provider, maxLength = 30)?.uppercase()
        val normalizedProviderPlaceId = normalizeOptionalText(providerPlaceId, maxLength = 128)
        val now = LocalDateTime.now()
        val activePlaces = recentRoutePlaceRepository
            .findByMemberIdAndDeletedFalseOrderByLastUsedAtDescIdDesc(memberId)

        val entity = activePlaces.firstOrNull {
            isSamePlace(
                it,
                normalizedLat,
                normalizedLng,
                normalizedProvider,
                normalizedProviderPlaceId,
            )
        } ?: RecentRoutePlace(memberId = memberId)

        entity.updateUsage(
            label = normalizedLabel,
            placeName = normalizedPlaceName,
            address = normalizedAddress,
            lat = normalizedLat,
            lng = normalizedLng,
            provider = normalizedProvider,
            providerPlaceId = normalizedProviderPlaceId,
            lastUsedAt = now,
        )

        val saved = recentRoutePlaceRepository.save(entity)
        pruneOldPlaces(memberId)
        return saved.toDto()
    }

    @Transactional
    fun deleteRecentPlace(memberId: Long, recentPlaceId: Long) {
        val entity = recentRoutePlaceRepository.findByIdAndMemberIdAndDeletedFalse(recentPlaceId, memberId)
            ?: throw BusinessException(ErrorCode.RECENT_ROUTE_PLACE_NOT_FOUND)

        entity.softDelete()
        recentRoutePlaceRepository.save(entity)
    }

    private fun pruneOldPlaces(memberId: Long) {
        val activePlaces = recentRoutePlaceRepository
            .findByMemberIdAndDeletedFalseOrderByLastUsedAtDescIdDesc(memberId)

        activePlaces.drop(maxRecentPlaceCount).forEach {
            it.softDelete()
            recentRoutePlaceRepository.save(it)
        }
    }

    private fun isSamePlace(
        entity: RecentRoutePlace,
        lat: Double,
        lng: Double,
        provider: String?,
        providerPlaceId: String?,
    ): Boolean {
        if (
            provider != null &&
            providerPlaceId != null &&
            entity.provider == provider &&
            entity.providerPlaceId == providerPlaceId
        ) {
            return true
        }

        return abs(entity.lat - lat) <= coordinateEpsilon &&
            abs(entity.lng - lng) <= coordinateEpsilon
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
