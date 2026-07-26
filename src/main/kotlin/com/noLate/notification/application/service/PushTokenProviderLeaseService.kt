package com.noLate.notification.application.service

import com.noLate.notification.application.PushClient
import com.noLate.notification.application.PushSendResult
import com.noLate.notification.application.ConfirmedPushDeliveryException
import com.noLate.notification.application.InvalidPushTokenException
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.global.observability.PushProviderMetricOutcome
import com.noLate.global.observability.recordSafely
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class PushTokenProviderLeaseOutcome {
    ACQUIRED,
    SUPERSEDED,
    /** Authoritative source is temporarily PROCESSING; provider was not called and retry is safe. */
    DEFERRED,
    BUSY,
}

data class PushTokenProviderLease(
    val outcome: PushTokenProviderLeaseOutcome,
    val tokenId: Long? = null,
    val leaseId: String? = null,
    val providerToken: String? = null,
)

data class PushTokenProviderSendResult(
    val outcome: PushTokenProviderLeaseOutcome,
    val providerResult: PushSendResult? = null,
)

/**
 * Delivery claim과 provider 호출 사이의 token ownership TOCTOU를 닫는다.
 *
 * [PushTokenProviderLeaseWriter]의 짧은 transaction은 token row 하나만 잠가 snapshot을
 * 검증하고 영속 lease를 남긴다. provider I/O는 transaction 밖에서 실행되므로 member row나
 * global lock을 보유하지 않는다. registration은 같은 token/device row의 활성 lease가
 * 해제될 때까지 fresh transaction으로 기다린다.
 */
@Service
class PushTokenProviderLeaseService(
    private val writer: PushTokenProviderLeaseWriter,
    private val pushClient: PushClient,
    private val observer: PushTokenProviderLeaseObserver? = null,
    private val operationalMetrics: NoLateOperationalMetrics? = null,
) {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun sendIfOwned(
        memberId: Long,
        claim: PushDeliveryClaim,
        title: String,
        body: String,
        data: Map<String, String>,
        dispatchFence: PushDispatchFence? = null,
        sessionFence: AuthenticatedPushSessionFence? = null,
    ): PushTokenProviderSendResult {
        observer?.beforeOwnershipLease(requireNotNull(claim.tokenId))
        val lease = writer.acquire(
            memberId = memberId,
            deliveryId = claim.deliveryId,
            tokenId = requireNotNull(claim.tokenId),
            tokenFingerprint = requireNotNull(claim.tokenFingerprint),
            ownershipVersion = requireNotNull(claim.tokenOwnershipVersion),
            dispatchFence = dispatchFence,
            sessionFence = sessionFence,
        )
        operationalMetrics.recordSafely { recordPushTokenLease(lease.outcome) }
        if (lease.outcome != PushTokenProviderLeaseOutcome.ACQUIRED) {
            return PushTokenProviderSendResult(lease.outcome)
        }

        val providerStartedAt = System.nanoTime()
        var providerOutcome = PushProviderMetricOutcome.UNKNOWN
        var providerDurationNanos = 0L
        return try {
            val result = PushTokenProviderSendResult(
                outcome = PushTokenProviderLeaseOutcome.ACQUIRED,
                providerResult = pushClient.sendToToken(
                    token = requireNotNull(lease.providerToken),
                    title = title,
                    body = body,
                    data = data,
                ),
            )
            providerOutcome = PushProviderMetricOutcome.SUCCESS
            providerDurationNanos = (System.nanoTime() - providerStartedAt).coerceAtLeast(1)
            result
        } catch (failure: Exception) {
            providerDurationNanos = (System.nanoTime() - providerStartedAt).coerceAtLeast(1)
            providerOutcome = when (failure) {
                is InvalidPushTokenException -> PushProviderMetricOutcome.INVALID_TOKEN
                is ConfirmedPushDeliveryException ->
                    PushProviderMetricOutcome.CONFIRMED_FAILURE
                else -> PushProviderMetricOutcome.UNKNOWN
            }
            throw failure
        } finally {
            try {
                writer.release(
                    memberId = memberId,
                    tokenId = requireNotNull(lease.tokenId),
                    leaseId = requireNotNull(lease.leaseId),
                    tokenFingerprint = requireNotNull(claim.tokenFingerprint),
                    ownershipVersion = requireNotNull(claim.tokenOwnershipVersion),
                )
            } finally {
                operationalMetrics.recordSafely {
                    recordPushProviderCall(
                        providerOutcome,
                        providerDurationNanos,
                    )
                }
            }
        }
    }
}

@Service
class PushTokenProviderLeaseWriter(
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val deliveryRepository: PushDeliveryRepository,
    private val appNotificationRepository: AppNotificationRepository,
    private val memberRepository: MemberRepository,
    private val clock: Clock,
    private val fenceValidator: PushDispatchFenceValidator? = null,
    private val recipientAuthorizationValidator: PushRecipientAuthorizationValidator? = null,
    private val sourceFreshnessValidator: PushSourceFreshnessValidator? = null,
    @Value("\${notification.push-token.dispatch-lease-seconds:600}")
    private val leaseSeconds: Long = 600,
    @Value("\${notification.push-token.provider-max-call-seconds:60}")
    private val providerMaxCallSeconds: Long = 60,
) {
    init {
        require(leaseSeconds > providerMaxCallSeconds) {
            "Push token dispatch lease must outlive the bounded provider call."
        }
    }

    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED,
    )
    fun acquire(
        memberId: Long,
        deliveryId: Long?,
        tokenId: Long,
        tokenFingerprint: String,
        ownershipVersion: Long,
        dispatchFence: PushDispatchFence? = null,
        sessionFence: AuthenticatedPushSessionFence? = null,
    ): PushTokenProviderLease {
        // This must be the first database read as well as the first lock. Calendar/share removal,
        // schedule edit, logout, and withdrawal all start with the same recipient member row.
        // READ_COMMITTED prevents an earlier identity lookup from pinning a stale authorization
        // snapshot while this transaction waits for that member lock.
        val recipient =
            memberRepository.findActiveNotificationRecipientForUpdate(memberId)
                ?: return PushTokenProviderLease(PushTokenProviderLeaseOutcome.SUPERSEDED)
        val deliveryIdentity = deliveryId
            ?.let { deliveryRepository.findById(it).orElse(null) }
        if (deliveryId != null && deliveryIdentity == null) {
            return PushTokenProviderLease(PushTokenProviderLeaseOutcome.SUPERSEDED)
        }
        if (sessionFence != null && !sessionFence.matches(recipient)) {
            return supersedeFencedDelivery(
                memberId,
                deliveryIdentity,
                "Authenticated session changed before provider dispatch.",
                "AUTHENTICATED_SESSION_GENERATION_CHANGED",
            )
        }
        if (dispatchFence != null) {
            when (
                fenceValidator?.evaluate(dispatchFence)
                    ?: PushDispatchFenceDecision.REJECT_TERMINAL
            ) {
                PushDispatchFenceDecision.ACCEPT -> Unit
                PushDispatchFenceDecision.RETRY_LATER ->
                    return deferFencedDelivery(memberId, deliveryIdentity)
                PushDispatchFenceDecision.REJECT_TERMINAL ->
                    return supersedeFencedDelivery(
                        memberId,
                        deliveryIdentity,
                        "Schedule source identity changed before provider dispatch.",
                        "SCHEDULE_SOURCE_FENCE_CHANGED",
                    )
            }
        }

        val delivery =
            if (deliveryIdentity != null) {
                val source = appNotificationRepository
                    .findByMemberIdAndLogicalEventKeyForUpdate(
                        memberId,
                        deliveryIdentity.eventKey,
                    )
                    ?: return supersedeFencedDelivery(
                        memberId,
                        deliveryIdentity,
                        "Immutable push source disappeared before provider dispatch.",
                        "PUSH_SOURCE_MISSING",
                    )
                val lockedDelivery = deliveryRepository.findByIdForUpdate(
                    requireNotNull(deliveryId),
                ) ?: return PushTokenProviderLease(PushTokenProviderLeaseOutcome.SUPERSEDED)
                if (
                    lockedDelivery.memberId != memberId ||
                    lockedDelivery.eventKey != deliveryIdentity.eventKey ||
                    lockedDelivery.status != PushDeliveryStatus.DISPATCHING
                ) {
                    return PushTokenProviderLease(PushTokenProviderLeaseOutcome.SUPERSEDED)
                }
                val authorizationRevoked =
                    recipientAuthorizationValidator?.canDispatch(
                        memberId = memberId,
                        scheduleId = source.scheduleId ?: lockedDelivery.scheduleId,
                        categoryId = source.categoryId,
                        payloadType = source.type,
                        calendarId = source.calendarId ?: lockedDelivery.calendarId,
                    ) == false
                val sourceStale =
                    sourceFreshnessValidator?.isFresh(source.toFrozenPushSource()) == false
                if (authorizationRevoked || sourceStale) {
                    lockedDelivery.markDispatchSuperseded(
                        Instant.now(clock),
                        if (authorizationRevoked) {
                            "RECIPIENT_ACCESS_REVOKED"
                        } else {
                            "PUSH_SOURCE_STALE"
                        },
                        if (authorizationRevoked) {
                            "Recipient access changed after delivery claim and before provider dispatch."
                        } else {
                            "Immutable push source became stale before provider dispatch."
                        },
                    )
                    deliveryRepository.saveAndFlush(lockedDelivery)
                    return PushTokenProviderLease(PushTokenProviderLeaseOutcome.SUPERSEDED)
                }
                lockedDelivery
            } else {
                null
            }

        val token = tokenRepository.findByIdForUpdate(tokenId)
            ?: return supersedeDelivery(delivery)
        if (
            token.memberId != memberId ||
            token.tokenFingerprint != tokenFingerprint ||
            token.ownershipVersion != ownershipVersion
        ) {
            return supersedeDelivery(delivery)
        }
        if (token.retirementRequested) {
            return supersedeDelivery(delivery)
        }

        val now = Instant.now(clock)
        val leaseId = UUID.randomUUID().toString()
        if (!token.acquireDispatchLease(
                leaseId = leaseId,
                now = now,
                leaseUntil = now.plusSeconds(leaseSeconds.coerceAtLeast(1)),
            )
        ) {
            return PushTokenProviderLease(PushTokenProviderLeaseOutcome.BUSY)
        }
        tokenRepository.saveAndFlush(token)
        return PushTokenProviderLease(
            outcome = PushTokenProviderLeaseOutcome.ACQUIRED,
            tokenId = tokenId,
            leaseId = leaseId,
            providerToken = token.token,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun release(
        memberId: Long,
        tokenId: Long,
        leaseId: String,
        tokenFingerprint: String,
        ownershipVersion: Long,
    ) {
        val token = tokenRepository.findByIdForUpdate(tokenId) ?: return
        if (
            token.memberId == memberId &&
            token.tokenFingerprint == tokenFingerprint &&
            token.ownershipVersion == ownershipVersion &&
            token.releaseDispatchLease(leaseId)
        ) {
            if (token.retirementRequested) {
                tokenRepository.delete(token)
                tokenRepository.flush()
            } else {
                tokenRepository.saveAndFlush(token)
            }
        }
    }

    private fun supersedeDelivery(
        delivery: com.noLate.notification.domain.PushDelivery?,
    ): PushTokenProviderLease {
        if (delivery?.markDispatchSuperseded(
                Instant.now(clock),
                "TOKEN_OWNERSHIP_CHANGED",
                "Token ownership changed after delivery claim and before provider dispatch.",
            ) == true
        ) {
            deliveryRepository.saveAndFlush(delivery)
        }
        return PushTokenProviderLease(PushTokenProviderLeaseOutcome.SUPERSEDED)
    }

    private fun supersedeFencedDelivery(
        memberId: Long,
        deliveryIdentity: com.noLate.notification.domain.PushDelivery?,
        deliveryReason: String,
        sourceReason: String,
    ): PushTokenProviderLease {
        if (deliveryIdentity == null) {
            return PushTokenProviderLease(PushTokenProviderLeaseOutcome.SUPERSEDED)
        }
        val source = appNotificationRepository.findByMemberIdAndLogicalEventKeyForUpdate(
            memberId,
            deliveryIdentity.eventKey,
        )
        val delivery = deliveryRepository.findByIdForUpdate(
            requireNotNull(deliveryIdentity.id),
        )
        if (
            delivery != null &&
            delivery.memberId == memberId &&
            delivery.eventKey == deliveryIdentity.eventKey &&
            delivery.markDispatchSuperseded(
                Instant.now(clock),
                sourceReason,
                deliveryReason,
            )
        ) {
            deliveryRepository.saveAndFlush(delivery)
        }
        source?.completeSupersededDispatch(
            Instant.now(clock),
            sourceReason,
        )
        appNotificationRepository.flush()
        return PushTokenProviderLease(PushTokenProviderLeaseOutcome.SUPERSEDED)
    }

    private fun deferFencedDelivery(
        memberId: Long,
        deliveryIdentity: com.noLate.notification.domain.PushDelivery?,
    ): PushTokenProviderLease {
        if (deliveryIdentity == null) {
            return PushTokenProviderLease(PushTokenProviderLeaseOutcome.DEFERRED)
        }
        val delivery = deliveryRepository.findByIdForUpdate(
            requireNotNull(deliveryIdentity.id),
        )
        if (
            delivery != null &&
            delivery.memberId == memberId &&
            delivery.eventKey == deliveryIdentity.eventKey &&
            delivery.deferBeforeProvider(
                "Authoritative schedule source is processing before provider dispatch.",
            )
        ) {
            deliveryRepository.saveAndFlush(delivery)
        }
        return PushTokenProviderLease(PushTokenProviderLeaseOutcome.DEFERRED)
    }
}

/**
 * claim commit 뒤 ownership lease 직전 account transfer를 결정적으로 재현하는 test seam.
 * raw token/device identifier는 전달하지 않는다.
 */
fun interface PushTokenProviderLeaseObserver {
    fun beforeOwnershipLease(tokenId: Long)
}
