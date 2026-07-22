package com.noLate.notification.application.useCase

import com.noLate.notification.application.PushClient
import com.noLate.notification.application.InvalidPushTokenException
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.AppNotificationService
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.domain.PushSendStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class NotificationUseCase(
    private val notificationTokenService: NotificationTokenService,
    private val pushClient: PushClient,
    private val pushSendHistoryService: PushSendHistoryService,
    private val appNotificationService: AppNotificationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 단일 회원에게 푸시 발송
     */
    fun sendToMember(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
        inboxDeduplicationKey: String? = null,
        persistInInbox: Boolean = true,
    ): NotificationSendResult {
        // 사용자 알림은 기기 토큰과 무관한 논리 이벤트다. FCM 조회보다 먼저 한 번 저장하면
        // 토큰이 없거나 모든 기기 발송이 실패해도 앱 안에서 나중에 확인할 수 있다.
        if (persistInInbox) {
            appNotificationService.record(
                memberId = memberId,
                title = title,
                body = body,
                data = data,
                deduplicationKey = inboxDeduplicationKey,
            )
        }

        val tokens = notificationTokenService.getTokensByMember(memberId)
        var sentCount = 0
        var failedCount = 0
        var removedTokenCount = 0

        if (tokens.isEmpty()) {
            pushSendHistoryService.recordNoToken(
                memberId = memberId,
                title = title,
                body = body,
                data = data,
            )
        }

        tokens.forEach { tokenEntity ->
            try {
                val sendResult = pushClient.sendToToken(
                    token = tokenEntity.token,
                    title = title,
                    body = body,
                    data = data
                )
                pushSendHistoryService.recordSuccess(
                    memberId = memberId,
                    token = tokenEntity,
                    title = title,
                    body = body,
                    data = data,
                    fcmMessageId = sendResult.messageId,
                )
                sentCount += 1
            } catch (exception: InvalidPushTokenException) {
                pushSendHistoryService.recordFailure(
                    memberId = memberId,
                    token = tokenEntity,
                    title = title,
                    body = body,
                    data = data,
                    status = PushSendStatus.INVALID_TOKEN,
                    errorCode = exception.javaClass.simpleName,
                    errorMessage = exception.message,
                )
                notificationTokenService.removeTokenValue(memberId, tokenEntity.token)
                removedTokenCount += 1
                failedCount += 1
                log.info("Removed invalid push token. memberId={}", memberId)
            } catch (exception: Exception) {
                pushSendHistoryService.recordFailure(
                    memberId = memberId,
                    token = tokenEntity,
                    title = title,
                    body = body,
                    data = data,
                    status = PushSendStatus.FAILED,
                    errorCode = exception.javaClass.simpleName,
                    errorMessage = exception.message,
                )
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
        data: Map<String, String> = emptyMap(),
        inboxDeduplicationKey: String? = null,
        persistInInbox: Boolean = true,
    ): NotificationSendResult {
        return memberIds.map { memberId ->
            sendToMember(
                memberId = memberId,
                title = title,
                body = body,
                data = data,
                inboxDeduplicationKey = inboxDeduplicationKey,
                persistInInbox = persistInInbox,
            )
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
