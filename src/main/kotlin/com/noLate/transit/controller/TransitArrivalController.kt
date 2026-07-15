package com.noLate.transit.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/transit-arrivals")
@Tag(name = "Transit Arrival", description = "대중교통 실시간 도착 정보 API")
class TransitArrivalController(
    private val transitArrivalService: TransitArrivalService,
) {
    @Operation(summary = "서울 지하철 실시간 도착 정보 조회")
    @GetMapping("/subway")
    fun getSubwayArrivals(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestParam stationName: String,
        @RequestParam(required = false) lineName: String?,
        @RequestParam(required = false) directionName: String?,
        @RequestParam(required = false) directionCode: String?,
        @RequestParam(required = false, defaultValue = "3") limit: Int,
    ): ApiResponse<List<TransitArrivalDto>> {
        requireMemberId(principal)
        return ApiResponse.success(
            transitArrivalService.getSubwayArrivals(
                stationName = stationName,
                lineName = lineName,
                directionName = directionName,
                directionCode = directionCode,
                limit = limit,
            )
        )
    }

    @Operation(summary = "서울 버스 정류소 도착 정보 조회")
    @GetMapping("/bus")
    fun getBusArrivals(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestParam(required = false) arsId: String?,
        @RequestParam(required = false) routeName: String?,
        @RequestParam(required = false) cityCode: String?,
        @RequestParam(required = false) nodeId: String?,
        @RequestParam(required = false) stationName: String?,
        @RequestParam(required = false, defaultValue = "2") limit: Int,
    ): ApiResponse<List<TransitArrivalDto>> {
        requireMemberId(principal)
        return ApiResponse.success(
            transitArrivalService.getBusArrivals(
                arsId = arsId,
                routeName = routeName,
                cityCode = cityCode,
                nodeId = nodeId,
                stationName = stationName,
                limit = limit,
            )
        )
    }

    private fun requireMemberId(principal: MemberPrincipal?): Long =
        principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
}
