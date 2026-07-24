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
     * DB token PK 기반 식별자다. 저장 전 테스트 토큰만 SHA-256 fingerprint를 사용한다.
     * 원문 push token은 이 테이블과 로그 어느 쪽에도 기록하지 않는다.
     */
    @Column(name = "device_key", nullable = false, length = 100)
    val deviceKey: String,

    @Column(name = "device_token_id")
    val deviceTokenId: Long? = null,

    @Column(name = "device_id", length = 100)
    val deviceId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val platform: PushPlatform,

    @Column(name = "schedule_id")
    val scheduleId: Long? = null,

    @Column(name = "payload_type", length = 80)
    val payloadType: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: PushDeliveryStatus = PushDeliveryStatus.DISPATCHING,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 1,

    @Column(name = "first_attempted_at", nullable = false)
    val firstAttemptedAt: Instant,

    @Column(name = "last_attempted_at", nullable = false)
    var lastAttemptedAt: Instant,

    @Column(name = "delivered_at")
    var deliveredAt: Instant? = null,

    @Column(name = "provider_message_id", length = 300)
    var providerMessageId: String? = null,

    @Column(name = "error_code", length = 120)
    var errorCode: String? = null,

    @Column(name = "error_message", length = 1000)
    var errorMessage: String? = null,
) {

    fun retry(at: Instant) {
        require(status == PushDeliveryStatus.FAILED) {
            "확인된 실패 상태만 재시도할 수 있습니다. status=$status"
        }
        status = PushDeliveryStatus.DISPATCHING
        attemptCount += 1
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

    protected constructor() : this(
        memberId = 0L,
        eventKey = "",
        deviceKey = "",
        platform = PushPlatform.UNKNOWN,
        firstAttemptedAt = Instant.EPOCH,
        lastAttemptedAt = Instant.EPOCH,
    )
}

enum class PushDeliveryStatus {
    /** Provider 호출 전 커밋되며, 성공 여부가 모호한 종료 뒤에는 자동 재시도하지 않는다. */
    DISPATCHING,
    SUCCESS,
    /** Provider가 실패를 명시적으로 반환해 안전하게 재시도할 수 있다. */
    FAILED,
    INVALID_TOKEN,
}
