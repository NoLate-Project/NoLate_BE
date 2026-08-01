package com.noLate.notification.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.service.DepartureAlarmSyncService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/notifications/departure-alarms")
@Tag(name = "Departure Alarm", description = "기기 출발 알람 desired-state 동기화 API")
class DepartureAlarmSnapshotController(
    private val departureAlarmSyncService: DepartureAlarmSyncService,
) {
    /**
     * 새 설치·토큰 교체·장기 오프라인 복구용 전체 snapshot이다.
     *
     * CANCEL tombstone도 반환하며, 응답에서 빠진 alarmId를 삭제 의미로 해석해서는 안 된다.
     */
    @Operation(summary = "내 출발 알람 desired-state snapshot 조회")
    @GetMapping("/snapshot")
    fun snapshot(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<DepartureAlarmSnapshotResponse> {
        val memberId = principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        return ApiResponse.success(
            DepartureAlarmSnapshotResponse(
                commands = departureAlarmSyncService.snapshot(memberId)
                    .map { it.toClientData() },
            )
        )
    }
}

data class DepartureAlarmSnapshotResponse(
    val commands: List<Map<String, String>>,
)
