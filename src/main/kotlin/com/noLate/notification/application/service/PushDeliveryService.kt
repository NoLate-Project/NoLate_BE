package com.noLate.notification.application.service

import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.AppNotification
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushDelivery
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

data class PushDeliveryClaim(
    val outcome: PushDeliveryClaimOutcome,
    val deliveryId: Long? = null,
    val providerToken: String? = null,
    val tokenId: Long? = null,
    val tokenFingerprint: String? = null,
    val tokenOwnershipVersion: Long? = null,
    val deliveredAt: Instant? = null,
    val token: NotificationDeviceToken? = null,
)

enum class PushDeliveryClaimOutcome {
    SEND,
    ALREADY_SUCCESS,
    /** 호출 전 상태가 남았으므로 성공 여부가 모호하다. 중복 방지를 위해 자동 재시도하지 않는다. */
    AMBIGUOUS,
    INVALID_TOKEN,
    /** 이 event/device가 확인 실패 재시도 예산을 모두 사용한 terminal 결과다. */
    EXHAUSTED,
    /** Persisted safety source의 authoritative worker가 아직 PROCESSING이라 budget 없이 연기한다. */
    DEFERRED,
    /** inbox는 기존 이벤트인데 기기 경계가 없으면 과거 호출 가능성을 우선해 보내지 않는다. */
    DEDUPLICATED,
    /** manifest 이후 token ownership이 바뀌어 stale snapshot을 terminal 처리했다. */
    SUPERSEDED,
    /** schedule edit/recovery가 먼저 linearize되어 이 worker의 lease/event identity가 오래됐다. */
    FENCE_REJECTED,
}

enum class PushDeliveryFailureTransition {
    /** Confirmed provider rejection was durably recorded and the same event/device may retry. */
    RETRYABLE,
    /** The schedule event became stale/terminal while provider I/O was in flight. */
    TERMINAL_SUPERSEDED,
    /** Withdrawal won the member-row fence; no notification row may be recreated. */
    RECIPIENT_INACTIVE,
    /** The local delivery transition could not be proven and must be treated as ambiguous. */
    NOT_APPLIED,
}

/**
 * NotificationUseCase와 독립된 transaction proxy 경계다.
 *
 * claim과 결과 전이를 REQUIRES_NEW로 커밋하므로 호출자를 감싼 schedule/share 트랜잭션이
 * rollback되어도 이미 provider에 넘긴 기기 이벤트가 다시 발송 대상으로 돌아가지 않는다.
 */
@Service
class PushDeliveryService(
    private val writer: PushDeliveryWriter,
) {

    fun claim(
        memberId: Long,
        eventKey: String,
        deliveryId: Long,
        fence: PushDispatchFence? = null,
    ): PushDeliveryClaim {
        val normalizedEventKey = eventKey.take(100)
        return try {
            writer.claim(memberId, normalizedEventKey, deliveryId, fence)
        } catch (_: OptimisticLockingFailureException) {
            writer.claim(memberId, normalizedEventKey, deliveryId, fence)
        }
    }

    fun markSuccess(
        deliveryId: Long,
        providerMessageId: String,
        fence: PushDispatchFence? = null,
        sourceLease: PushOutboxDispatchLease? = null,
    ): Boolean =
        writer.markSuccess(deliveryId, providerMessageId, fence, sourceLease)

    fun markFailure(
        deliveryId: Long,
        errorCode: String,
        errorMessage: String?,
        fence: PushDispatchFence? = null,
        sourceLease: PushOutboxDispatchLease? = null,
    ): PushDeliveryFailureTransition =
        writer.markFailure(deliveryId, errorCode, errorMessage, fence, sourceLease)

    fun markInvalidToken(deliveryId: Long, errorCode: String, errorMessage: String?): Boolean =
        writer.markInvalidToken(deliveryId, errorCode, errorMessage)
}

@Service
class PushDeliveryWriter(
    private val repository: PushDeliveryRepository,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val appNotificationRepository: AppNotificationRepository,
    private val memberRepository: MemberRepository,
    private val clock: Clock,
    private val fenceValidator: PushDispatchFenceValidator? = null,
    @Value("\${notification.push-outbox.retry-delay-seconds:60}")
    private val outboxRetryDelaySeconds: Long = 60,
    @Value("\${notification.push-outbox.max-attempts:5}")
    private val outboxMaxAttempts: Int = 5,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(
        memberId: Long,
        eventKey: String,
        deliveryId: Long,
        fence: PushDispatchFence? = null,
    ): PushDeliveryClaim {
        // member -> schedule fence -> delivery -> token is shared with withdrawal cleanup.
        if (memberRepository.findActiveNotificationRecipientForUpdate(memberId) == null) {
            return PushDeliveryClaim(PushDeliveryClaimOutcome.SUPERSEDED)
        }
        if (fence != null) {
            val decision = fenceValidator?.evaluate(fence)
                ?: PushDispatchFenceDecision.REJECT_TERMINAL
            if (decision != PushDispatchFenceDecision.ACCEPT) {
                if (!fence.requireWorkerLease) {
                    return if (decision == PushDispatchFenceDecision.RETRY_LATER) {
                        PushDeliveryClaim(PushDeliveryClaimOutcome.DEFERRED, deliveryId)
                    } else {
                        terminalizeRejectedPersistedClaim(memberId, eventKey, deliveryId)
                    }
                }
                return PushDeliveryClaim(PushDeliveryClaimOutcome.FENCE_REJECTED)
            }
        }
        val existing = repository.findByIdAndMemberIdAndEventKey(
            deliveryId,
            memberId,
            eventKey,
        ) ?: return PushDeliveryClaim(PushDeliveryClaimOutcome.DEDUPLICATED)
        return when (existing.status) {
            PushDeliveryStatus.SUCCESS ->
                PushDeliveryClaim(
                    PushDeliveryClaimOutcome.ALREADY_SUCCESS,
                    existing.id,
                    deliveredAt = existing.deliveredAt,
                )

            PushDeliveryStatus.DISPATCHING ->
                PushDeliveryClaim(PushDeliveryClaimOutcome.AMBIGUOUS, existing.id)

            PushDeliveryStatus.INVALID_TOKEN ->
                PushDeliveryClaim(PushDeliveryClaimOutcome.INVALID_TOKEN, existing.id)

            PushDeliveryStatus.EXHAUSTED ->
                PushDeliveryClaim(PushDeliveryClaimOutcome.EXHAUSTED, existing.id)

            PushDeliveryStatus.SUPERSEDED ->
                PushDeliveryClaim(PushDeliveryClaimOutcome.SUPERSEDED, existing.id)

            PushDeliveryStatus.PENDING -> dispatch(existing, memberId)

            PushDeliveryStatus.FAILED -> {
                val maxAttempts = outboxMaxAttempts.coerceAtLeast(1)
                if (existing.attemptCount >= maxAttempts) {
                    existing.markExhausted(Instant.now(clock), maxAttempts)
                    repository.saveAndFlush(existing)
                    return PushDeliveryClaim(PushDeliveryClaimOutcome.EXHAUSTED, existing.id)
                }
                dispatch(existing, memberId)
            }
        }
    }

    private fun dispatch(
        existing: PushDelivery,
        memberId: Long,
    ): PushDeliveryClaim {
        val currentToken = existing.deviceTokenId
            ?.let(tokenRepository::findByIdForUpdate)
        val verifiedToken = currentToken?.takeIf {
            it.memberId == memberId &&
                it.tokenFingerprint == existing.tokenFingerprint &&
                it.ownershipVersion == existing.tokenOwnershipVersion &&
                it.deliveryDeviceKey() == existing.deviceKey
        }
        if (verifiedToken == null) {
            existing.markSuperseded(
                Instant.now(clock),
                "Token ownership snapshot changed before provider dispatch.",
            )
            repository.saveAndFlush(existing)
            return PushDeliveryClaim(PushDeliveryClaimOutcome.SUPERSEDED, existing.id)
        }
        existing.beginDispatch(Instant.now(clock))
        repository.saveAndFlush(existing)
        return PushDeliveryClaim(
            outcome = PushDeliveryClaimOutcome.SEND,
            deliveryId = requireNotNull(existing.id),
            providerToken = verifiedToken.token,
            tokenId = verifiedToken.id,
            tokenFingerprint = verifiedToken.tokenFingerprint,
            tokenOwnershipVersion = verifiedToken.ownershipVersion,
            token = verifiedToken,
        )
    }

    private fun terminalizeRejectedPersistedClaim(
        memberId: Long,
        eventKey: String,
        deliveryId: Long,
    ): PushDeliveryClaim {
        val delivery = repository.findByIdAndMemberIdAndEventKey(
            deliveryId,
            memberId,
            eventKey,
        ) ?: return PushDeliveryClaim(PushDeliveryClaimOutcome.DEDUPLICATED)
        return when (delivery.status) {
            PushDeliveryStatus.PENDING,
            PushDeliveryStatus.FAILED -> {
                delivery.markSuperseded(
                    Instant.now(clock),
                    "Persisted source identity changed before safety dispatch.",
                )
                repository.saveAndFlush(delivery)
                PushDeliveryClaim(PushDeliveryClaimOutcome.SUPERSEDED, delivery.id)
            }

            PushDeliveryStatus.SUCCESS ->
                PushDeliveryClaim(
                    PushDeliveryClaimOutcome.ALREADY_SUCCESS,
                    delivery.id,
                    deliveredAt = delivery.deliveredAt,
                )

            PushDeliveryStatus.DISPATCHING ->
                PushDeliveryClaim(PushDeliveryClaimOutcome.AMBIGUOUS, delivery.id)

            PushDeliveryStatus.INVALID_TOKEN ->
                PushDeliveryClaim(PushDeliveryClaimOutcome.INVALID_TOKEN, delivery.id)

            PushDeliveryStatus.EXHAUSTED ->
                PushDeliveryClaim(PushDeliveryClaimOutcome.EXHAUSTED, delivery.id)

            PushDeliveryStatus.SUPERSEDED ->
                PushDeliveryClaim(PushDeliveryClaimOutcome.SUPERSEDED, delivery.id)
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markSuccess(
        deliveryId: Long,
        providerMessageId: String,
        fence: PushDispatchFence? = null,
        sourceLease: PushOutboxDispatchLease? = null,
    ): Boolean {
        val identity = repository.findById(deliveryId).orElse(null) ?: return false
        if (memberRepository.findActiveNotificationRecipientForUpdate(identity.memberId) == null) {
            return false
        }
        val directSourceFenceStillOwned =
            fence?.requireWorkerLease == true && fenceValidator?.validate(fence) == true
        val source = appNotificationRepository.findByMemberIdAndLogicalEventKeyForUpdate(
            identity.memberId,
            identity.eventKey,
        )
        val sourceFenceStillOwned =
            directSourceFenceStillOwned || source.ownsDispatchLease(sourceLease)
        val delivery = repository.findByIdForUpdate(deliveryId) ?: return false
        if (delivery.memberId != identity.memberId || delivery.eventKey != identity.eventKey) return false

        val now = Instant.now(clock)
        if (!delivery.markSuccess(now, providerMessageId)) return false
        if (!sourceFenceStillOwned) {
            source?.scheduleConfirmedDeliveryReconciliation(now.plusSeconds(1))
        }
        repository.flush()
        appNotificationRepository.flush()
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailure(
        deliveryId: Long,
        errorCode: String,
        errorMessage: String?,
        fence: PushDispatchFence? = null,
        sourceLease: PushOutboxDispatchLease? = null,
    ): PushDeliveryFailureTransition {
        val identity = repository.findById(deliveryId).orElse(null)
            ?: return PushDeliveryFailureTransition.NOT_APPLIED
        if (memberRepository.findActiveNotificationRecipientForUpdate(identity.memberId) == null) {
            return PushDeliveryFailureTransition.RECIPIENT_INACTIVE
        }

        val scheduleFence = evaluateLateScheduleFailure(fence)
        val scheduleFenceDecision = scheduleFence.decision
        val directSourceFenceStillOwned = scheduleFence.directLeaseOwned
        // After the member and optional schedule fence, source precedes delivery. Outbox
        // completion/recovery follows the same member -> source order.
        val source = appNotificationRepository.findByMemberIdAndLogicalEventKeyForUpdate(
            identity.memberId,
            identity.eventKey,
        )
        val sourceFenceStillOwned =
            directSourceFenceStillOwned || source.ownsDispatchLease(sourceLease)
        val delivery = repository.findByIdForUpdate(deliveryId)
            ?: return PushDeliveryFailureTransition.NOT_APPLIED
        if (delivery.memberId != identity.memberId || delivery.eventKey != identity.eventKey) {
            return PushDeliveryFailureTransition.NOT_APPLIED
        }

        val now = Instant.now(clock)
        if (scheduleFenceDecision == PushDispatchFenceDecision.REJECT_TERMINAL) {
            if (!delivery.markConfirmedFailureSuperseded(
                    now,
                    "Schedule source identity or status became terminal during provider dispatch.",
                )
            ) {
                return PushDeliveryFailureTransition.NOT_APPLIED
            }
            source?.completeSupersededDispatch(
                now,
                "SCHEDULE_SOURCE_TERMINAL",
            )
            repository.flush()
            appNotificationRepository.flush()
            return PushDeliveryFailureTransition.TERMINAL_SUPERSEDED
        }

        if (!delivery.markFailure(now, errorCode, errorMessage)) {
            return PushDeliveryFailureTransition.NOT_APPLIED
        }
        if (!sourceFenceStillOwned) {
            source?.scheduleAfterConfirmedDeliveryFailure(
                nextAt = now.plusSeconds(outboxRetryDelaySeconds.coerceAtLeast(1)),
                retryAllowed = delivery.attemptCount < outboxMaxAttempts.coerceAtLeast(1),
                reason = "CONFIRMED_PROVIDER_FAILURE",
            )
        }
        repository.flush()
        appNotificationRepository.flush()
        return PushDeliveryFailureTransition.RETRYABLE
    }

    /**
     * An exact direct lease is authoritative. Once it is lost, only the persisted identity/state
     * allowlist may decide whether a confirmed failure can reopen the frozen event.
     */
    private fun evaluateLateScheduleFailure(
        fence: PushDispatchFence?,
    ): LateScheduleFailureFence {
        if (fence == null) return LateScheduleFailureFence()
        val validator = fenceValidator ?: return LateScheduleFailureFence(
            decision = PushDispatchFenceDecision.REJECT_TERMINAL,
        )
        if (!fence.requireWorkerLease) {
            return LateScheduleFailureFence(decision = validator.evaluate(fence))
        }
        val exact = validator.evaluate(fence)
        if (exact == PushDispatchFenceDecision.ACCEPT) {
            return LateScheduleFailureFence(
                decision = exact,
                directLeaseOwned = true,
            )
        }
        return LateScheduleFailureFence(
            decision = validator.evaluate(
                fence.copy(
                    workerId = "schedule-late-result-safety",
                    jobVersion = -1,
                    requireWorkerLease = false,
                ),
            ),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markInvalidToken(deliveryId: Long, errorCode: String, errorMessage: String?): Boolean {
        val identity = repository.findById(deliveryId).orElse(null) ?: return false
        if (memberRepository.findActiveNotificationRecipientForUpdate(identity.memberId) == null) {
            return false
        }
        val delivery = repository.findByIdForUpdate(deliveryId) ?: return false
        if (delivery.memberId != identity.memberId || delivery.eventKey != identity.eventKey) return false
        delivery.markInvalidToken(Instant.now(clock), errorCode, errorMessage)
        repository.flush()
        return true
    }
}

private data class LateScheduleFailureFence(
    val decision: PushDispatchFenceDecision? = null,
    val directLeaseOwned: Boolean = false,
)

internal fun NotificationDeviceToken.deliveryDeviceKey(): String {
    return if (deviceFingerprint != null) {
        "device-sha256:$deviceFingerprint"
    } else {
        "token-sha256:$tokenFingerprint"
    }
}

/**
 * A persisted schedule fence proves only event identity. The active outbox attempt is a separate
 * lease and must be checked against the locked source row before a provider result may rely on its
 * outer worker to perform completion/retry. A stale late result therefore reopens the source.
 */
private fun AppNotification?.ownsDispatchLease(lease: PushOutboxDispatchLease?): Boolean =
    this != null &&
        lease != null &&
        id == lease.notificationId &&
        memberId == lease.memberId &&
        logicalEventKey == lease.logicalEventKey &&
        dispatchStatus == com.noLate.notification.domain.PushOutboxDispatchStatus.PROCESSING &&
        dispatchLockedBy == lease.workerId &&
        dispatchAttemptCount == lease.attemptCount
