package com.noLate.notification.application.service

import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushDelivery
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.infrastructure.PushDeliveryRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

data class PushDeliveryClaim(
    val outcome: PushDeliveryClaimOutcome,
    val deliveryId: Long? = null,
)

enum class PushDeliveryClaimOutcome {
    SEND,
    ALREADY_SUCCESS,
    /** 호출 전 상태가 남았으므로 성공 여부가 모호하다. 중복 방지를 위해 자동 재시도하지 않는다. */
    AMBIGUOUS,
    INVALID_TOKEN,
    /** inbox는 기존 이벤트인데 기기 경계가 없으면 과거 호출 가능성을 우선해 보내지 않는다. */
    DEDUPLICATED,
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
        token: NotificationDeviceToken,
        data: Map<String, String>,
        allowCreate: Boolean,
    ): PushDeliveryClaim {
        val deviceKey = token.deliveryDeviceKey()
        val normalizedEventKey = eventKey.take(100)
        return try {
            writer.claim(
                memberId = memberId,
                eventKey = normalizedEventKey,
                deviceKey = deviceKey,
                token = token,
                data = data,
                allowCreate = allowCreate,
            )
        } catch (_: DataIntegrityViolationException) {
            // 두 caller가 inbox 없이 같은 명시적 key를 사용한 경우 유니크 제약이 최종 경계다.
            writer.observe(memberId, normalizedEventKey, deviceKey)
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
    private val clock: Clock,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(
        memberId: Long,
        eventKey: String,
        deviceKey: String,
        token: NotificationDeviceToken,
        data: Map<String, String>,
        allowCreate: Boolean,
    ): PushDeliveryClaim {
        val existing = repository.findByMemberIdAndEventKeyAndDeviceKey(
            memberId,
            eventKey,
            deviceKey,
        )
        if (existing != null) {
            return when (existing.status) {
                PushDeliveryStatus.SUCCESS ->
                    PushDeliveryClaim(PushDeliveryClaimOutcome.ALREADY_SUCCESS, existing.id)

                PushDeliveryStatus.DISPATCHING ->
                    PushDeliveryClaim(PushDeliveryClaimOutcome.AMBIGUOUS, existing.id)

                PushDeliveryStatus.INVALID_TOKEN ->
                    PushDeliveryClaim(PushDeliveryClaimOutcome.INVALID_TOKEN, existing.id)

                PushDeliveryStatus.FAILED -> {
                    existing.retry(Instant.now(clock))
                    repository.saveAndFlush(existing)
                    PushDeliveryClaim(PushDeliveryClaimOutcome.SEND, requireNotNull(existing.id))
                }
            }
        }

        if (!allowCreate) {
            return PushDeliveryClaim(PushDeliveryClaimOutcome.DEDUPLICATED)
        }

        val now = Instant.now(clock)
        val created = repository.saveAndFlush(
            PushDelivery(
                memberId = memberId,
                eventKey = eventKey,
                deviceKey = deviceKey,
                deviceTokenId = token.id,
                deviceId = token.deviceId?.take(100),
                platform = token.platform,
                scheduleId = data["scheduleId"]?.toLongOrNull(),
                payloadType = data["type"]?.take(80),
                firstAttemptedAt = now,
                lastAttemptedAt = now,
            )
        )
        return PushDeliveryClaim(PushDeliveryClaimOutcome.SEND, requireNotNull(created.id))
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun observe(memberId: Long, eventKey: String, deviceKey: String): PushDeliveryClaim {
        val existing = repository.findByMemberIdAndEventKeyAndDeviceKey(
            memberId,
            eventKey,
            deviceKey,
        ) ?: return PushDeliveryClaim(PushDeliveryClaimOutcome.DEDUPLICATED)
        return when (existing.status) {
            PushDeliveryStatus.SUCCESS ->
                PushDeliveryClaim(PushDeliveryClaimOutcome.ALREADY_SUCCESS, existing.id)
            PushDeliveryStatus.INVALID_TOKEN ->
                PushDeliveryClaim(PushDeliveryClaimOutcome.INVALID_TOKEN, existing.id)
            PushDeliveryStatus.DISPATCHING,
            PushDeliveryStatus.FAILED ->
                // 유니크 경합 caller는 소유권을 얻지 못했으므로 FAILED여도 보내지 않는다.
                PushDeliveryClaim(PushDeliveryClaimOutcome.AMBIGUOUS, existing.id)
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

private fun NotificationDeviceToken.deliveryDeviceKey(): String =
    id?.let { "token-id:$it" }
        ?: "token-sha256:${token.sha256()}"

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
