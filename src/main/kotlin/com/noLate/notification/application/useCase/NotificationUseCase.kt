package com.noLate.notification.application.useCase

import com.noLate.notification.application.PushClient
import com.noLate.notification.application.InvalidPushTokenException
import com.noLate.notification.application.service.NotificationTokenService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class NotificationUseCase(
    private val notificationTokenService: NotificationTokenService,
    private val pushClient: PushClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 단일 회원에게 푸시 발송
     */
    fun sendToMember(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ): NotificationSendResult {
        val tokens = notificationTokenService.getTokensByMember(memberId)
        var sentCount = 0
        var failedCount = 0
        var removedTokenCount = 0

        tokens.forEach { tokenEntity ->
            try {
                pushClient.sendToToken(
                    token = tokenEntity.token,
                    title = title,
                    body = body,
                    data = data
                )
                sentCount += 1
            } catch (exception: InvalidPushTokenException) {
                notificationTokenService.removeTokenValue(memberId, tokenEntity.token)
                removedTokenCount += 1
                failedCount += 1
                log.info("Removed invalid push token. memberId={}", memberId)
            } catch (exception: Exception) {
                failedCount += 1
                log.warn("Push send failed. memberId={}, tokenId={}", memberId, tokenEntity.id, exception)
            }
        }

        return NotificationSendResult(
            requestedCount = tokens.size,
            sentCount = sentCount,
            failedCount = failedCount,
            removedTokenCount = removedTokenCount,
        )
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
    ): NotificationSendResult {
        return memberIds.map { memberId ->
            sendToMember(memberId, title, body, data)
        }.fold(NotificationSendResult()) { total, result -> total + result }
    }
}

data class NotificationSendResult(
    val requestedCount: Int = 0,
    val sentCount: Int = 0,
    val failedCount: Int = 0,
    val removedTokenCount: Int = 0,
) {
    operator fun plus(other: NotificationSendResult): NotificationSendResult =
        NotificationSendResult(
            requestedCount = requestedCount + other.requestedCount,
            sentCount = sentCount + other.sentCount,
            failedCount = failedCount + other.failedCount,
            removedTokenCount = removedTokenCount + other.removedTokenCount,
        )
}
