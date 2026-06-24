package com.noLate.routehistory.domain

import com.noLate.global.common.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Comment
import java.time.LocalDateTime

@Entity
@Table(name = "recent_route_places")
@Comment("사용자별 최근 경로 검색 장소")
class RecentRoutePlace(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("최근 경로 검색 장소 PK")
    var id: Long? = null,

    @Column(name = "member_id", nullable = false)
    @Comment("최근 검색 장소를 소유한 회원 id")
    var memberId: Long = 0,

    @Column(nullable = false, length = 120)
    @Comment("화면에 표시할 장소명")
    var label: String = "",

    @Column(name = "place_name", length = 255)
    @Comment("검색/지도 공급자가 반환한 장소명")
    var placeName: String? = null,

    @Column(length = 500)
    @Comment("장소 주소")
    var address: String? = null,

    @Column(nullable = false)
    @Comment("장소 위도")
    var lat: Double = 0.0,

    @Column(nullable = false)
    @Comment("장소 경도")
    var lng: Double = 0.0,

    @Column(length = 30)
    @Comment("장소 공급자")
    var provider: String? = null,

    @Column(name = "provider_place_id", length = 128)
    @Comment("장소 공급자의 장소 id")
    var providerPlaceId: String? = null,

    @Column(name = "last_used_at", nullable = false)
    @Comment("마지막 선택 시각")
    var lastUsedAt: LocalDateTime = LocalDateTime.now(),
) : BaseEntity() {

    fun updateUsage(
        label: String,
        placeName: String?,
        address: String?,
        lat: Double,
        lng: Double,
        provider: String?,
        providerPlaceId: String?,
        lastUsedAt: LocalDateTime,
    ) {
        this.label = label
        this.placeName = placeName
        this.address = address
        this.lat = lat
        this.lng = lng
        this.provider = provider
        this.providerPlaceId = providerPlaceId
        this.lastUsedAt = lastUsedAt
    }

    fun toDto(): RecentRoutePlaceDto {
        return RecentRoutePlaceDto(
            id = id,
            label = label,
            placeName = placeName,
            address = address,
            lat = lat,
            lng = lng,
            provider = provider,
            providerPlaceId = providerPlaceId,
            lastUsedAt = lastUsedAt.toString(),
            updatedAt = (updateDt ?: updatedAt)?.toString(),
        )
    }
}
