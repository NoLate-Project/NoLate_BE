package com.noLate.schedule.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.schedule.application.service.ScheduleDepartureNotificationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/schedules/{scheduleId}/departure-nudges")
@Tag(name = "Schedule departure notification", description = "공유 일정 참가자 출발 확인 알림 API")
class ScheduleDepartureNotificationController(
    private val service: ScheduleDepartureNotificationService,
) {

    @Operation(summary = "특정 공유 참가자에게 출발 확인 푸시 전송")
    @PostMapping("/{targetMemberId}")
    fun sendDepartureNudge(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
        @PathVariable targetMemberId: Long,
    ): ApiResponse<NotificationSendResult> = ApiResponse.success(
        service.sendDepartureNudge(
            ownerMemberId = principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED),
            scheduleId = scheduleId,
            targetMemberId = targetMemberId,
        )
    )
}
