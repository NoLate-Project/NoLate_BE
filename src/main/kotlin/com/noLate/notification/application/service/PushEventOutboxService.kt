package com.noLate.notification.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.notification.domain.AppNotification
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushDelivery
import com.noLate.notification.domain.PushLogicalEventKey
import com.noLate.notification.domain.withPushAccountBinding
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

data class PreparedPushEvent(
    val snapshot: AppNotificationSnapshot?,
    val logicalEventKey: String,
    val tokens: List<NotificationDeviceToken>,
    val inboxCreated: Boolean,
    val fenceAccepted: Boolean,
)

/**
 * 사용자 대상 push의 immutable outbox와 전체 device manifest를 한 transaction에서 만든다.
 * schedule fence가 있으면 job row lock/lease/generation/input fingerprint 검증도 같은
 * transaction에 포함된다.
 */
@Service
class PushEventOutboxService(
    private val writer: PushEventOutboxWriter,
) {
    fun prepare(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        deduplicationKey: String?,
        persistInInbox: Boolean,
        tokens: List<NotificationDeviceToken>,
        fence: PushDispatchFence?,
    ): PreparedPushEvent {
        return try {
            writer.prepare(
                memberId,
                title,
                body,
                data,
                deduplicationKey,
                persistInInbox,
                tokens,
                fence,
            )
        } catch (_: DataIntegrityViolationException) {
            // no-fence 동시 caller의 inbox/delivery unique 충돌 transaction을 버리고 재조회한다.
            writer.prepare(
                memberId,
                title,
                body,
                data,
                deduplicationKey,
                persistInInbox,
                tokens,
                fence,
            )
        } catch (_: OptimisticLockingFailureException) {
            // 편집이 pessimistic lock 대기 중 generation/version을 먼저 바꾼 경우 Hibernate가
            // stale snapshot을 보고할 수 있다. 실패 transaction을 버리고 새 transaction에서
            // fence를 다시 읽으면 명확한 rejected 결과로 수렴한다.
            writer.prepare(
                memberId,
                title,
                body,
                data,
                deduplicationKey,
                persistInInbox,
                tokens,
                fence,
            )
        }
    }
}

@Service
class PushEventOutboxWriter(
    private val appNotificationRepository: AppNotificationRepository,
    private val pushDeliveryRepository: PushDeliveryRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val fenceValidator: PushDispatchFenceValidator? = null,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun prepare(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        deduplicationKey: String?,
        persistInInbox: Boolean,
        tokens: List<NotificationDeviceToken>,
        fence: PushDispatchFence?,
    ): PreparedPushEvent {
        if (fence != null && fenceValidator?.validate(fence) != true) {
            return PreparedPushEvent(
                snapshot = null,
                logicalEventKey = "",
                tokens = emptyList(),
                inboxCreated = false,
                fenceAccepted = false,
            )
        }

        val normalizedKey = deduplicationKey
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(180)
        val existing = if (persistInInbox && normalizedKey != null) {
            appNotificationRepository.findByMemberIdAndDeduplicationKey(memberId, normalizedKey)
        } else {
            null
        }
        val logicalEventKey = existing?.logicalEventKey
            ?: normalizedKey?.let { PushLogicalEventKey.deterministic(memberId, it) }
            ?: PushLogicalEventKey.newEvent()
        val canonicalData = data.withPushAccountBinding(logicalEventKey, memberId)
        val notification = when {
            existing != null -> existing
            persistInInbox -> appNotificationRepository.saveAndFlush(
                AppNotification(
                    memberId = memberId,
                    deduplicationKey = normalizedKey,
                    logicalEventKey = logicalEventKey,
                    type = canonicalData["type"]?.trim()?.takeIf(String::isNotEmpty)?.take(80)
                        ?: "GENERAL",
                    scheduleId = canonicalData["scheduleId"]?.toLongOrNull(),
                    categoryId = canonicalData["categoryId"]?.toLongOrNull(),
                    title = title.take(200),
                    body = body.take(1000),
                    dataJson = objectMapper.writeValueAsString(canonicalData),
                    createdAt = Instant.now(clock),
                )
            )
            else -> null
        }
        val snapshot = notification?.toSnapshot(objectMapper)
        val effectiveData = snapshot?.data ?: canonicalData
        val existingDeviceKeys = pushDeliveryRepository
            .findAllByMemberIdAndEventKey(memberId, logicalEventKey)
            .mapTo(mutableSetOf()) { it.deviceKey }
        val missing = tokens
            .distinctBy { it.deliveryDeviceKey(memberId) }
            .filter { existingDeviceKeys.add(it.deliveryDeviceKey(memberId)) }
            .map { token ->
                PushDelivery(
                    memberId = memberId,
                    eventKey = logicalEventKey,
                    deviceKey = token.deliveryDeviceKey(memberId),
                    deviceTokenId = token.id,
                    tokenFingerprint = token.tokenFingerprint,
                    tokenOwnershipVersion = token.ownershipVersion,
                    deviceFingerprint = token.deviceFingerprint,
                    platform = token.platform,
                    scheduleId = effectiveData["scheduleId"]?.toLongOrNull(),
                    payloadType = effectiveData["type"]?.take(80),
                )
            }
        if (missing.isNotEmpty()) {
            pushDeliveryRepository.saveAllAndFlush(missing)
        }
        return PreparedPushEvent(
            snapshot = snapshot,
            logicalEventKey = logicalEventKey,
            tokens = tokens,
            inboxCreated = notification != null && existing == null,
            fenceAccepted = true,
        )
    }
}
