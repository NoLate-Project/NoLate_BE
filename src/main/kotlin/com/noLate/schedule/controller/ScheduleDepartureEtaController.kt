package com.noLate.schedule.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.service.ScheduleDepartureEtaService
import com.noLate.schedule.domain.ScheduleDepartureEtaStatusDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/schedules/{scheduleId}/departure-status")
@Tag(name = "Schedule departure status", description = "현재 회원의 출발 ETA 상태 API")
class ScheduleDepartureEtaController(
    private val departureEtaService: ScheduleDepartureEtaService,
) {
    @Operation(summary = "현재 회원의 출발 ETA 상태 조회")
    @GetMapping
    fun getDepartureStatus(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
    ): ApiResponse<ScheduleDepartureEtaStatusDto> =
        ApiResponse.success(
            departureEtaService.getDepartureStatus(
                memberId = principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED),
                scheduleId = scheduleId,
            )
        )
}
