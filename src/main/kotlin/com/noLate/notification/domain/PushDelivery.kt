package com.noLate.notification.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant

/**
 * 논리 알림 한 건을 특정 기기에 전달하려는 영속 경계다.
 *
 * provider 호출 전에 DISPATCHING 상태를 별도 트랜잭션으로 커밋한다. 응답 직후 프로세스가
 * 종료돼 성공 여부를 DB에 반영하지 못하더라도 같은 이벤트/기기를 다시 호출하지 않는
 * at-most-once 경계다. 확인된 provider 실패만 FAILED로 전환해 재시도를 허용한다.
 */
@Entity
@Table(
    name = "push_deliveries",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_push_deliveries_member_event_device",
            columnNames = ["member_id", "event_key", "device_key"],
        )
    ],
    indexes = [
        Index(
            name = "idx_push_deliveries_member_event",
            columnList = "member_id, event_key",
        ),
        Index(
            name = "idx_push_deliveries_status_attempted_at",
            columnList = "status, last_attempted_at",
        ),
        Index(
            name = "idx_push_deliveries_schedule_id",
            columnList = "schedule_id",
        ),
    ],
)
class PushDelivery(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "event_key", nullable = false, length = 100)
    val eventKey: String,

    /**
     * 안정 device id 또는 push token의 SHA-256 fingerprint다.
     * token row PK가 달라도 같은 실제 기기/토큰이면 하나의 전달 경계로 수렴한다.
     */
    @Column(name = "device_key", nullable = false, length = 100)
    val deviceKey: String,

    @Column(name = "device_token_id")
    val deviceTokenId: Long? = null,

    @Column(name = "token_fingerprint", nullable = false, length = 64)
    val tokenFingerprint: String,

    @Column(name = "token_ownership_version", nullable = false)
    val tokenOwnershipVersion: Long,

    @Column(name = "device_fingerprint", length = 64)
    val deviceFingerprint: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val platform: PushPlatform,

    @Column(name = "schedule_id")
    val scheduleId: Long? = null,

    @Column(name = "payload_type", length = 80)
    val payloadType: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: PushDeliveryStatus = PushDeliveryStatus.PENDING,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "first_attempted_at")
    var firstAttemptedAt: Instant? = null,

    @Column(name = "last_attempted_at")
    var lastAttemptedAt: Instant? = null,

    @Column(name = "delivered_at")
    var deliveredAt: Instant? = null,

    @Column(name = "provider_message_id", length = 300)
    var providerMessageId: String? = null,

    @Column(name = "error_code", length = 120)
    var errorCode: String? = null,

    @Column(name = "error_message", length = 1000)
    var errorMessage: String? = null,
) {

    fun beginDispatch(at: Instant) {
        require(status == PushDeliveryStatus.PENDING || status == PushDeliveryStatus.FAILED) {
            "대기 또는 확인된 실패 상태만 발송할 수 있습니다. status=$status"
        }
        status = PushDeliveryStatus.DISPATCHING
        attemptCount += 1
        if (firstAttemptedAt == null) {
            firstAttemptedAt = at
        }
        lastAttemptedAt = at
        errorCode = null
        errorMessage = null
    }

    fun markSuccess(at: Instant, messageId: String) {
        if (status != PushDeliveryStatus.DISPATCHING) return
        status = PushDeliveryStatus.SUCCESS
        deliveredAt = at
        providerMessageId = messageId.take(300)
        errorCode = null
        errorMessage = null
    }

    fun markFailure(at: Instant, code: String, message: String?) {
        if (status != PushDeliveryStatus.DISPATCHING) return
        status = PushDeliveryStatus.FAILED
        lastAttemptedAt = at
        errorCode = code.take(120)
        errorMessage = message?.take(1000)
    }

    fun markInvalidToken(at: Instant, code: String, message: String?) {
        if (status != PushDeliveryStatus.DISPATCHING) return
        status = PushDeliveryStatus.INVALID_TOKEN
        lastAttemptedAt = at
        errorCode = code.take(120)
        errorMessage = message?.take(1000)
    }

    fun markSuperseded(at: Instant, reason: String) {
        if (status != PushDeliveryStatus.PENDING && status != PushDeliveryStatus.FAILED) return
        status = PushDeliveryStatus.SUPERSEDED
        lastAttemptedAt = at
        errorCode = "TOKEN_OWNERSHIP_CHANGED"
        errorMessage = reason.take(1000)
    }

    protected constructor() : this(
        memberId = 0L,
        eventKey = "",
        deviceKey = "",
        tokenFingerprint = "",
        tokenOwnershipVersion = 0,
        platform = PushPlatform.UNKNOWN,
    )
}

enum class PushDeliveryStatus {
    /** 현재 이벤트의 기기 manifest에는 포함됐지만 아직 provider 호출 경계를 만들지 않았다. */
    PENDING,
    /** Provider 호출 전 커밋되며, 성공 여부가 모호한 종료 뒤에는 자동 재시도하지 않는다. */
    DISPATCHING,
    SUCCESS,
    /** Provider가 실패를 명시적으로 반환해 안전하게 재시도할 수 있다. */
    FAILED,
    INVALID_TOKEN,
    /** Manifest 이후 token/member/device ownership이 바뀌어 stale snapshot을 보내지 않았다. */
    SUPERSEDED,
}
