package com.noLate.notification.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.notification.application.service.DepartureAlarmScheduleReceiptResult
import com.noLate.notification.application.service.DepartureAlarmScheduleReceiptService
import com.noLate.notification.domain.DepartureAlarmScheduleOutcome
import com.noLate.notification.domain.DepartureAlarmDeliveryMode
import com.noLate.notification.domain.DepartureAlarmScheduleSource
import com.noLate.notification.domain.PushPlatform
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/notifications/departure-alarm-schedule-receipts")
class DepartureAlarmScheduleReceiptController(
    private val service: DepartureAlarmScheduleReceiptService,
) {
    @PostMapping
    fun record(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: DepartureAlarmScheduleReceiptRequest,
    ): ApiResponse<DepartureAlarmScheduleReceiptResult> = ApiResponse.success(
        service.record(
            memberId = principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED),
            receiptId = request.receiptId,
            alarmId = request.alarmId,
            scheduleId = request.scheduleId,
            generation = request.generation,
            recipientMemberId = request.recipientMemberId,
            operation = request.operation,
            triggerAt = request.triggerAt,
            outcome = request.outcome,
            applied = request.applied,
            scheduled = request.scheduled,
            platform = request.platform,
            deliveryMode = request.deliveryMode,
            source = request.source,
            reason = request.reason,
            occurredAt = request.occurredAt,
            deviceId = request.deviceId,
        )
    )
}

data class DepartureAlarmScheduleReceiptRequest(
    val receiptId: String,
    val alarmId: String,
    val scheduleId: Long,
    val generation: Long,
    val recipientMemberId: Long,
    val operation: DepartureAlarmSyncOperation,
    val triggerAt: Instant? = null,
    val outcome: DepartureAlarmScheduleOutcome,
    val applied: Boolean,
    val scheduled: Boolean,
    val platform: PushPlatform,
    val deliveryMode: DepartureAlarmDeliveryMode,
    val source: DepartureAlarmScheduleSource,
    val reason: String? = null,
    val occurredAt: Instant,
    val deviceId: String,
)
