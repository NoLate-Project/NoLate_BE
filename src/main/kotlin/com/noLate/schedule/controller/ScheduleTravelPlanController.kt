package com.noLate.schedule.controller

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.useCase.ScheduleTravelPlanUseCase
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import com.noLate.schedule.domain.ScheduleTravelPlanOverviewDto
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/schedules/{scheduleId}/travel-plans")
@Tag(name = "Schedule travel plan", description = "공유 일정 참가자별 이동 계획 API")
class ScheduleTravelPlanController(
    private val useCase: ScheduleTravelPlanUseCase,
) {
    @Operation(summary = "참가자별 이동 계획 요약 조회")
    @GetMapping
    fun getOverview(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
    ): ApiResponse<ScheduleTravelPlanOverviewDto> = ApiResponse.success(
        useCase.getOverview(requireMemberId(principal), scheduleId)
    )

    @Operation(summary = "권한이 허용된 참가자의 이동 계획 상세 조회")
    @GetMapping("/{memberId}")
    fun getTravelPlan(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
        @PathVariable memberId: Long,
    ): ApiResponse<ScheduleTravelPlanDto> = ApiResponse.success(
        useCase.getTravelPlan(requireMemberId(principal), scheduleId, memberId)
    )

    @Operation(summary = "내 이동 계획 생성 또는 교체")
    @PutMapping("/my")
    fun upsertMyTravelPlan(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
        @RequestBody request: ScheduleTravelPlanUpsertRequest,
    ): ApiResponse<ScheduleTravelPlanDto> = ApiResponse.success(
        useCase.upsertMyTravelPlan(requireMemberId(principal), scheduleId, request.toCommand())
    )

    private fun requireMemberId(principal: MemberPrincipal?): Long =
        principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
}

data class ScheduleTravelPlanUpsertRequest(
    val travelMinutes: Int? = null,
    val departAt: String? = null,
    val travelMode: ScheduleTravelMode? = null,
    val origin: SchedulePlaceDto? = null,
    val route: JsonNode? = null,
    val notificationEnabled: Boolean? = null,
    val notificationLeadMinutes: Int? = null,
    val notificationIntervalMinutes: Int? = null,
) {
    fun toCommand(): ScheduleTravelPlanUpsertCommand = ScheduleTravelPlanUpsertCommand(
        travelMinutes = travelMinutes,
        departAt = departAt,
        travelMode = travelMode,
        originName = origin?.name,
        originAddress = origin?.address,
        originLat = origin?.lat,
        originLng = origin?.lng,
        routeJson = route?.toString(),
        notificationEnabled = notificationEnabled == true,
        notificationLeadMinutes = notificationLeadMinutes,
        notificationIntervalMinutes = notificationIntervalMinutes,
    )
}
