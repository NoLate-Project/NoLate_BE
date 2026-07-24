package com.noLate.notification.application.useCase

import com.noLate.notification.application.ConfirmedPushDeliveryException
import com.noLate.notification.application.InvalidPushTokenException
import com.noLate.notification.application.service.AppNotificationService
import com.noLate.notification.application.service.AppNotificationSnapshot
import com.noLate.notification.application.service.AuthenticatedPushSessionFence
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.PersistedPushDispatchFenceFactory
import com.noLate.notification.application.service.PreparedPushEvent
import com.noLate.notification.application.service.PushDeliveryClaim
import com.noLate.notification.application.service.PushDeliveryClaimOutcome
import com.noLate.notification.application.service.PushDeliveryFailureTransition
import com.noLate.notification.application.service.PushDeliveryService
import com.noLate.notification.application.service.PushDispatchFence
import com.noLate.notification.application.service.PushEventOutboxService
import com.noLate.notification.application.service.PushOutboxDispatchLease
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.application.service.PushTokenProviderLeaseOutcome
import com.noLate.notification.application.service.PushTokenProviderLeaseService
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushLogicalEventKey
import com.noLate.notification.domain.PushSendStatus
import com.noLate.notification.domain.withPushAccountBinding
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Push provider 호출 orchestration.
 *
 * 사용자 알림은 [PushEventOutboxService]가 최초 transaction에서 payload와 recipient
 * delivery manifest를 함께 동결한다. 이후 redrive는 현재 token 목록을 다시 조회하거나
 * 새 기기를 manifest에 추가하지 않고, 오직 영속 delivery PK를 순회한다.
 */
@Component
class NotificationUseCase(
    private val notificationTokenService: NotificationTokenService,
    private val pushTokenProviderLeaseService: PushTokenProviderLeaseService,
    private val pushSendHistoryService: PushSendHistoryService,
    private val appNotificationService: AppNotificationService,
    private val pushDeliveryService: PushDeliveryService,
    private val pushEventOutboxService: PushEventOutboxService,
    private val persistedPushDispatchFenceFactory: PersistedPushDispatchFenceFactory? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun findPersistedEvent(
        memberId: Long,
        inboxDeduplicationKey: String,
    ): AppNotificationSnapshot? =
        pushEventOutboxService.findSnapshot(memberId, inboxDeduplicationKey)
            ?: appNotificationService.findSnapshot(memberId, inboxDeduplicationKey)

    fun sendToMember(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
        inboxDeduplicationKey: String? = null,
        persistInInbox: Boolean = true,
    ): NotificationSendResult =
        if (persistInInbox) {
            prepareAndDispatch(
                memberId = memberId,
                title = title,
                body = body,
                data = data,
                inboxDeduplicationKey = inboxDeduplicationKey,
                dispatchFence = null,
            )
        } else {
            sendEphemeral(memberId, title, body, data)
        }

    /**
     * Public access-authenticated test send.
     *
     * Unlike worker/internal sends, this mutation must still own the signed access session at the
     * outbox write, delivery claim, and final provider ownership lease. Its durable source key
     * retains that fence for confirmed-failure redrives without exposing the generation in the
     * client payload.
     */
    fun sendAuthenticatedToMember(
        memberId: Long,
        presentedSessionGeneration: Long,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
    ): NotificationSendResult {
        val sessionFence = AuthenticatedPushSessionFence(
            memberId = memberId,
            sessionGeneration = presentedSessionGeneration,
        )
        return prepareAndDispatch(
            memberId = memberId,
            title = title,
            body = body,
            data = data,
            inboxDeduplicationKey = sessionFence.newDeduplicationKey(),
            dispatchFence = null,
            sessionFence = sessionFence,
        )
    }

    fun sendToMemberFenced(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        inboxDeduplicationKey: String,
        persistInInbox: Boolean = true,
        dispatchFence: PushDispatchFence,
    ): NotificationSendResult {
        require(persistInInbox) {
            "A fenced notification must use the durable outbox."
        }
        return prepareAndDispatch(
            memberId = memberId,
            title = title,
            body = body,
            data = data,
            inboxDeduplicationKey = inboxDeduplicationKey,
            dispatchFence = dispatchFence,
        )
    }

    /**
     * Durable share/departure drainer entry point. The payload and recipients are loaded from the
     * frozen outbox; caller-supplied live data is intentionally impossible.
     */
    fun redrivePersistedEvent(
        memberId: Long,
        logicalEventKey: String,
        sourceLease: PushOutboxDispatchLease? = null,
    ): NotificationSendResult {
        val prepared = pushEventOutboxService.loadPersisted(memberId, logicalEventKey)
            ?: throw IllegalStateException("Persisted push event does not exist.")
        val recoveredFence = prepared.snapshot
            ?.let { persistedPushDispatchFenceFactory?.create(it) }
        val recoveredSessionFence = prepared.snapshot
            ?.let {
                AuthenticatedPushSessionFence.restore(
                    memberId = memberId,
                    deduplicationKey = it.deduplicationKey,
                )
            }
        return dispatchPrepared(
            prepared = prepared,
            dispatchFence = recoveredFence,
            sourceLease = sourceLease,
            sessionFence = recoveredSessionFence,
        )
    }

    private fun prepareAndDispatch(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        inboxDeduplicationKey: String?,
        dispatchFence: PushDispatchFence?,
        sessionFence: AuthenticatedPushSessionFence? = null,
    ): NotificationSendResult {
        val prepared = pushEventOutboxService.prepare(
            memberId = memberId,
            title = title,
            body = body,
            data = data,
            deduplicationKey = inboxDeduplicationKey,
            persistInInbox = true,
            fence = dispatchFence,
            sessionFence = sessionFence,
        )
        if (!prepared.recipientActive) {
            return NotificationSendResult(recipientInactive = true)
        }
        if (!prepared.fenceAccepted) {
            return NotificationSendResult(fenceRejected = true)
        }
        return dispatchPrepared(prepared, dispatchFence, sessionFence = sessionFence)
    }

    private fun dispatchPrepared(
        prepared: PreparedPushEvent,
        dispatchFence: PushDispatchFence?,
        sourceLease: PushOutboxDispatchLease? = null,
        sessionFence: AuthenticatedPushSessionFence? = null,
    ): NotificationSendResult {
        val snapshot = requireNotNull(prepared.snapshot) {
            "A frozen push event must contain its immutable payload."
        }
        val memberId = snapshot.data["recipientMemberId"]?.toLongOrNull()
            ?: throw IllegalStateException("Persisted push event has no recipient binding.")
        check(snapshot.logicalEventKey == prepared.logicalEventKey) {
            "Persisted push event identity does not match its manifest."
        }

        if (prepared.emptyManifest) {
            recordNoToken(memberId, snapshot)
            return NotificationSendResult(
                requestedCount = 0,
                noDeviceEventCount = 1,
                eventSnapshot = snapshot,
                inboxDeduplicated = !prepared.inboxCreated,
            )
        }

        var result = NotificationSendResult(
            requestedCount = prepared.manifestRecipientCount,
            eventSnapshot = snapshot,
            inboxDeduplicated = !prepared.inboxCreated,
        )
        prepared.deliveryIds.forEach { deliveryId ->
            val claim = pushDeliveryService.claim(
                memberId = memberId,
                eventKey = prepared.logicalEventKey,
                deliveryId = deliveryId,
                fence = dispatchFence,
                sessionFence = sessionFence,
            )
            result += when (claim.outcome) {
                PushDeliveryClaimOutcome.SEND ->
                    sendClaimed(
                        memberId,
                        snapshot,
                        claim,
                        dispatchFence,
                        sourceLease,
                        sessionFence,
                    )

                PushDeliveryClaimOutcome.ALREADY_SUCCESS ->
                    NotificationSendResult(
                        alreadyDeliveredCount = 1,
                        alreadyDeliveredAt = claim.deliveredAt,
                    )

                PushDeliveryClaimOutcome.AMBIGUOUS ->
                    NotificationSendResult(ambiguousCount = 1)

                PushDeliveryClaimOutcome.INVALID_TOKEN ->
                    NotificationSendResult(invalidTokenCount = 1)

                PushDeliveryClaimOutcome.EXHAUSTED ->
                    NotificationSendResult(exhaustedCount = 1)

                PushDeliveryClaimOutcome.DEFERRED ->
                    NotificationSendResult(deferredCount = 1)

                PushDeliveryClaimOutcome.DEDUPLICATED ->
                    NotificationSendResult(deduplicatedCount = 1)

                PushDeliveryClaimOutcome.SUPERSEDED ->
                    NotificationSendResult(supersededCount = 1)

                PushDeliveryClaimOutcome.FENCE_REJECTED ->
                    NotificationSendResult(fenceRejected = true)
            }
        }
        return result
    }

    /**
     * 운영자 provider 점검용 명시적 비영속 경로다. 사용자 알림/action에는 사용할 수 없다.
     */
    private fun sendEphemeral(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
    ): NotificationSendResult {
        val eventKey = PushLogicalEventKey.newEvent()
        val canonicalData = data.withPushAccountBinding(eventKey, memberId)
        val tokens = notificationTokenService.getTokensByMember(memberId)
        if (tokens.isEmpty()) {
            runCatching {
                pushSendHistoryService.recordNoToken(memberId, title, body, canonicalData)
            }.onFailure {
                log.warn(
                    "Push no-token history persistence failed. memberId={}, errorCode={}",
                    memberId,
                    it.javaClass.simpleName,
                )
            }
        }
        return tokens.fold(NotificationSendResult(requestedCount = tokens.size)) { total, token ->
            total + sendClaimed(
                memberId = memberId,
                snapshot = AppNotificationSnapshot(
                    id = null,
                    logicalEventKey = eventKey,
                    title = title,
                    body = body,
                    data = canonicalData,
                    createdAt = Instant.now(),
                ),
                claim = token.directSendClaim(),
            )
        }
    }

    private fun sendClaimed(
        memberId: Long,
        snapshot: AppNotificationSnapshot,
        claim: PushDeliveryClaim,
        dispatchFence: PushDispatchFence? = null,
        sourceLease: PushOutboxDispatchLease? = null,
        sessionFence: AuthenticatedPushSessionFence? = null,
    ): NotificationSendResult {
        val tokenEntity = requireNotNull(claim.token) {
            "A SEND claim must contain the verified token snapshot."
        }
        val providerToken = requireNotNull(claim.providerToken) {
            "A SEND claim must contain a provider token."
        }
        val deliveryId = claim.deliveryId

        return try {
            val providerSend = pushTokenProviderLeaseService.sendIfOwned(
                memberId = memberId,
                claim = claim,
                title = snapshot.title,
                body = snapshot.body,
                data = snapshot.data,
                dispatchFence = dispatchFence,
                sessionFence = sessionFence,
            )
            if (providerSend.outcome == PushTokenProviderLeaseOutcome.SUPERSEDED) {
                deliveryId?.let { pushDeliveryService.markOwnershipSuperseded(it) }
                return NotificationSendResult(supersededCount = 1)
            }
            if (providerSend.outcome == PushTokenProviderLeaseOutcome.DEFERRED) {
                return NotificationSendResult(deferredCount = 1)
            }
            if (providerSend.outcome == PushTokenProviderLeaseOutcome.BUSY) {
                // 다른 logical event가 같은 token의 provider boundary를 잠시 보유한 경우다.
                // 이 delivery는 provider에 넘기기 전임이 확실하므로 UNKNOWN으로 버리지 않고
                // confirmed local deferral로 되돌려 안전하게 재시도한다.
                val transition = deliveryId?.let {
                    pushDeliveryService.markFailure(
                        deliveryId = it,
                        errorCode = "TOKEN_DISPATCH_LEASE_BUSY",
                        errorMessage = "Token provider dispatch is already in progress.",
                        fence = dispatchFence,
                        sourceLease = sourceLease,
                    )
                } ?: PushDeliveryFailureTransition.NOT_APPLIED
                return NotificationSendResult(
                    failedCount = 1,
                    retryableFailedCount =
                        if (transition == PushDeliveryFailureTransition.RETRYABLE) 1 else 0,
                    supersededCount =
                        if (transition == PushDeliveryFailureTransition.TERMINAL_SUPERSEDED) 1 else 0,
                    ambiguousCount =
                        if (transition == PushDeliveryFailureTransition.NOT_APPLIED) 1 else 0,
                    recipientInactive =
                        transition == PushDeliveryFailureTransition.RECIPIENT_INACTIVE,
                )
            }
            val providerResult = requireNotNull(providerSend.providerResult) {
                "An acquired provider lease must return a provider result."
            }

            // Provider 성공 직후 로컬 기록 전 종료되면 DISPATCHING이 남는다. 이를 재시도하지
            // 않는 것이 exactly-once를 거짓 주장하지 않는 at-most-once 경계다.
            deliveryId?.let {
                runCatching {
                    pushDeliveryService.markSuccess(
                        it,
                        providerResult.messageId,
                        dispatchFence,
                        sourceLease,
                    )
                }.onFailure { failure ->
                    log.warn(
                        "Push delivery success transition failed. memberId={}, tokenId={}, deliveryId={}, errorCode={}",
                        memberId,
                        tokenEntity.id,
                        deliveryId,
                        failure.javaClass.simpleName,
                    )
                }
            }
            recordSuccess(memberId, tokenEntity, snapshot, providerResult.messageId)
            NotificationSendResult(attemptedCount = 1, sentCount = 1)
        } catch (exception: InvalidPushTokenException) {
            val errorCode = exception.javaClass.simpleName
            val errorMessage = exception.safeMessage(providerToken)
            deliveryId?.let {
                runCatching {
                    pushDeliveryService.markInvalidToken(it, errorCode, errorMessage)
                }.onFailure { failure ->
                    log.warn(
                        "Push invalid-token transition failed. memberId={}, tokenId={}, deliveryId={}, errorCode={}",
                        memberId,
                        tokenEntity.id,
                        deliveryId,
                        failure.javaClass.simpleName,
                    )
                }
            }
            recordFailure(
                memberId,
                tokenEntity,
                snapshot,
                PushSendStatus.INVALID_TOKEN,
                errorCode,
                errorMessage,
            )
            val removed = runCatching {
                notificationTokenService.removeTokenByOwnership(
                    memberId = memberId,
                    tokenId = requireNotNull(claim.tokenId),
                    tokenFingerprint = requireNotNull(claim.tokenFingerprint),
                    ownershipVersion = requireNotNull(claim.tokenOwnershipVersion),
                )
            }.onFailure { failure ->
                log.warn(
                    "Invalid push token removal failed. memberId={}, tokenId={}, errorCode={}",
                    memberId,
                    tokenEntity.id,
                    failure.javaClass.simpleName,
                )
            }.getOrDefault(false)
            log.info(
                "Processed invalid push token. memberId={}, tokenId={}, deliveryId={}, removed={}",
                memberId,
                tokenEntity.id,
                deliveryId,
                removed,
            )
            NotificationSendResult(
                attemptedCount = 1,
                failedCount = 1,
                invalidTokenCount = 1,
                removedTokenCount = if (removed) 1 else 0,
            )
        } catch (exception: ConfirmedPushDeliveryException) {
            val errorCode = exception.javaClass.simpleName
            val errorMessage = exception.safeMessage(providerToken)
            val transition = deliveryId?.let {
                runCatching<PushDeliveryFailureTransition> {
                    pushDeliveryService.markFailure(
                        it,
                        errorCode,
                        errorMessage,
                        dispatchFence,
                        sourceLease,
                    )
                }.onFailure { failure ->
                    log.warn(
                        "Push retry state persistence failed. memberId={}, tokenId={}, deliveryId={}, errorCode={}",
                        memberId,
                        tokenEntity.id,
                        deliveryId,
                        failure.javaClass.simpleName,
                    )
                }.getOrDefault(PushDeliveryFailureTransition.NOT_APPLIED)
            } ?: PushDeliveryFailureTransition.NOT_APPLIED
            recordFailure(
                memberId,
                tokenEntity,
                snapshot,
                PushSendStatus.FAILED,
                errorCode,
                errorMessage,
            )
            log.warn(
                "Push send failed. memberId={}, tokenId={}, deliveryId={}, errorCode={}, retryable={}",
                memberId,
                tokenEntity.id,
                deliveryId,
                errorCode,
                transition == PushDeliveryFailureTransition.RETRYABLE,
            )
            NotificationSendResult(
                attemptedCount = 1,
                failedCount = 1,
                retryableFailedCount =
                    if (transition == PushDeliveryFailureTransition.RETRYABLE) 1 else 0,
                supersededCount =
                    if (transition == PushDeliveryFailureTransition.TERMINAL_SUPERSEDED) 1 else 0,
                ambiguousCount =
                    if (transition == PushDeliveryFailureTransition.NOT_APPLIED) 1 else 0,
                recipientInactive =
                    transition == PushDeliveryFailureTransition.RECIPIENT_INACTIVE,
            )
        } catch (exception: Exception) {
            val errorCode = exception.javaClass.simpleName
            val errorMessage = exception.safeMessage(providerToken)
            recordFailure(
                memberId,
                tokenEntity,
                snapshot,
                PushSendStatus.UNKNOWN,
                errorCode,
                errorMessage,
            )
            log.warn(
                "Push outcome unknown. memberId={}, tokenId={}, deliveryId={}, errorCode={}, retryable=false",
                memberId,
                tokenEntity.id,
                deliveryId,
                errorCode,
            )
            NotificationSendResult(attemptedCount = 1, ambiguousCount = 1)
        }
    }

    private fun recordNoToken(memberId: Long, snapshot: AppNotificationSnapshot) {
        runCatching {
            pushSendHistoryService.recordNoToken(
                memberId = memberId,
                title = snapshot.title,
                body = snapshot.body,
                data = snapshot.data,
                logicalEventKey = snapshot.logicalEventKey,
                scheduleId = snapshot.scheduleId ?: snapshot.data["scheduleId"]?.toLongOrNull(),
                categoryId = snapshot.categoryId ?: snapshot.data["categoryId"]?.toLongOrNull(),
                calendarId = snapshot.calendarId ?: snapshot.data["calendarId"]?.toLongOrNull(),
            )
        }.onFailure {
            log.warn(
                "Push no-token history persistence failed. memberId={}, errorCode={}",
                memberId,
                it.javaClass.simpleName,
            )
        }
    }

    private fun recordSuccess(
        memberId: Long,
        token: NotificationDeviceToken,
        snapshot: AppNotificationSnapshot,
        providerMessageId: String,
    ) {
        runCatching {
            pushSendHistoryService.recordSuccess(
                memberId = memberId,
                token = token,
                title = snapshot.title,
                body = snapshot.body,
                data = snapshot.data,
                fcmMessageId = providerMessageId,
                logicalEventKey = snapshot.logicalEventKey,
                scheduleId = snapshot.scheduleId ?: snapshot.data["scheduleId"]?.toLongOrNull(),
                categoryId = snapshot.categoryId ?: snapshot.data["categoryId"]?.toLongOrNull(),
                calendarId = snapshot.calendarId ?: snapshot.data["calendarId"]?.toLongOrNull(),
            )
        }.onFailure {
            log.warn(
                "Push success history persistence failed. memberId={}, tokenId={}, errorCode={}",
                memberId,
                token.id,
                it.javaClass.simpleName,
            )
        }
    }

    private fun recordFailure(
        memberId: Long,
        token: NotificationDeviceToken,
        snapshot: AppNotificationSnapshot,
        status: PushSendStatus,
        errorCode: String,
        errorMessage: String?,
    ) {
        runCatching {
            pushSendHistoryService.recordFailure(
                memberId = memberId,
                token = token,
                title = snapshot.title,
                body = snapshot.body,
                data = snapshot.data,
                status = status,
                errorCode = errorCode,
                errorMessage = errorMessage,
                logicalEventKey = snapshot.logicalEventKey,
                scheduleId = snapshot.scheduleId ?: snapshot.data["scheduleId"]?.toLongOrNull(),
                categoryId = snapshot.categoryId ?: snapshot.data["categoryId"]?.toLongOrNull(),
                calendarId = snapshot.calendarId ?: snapshot.data["calendarId"]?.toLongOrNull(),
            )
        }.onFailure {
            log.warn(
                "Push failure history persistence failed. memberId={}, tokenId={}, errorCode={}",
                memberId,
                token.id,
                it.javaClass.simpleName,
            )
        }
    }

    fun sendToMembers(
        memberIds: List<Long>,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
        inboxDeduplicationKey: String? = null,
        persistInInbox: Boolean = true,
    ): NotificationSendResult =
        memberIds.map { memberId ->
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

data class NotificationSendResult(
    val requestedCount: Int = 0,
    /** 이번 호출에서 실제 provider API를 호출한 기기 수 */
    val attemptedCount: Int = 0,
    val sentCount: Int = 0,
    val failedCount: Int = 0,
    /** provider가 명시적으로 미수락해 동일 event/device로 재시도할 수 있는 실패 수 */
    val retryableFailedCount: Int = 0,
    val removedTokenCount: Int = 0,
    val invalidTokenCount: Int = 0,
    /** confirmed failure가 per-device provider attempt 한도를 모두 사용한 terminal 수 */
    val exhaustedCount: Int = 0,
    /** authoritative schedule source가 PROCESSING이라 provider 없이 연기한 manifest 수 */
    val deferredCount: Int = 0,
    val alreadyDeliveredCount: Int = 0,
    /** provider 호출 전 경계만 남아 성공 여부가 모호해 재전송하지 않은 기기 수 */
    val ambiguousCount: Int = 0,
    val deduplicatedCount: Int = 0,
    /** frozen snapshot이 현재 ownership과 달라 외부 호출 없이 terminal 처리한 수 */
    val supersededCount: Int = 0,
    /** manifest가 0건으로 동결되어 이후 등록 기기로 확장하지 않는 event 수 */
    val noDeviceEventCount: Int = 0,
    val fenceRejected: Boolean = false,
    /** withdrawal이 먼저 linearize되어 source/history/provider 작업을 만들지 않은 terminal no-op */
    val recipientInactive: Boolean = false,
    /** ALREADY_SUCCESS 재조회 시 원래 provider 성공 시각 */
    val alreadyDeliveredAt: Instant? = null,
    val eventSnapshot: AppNotificationSnapshot? = null,
    val inboxDeduplicated: Boolean = false,
) {
    val durablyHandledCount: Int
        get() =
            sentCount + alreadyDeliveredCount + ambiguousCount + deduplicatedCount +
                invalidTokenCount + exhaustedCount + supersededCount + noDeviceEventCount

    val confirmedSuccessCount: Int
        get() = sentCount + alreadyDeliveredCount

    val terminalManifestCount: Int
        get() =
            sentCount + alreadyDeliveredCount + ambiguousCount + invalidTokenCount +
                exhaustedCount + supersededCount + deduplicatedCount

    operator fun plus(other: NotificationSendResult): NotificationSendResult =
        NotificationSendResult(
            requestedCount = requestedCount + other.requestedCount,
            attemptedCount = attemptedCount + other.attemptedCount,
            sentCount = sentCount + other.sentCount,
            failedCount = failedCount + other.failedCount,
            retryableFailedCount = retryableFailedCount + other.retryableFailedCount,
            removedTokenCount = removedTokenCount + other.removedTokenCount,
            invalidTokenCount = invalidTokenCount + other.invalidTokenCount,
            exhaustedCount = exhaustedCount + other.exhaustedCount,
            deferredCount = deferredCount + other.deferredCount,
            alreadyDeliveredCount = alreadyDeliveredCount + other.alreadyDeliveredCount,
            ambiguousCount = ambiguousCount + other.ambiguousCount,
            deduplicatedCount = deduplicatedCount + other.deduplicatedCount,
            supersededCount = supersededCount + other.supersededCount,
            noDeviceEventCount = noDeviceEventCount + other.noDeviceEventCount,
            fenceRejected = fenceRejected || other.fenceRejected,
            recipientInactive = recipientInactive || other.recipientInactive,
            alreadyDeliveredAt = listOfNotNull(alreadyDeliveredAt, other.alreadyDeliveredAt).maxOrNull(),
            eventSnapshot = eventSnapshot ?: other.eventSnapshot,
            inboxDeduplicated = inboxDeduplicated || other.inboxDeduplicated,
        )
}

private fun Throwable.safeMessage(token: String): String =
    (message ?: javaClass.simpleName)
        .let { raw -> if (token.isEmpty()) raw else raw.replace(token, "[REDACTED]") }
        .take(1000)

private fun NotificationDeviceToken.directSendClaim(): PushDeliveryClaim =
    PushDeliveryClaim(
        outcome = PushDeliveryClaimOutcome.SEND,
        providerToken = token,
        tokenId = id,
        tokenFingerprint = tokenFingerprint,
        tokenOwnershipVersion = ownershipVersion,
        token = this,
    )
