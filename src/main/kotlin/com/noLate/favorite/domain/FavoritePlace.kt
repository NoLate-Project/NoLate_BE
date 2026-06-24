package com.noLate.favorite.domain

import com.noLate.global.common.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Comment

@Entity
@Table(name = "favorite_places")
@Comment("사용자별 즐겨찾기 장소")
class FavoritePlace(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("즐겨찾기 장소 PK")
    var id: Long? = null,

    @Column(name = "member_id", nullable = false)
    @Comment("즐겨찾기 장소를 소유한 회원 id")
    var memberId: Long = 0,

    @Column(name = "category_id")
    @Comment("즐겨찾기 장소 카테고리 id")
    var categoryId: Long? = null,

    @Column(nullable = false, length = 120)
    @Comment("사용자가 지정한 장소 이름")
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

    @Column(name = "is_default_origin", nullable = false)
    @Comment("기본 출발지 여부")
    var defaultOrigin: Boolean = false,

    @Column(name = "sort_order", nullable = false)
    @Comment("사용자 지정 정렬 순서")
    var sortOrder: Int = 0,
) : BaseEntity() {

    fun update(
        categoryId: Long?,
        label: String,
        placeName: String?,
        address: String?,
        lat: Double,
        lng: Double,
        provider: String?,
        providerPlaceId: String?,
        defaultOrigin: Boolean,
        sortOrder: Int,
    ) {
        this.categoryId = categoryId
        this.label = label
        this.placeName = placeName
        this.address = address
        this.lat = lat
        this.lng = lng
        this.provider = provider
        this.providerPlaceId = providerPlaceId
        this.defaultOrigin = defaultOrigin
        this.sortOrder = sortOrder
    }

    fun toDto(category: FavoritePlaceCategory? = null): FavoritePlaceDto {
        return FavoritePlaceDto(
            id = id,
            categoryId = categoryId,
            category = category?.toDto(),
            label = label,
            placeName = placeName,
            address = address,
            lat = lat,
            lng = lng,
            provider = provider,
            providerPlaceId = providerPlaceId,
            defaultOrigin = defaultOrigin,
            sortOrder = sortOrder,
            updatedAt = (updateDt ?: updatedAt)?.toString(),
        )
    }
}
