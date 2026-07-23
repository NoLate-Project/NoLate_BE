package com.noLate.notification.controller

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.notification.application.service.AppNotificationService
import com.noLate.notification.domain.AppNotification
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notification Inbox", description = "앱 내 알림함 API")
class AppNotificationController(
    private val appNotificationService: AppNotificationService,
    private val objectMapper: ObjectMapper,
) {

    @Operation(summary = "내 앱 알림함 조회")
    @GetMapping("/inbox")
    fun getInbox(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestParam(required = false) cursorId: Long?,
        @RequestParam(defaultValue = "30") limit: Int,
        @RequestParam(defaultValue = "false") unreadOnly: Boolean,
    ): ApiResponse<AppNotificationInboxResponse> {
        val page = appNotificationService.getInbox(
            memberId = requireMemberId(principal),
            cursorId = cursorId,
            limit = limit,
            unreadOnly = unreadOnly,
        )

        return ApiResponse.success(
            AppNotificationInboxResponse(
                items = page.items.map { it.toResponse() },
                nextCursor = page.nextCursor,
                unreadCount = page.unreadCount,
            )
        )
    }

    @Operation(summary = "읽지 않은 앱 알림 개수 조회")
    @GetMapping("/unread-count")
    fun getUnreadCount(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<AppNotificationUnreadCountResponse> =
        ApiResponse.success(
            AppNotificationUnreadCountResponse(
                unreadCount = appNotificationService.getUnreadCount(requireMemberId(principal))
            )
        )

    @Operation(summary = "앱 알림 한 건 읽음 처리")
    @PatchMapping("/{notificationId}/read")
    fun markRead(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable notificationId: Long,
    ): ApiResponse<AppNotificationResponse> =
        ApiResponse.success(
            appNotificationService
                .markRead(requireMemberId(principal), notificationId)
                .toResponse()
        )

    @Operation(summary = "내 앱 알림 모두 읽음 처리")
    @PatchMapping("/read-all")
    fun markAllRead(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<AppNotificationMarkAllReadResponse> =
        ApiResponse.success(
            AppNotificationMarkAllReadResponse(
                updatedCount = appNotificationService.markAllRead(requireMemberId(principal))
            )
        )

    private fun requireMemberId(principal: MemberPrincipal?): Long =
        principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)

    private fun AppNotification.toResponse(): AppNotificationResponse =
        AppNotificationResponse(
            id = requireNotNull(id) { "저장된 앱 알림에는 id가 필요합니다." },
            type = type,
            scheduleId = scheduleId,
            categoryId = categoryId,
            title = title,
            body = body,
            data = objectMapper.readValue(
                dataJson,
                object : TypeReference<Map<String, String>>() {},
            ),
            read = isRead,
            readAt = readAt,
            createdAt = createdAt,
        )
}

data class AppNotificationInboxResponse(
    val items: List<AppNotificationResponse>,
    val nextCursor: Long?,
    val unreadCount: Long,
)

data class AppNotificationResponse(
    val id: Long,
    val type: String,
    val scheduleId: Long?,
    val categoryId: Long?,
    val title: String,
    val body: String,
    val data: Map<String, String>,
    val read: Boolean,
    val readAt: Instant?,
    val createdAt: Instant,
)

data class AppNotificationUnreadCountResponse(
    val unreadCount: Long,
)

data class AppNotificationMarkAllReadResponse(
    val updatedCount: Int,
)
