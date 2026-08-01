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

const val CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION = 1

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
            name = "idx_push_deliveries_reliability_cohort",
            columnList =
                "status, delivered_at, delivery_ack_capability_version, client_received_at",
        ),
        Index(
            name = "idx_push_deliveries_schedule_id",
            columnList = "schedule_id",
        ),
        Index(
            name = "idx_push_deliveries_calendar_id",
            columnList = "calendar_id",
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

    /**
     * Frozen shared-calendar grant identity. A redrive validates this snapshot rather than
     * attaching the delivery to whichever calendar/device state happens to exist later.
     */
    @Column(name = "calendar_id")
    val calendarId: Long? = null,

    @Column(name = "payload_type", length = 80)
    val payloadType: String? = null,

    /** Frozen client ACK protocol capability captured with the token manifest. */
    @Column(name = "delivery_ack_capability_version")
    val deliveryAckCapabilityVersion: Int? = null,

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

    /** Server receipt times of authenticated client lifecycle ACKs, not device wall-clock times. */
    /** Server receipt time of the first authenticated RECEIVED acknowledgement. */
    @Column(name = "client_received_at")
    var clientReceivedAt: Instant? = null,

    /** Server receipt time of the first authenticated PRESENTED acknowledgement. */
    @Column(name = "client_presented_at")
    var clientPresentedAt: Instant? = null,

    /** Server receipt time of the first authenticated ALARM_SCHEDULED acknowledgement. */
    @Column(name = "alarm_scheduled_at")
    var alarmScheduledAt: Instant? = null,

    /** Server receipt time of the first authenticated ALARM_FIRED acknowledgement. */
    @Column(name = "alarm_fired_at")
    var alarmFiredAt: Instant? = null,

    /** Server receipt time of the first authenticated ACTIONED acknowledgement. */
    @Column(name = "client_actioned_at")
    var clientActionedAt: Instant? = null,

    /** Server receipt time of the latest first-seen client acknowledgement. */
    @Column(name = "client_ack_recorded_at")
    var clientAckRecordedAt: Instant? = null,
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

    fun markSuccess(at: Instant, messageId: String): Boolean {
        if (status != PushDeliveryStatus.DISPATCHING) return false
        status = PushDeliveryStatus.SUCCESS
        deliveredAt = at
        providerMessageId = messageId.take(300)
        errorCode = null
        errorMessage = null
        return true
    }

    fun markFailure(at: Instant, code: String, message: String?): Boolean {
        if (status != PushDeliveryStatus.DISPATCHING) return false
        status = PushDeliveryStatus.FAILED
        clearClientAcknowledgements()
        lastAttemptedAt = at
        errorCode = code.take(120)
        errorMessage = message?.take(1000)
        return true
    }

    /**
     * The authoritative source became temporarily busy after this delivery was claimed but before
     * the provider ownership lease was acquired. No provider call occurred, so restore a safely
     * retryable state and return the provisional claim attempt to the delivery budget.
     */
    fun deferBeforeProvider(reason: String): Boolean {
        if (status != PushDeliveryStatus.DISPATCHING || attemptCount <= 0) return false
        attemptCount -= 1
        status = if (attemptCount == 0) {
            firstAttemptedAt = null
            lastAttemptedAt = null
            errorCode = null
            errorMessage = null
            PushDeliveryStatus.PENDING
        } else {
            errorCode = "AUTHORITATIVE_SOURCE_PROCESSING"
            errorMessage = reason.take(1000)
            PushDeliveryStatus.FAILED
        }
        return true
    }

    fun markConfirmedFailureSuperseded(at: Instant, reason: String): Boolean {
        if (status != PushDeliveryStatus.DISPATCHING) return false
        status = PushDeliveryStatus.SUPERSEDED
        clearClientAcknowledgements()
        lastAttemptedAt = at
        errorCode = "SCHEDULE_SOURCE_TERMINAL"
        errorMessage = reason.take(1000)
        return true
    }

    fun markDispatchSuperseded(
        at: Instant,
        code: String,
        reason: String,
    ): Boolean {
        if (status != PushDeliveryStatus.DISPATCHING) return false
        status = PushDeliveryStatus.SUPERSEDED
        clearClientAcknowledgements()
        lastAttemptedAt = at
        errorCode = code.take(120)
        errorMessage = reason.take(1000)
        return true
    }

    fun markInvalidToken(at: Instant, code: String, message: String?) {
        if (status != PushDeliveryStatus.DISPATCHING) return
        status = PushDeliveryStatus.INVALID_TOKEN
        clearClientAcknowledgements()
        lastAttemptedAt = at
        errorCode = code.take(120)
        errorMessage = message?.take(1000)
    }

    fun markSuperseded(
        at: Instant,
        code: String,
        reason: String,
    ) {
        if (status != PushDeliveryStatus.PENDING && status != PushDeliveryStatus.FAILED) return
        status = PushDeliveryStatus.SUPERSEDED
        lastAttemptedAt = at
        errorCode = code.take(120)
        errorMessage = reason.take(1000)
    }

    fun markExhausted(at: Instant, maxAttempts: Int) {
        if (status != PushDeliveryStatus.FAILED || attemptCount < maxAttempts) return
        status = PushDeliveryStatus.EXHAUSTED
        lastAttemptedAt = at
        errorCode = "DELIVERY_ATTEMPT_LIMIT_REACHED"
        errorMessage = "Push delivery reached the per-device retry limit."
    }

    /**
     * Records each client lifecycle stage once. Replayed foreground/background callbacks are
     * deliberately idempotent and cannot move an already-observed timestamp.
     */
    fun acknowledgeClientStage(
        stage: PushClientAckStage,
        occurredAt: Instant,
        recordedAt: Instant,
    ): Boolean {
        val changed = when (stage) {
            PushClientAckStage.RECEIVED -> setIfAbsent(clientReceivedAt, occurredAt) {
                clientReceivedAt = it
            }
            PushClientAckStage.PRESENTED -> setIfAbsent(clientPresentedAt, occurredAt) {
                clientPresentedAt = it
            }
            PushClientAckStage.ALARM_SCHEDULED -> setIfAbsent(alarmScheduledAt, occurredAt) {
                alarmScheduledAt = it
            }
            PushClientAckStage.ALARM_FIRED -> setIfAbsent(alarmFiredAt, occurredAt) {
                alarmFiredAt = it
            }
            PushClientAckStage.ACTIONED -> setIfAbsent(clientActionedAt, occurredAt) {
                clientActionedAt = it
            }
        }
        if (changed) {
            clientAckRecordedAt = recordedAt
        }
        return changed
    }

    private inline fun setIfAbsent(
        current: Instant?,
        value: Instant,
        setter: (Instant) -> Unit,
    ): Boolean {
        if (current != null) return false
        setter(value)
        return true
    }

    private fun clearClientAcknowledgements() {
        clientReceivedAt = null
        clientPresentedAt = null
        alarmScheduledAt = null
        alarmFiredAt = null
        clientActionedAt = null
        clientAckRecordedAt = null
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
    /** 확인된 실패 재시도 횟수를 모두 사용해 더 이상 provider를 호출하지 않는 terminal 상태 */
    EXHAUSTED,
    /** Manifest 이후 token/member/device ownership이 바뀌어 stale snapshot을 보내지 않았다. */
    SUPERSEDED,
}

enum class PushClientAckStage {
    RECEIVED,
    PRESENTED,
    ALARM_SCHEDULED,
    ALARM_FIRED,
    ACTIONED,
}
