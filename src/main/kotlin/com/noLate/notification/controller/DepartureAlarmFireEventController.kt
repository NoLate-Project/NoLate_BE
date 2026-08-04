package com.noLate.notification.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.notification.application.service.DepartureAlarmFireEventResult
import com.noLate.notification.application.service.DepartureAlarmFireEventService
import com.noLate.notification.domain.DepartureAlarmFireTimingBasis
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/notifications/departure-alarm-fired-events")
@Tag(name = "Departure Alarm", description = "기기 출발 알람 실행 증거 API")
class DepartureAlarmFireEventController(
    private val service: DepartureAlarmFireEventService,
) {
    @Operation(summary = "네이티브 출발 알람 실제 실행 기록")
    @PostMapping
    fun record(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: DepartureAlarmFireEventRequest,
    ): ApiResponse<DepartureAlarmFireEventResult> {
        val memberId = principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        return ApiResponse.success(
            service.record(
                memberId = memberId,
                eventId = request.eventId,
                alarmId = request.alarmId,
                scheduleId = request.scheduleId,
                generation = request.generation,
                recipientMemberId = request.recipientMemberId,
                scheduledFor = request.scheduledFor,
                occurredAt = request.occurredAt,
                timingBasis = request.timingBasis,
                sourceTriggerAt = request.sourceTriggerAt,
                deviceId = request.deviceId,
                occurrenceId = request.occurrenceId,
            )
        )
    }
}

data class DepartureAlarmFireEventRequest(
    val eventId: String,
    val alarmId: String,
    val scheduleId: Long,
    val generation: Long,
    val recipientMemberId: Long,
    val scheduledFor: Instant,
    val occurredAt: Instant,
    val timingBasis: DepartureAlarmFireTimingBasis,
    val sourceTriggerAt: Instant? = null,
    val deviceId: String,
    /** null is retained for a legacy single-M0 fire event. */
    val occurrenceId: String? = null,
)
