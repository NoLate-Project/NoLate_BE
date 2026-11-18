package com.noLate.route.controller

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.route.application.TransitRouteService
import com.noLate.route.application.TransitBusPreviewRequest
import com.noLate.route.application.TransitBusPreviewResponse
import com.noLate.route.application.TransitTimetablePreviewRequest
import com.noLate.route.application.TransitTimetablePreviewResponse
import com.noLate.route.application.TransitTimetablePreviewService
import com.noLate.route.domain.TransitRouteRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/routes")
@Tag(name = "Route", description = "서버 기반 길찾기 API")
class TransitRouteController(
    private val transitRouteService: TransitRouteService,
    private val timetablePreviewService: TransitTimetablePreviewService,
) {
    @Operation(summary = "대중교통 경로 조회")
    @PostMapping("/transit")
    fun getTransitRoutes(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: TransitRouteRequest,
    ): ApiResponse<JsonNode> {
        requireMember(principal)
        return ApiResponse.success(transitRouteService.getRoutes(request))
    }

    @Operation(summary = "선택한 ODsay 지하철 경로의 일정 도착 시간표")
    @PostMapping("/transit/timetable-preview")
    fun previewTransitTimetable(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: TransitTimetablePreviewRequest,
    ): ApiResponse<TransitTimetablePreviewResponse> {
        requireMember(principal)
        return ApiResponse.success(timetablePreviewService.preview(request))
    }

    @Operation(summary = "선택한 ODsay 버스 경로의 실시간 승하차 예측")
    @PostMapping("/transit/bus-preview")
    fun previewTransitBus(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: TransitBusPreviewRequest,
    ): ApiResponse<TransitBusPreviewResponse> {
        requireMember(principal)
        return ApiResponse.success(TransitBusPreviewResponse(timetablePreviewService.previewBus(request)))
    }

    private fun requireMember(principal: MemberPrincipal?) {
        if (principal == null) throw BusinessException(ErrorCode.UNAUTHORIZED)
    }
}
