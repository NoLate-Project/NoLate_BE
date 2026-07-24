package com.noLate.notification.application.useCase

import com.noLate.notification.application.PushClient
import com.noLate.notification.application.InvalidPushTokenException
import com.noLate.notification.application.ConfirmedPushDeliveryException
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.AppNotificationService
import com.noLate.notification.application.service.PushDeliveryClaim
import com.noLate.notification.application.service.PushDeliveryClaimOutcome
import com.noLate.notification.application.service.PushDeliveryService
import com.noLate.notification.application.service.PushDispatchFence
import com.noLate.notification.application.service.PushEventOutboxService
import com.noLate.notification.application.service.AppNotificationSnapshot
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.domain.PushLogicalEventKey
import com.noLate.notification.domain.PushSendStatus
import com.noLate.notification.domain.withPushAccountBinding
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class NotificationUseCase(
    private val notificationTokenService: NotificationTokenService,
    private val pushClient: PushClient,
    private val pushSendHistoryService: PushSendHistoryService,
    private val appNotificationService: AppNotificationService,
    private val pushDeliveryService: PushDeliveryService? = null,
    private val pushEventOutboxService: PushEventOutboxService? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun findPersistedEvent(
        memberId: Long,
        inboxDeduplicationKey: String,
    ): AppNotificationSnapshot? =
        appNotificationService.findSnapshot(memberId, inboxDeduplicationKey)

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
    ): NotificationSendResult =
        sendToMemberInternal(
            memberId,
            title,
            body,
            data,
            inboxDeduplicationKey,
            persistInInbox,
            dispatchFence = null,
        )

    fun sendToMemberFenced(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        inboxDeduplicationKey: String,
        persistInInbox: Boolean = true,
        dispatchFence: PushDispatchFence,
    ): NotificationSendResult =
        sendToMemberInternal(
            memberId,
            title,
            body,
            data,
            inboxDeduplicationKey,
            persistInInbox,
            dispatchFence,
        )

    private fun sendToMemberInternal(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        inboxDeduplicationKey: String?,
        persistInInbox: Boolean,
        dispatchFence: PushDispatchFence?,
    ): NotificationSendResult {
        val tokens = notificationTokenService.getTokensByMember(memberId)
        val preparedEvent = pushEventOutboxService?.prepare(
            memberId = memberId,
            title = title,
            body = body,
            data = data,
            deduplicationKey = inboxDeduplicationKey,
            persistInInbox = persistInInbox,
            tokens = tokens,
            fence = dispatchFence,
        )
        if (preparedEvent?.fenceAccepted == false) {
            return NotificationSendResult(fenceRejected = true)
        }

        // 사용자 알림은 기기 토큰과 무관한 논리 이벤트다. FCM 조회보다 먼저 한 번 저장하면
        // 토큰이 없거나 모든 기기 발송이 실패해도 앱 안에서 나중에 확인할 수 있다.
        val inboxRecord = if (preparedEvent == null && persistInInbox) {
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
        val fallbackEventKey = inboxRecord?.notification?.logicalEventKey
            ?: inboxDeduplicationKey
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { PushLogicalEventKey.deterministic(memberId, it) }
            ?: PushLogicalEventKey.newEvent()
        val eventKey = preparedEvent?.logicalEventKey ?: fallbackEventKey
        val eventSnapshot = preparedEvent?.snapshot
        val effectiveTitle = eventSnapshot?.title ?: title
        val effectiveBody = eventSnapshot?.body ?: body
        val effectiveData = eventSnapshot?.data
            ?: data.withPushAccountBinding(eventKey, memberId)
        if (preparedEvent == null && pushDeliveryService != null) {
            pushDeliveryService.prepareManifest(
                memberId = memberId,
                eventKey = eventKey,
                tokens = tokens,
                data = effectiveData,
            )
        }
        var sentCount = 0
        var failedCount = 0
        var retryableFailedCount = 0
        var removedTokenCount = 0
        var invalidTokenCount = 0
        var attemptedCount = 0
        var alreadyDeliveredCount = 0
        var ambiguousCount = 0
        var deduplicatedCount = 0
        var supersededCount = 0
        var fenceRejected = false
        var alreadyDeliveredAt: Instant? = null

        if (tokens.isEmpty()) {
            runCatching {
                pushSendHistoryService.recordNoToken(
                    memberId = memberId,
                    title = effectiveTitle,
                    body = effectiveBody,
                    data = effectiveData,
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
                dispatchFence = dispatchFence,
            )
            when (claim.outcome) {
                PushDeliveryClaimOutcome.ALREADY_SUCCESS -> {
                    alreadyDeliveredCount += 1
                    alreadyDeliveredAt = listOfNotNull(alreadyDeliveredAt, claim.deliveredAt).maxOrNull()
                    return@forEach
                }
                PushDeliveryClaimOutcome.AMBIGUOUS -> {
                    ambiguousCount += 1
                    return@forEach
                }
                PushDeliveryClaimOutcome.INVALID_TOKEN -> {
                    invalidTokenCount += 1
                    return@forEach
                }
                PushDeliveryClaimOutcome.DEDUPLICATED -> {
                    deduplicatedCount += 1
                    return@forEach
                }
                PushDeliveryClaimOutcome.SUPERSEDED -> {
                    supersededCount += 1
                    return@forEach
                }
                PushDeliveryClaimOutcome.FENCE_REJECTED -> {
                    fenceRejected = true
                    return@forEach
                }
                PushDeliveryClaimOutcome.SEND -> Unit
            }

            attemptedCount += 1
            val providerToken = requireNotNull(claim.providerToken) {
                "SEND claim에는 검증된 provider token이 필요합니다."
            }
            try {
                val sendResult = pushClient.sendToToken(
                    token = providerToken,
                    title = effectiveTitle,
                    body = effectiveBody,
                    data = effectiveData,
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
                        title = effectiveTitle,
                        body = effectiveBody,
                        data = effectiveData,
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
                val errorMessage = exception.safeMessage(providerToken)
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
                        title = effectiveTitle,
                        body = effectiveBody,
                        data = effectiveData,
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
                    notificationTokenService.removeTokenByOwnership(
                        memberId = memberId,
                        tokenId = requireNotNull(claim.tokenId),
                        tokenFingerprint = requireNotNull(claim.tokenFingerprint),
                        ownershipVersion = requireNotNull(claim.tokenOwnershipVersion),
                    )
                }.onFailure {
                    log.warn(
                        "Invalid push token removal failed. memberId={}, tokenId={}, errorCode={}",
                        memberId,
                        tokenEntity.id,
                        it.javaClass.simpleName,
                    )
                }.getOrDefault(false)
                if (tokenRemoved) {
                    removedTokenCount += 1
                }
                invalidTokenCount += 1
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
                val errorMessage = exception.safeMessage(providerToken)
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
                        title = effectiveTitle,
                        body = effectiveBody,
                        data = effectiveData,
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
                val errorMessage = exception.safeMessage(providerToken)
                // 요청 수락 여부를 증명할 수 없는 transport/local 예외다. 사전 커밋한
                // DISPATCHING을 그대로 두고 UNKNOWN 이력만 남겨 자동 재전송을 막는다.
                runCatching {
                    pushSendHistoryService.recordFailure(
                        memberId = memberId,
                        token = tokenEntity,
                        title = effectiveTitle,
                        body = effectiveBody,
                        data = effectiveData,
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
            invalidTokenCount = invalidTokenCount,
            alreadyDeliveredCount = alreadyDeliveredCount,
            ambiguousCount = ambiguousCount,
            deduplicatedCount = deduplicatedCount,
            supersededCount = supersededCount,
            fenceRejected = fenceRejected,
            alreadyDeliveredAt = alreadyDeliveredAt,
            eventSnapshot = eventSnapshot,
            inboxDeduplicated =
                preparedEvent?.let { persistInInbox && !it.inboxCreated }
                    ?: (inboxRecord?.created == false),
        )
    }

    private fun claimDelivery(
        memberId: Long,
        eventKey: String?,
        token: com.noLate.notification.domain.NotificationDeviceToken,
        inboxAlreadyExisted: Boolean,
        dispatchFence: PushDispatchFence?,
    ): PushDeliveryClaim {
        if (eventKey == null) {
            return token.directSendClaim()
        }
        val service = pushDeliveryService
            ?: return if (inboxAlreadyExisted) {
                PushDeliveryClaim(PushDeliveryClaimOutcome.DEDUPLICATED)
            } else {
                token.directSendClaim()
            }
        return service.claim(
            memberId = memberId,
            eventKey = eventKey,
            token = token,
            fence = dispatchFence,
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
    /** provider가 확정 무효로 판정했거나 이미 INVALID_TOKEN terminal인 기기 수 */
    val invalidTokenCount: Int = 0,
    val alreadyDeliveredCount: Int = 0,
    /** provider 호출 전 경계만 남아 성공 여부가 모호해 재전송하지 않은 기기 수 */
    val ambiguousCount: Int = 0,
    val deduplicatedCount: Int = 0,
    /** 현재 token ownership과 manifest snapshot이 달라 외부 호출 없이 terminal 처리한 수 */
    val supersededCount: Int = 0,
    val fenceRejected: Boolean = false,
    /** ALREADY_SUCCESS 재조회 시 원래 provider 성공 시각 */
    val alreadyDeliveredAt: Instant? = null,
    val eventSnapshot: AppNotificationSnapshot? = null,
    val inboxDeduplicated: Boolean = false,
) {
    /**
     * 새 성공, 과거 성공, 모호한 호출 경계, inbox dedupe 중 하나가 있으면 동일 이벤트를
     * 다시 provider로 보내지 않고 schedule job 상태를 전진시킬 수 있다.
     */
    val durablyHandledCount: Int
        get() =
            sentCount + alreadyDeliveredCount + ambiguousCount + deduplicatedCount +
                invalidTokenCount + supersededCount

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
            invalidTokenCount = invalidTokenCount + other.invalidTokenCount,
            alreadyDeliveredCount = alreadyDeliveredCount + other.alreadyDeliveredCount,
            ambiguousCount = ambiguousCount + other.ambiguousCount,
            deduplicatedCount = deduplicatedCount + other.deduplicatedCount,
            supersededCount = supersededCount + other.supersededCount,
            fenceRejected = fenceRejected || other.fenceRejected,
            alreadyDeliveredAt = listOfNotNull(alreadyDeliveredAt, other.alreadyDeliveredAt).maxOrNull(),
            eventSnapshot = eventSnapshot ?: other.eventSnapshot,
            inboxDeduplicated = inboxDeduplicated || other.inboxDeduplicated,
        )
}

private fun Throwable.safeMessage(token: String): String =
    (message ?: javaClass.simpleName)
        .let { raw -> if (token.isEmpty()) raw else raw.replace(token, "[REDACTED]") }
        .take(1000)

private fun com.noLate.notification.domain.NotificationDeviceToken.directSendClaim(): PushDeliveryClaim =
    PushDeliveryClaim(
        outcome = PushDeliveryClaimOutcome.SEND,
        providerToken = token,
        tokenId = id,
        tokenFingerprint = tokenFingerprint,
        tokenOwnershipVersion = ownershipVersion,
    )
