package com.noLate.notification.application.service

import com.noLate.notification.application.PushClient
import com.noLate.notification.application.PushSendResult
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class PushTokenProviderLeaseOutcome {
    ACQUIRED,
    SUPERSEDED,
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
) {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun sendIfOwned(
        memberId: Long,
        claim: PushDeliveryClaim,
        title: String,
        body: String,
        data: Map<String, String>,
    ): PushTokenProviderSendResult {
        observer?.beforeOwnershipLease(requireNotNull(claim.tokenId))
        val lease = writer.acquire(
            memberId = memberId,
            deliveryId = claim.deliveryId,
            tokenId = requireNotNull(claim.tokenId),
            tokenFingerprint = requireNotNull(claim.tokenFingerprint),
            ownershipVersion = requireNotNull(claim.tokenOwnershipVersion),
        )
        if (lease.outcome != PushTokenProviderLeaseOutcome.ACQUIRED) {
            return PushTokenProviderSendResult(lease.outcome)
        }

        return try {
            PushTokenProviderSendResult(
                outcome = PushTokenProviderLeaseOutcome.ACQUIRED,
                providerResult = pushClient.sendToToken(
                    token = requireNotNull(lease.providerToken),
                    title = title,
                    body = body,
                    data = data,
                ),
            )
        } finally {
            writer.release(
                memberId = memberId,
                tokenId = requireNotNull(lease.tokenId),
                leaseId = requireNotNull(lease.leaseId),
                tokenFingerprint = requireNotNull(claim.tokenFingerprint),
                ownershipVersion = requireNotNull(claim.tokenOwnershipVersion),
            )
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
    private val recipientAuthorizationValidator: PushRecipientAuthorizationValidator? = null,
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun acquire(
        memberId: Long,
        deliveryId: Long?,
        tokenId: Long,
        tokenFingerprint: String,
        ownershipVersion: Long,
    ): PushTokenProviderLease {
        val deliveryIdentity = deliveryId
            ?.let { deliveryRepository.findById(it).orElse(null) }
        if (deliveryId != null && deliveryIdentity == null) {
            return PushTokenProviderLease(PushTokenProviderLeaseOutcome.SUPERSEDED)
        }
        if (memberRepository.findActiveNotificationRecipientForUpdate(memberId) == null) {
            return PushTokenProviderLease(PushTokenProviderLeaseOutcome.SUPERSEDED)
        }

        val delivery =
            if (deliveryIdentity != null) {
                val source = appNotificationRepository
                    .findByMemberIdAndLogicalEventKeyForUpdate(
                        memberId,
                        deliveryIdentity.eventKey,
                    )
                    ?: return PushTokenProviderLease(PushTokenProviderLeaseOutcome.SUPERSEDED)
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
                if (
                    recipientAuthorizationValidator?.canDispatch(
                        memberId = memberId,
                        scheduleId = source.scheduleId ?: lockedDelivery.scheduleId,
                        categoryId = source.categoryId,
                        payloadType = source.type,
                    ) == false
                ) {
                    lockedDelivery.markDispatchOwnershipSuperseded(
                        Instant.now(clock),
                        "Recipient access changed after delivery claim and before provider dispatch.",
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
        if (delivery?.markDispatchOwnershipSuperseded(
                Instant.now(clock),
                "Token ownership changed after delivery claim and before provider dispatch.",
            ) == true
        ) {
            deliveryRepository.saveAndFlush(delivery)
        }
        return PushTokenProviderLease(PushTokenProviderLeaseOutcome.SUPERSEDED)
    }
}

/**
 * claim commit 뒤 ownership lease 직전 account transfer를 결정적으로 재현하는 test seam.
 * raw token/device identifier는 전달하지 않는다.
 */
fun interface PushTokenProviderLeaseObserver {
    fun beforeOwnershipLease(tokenId: Long)
}
