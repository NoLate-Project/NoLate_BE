package com.swyp.notification.application.useCase

import com.swyp.notification.application.PushClient
import com.swyp.notification.application.service.NotificationTokenService
import org.springframework.stereotype.Component

@Component
class NotificationUseCase(
    private val notificationTokenService: NotificationTokenService,
    private val pushClient: PushClient
) {
    /**
     * 단일 회원에게 푸시 발송
     */
    fun sendToMember(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ) {
        val tokens = notificationTokenService.getTokensByMember(memberId)

        tokens.forEach { tokenEntity ->
            pushClient.sendToToken(
                token = tokenEntity.token,
                title = title,
                body = body,
                data = data
            )
        }
    }

    /**
     * 여러 회원에게 동일한 내용 푸시 발송
     * (간단하게 memberId 루프 돌려서 재사용)
     */
    fun sendToMembers(
        memberIds: List<Long>,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ) {
        memberIds.forEach { memberId ->
            sendToMember(memberId, title, body, data)
        }
    }
}