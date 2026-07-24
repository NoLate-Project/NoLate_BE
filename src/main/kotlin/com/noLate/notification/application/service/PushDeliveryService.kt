package com.noLate.notification.application.service

import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushDelivery
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
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

    /**
     * provider loop 전에 현재 대상 기기 전체를 한 transaction에서 PENDING으로 만든다.
     * inbox가 이미 존재해도 누락 row를 보충하므로 manifest 직전 crash를 다음 실행이 복구한다.
     */
    fun prepareManifest(
        memberId: Long,
        eventKey: String,
        tokens: List<NotificationDeviceToken>,
        data: Map<String, String>,
    ) {
        if (tokens.isEmpty()) return
        val normalizedEventKey = eventKey.take(100)
        try {
            writer.prepareManifest(memberId, normalizedEventKey, tokens, data)
        } catch (_: DataIntegrityViolationException) {
            // 동시 manifest 생성의 unique 충돌 transaction이 끝난 뒤 누락분을 다시 보충한다.
            writer.prepareManifest(memberId, normalizedEventKey, tokens, data)
        }
    }

    fun claim(
        memberId: Long,
        eventKey: String,
        token: NotificationDeviceToken,
        fence: PushDispatchFence? = null,
    ): PushDeliveryClaim {
        val deviceKey = token.deliveryDeviceKey(memberId)
        val normalizedEventKey = eventKey.take(100)
        return try {
            writer.claim(memberId, normalizedEventKey, deviceKey, fence)
        } catch (_: OptimisticLockingFailureException) {
            writer.claim(memberId, normalizedEventKey, deviceKey, fence)
        }
    }

    fun markSuccess(deliveryId: Long, providerMessageId: String) {
        writer.markSuccess(deliveryId, providerMessageId)
    }

    fun markFailure(deliveryId: Long, errorCode: String, errorMessage: String?) {
        writer.markFailure(deliveryId, errorCode, errorMessage)
    }

    fun markInvalidToken(deliveryId: Long, errorCode: String, errorMessage: String?) {
        writer.markInvalidToken(deliveryId, errorCode, errorMessage)
    }
}

@Service
class PushDeliveryWriter(
    private val repository: PushDeliveryRepository,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val clock: Clock,
    private val fenceValidator: PushDispatchFenceValidator? = null,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun prepareManifest(
        memberId: Long,
        eventKey: String,
        tokens: List<NotificationDeviceToken>,
        data: Map<String, String>,
    ) {
        val existingDeviceKeys = repository.findAllByMemberIdAndEventKey(memberId, eventKey)
            .mapTo(mutableSetOf()) { it.deviceKey }
        val missing = tokens
            .distinctBy { it.deliveryDeviceKey(memberId) }
            .filter { existingDeviceKeys.add(it.deliveryDeviceKey(memberId)) }
            .map { token ->
                PushDelivery(
                    memberId = memberId,
                    eventKey = eventKey,
                    deviceKey = token.deliveryDeviceKey(memberId),
                    deviceTokenId = token.id,
                    tokenFingerprint = token.tokenFingerprint,
                    tokenOwnershipVersion = token.ownershipVersion,
                    deviceFingerprint = token.deviceFingerprint,
                    platform = token.platform,
                    scheduleId = data["scheduleId"]?.toLongOrNull(),
                    payloadType = data["type"]?.take(80),
                )
            }
        if (missing.isNotEmpty()) {
            repository.saveAllAndFlush(missing)
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(
        memberId: Long,
        eventKey: String,
        deviceKey: String,
        fence: PushDispatchFence? = null,
    ): PushDeliveryClaim {
        if (fence != null && fenceValidator?.validate(fence) != true) {
            return PushDeliveryClaim(PushDeliveryClaimOutcome.FENCE_REJECTED)
        }
        val existing = repository.findByMemberIdAndEventKeyAndDeviceKey(
            memberId,
            eventKey,
            deviceKey,
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
                            it.deliveryDeviceKey(memberId) == existing.deviceKey
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
                )
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markSuccess(deliveryId: Long, providerMessageId: String) {
        repository.findById(deliveryId).orElse(null)
            ?.markSuccess(Instant.now(clock), providerMessageId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailure(deliveryId: Long, errorCode: String, errorMessage: String?) {
        repository.findById(deliveryId).orElse(null)
            ?.markFailure(Instant.now(clock), errorCode, errorMessage)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markInvalidToken(deliveryId: Long, errorCode: String, errorMessage: String?) {
        repository.findById(deliveryId).orElse(null)
            ?.markInvalidToken(Instant.now(clock), errorCode, errorMessage)
    }
}

internal fun NotificationDeviceToken.deliveryDeviceKey(memberId: Long): String {
    return if (deviceFingerprint != null) {
        "device-sha256:${OpaquePushIdentifier.fingerprint("$memberId:$deviceFingerprint")}"
    } else {
        "token-sha256:$tokenFingerprint"
    }
}
