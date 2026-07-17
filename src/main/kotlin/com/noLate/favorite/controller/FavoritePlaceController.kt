package com.noLate.favorite.controller

import com.noLate.favorite.application.service.FavoritePlaceReorderItem
import com.noLate.favorite.application.service.FavoritePlaceService
import com.noLate.favorite.domain.FavoritePlaceCategoryDto
import com.noLate.favorite.domain.FavoritePlaceDto
import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/favorite-place-categories")
@Tag(name = "Favorite Place Category", description = "즐겨찾기 장소 카테고리 API")
class FavoritePlaceCategoryController(
    private val favoritePlaceService: FavoritePlaceService,
) {
    @Operation(summary = "즐겨찾기 장소 카테고리 목록 조회")
    @GetMapping
    fun getCategories(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<List<FavoritePlaceCategoryDto>> {
        return ApiResponse.success(favoritePlaceService.getCategories(requireMemberId(principal)))
    }

    @Operation(summary = "즐겨찾기 장소 카테고리 생성")
    @PostMapping
    fun createCategory(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: CreateFavoritePlaceCategoryRequest,
    ): ApiResponse<FavoritePlaceCategoryDto> {
        val result = favoritePlaceService.createCategory(
            memberId = requireMemberId(principal),
            name = request.name,
            color = request.color,
            iconKey = request.iconKey,
            sortOrder = request.sortOrder,
        )
        return ApiResponse.success(result)
    }

    @Operation(summary = "즐겨찾기 장소 카테고리 수정")
    @PatchMapping("/{categoryId}")
    fun updateCategory(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable categoryId: Long,
        @RequestBody request: UpdateFavoritePlaceCategoryRequest,
    ): ApiResponse<FavoritePlaceCategoryDto> {
        val result = favoritePlaceService.updateCategory(
            memberId = requireMemberId(principal),
            categoryId = categoryId,
            name = request.name,
            color = request.color,
            iconKey = request.iconKey,
            sortOrder = request.sortOrder,
        )
        return ApiResponse.success(result)
    }

    @Operation(summary = "즐겨찾기 장소 카테고리 삭제")
    @DeleteMapping("/{categoryId}")
    fun deleteCategory(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable categoryId: Long,
    ): ApiResponse<Unit> {
        favoritePlaceService.deleteCategory(requireMemberId(principal), categoryId)
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "즐겨찾기 장소 카테고리 정렬 순서 변경")
    @PatchMapping("/reorder")
    fun reorderCategories(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: ReorderFavoritePlacesRequest,
    ): ApiResponse<List<FavoritePlaceCategoryDto>> {
        return ApiResponse.success(
            favoritePlaceService.reorderCategories(
                memberId = requireMemberId(principal),
                items = request.items.map { it.toServiceItem() },
            )
        )
    }
}

@RestController
@RequestMapping("/api/favorite-places")
@Tag(name = "Favorite Place", description = "즐겨찾기 장소 API")
class FavoritePlaceController(
    private val favoritePlaceService: FavoritePlaceService,
) {
    @Operation(summary = "즐겨찾기 장소 목록 조회")
    @GetMapping
    fun getPlaces(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<List<FavoritePlaceDto>> {
        return ApiResponse.success(favoritePlaceService.getPlaces(requireMemberId(principal)))
    }

    @Operation(summary = "기본 출발지 조회")
    @GetMapping("/default-origin")
    fun getDefaultOrigin(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<FavoritePlaceDto?> {
        return ApiResponse.success(favoritePlaceService.getDefaultOrigin(requireMemberId(principal)))
    }

    @Operation(summary = "기본 출발지 저장")
    @PutMapping("/default-origin")
    fun saveDefaultOrigin(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: SaveDefaultOriginRequest,
    ): ApiResponse<FavoritePlaceDto> {
        val result = favoritePlaceService.saveDefaultOrigin(
            memberId = requireMemberId(principal),
            label = request.label,
            placeName = request.placeName,
            address = request.address,
            lat = request.lat,
            lng = request.lng,
            provider = request.provider,
            providerPlaceId = request.providerPlaceId,
        )
        return ApiResponse.success(result)
    }

    @Operation(summary = "기본 출발지 해제")
    @DeleteMapping("/default-origin")
    fun clearDefaultOrigin(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<Unit> {
        favoritePlaceService.clearDefaultOrigin(requireMemberId(principal))
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "즐겨찾기 장소 생성")
    @PostMapping
    fun createPlace(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: CreateFavoritePlaceRequest,
    ): ApiResponse<FavoritePlaceDto> {
        val result = favoritePlaceService.createPlace(
            memberId = requireMemberId(principal),
            categoryId = request.categoryId,
            label = request.label,
            placeName = request.placeName,
            address = request.address,
            lat = request.lat,
            lng = request.lng,
            provider = request.provider,
            providerPlaceId = request.providerPlaceId,
            defaultOrigin = request.defaultOrigin,
            sortOrder = request.sortOrder,
        )
        return ApiResponse.success(result)
    }

    @Operation(summary = "즐겨찾기 장소 수정")
    @PatchMapping("/{placeId}")
    fun updatePlace(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable placeId: Long,
        @RequestBody request: UpdateFavoritePlaceRequest,
    ): ApiResponse<FavoritePlaceDto> {
        val result = favoritePlaceService.updatePlace(
            memberId = requireMemberId(principal),
            placeId = placeId,
            categoryId = request.categoryId,
            clearCategory = request.clearCategory ?: false,
            label = request.label,
            placeName = request.placeName,
            address = request.address,
            lat = request.lat,
            lng = request.lng,
            provider = request.provider,
            providerPlaceId = request.providerPlaceId,
            defaultOrigin = request.defaultOrigin,
            sortOrder = request.sortOrder,
        )
        return ApiResponse.success(result)
    }

    @Operation(summary = "즐겨찾기 장소 삭제")
    @DeleteMapping("/{placeId}")
    fun deletePlace(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable placeId: Long,
    ): ApiResponse<Unit> {
        favoritePlaceService.deletePlace(requireMemberId(principal), placeId)
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "즐겨찾기 장소를 기본 출발지로 지정")
    @PatchMapping("/{placeId}/default-origin")
    fun setDefaultOrigin(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable placeId: Long,
    ): ApiResponse<FavoritePlaceDto> {
        return ApiResponse.success(favoritePlaceService.setDefaultOrigin(requireMemberId(principal), placeId))
    }

    @Operation(summary = "즐겨찾기 장소 정렬 순서 변경")
    @PatchMapping("/reorder")
    fun reorderPlaces(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: ReorderFavoritePlacesRequest,
    ): ApiResponse<List<FavoritePlaceDto>> {
        return ApiResponse.success(
            favoritePlaceService.reorderPlaces(
                memberId = requireMemberId(principal),
                items = request.items.map { it.toServiceItem() },
            )
        )
    }
}

data class CreateFavoritePlaceCategoryRequest(
    val name: String,
    val color: String? = null,
    val iconKey: String? = null,
    val sortOrder: Int? = null,
)

data class UpdateFavoritePlaceCategoryRequest(
    val name: String? = null,
    val color: String? = null,
    val iconKey: String? = null,
    val sortOrder: Int? = null,
)

data class CreateFavoritePlaceRequest(
    val categoryId: Long? = null,
    val label: String? = null,
    val placeName: String? = null,
    val address: String? = null,
    val lat: Double,
    val lng: Double,
    val provider: String? = null,
    val providerPlaceId: String? = null,
    val defaultOrigin: Boolean? = null,
    val sortOrder: Int? = null,
)

data class SaveDefaultOriginRequest(
    val label: String? = null,
    val placeName: String? = null,
    val address: String? = null,
    val lat: Double,
    val lng: Double,
    val provider: String? = null,
    val providerPlaceId: String? = null,
)

data class UpdateFavoritePlaceRequest(
    val categoryId: Long? = null,
    val clearCategory: Boolean? = null,
    val label: String? = null,
    val placeName: String? = null,
    val address: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val provider: String? = null,
    val providerPlaceId: String? = null,
    val defaultOrigin: Boolean? = null,
    val sortOrder: Int? = null,
)

data class ReorderFavoritePlacesRequest(
    val items: List<ReorderFavoritePlaceItemRequest>,
)

data class ReorderFavoritePlaceItemRequest(
    val id: Long,
    val sortOrder: Int,
) {
    fun toServiceItem(): FavoritePlaceReorderItem {
        return FavoritePlaceReorderItem(id = id, sortOrder = sortOrder)
    }
}

private fun requireMemberId(principal: MemberPrincipal?): Long =
    principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
