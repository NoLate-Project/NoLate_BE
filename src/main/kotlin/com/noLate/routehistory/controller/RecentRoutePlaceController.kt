package com.noLate.routehistory.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.routehistory.application.service.RecentRoutePlaceService
import com.noLate.routehistory.domain.RecentRoutePlaceDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/recent-route-places")
@Tag(name = "Recent Route Place", description = "최근 경로 검색 장소 API")
class RecentRoutePlaceController(
    private val recentRoutePlaceService: RecentRoutePlaceService,
) {
    @Operation(summary = "최근 경로 검색 장소 목록 조회")
    @GetMapping
    fun getRecentPlaces(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<List<RecentRoutePlaceDto>> {
        return ApiResponse.success(
            recentRoutePlaceService.getRecentPlaces(
                memberId = requireMemberId(principal),
                limit = limit,
            )
        )
    }

    @Operation(summary = "최근 경로 검색 장소 저장")
    @PostMapping
    fun saveRecentPlace(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: SaveRecentRoutePlaceRequest,
    ): ApiResponse<RecentRoutePlaceDto> {
        val result = recentRoutePlaceService.saveRecentPlace(
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

    @Operation(summary = "최근 경로 검색 장소 삭제")
    @DeleteMapping("/{recentPlaceId}")
    fun deleteRecentPlace(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable recentPlaceId: Long,
    ): ApiResponse<Unit> {
        recentRoutePlaceService.deleteRecentPlace(
            memberId = requireMemberId(principal),
            recentPlaceId = recentPlaceId,
        )
        return ApiResponse.success(Unit)
    }
}

data class SaveRecentRoutePlaceRequest(
    val label: String? = null,
    val placeName: String? = null,
    val address: String? = null,
    val lat: Double,
    val lng: Double,
    val provider: String? = null,
    val providerPlaceId: String? = null,
)

private fun requireMemberId(principal: MemberPrincipal?): Long =
    principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
