package com.noLate.notification.application.service

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
    /** inbox는 기존 이벤트인데 기기 경계가 없으면 과거 호출 가능성을 우선해 보내지 않는다. */
    DEDUPLICATED,
    /** manifest 이후 token ownership이 바뀌어 stale snapshot을 terminal 처리했다. */
    SUPERSEDED,
    /** schedule edit/recovery가 먼저 linearize되어 이 worker의 lease/event identity가 오래됐다. */
    FENCE_REJECTED,
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
    ) {
        writer.markSuccess(deliveryId, providerMessageId, fence, sourceLease)
    }

    fun markFailure(
        deliveryId: Long,
        errorCode: String,
        errorMessage: String?,
        fence: PushDispatchFence? = null,
        sourceLease: PushOutboxDispatchLease? = null,
    ) {
        writer.markFailure(deliveryId, errorCode, errorMessage, fence, sourceLease)
    }

    fun markInvalidToken(deliveryId: Long, errorCode: String, errorMessage: String?) {
        writer.markInvalidToken(deliveryId, errorCode, errorMessage)
    }
}

@Service
class PushDeliveryWriter(
    private val repository: PushDeliveryRepository,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val appNotificationRepository: AppNotificationRepository,
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
        if (fence != null && fenceValidator?.validate(fence) != true) {
            if (!fence.requireWorkerLease) {
                return terminalizeRejectedPersistedClaim(memberId, eventKey, deliveryId)
            }
            return PushDeliveryClaim(PushDeliveryClaimOutcome.FENCE_REJECTED)
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

            PushDeliveryStatus.SUPERSEDED ->
                PushDeliveryClaim(PushDeliveryClaimOutcome.SUPERSEDED, existing.id)

            PushDeliveryStatus.PENDING,
            PushDeliveryStatus.FAILED -> {
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
                PushDeliveryClaim(
                    outcome = PushDeliveryClaimOutcome.SEND,
                    deliveryId = requireNotNull(existing.id),
                    providerToken = verifiedToken.token,
                    tokenId = verifiedToken.id,
                    tokenFingerprint = verifiedToken.tokenFingerprint,
                    tokenOwnershipVersion = verifiedToken.ownershipVersion,
                    token = verifiedToken,
                )
            }
        }
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
    ) {
        val identity = repository.findById(deliveryId).orElse(null) ?: return
        val directSourceFenceStillOwned =
            fence?.requireWorkerLease == true && fenceValidator?.validate(fence) == true
        val source = appNotificationRepository.findByMemberIdAndLogicalEventKeyForUpdate(
            identity.memberId,
            identity.eventKey,
        )
        val sourceFenceStillOwned =
            directSourceFenceStillOwned || source.ownsDispatchLease(sourceLease)
        val delivery = repository.findByIdForUpdate(deliveryId) ?: return
        if (delivery.memberId != identity.memberId || delivery.eventKey != identity.eventKey) return

        val now = Instant.now(clock)
        if (!delivery.markSuccess(now, providerMessageId)) return
        if (!sourceFenceStillOwned) {
            source?.scheduleConfirmedDeliveryReconciliation(now.plusSeconds(1))
        }
        repository.flush()
        appNotificationRepository.flush()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailure(
        deliveryId: Long,
        errorCode: String,
        errorMessage: String?,
        fence: PushDispatchFence? = null,
        sourceLease: PushOutboxDispatchLease? = null,
    ) {
        val identity = repository.findById(deliveryId).orElse(null) ?: return

        val directSourceFenceStillOwned =
            fence?.requireWorkerLease == true && fenceValidator?.validate(fence) == true
        // Source row first, delivery row second is the global lock order for late confirmed
        // failures. Outbox completion/recovery also locks the source row first, so a provider
        // response racing lease recovery serializes without a source<->delivery deadlock.
        val source = appNotificationRepository.findByMemberIdAndLogicalEventKeyForUpdate(
            identity.memberId,
            identity.eventKey,
        )
        val sourceFenceStillOwned =
            directSourceFenceStillOwned || source.ownsDispatchLease(sourceLease)
        val delivery = repository.findByIdForUpdate(deliveryId) ?: return
        if (delivery.memberId != identity.memberId || delivery.eventKey != identity.eventKey) return

        val now = Instant.now(clock)
        if (!delivery.markFailure(now, errorCode, errorMessage)) return
        if (!sourceFenceStillOwned) {
            source?.scheduleAfterConfirmedDeliveryFailure(
                nextAt = now.plusSeconds(outboxRetryDelaySeconds.coerceAtLeast(1)),
                retryAllowed = delivery.attemptCount < outboxMaxAttempts.coerceAtLeast(1),
                reason = "CONFIRMED_PROVIDER_FAILURE",
            )
        }
        repository.flush()
        appNotificationRepository.flush()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markInvalidToken(deliveryId: Long, errorCode: String, errorMessage: String?) {
        repository.findById(deliveryId).orElse(null)
            ?.markInvalidToken(Instant.now(clock), errorCode, errorMessage)
    }
}

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
