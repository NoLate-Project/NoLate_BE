// src/main/kotlin/com/swyp/notification/controller/NotificationController.kt
package com.noLate.notification.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.domain.PushSendHistory
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.domain.PushSendStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.Instant
import org.springframework.web.bind.annotation.*
import org.springframework.security.core.annotation.AuthenticationPrincipal

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notification", description = "알림/푸시 관련 API")
class NotificationController(
    private val notificationTokenService : NotificationTokenService,
    private val notificationUseCase: NotificationUseCase,
    private val pushSendHistoryService: PushSendHistoryService,
) {

    @Operation(summary = "푸시 토큰 등록")
    @PostMapping("/token")
    fun registerToken(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: RegisterPushTokenRequest
    ): ApiResponse<Unit> {
        val authenticated = requirePrincipal(principal)
        notificationTokenService.registerToken(
            memberId = authenticated.id,
            deviceId = request.deviceId,
            platform = request.platform,
            token = request.token,
            accessTokenIssuedAt = authenticated.accessTokenIssuedAt
                ?: throw BusinessException(ErrorCode.UNAUTHORIZED),
            accessTokenSessionGeneration = authenticated.accessTokenSessionGeneration
                ?: throw BusinessException(ErrorCode.UNAUTHORIZED),
        )
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "단일 사용자 테스트 푸시 발송")
    @PostMapping("/test/send")
    fun sendTest(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: SendTestNotificationRequest
    ): ApiResponse<NotificationSendResult> {
        // 인증된 공개 API는 테스트 발송도 recipient/member withdrawal fence와 frozen manifest를
        // 통과한다. 그래야 security filter 이후 탈퇴가 commit된 요청이 token을 다시 조회해
        // provider를 호출하거나 notification row를 재생성할 수 없다.
        val result = notificationUseCase.sendToMember(
            memberId = requireMemberId(principal),
            title = request.title,
            body = request.body,
            data = request.data ?: emptyMap(),
            persistInInbox = true,
        )
        return ApiResponse.success(result)
    }

    @Operation(summary = "내 푸시 발송 이력 조회")
    @GetMapping("/send-histories")
    fun getSendHistories(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ApiResponse<List<PushSendHistoryResponse>> {
        val histories = pushSendHistoryService.getRecentByMember(
            memberId = requireMemberId(principal),
            limit = limit,
        )
        return ApiResponse.success(histories.map { it.toResponse() })
    }

    private fun requireMemberId(principal: MemberPrincipal?): Long =
        principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)

    private fun requirePrincipal(principal: MemberPrincipal?): MemberPrincipal =
        principal ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
}

data class RegisterPushTokenRequest(
    val deviceId: String?,
    val platform: PushPlatform,
    val token: String
)

data class SendTestNotificationRequest(
    val title: String,
    val body: String,
    val data: Map<String, String>? = null
)

data class PushSendHistoryResponse(
    val id: Long?,
    val memberId: Long,
    val deviceTokenId: Long?,
    val deviceId: String?,
    val platform: PushPlatform,
    val scheduleId: Long?,
    val payloadType: String?,
    val title: String,
    val body: String,
    val dataJson: String,
    val status: PushSendStatus,
    val fcmMessageId: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val sentAt: Instant,
)

private fun PushSendHistory.toResponse(): PushSendHistoryResponse =
    PushSendHistoryResponse(
        id = id,
        memberId = memberId,
        deviceTokenId = deviceTokenId,
        deviceId = deviceId,
        platform = platform,
        scheduleId = scheduleId,
        payloadType = payloadType,
        title = title,
        body = body,
        dataJson = dataJson,
        status = status,
        fcmMessageId = fcmMessageId,
        errorCode = errorCode,
        errorMessage = errorMessage,
        sentAt = sentAt,
    )
