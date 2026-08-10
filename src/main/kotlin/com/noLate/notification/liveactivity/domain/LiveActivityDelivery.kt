package com.noLate.notification.liveactivity.domain

import com.noLate.global.common.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Lob
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(
    name = "live_activity_deliveries",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_live_activity_delivery_event_key", columnNames = ["event_key"]),
    ],
    indexes = [
        Index(name = "idx_live_activity_delivery_due", columnList = "status, available_at, id"),
        Index(name = "idx_live_activity_delivery_activity", columnList = "live_activity_id, revision, id"),
        Index(name = "idx_live_activity_delivery_member", columnList = "member_id, schedule_id, id"),
    ],
)
class LiveActivityDelivery(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0,

    @Column(name = "event_key", nullable = false, length = 160)
    val eventKey: String,

    @Column(name = "live_activity_id", nullable = false)
    val liveActivityId: Long,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "schedule_id", nullable = false)
    val scheduleId: Long,

    @Column(name = "push_device_token_id", nullable = false)
    val pushDeviceTokenId: Long,

    @Column(name = "fcm_token_fingerprint", nullable = false, length = 64)
    val fcmTokenFingerprint: String,

    @Column(name = "fcm_ownership_version", nullable = false)
    val fcmOwnershipVersion: Long,

    @Column(name = "device_fingerprint", nullable = false, length = 64)
    val deviceFingerprint: String,

    @Column(name = "activity_token", nullable = false, length = 500)
    val activityToken: String,

    @Column(name = "activity_token_fingerprint", nullable = false, length = 64)
    val activityTokenFingerprint: String,

    /** START credential source snapshot. UPDATE/END use the per-activity token on state. */
    @Column(name = "start_token_id")
    val startTokenId: Long? = null,

    @Column(name = "start_token_generation")
    val startTokenGeneration: Long? = null,

    /** UPDATE/END credential epoch frozen with the immutable provider attempt. */
    @Column(name = "update_token_generation")
    val updateTokenGeneration: Long? = null,

    /** Client ActivityKit instance identity snapshot; null only for remote START. */
    @Column(name = "target_activity_id", length = 200)
    val targetActivityId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 20)
    val operation: LiveActivityDeliveryOperation,

    @Column(name = "revision", nullable = false)
    val revision: Long,

    /** Immutable schedule-push generation fence captured when this delivery was enqueued. */
    @Column(name = "source_notification_generation", nullable = false)
    val sourceNotificationGeneration: Long,

    @Column(name = "apns_timestamp", nullable = false)
    val apnsTimestamp: Long,

    @Column(name = "activity_type", nullable = false, length = 120)
    val activityType: String,

    @Lob
    @Column(name = "attributes_json", columnDefinition = "TEXT")
    val attributesJson: String? = null,

    @Lob
    @Column(name = "content_state_json", nullable = false, columnDefinition = "TEXT")
    val contentStateJson: String,

    @Column(name = "alert_title", length = 100)
    val alertTitle: String? = null,

    @Column(name = "alert_body", length = 300)
    val alertBody: String? = null,

    @Column(name = "stale_at")
    val staleAt: Instant? = null,

    @Column(name = "dismissal_at")
    val dismissalAt: Instant? = null,

    @Column(name = "expiration_at", nullable = false)
    val expirationAt: Instant,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: LiveActivityDeliveryStatus = LiveActivityDeliveryStatus.PENDING,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "available_at", nullable = false)
    var availableAt: Instant,

    @Column(name = "first_attempted_at")
    var firstAttemptedAt: Instant? = null,

    @Column(name = "last_attempted_at")
    var lastAttemptedAt: Instant? = null,

    @Column(name = "provider_message_id", length = 300)
    var providerMessageId: String? = null,

    @Column(name = "error_code", length = 120)
    var errorCode: String? = null,

    @Column(name = "error_message", length = 500)
    var errorMessage: String? = null,

    @Column(name = "dispatch_lease_id", length = 64)
    var dispatchLeaseId: String? = null,

    @Column(name = "dispatch_lease_until")
    var dispatchLeaseUntil: Instant? = null,
) : BaseEntity() {
    fun beginDispatch(now: Instant, leaseId: String, leaseUntil: Instant) {
        require(status == LiveActivityDeliveryStatus.PENDING || status == LiveActivityDeliveryStatus.FAILED)
        status = LiveActivityDeliveryStatus.DISPATCHING
        attemptCount += 1
        firstAttemptedAt = firstAttemptedAt ?: now
        lastAttemptedAt = now
        errorCode = null
        errorMessage = null
        dispatchLeaseId = leaseId
        dispatchLeaseUntil = leaseUntil
    }

    fun markSuccess(now: Instant, messageId: String) {
        if (status != LiveActivityDeliveryStatus.DISPATCHING) return
        status = LiveActivityDeliveryStatus.SUCCESS
        lastAttemptedAt = now
        providerMessageId = messageId.take(300)
        clearLease()
    }

    fun markConfirmedFailure(now: Instant, code: String, message: String?, retryAt: Instant) {
        if (status != LiveActivityDeliveryStatus.DISPATCHING) return
        status = LiveActivityDeliveryStatus.FAILED
        lastAttemptedAt = now
        availableAt = retryAt
        errorCode = code.take(120)
        errorMessage = message?.take(500)
        clearLease()
    }

    fun markInvalid(now: Instant, code: String) {
        if (status != LiveActivityDeliveryStatus.DISPATCHING) return
        status = LiveActivityDeliveryStatus.INVALID
        lastAttemptedAt = now
        errorCode = code.take(120)
        errorMessage = null
        clearLease()
    }

    fun markAmbiguous(now: Instant, code: String) {
        if (status != LiveActivityDeliveryStatus.DISPATCHING) return
        // Never blindly replay an uncertain provider boundary. The coordinator separately applies
        // monotonic desired-state reconciliation without changing this terminal delivery fact.
        status = LiveActivityDeliveryStatus.UNCERTAIN
        lastAttemptedAt = now
        errorCode = code.take(120)
        errorMessage = null
        clearLease()
    }

    /**
     * END is idempotent for one immutable ActivityKit instance. An unknown provider boundary is
     * therefore safe to retry with the exact same token, timestamp, revision, and content state;
     * unlike START, it must not be converted into a false local success.
     */
    fun markRetryableAfterAmbiguous(now: Instant, code: String, retryAt: Instant) {
        if (status != LiveActivityDeliveryStatus.DISPATCHING) return
        status = LiveActivityDeliveryStatus.FAILED
        lastAttemptedAt = now
        availableAt = retryAt
        errorCode = code.take(120)
        errorMessage = null
        clearLease()
    }

    fun markSuperseded(now: Instant, code: String) {
        if (status !in setOf(LiveActivityDeliveryStatus.PENDING, LiveActivityDeliveryStatus.FAILED)) return
        status = LiveActivityDeliveryStatus.SUPERSEDED
        lastAttemptedAt = now
        errorCode = code.take(120)
        errorMessage = null
        clearLease()
    }

    /** Authenticated ActivityKit observation is stronger than any unresolved provider attempt. */
    fun markClientReconciled(now: Instant, code: String) {
        if (status in setOf(
                LiveActivityDeliveryStatus.SUCCESS,
                LiveActivityDeliveryStatus.SUPERSEDED,
            )
        ) return
        status = LiveActivityDeliveryStatus.SUPERSEDED
        lastAttemptedAt = now
        errorCode = code.take(120)
        errorMessage = null
        clearLease()
    }

    fun markExhausted(now: Instant) {
        status = LiveActivityDeliveryStatus.EXHAUSTED
        lastAttemptedAt = now
        errorCode = "ATTEMPT_LIMIT_REACHED"
        errorMessage = null
        clearLease()
    }

    fun requeueKnownUnaccepted(now: Instant): Boolean {
        if (status !in setOf(
                LiveActivityDeliveryStatus.SUPERSEDED,
                LiveActivityDeliveryStatus.EXHAUSTED,
            )
        ) return false
        status = LiveActivityDeliveryStatus.PENDING
        attemptCount = 0
        availableAt = now
        providerMessageId = null
        errorCode = null
        errorMessage = null
        clearLease()
        return true
    }

    fun resetAttemptCycleForEndRetry() {
        require(operation == LiveActivityDeliveryOperation.END)
        require(status in setOf(LiveActivityDeliveryStatus.PENDING, LiveActivityDeliveryStatus.FAILED))
        attemptCount = 0
    }

    fun ownsLease(leaseId: String): Boolean = dispatchLeaseId == leaseId

    private fun clearLease() {
        dispatchLeaseId = null
        dispatchLeaseUntil = null
    }

    protected constructor() : this(
        eventKey = "",
        liveActivityId = 0,
        memberId = 0,
        scheduleId = 0,
        pushDeviceTokenId = 0,
        fcmTokenFingerprint = "",
        fcmOwnershipVersion = 0,
        deviceFingerprint = "",
        activityToken = "",
        activityTokenFingerprint = "",
        startTokenId = null,
        startTokenGeneration = null,
        updateTokenGeneration = null,
        targetActivityId = null,
        operation = LiveActivityDeliveryOperation.UPDATE,
        revision = 0,
        sourceNotificationGeneration = 0,
        apnsTimestamp = 0,
        activityType = "",
        contentStateJson = "{}",
        expirationAt = Instant.EPOCH,
        availableAt = Instant.EPOCH,
    )
}

enum class LiveActivityDeliveryOperation {
    START,
    UPDATE,
    END,
}

enum class LiveActivityDeliveryStatus {
    PENDING,
    DISPATCHING,
    SUCCESS,
    FAILED,
    /** Provider acceptance was ambiguous; automatic replay is forbidden. */
    UNCERTAIN,
    INVALID,
    EXHAUSTED,
    SUPERSEDED,
}
