package com.noLate.notification.application.useCase

import com.noLate.notification.application.PushClient
import com.noLate.notification.application.InvalidPushTokenException
import com.noLate.notification.application.ConfirmedPushDeliveryException
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.AppNotificationService
import com.noLate.notification.application.service.PushDeliveryClaim
import com.noLate.notification.application.service.PushDeliveryClaimOutcome
import com.noLate.notification.application.service.PushDeliveryService
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.domain.PushSendStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Component
class NotificationUseCase(
    private val notificationTokenService: NotificationTokenService,
    private val pushClient: PushClient,
    private val pushSendHistoryService: PushSendHistoryService,
    private val appNotificationService: AppNotificationService,
    private val pushDeliveryService: PushDeliveryService? = null,
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
        val inboxRecord = if (persistInInbox) {
            appNotificationService.recordWithResult(
                memberId = memberId,
                title = title,
                body = body,
                data = data,
                deduplicationKey = inboxDeduplicationKey,
            )
        } else {
            null
        }

        val tokens = notificationTokenService.getTokensByMember(memberId)
        val eventKey = inboxRecord?.notification?.id
            ?.let { "inbox:$it" }
            ?: inboxDeduplicationKey
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { "key:${it.sha256()}" }
        if (eventKey != null && pushDeliveryService != null) {
            pushDeliveryService.prepareManifest(
                memberId = memberId,
                eventKey = eventKey,
                tokens = tokens,
                data = data,
            )
        }
        var sentCount = 0
        var failedCount = 0
        var retryableFailedCount = 0
        var removedTokenCount = 0
        var attemptedCount = 0
        var alreadyDeliveredCount = 0
        var ambiguousCount = 0
        var deduplicatedCount = 0

        if (tokens.isEmpty()) {
            runCatching {
                pushSendHistoryService.recordNoToken(
                    memberId = memberId,
                    title = title,
                    body = body,
                    data = data,
                )
            }.onFailure {
                log.warn(
                    "Push no-token history persistence failed. memberId={}, errorCode={}",
                    memberId,
                    it.javaClass.simpleName,
                )
            }
        }

        tokens.forEach { tokenEntity ->
            val claim = claimDelivery(
                memberId = memberId,
                eventKey = eventKey,
                token = tokenEntity,
                inboxAlreadyExisted = inboxRecord?.created == false,
            )
            when (claim.outcome) {
                PushDeliveryClaimOutcome.ALREADY_SUCCESS -> {
                    alreadyDeliveredCount += 1
                    return@forEach
                }
                PushDeliveryClaimOutcome.AMBIGUOUS -> {
                    ambiguousCount += 1
                    return@forEach
                }
                PushDeliveryClaimOutcome.INVALID_TOKEN,
                PushDeliveryClaimOutcome.DEDUPLICATED -> {
                    deduplicatedCount += 1
                    return@forEach
                }
                PushDeliveryClaimOutcome.SEND -> Unit
            }

            attemptedCount += 1
            try {
                val sendResult = pushClient.sendToToken(
                    token = tokenEntity.token,
                    title = title,
                    body = body,
                    data = data
                )

                // provider가 성공한 뒤 로컬 기록이 실패해도 FAILED로 되돌리지 않는다.
                // 사전 커밋된 DISPATCHING 경계가 남으면 후속 실행은 AMBIGUOUS로 보고 재전송을 막는다.
                claim.deliveryId?.let { deliveryId ->
                    runCatching {
                        pushDeliveryService?.markSuccess(deliveryId, sendResult.messageId)
                    }.onFailure {
                        log.warn(
                            "Push delivery success transition failed. memberId={}, tokenId={}, deliveryId={}, errorCode={}",
                            memberId,
                            tokenEntity.id,
                            deliveryId,
                            it.javaClass.simpleName,
                        )
                    }
                }
                runCatching {
                    pushSendHistoryService.recordSuccess(
                        memberId = memberId,
                        token = tokenEntity,
                        title = title,
                        body = body,
                        data = data,
                        fcmMessageId = sendResult.messageId,
                    )
                }.onFailure {
                    log.warn(
                        "Push success history persistence failed. memberId={}, tokenId={}, errorCode={}",
                        memberId,
                        tokenEntity.id,
                        it.javaClass.simpleName,
                    )
                }
                sentCount += 1
            } catch (exception: InvalidPushTokenException) {
                val errorCode = exception.javaClass.simpleName
                val errorMessage = exception.safeMessage(tokenEntity.token)
                claim.deliveryId?.let { deliveryId ->
                    runCatching {
                        pushDeliveryService?.markInvalidToken(deliveryId, errorCode, errorMessage)
                    }.onFailure {
                        log.warn(
                            "Push invalid-token transition failed. memberId={}, tokenId={}, deliveryId={}, errorCode={}",
                            memberId,
                            tokenEntity.id,
                            deliveryId,
                            it.javaClass.simpleName,
                        )
                    }
                }
                runCatching {
                    pushSendHistoryService.recordFailure(
                        memberId = memberId,
                        token = tokenEntity,
                        title = title,
                        body = body,
                        data = data,
                        status = PushSendStatus.INVALID_TOKEN,
                        errorCode = errorCode,
                        errorMessage = errorMessage,
                    )
                }.onFailure {
                    log.warn(
                        "Push invalid-token history persistence failed. memberId={}, tokenId={}, errorCode={}",
                        memberId,
                        tokenEntity.id,
                        it.javaClass.simpleName,
                    )
                }
                val tokenRemoved = runCatching {
                    tokenEntity.id
                        ?.let { notificationTokenService.removeTokenById(memberId, it) }
                        ?: error("저장된 push token ID가 없습니다.")
                }.onFailure {
                    log.warn(
                        "Invalid push token removal failed. memberId={}, tokenId={}, errorCode={}",
                        memberId,
                        tokenEntity.id,
                        it.javaClass.simpleName,
                    )
                }.isSuccess
                if (tokenRemoved) {
                    removedTokenCount += 1
                }
                failedCount += 1
                log.info(
                    "Processed invalid push token. memberId={}, tokenId={}, deliveryId={}, removed={}",
                    memberId,
                    tokenEntity.id,
                    claim.deliveryId,
                    tokenRemoved,
                )
            } catch (exception: ConfirmedPushDeliveryException) {
                val errorCode = exception.javaClass.simpleName
                val errorMessage = exception.safeMessage(tokenEntity.token)
                val retryStatePersisted = claim.deliveryId?.let { deliveryId ->
                    runCatching {
                        pushDeliveryService?.markFailure(deliveryId, errorCode, errorMessage)
                    }.onFailure {
                        log.warn(
                            "Push retry state persistence failed. memberId={}, tokenId={}, deliveryId={}, errorCode={}",
                            memberId,
                            tokenEntity.id,
                            deliveryId,
                            it.javaClass.simpleName,
                        )
                    }.isSuccess
                } ?: true
                runCatching {
                    pushSendHistoryService.recordFailure(
                        memberId = memberId,
                        token = tokenEntity,
                        title = title,
                        body = body,
                        data = data,
                        status = PushSendStatus.FAILED,
                        errorCode = errorCode,
                        errorMessage = errorMessage,
                    )
                }.onFailure {
                    log.warn(
                        "Push failure history persistence failed. memberId={}, tokenId={}, errorCode={}",
                        memberId,
                        tokenEntity.id,
                        it.javaClass.simpleName,
                    )
                }
                failedCount += 1
                if (retryStatePersisted) {
                    retryableFailedCount += 1
                } else {
                    // provider 미수락은 확인했지만 FAILED 전이를 잃었다. 영속 상태가
                    // DISPATCHING이므로 자동 재시도할 수 없는 ambiguous 경계로 보고한다.
                    ambiguousCount += 1
                }
                // provider 예외 및 cause에는 token 원문이 포함될 수 있어 stack trace를 로그에 싣지 않는다.
                log.warn(
                    "Push send failed. memberId={}, tokenId={}, deliveryId={}, errorCode={}, retryable={}",
                    memberId,
                    tokenEntity.id,
                    claim.deliveryId,
                    errorCode,
                    retryStatePersisted,
                )
            } catch (exception: Exception) {
                val errorCode = exception.javaClass.simpleName
                val errorMessage = exception.safeMessage(tokenEntity.token)
                // 요청 수락 여부를 증명할 수 없는 transport/local 예외다. 사전 커밋한
                // DISPATCHING을 그대로 두고 UNKNOWN 이력만 남겨 자동 재전송을 막는다.
                runCatching {
                    pushSendHistoryService.recordFailure(
                        memberId = memberId,
                        token = tokenEntity,
                        title = title,
                        body = body,
                        data = data,
                        status = PushSendStatus.UNKNOWN,
                        errorCode = errorCode,
                        errorMessage = errorMessage,
                    )
                }.onFailure {
                    log.warn(
                        "Push unknown-outcome history persistence failed. memberId={}, tokenId={}, errorCode={}",
                        memberId,
                        tokenEntity.id,
                        it.javaClass.simpleName,
                    )
                }
                ambiguousCount += 1
                log.warn(
                    "Push outcome unknown. memberId={}, tokenId={}, deliveryId={}, errorCode={}, retryable=false",
                    memberId,
                    tokenEntity.id,
                    claim.deliveryId,
                    errorCode,
                )
            }
        }

        return NotificationSendResult(
            requestedCount = tokens.size,
            attemptedCount = attemptedCount,
            sentCount = sentCount,
            failedCount = failedCount,
            retryableFailedCount = retryableFailedCount,
            removedTokenCount = removedTokenCount,
            alreadyDeliveredCount = alreadyDeliveredCount,
            ambiguousCount = ambiguousCount,
            deduplicatedCount = deduplicatedCount,
            inboxDeduplicated = inboxRecord?.created == false,
        )
    }

    private fun claimDelivery(
        memberId: Long,
        eventKey: String?,
        token: com.noLate.notification.domain.NotificationDeviceToken,
        inboxAlreadyExisted: Boolean,
    ): PushDeliveryClaim {
        if (eventKey == null) {
            return PushDeliveryClaim(PushDeliveryClaimOutcome.SEND)
        }
        val service = pushDeliveryService
            ?: return if (inboxAlreadyExisted) {
                PushDeliveryClaim(PushDeliveryClaimOutcome.DEDUPLICATED)
            } else {
                PushDeliveryClaim(PushDeliveryClaimOutcome.SEND)
            }
        return service.claim(
            memberId = memberId,
            eventKey = eventKey,
            token = token,
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
    /** 이번 호출에서 실제 provider API를 호출한 기기 수 */
    val attemptedCount: Int = 0,
    val sentCount: Int = 0,
    val failedCount: Int = 0,
    /** provider가 명시적으로 미수락해 동일 event/device로 재시도할 수 있는 실패 수 */
    val retryableFailedCount: Int = 0,
    val removedTokenCount: Int = 0,
    val alreadyDeliveredCount: Int = 0,
    /** provider 호출 전 경계만 남아 성공 여부가 모호해 재전송하지 않은 기기 수 */
    val ambiguousCount: Int = 0,
    val deduplicatedCount: Int = 0,
    val inboxDeduplicated: Boolean = false,
) {
    /**
     * 새 성공, 과거 성공, 모호한 호출 경계, inbox dedupe 중 하나가 있으면 동일 이벤트를
     * 다시 provider로 보내지 않고 schedule job 상태를 전진시킬 수 있다.
     */
    val durablyHandledCount: Int
        get() = sentCount + alreadyDeliveredCount + ambiguousCount + deduplicatedCount

    val confirmedSuccessCount: Int
        get() = sentCount + alreadyDeliveredCount

    operator fun plus(other: NotificationSendResult): NotificationSendResult =
        NotificationSendResult(
            requestedCount = requestedCount + other.requestedCount,
            attemptedCount = attemptedCount + other.attemptedCount,
            sentCount = sentCount + other.sentCount,
            failedCount = failedCount + other.failedCount,
            retryableFailedCount = retryableFailedCount + other.retryableFailedCount,
            removedTokenCount = removedTokenCount + other.removedTokenCount,
            alreadyDeliveredCount = alreadyDeliveredCount + other.alreadyDeliveredCount,
            ambiguousCount = ambiguousCount + other.ambiguousCount,
            deduplicatedCount = deduplicatedCount + other.deduplicatedCount,
            inboxDeduplicated = inboxDeduplicated || other.inboxDeduplicated,
        )
}

private fun Throwable.safeMessage(token: String): String =
    (message ?: javaClass.simpleName)
        .let { raw -> if (token.isEmpty()) raw else raw.replace(token, "[REDACTED]") }
        .take(1000)

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
