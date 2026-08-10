package com.noLate.notification.liveactivity.domain

import com.noLate.global.common.BaseEntity
import com.noLate.notification.domain.OpaquePushIdentifier
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
    name = "schedule_live_activities",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_schedule_live_activity_schedule_member_device",
            columnNames = ["schedule_id", "member_id", "device_fingerprint"],
        ),
        UniqueConstraint(
            name = "uk_schedule_live_activity_update_token_fingerprint",
            columnNames = ["update_token_fingerprint"],
        ),
    ],
    indexes = [
        Index(name = "idx_schedule_live_activity_member_state", columnList = "member_id, state, id"),
        Index(name = "idx_schedule_live_activity_expiry", columnList = "state, expires_at, id"),
        Index(
            name = "idx_schedule_live_activity_terminal_retention",
            columnList = "state, ended_at, id",
        ),
        Index(name = "idx_schedule_live_activity_lease", columnList = "dispatch_lease_until, id"),
    ],
)
class ScheduleLiveActivity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "schedule_id", nullable = false)
    val scheduleId: Long,

    @Column(name = "push_device_token_id", nullable = false)
    var pushDeviceTokenId: Long,

    /** Ownership epoch paired with [pushDeviceTokenId]; a row id alone is not a stable route. */
    @Column(name = "bound_fcm_ownership_version", nullable = false)
    var boundFcmOwnershipVersion: Long = 0,

    @Column(name = "device_fingerprint", nullable = false, length = 64)
    val deviceFingerprint: String,

    @Column(name = "activity_type", nullable = false, length = 120)
    val activityType: String,

    @Column(name = "start_token_id")
    var startTokenId: Long? = null,

    @Column(name = "start_token_fingerprint", length = 64)
    var startTokenFingerprint: String? = null,

    @Column(name = "start_token_generation")
    var startTokenGeneration: Long? = null,

    @Column(name = "activity_id", length = 200)
    var activityId: String? = null,

    /** ActivityKit credential. Never include this value in logs, responses, or errors. */
    @Column(name = "update_token", length = 500)
    var updateToken: String? = null,

    @Column(name = "update_token_fingerprint", length = 64)
    var updateTokenFingerprint: String? = null,

    @Column(name = "update_token_generation", nullable = false)
    var updateTokenGeneration: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    var state: ScheduleLiveActivityState = ScheduleLiveActivityState.START_PENDING,

    @Column(name = "source_notification_generation", nullable = false)
    var sourceNotificationGeneration: Long,

    @Column(name = "source_check_count", nullable = false)
    var sourceCheckCount: Int,

    @Column(name = "content_revision", nullable = false)
    var contentRevision: Long = 1,

    @Column(name = "content_fingerprint", nullable = false, length = 64)
    var contentFingerprint: String,

    @Lob
    @Column(name = "attributes_json", nullable = false, columnDefinition = "TEXT")
    var attributesJson: String,

    @Lob
    @Column(name = "content_state_json", nullable = false, columnDefinition = "TEXT")
    var contentStateJson: String,

    @Column(name = "last_apns_timestamp", nullable = false)
    var lastApnsTimestamp: Long = 0,

    @Column(name = "stale_at")
    var staleAt: Instant? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "ended_at")
    var endedAt: Instant? = null,

    @Column(name = "end_reason", length = 80)
    var endReason: String? = null,

    /** Frozen authorization proof for an ACK that was already in flight when logout committed. */
    @Column(name = "late_end_ack_session_generation")
    var lateEndAckSessionGeneration: Long? = null,

    @Column(name = "late_end_ack_push_device_token_id")
    var lateEndAckPushDeviceTokenId: Long? = null,

    @Column(name = "late_end_ack_fcm_ownership_version")
    var lateEndAckFcmOwnershipVersion: Long? = null,

    @Column(name = "dispatch_lease_id", length = 64)
    var dispatchLeaseId: String? = null,

    @Column(name = "dispatch_lease_until")
    var dispatchLeaseUntil: Instant? = null,
) : BaseEntity() {
    fun sourceIsNewer(notificationGeneration: Long, checkCount: Int): Boolean =
        notificationGeneration > sourceNotificationGeneration ||
            (notificationGeneration == sourceNotificationGeneration && checkCount > sourceCheckCount)

    fun bindFcmRoute(pushDeviceTokenId: Long, ownershipVersion: Long) {
        require(pushDeviceTokenId > 0)
        require(ownershipVersion >= 0)
        this.pushDeviceTokenId = pushDeviceTokenId
        this.boundFcmOwnershipVersion = ownershipVersion
    }

    fun refreshProjection(
        pushDeviceTokenId: Long,
        startTokenId: Long,
        startTokenFingerprint: String,
        startTokenGeneration: Long,
        notificationGeneration: Long,
        checkCount: Int,
        fingerprint: String,
        contentJson: String,
        staleAt: Instant?,
        expiresAt: Instant,
        fcmOwnershipVersion: Long = boundFcmOwnershipVersion,
    ): Boolean {
        if (notificationGeneration != sourceNotificationGeneration) return false
        if (!sourceIsNewer(notificationGeneration, checkCount)) return false
        sourceNotificationGeneration = notificationGeneration
        sourceCheckCount = checkCount
        // START_SENT describes one provider attempt. Keep that attempted credential immutable
        // while newer ETA content advances. An ambiguous START remains fail-closed and is never
        // replayed merely because a later push-to-start credential appears.
        if (state != ScheduleLiveActivityState.START_SENT) {
            bindFcmRoute(pushDeviceTokenId, fcmOwnershipVersion)
            this.startTokenId = startTokenId
            this.startTokenFingerprint = startTokenFingerprint
            this.startTokenGeneration = startTokenGeneration
        }
        this.staleAt = staleAt
        this.expiresAt = expiresAt
        if (contentFingerprint == fingerprint) return false
        contentRevision = Math.addExact(contentRevision, 1)
        contentFingerprint = fingerprint
        contentStateJson = contentJson
        return true
    }

    fun requiresGenerationRestart(notificationGeneration: Long): Boolean =
        notificationGeneration > sourceNotificationGeneration

    /**
     * Activity attributes are immutable on iOS. A schedule notification generation change must
     * therefore create a fresh remote Activity instead of updating the old instance with stale
     * action identity/attributes.
     */
    fun resetForGeneration(
        pushDeviceTokenId: Long,
        startTokenId: Long,
        startTokenFingerprint: String,
        startTokenGeneration: Long,
        notificationGeneration: Long,
        checkCount: Int,
        fingerprint: String,
        attributesJson: String,
        contentJson: String,
        staleAt: Instant?,
        expiresAt: Instant,
        fcmOwnershipVersion: Long = boundFcmOwnershipVersion,
    ) {
        require(notificationGeneration > sourceNotificationGeneration)
        bindFcmRoute(pushDeviceTokenId, fcmOwnershipVersion)
        this.startTokenId = startTokenId
        this.startTokenFingerprint = startTokenFingerprint
        this.startTokenGeneration = startTokenGeneration
        this.sourceNotificationGeneration = notificationGeneration
        this.sourceCheckCount = checkCount
        this.contentRevision = Math.addExact(contentRevision, 1)
        this.contentFingerprint = fingerprint
        this.attributesJson = attributesJson
        this.contentStateJson = contentJson
        this.staleAt = staleAt
        this.expiresAt = expiresAt
        this.activityId = null
        this.updateToken = null
        this.updateTokenFingerprint = null
        this.updateTokenGeneration = 0
        this.state = ScheduleLiveActivityState.START_PENDING
        this.endedAt = null
        this.endReason = null
        clearLateEndAckAuthorization()
    }

    fun retryInvalidStartCredential(
        pushDeviceTokenId: Long,
        startTokenId: Long,
        startTokenFingerprint: String,
        startTokenGeneration: Long,
        contentJson: String,
        fcmOwnershipVersion: Long = boundFcmOwnershipVersion,
    ) {
        require(state == ScheduleLiveActivityState.INVALID)
        require(activityId == null && updateToken == null)
        require(this.startTokenFingerprint != startTokenFingerprint)
        bindFcmRoute(pushDeviceTokenId, fcmOwnershipVersion)
        this.startTokenId = startTokenId
        this.startTokenFingerprint = startTokenFingerprint
        this.startTokenGeneration = startTokenGeneration
        this.contentRevision = Math.addExact(contentRevision, 1)
        this.contentStateJson = contentJson
        this.state = ScheduleLiveActivityState.START_PENDING
        this.endedAt = null
        this.endReason = null
        clearLateEndAckAuthorization()
    }

    fun bindUpdateToken(activityId: String, token: String): Boolean {
        if (state == ScheduleLiveActivityState.ENDED || state == ScheduleLiveActivityState.INVALID) {
            return false
        }
        val fingerprint = OpaquePushIdentifier.fingerprint(token)
        val changed = this.activityId != activityId || updateTokenFingerprint != fingerprint
        this.activityId = activityId
        this.updateToken = token
        this.updateTokenFingerprint = fingerprint
        if (changed) updateTokenGeneration = Math.addExact(updateTokenGeneration, 1)
        // A token callback may race cancellation/logout or arrive after an uncertain START. Keep
        // the tombstone ending state so reconciliation emits END instead of reviving UPDATE.
        if (state != ScheduleLiveActivityState.END_PENDING) {
            state = ScheduleLiveActivityState.ACTIVE
        }
        return changed
    }

    /**
     * A BadDeviceToken response invalidates one credential epoch, not the remote Activity itself.
     * Keep the instance identity recoverable so ActivityKit can publish its rotated update token.
     */
    fun invalidateUpdateCredential(
        expectedFingerprint: String,
        expectedGeneration: Long,
    ): Boolean {
        if (
            state != ScheduleLiveActivityState.ACTIVE ||
            updateTokenFingerprint != expectedFingerprint ||
            updateTokenGeneration != expectedGeneration
        ) return false
        updateToken = null
        updateTokenFingerprint = null
        updateTokenGeneration = Math.addExact(updateTokenGeneration, 1)
        state = ScheduleLiveActivityState.TOKEN_PENDING
        return true
    }

    fun nextApnsTimestamp(now: Instant): Long {
        lastApnsTimestamp = maxOf(now.epochSecond, Math.addExact(lastApnsTimestamp, 1))
        return lastApnsTimestamp
    }

    fun markStartAccepted(revision: Long) {
        if (contentRevision == revision && state == ScheduleLiveActivityState.START_PENDING) {
            state = ScheduleLiveActivityState.START_SENT
        }
    }

    fun markEndAccepted(revision: Long, targetActivityId: String?, now: Instant) {
        if (
            contentRevision == revision &&
            state == ScheduleLiveActivityState.END_PENDING &&
            activityId == targetActivityId
        ) {
            markEnded(now)
        }
    }

    fun markEndPending(now: Instant, reason: String): Boolean {
        if (state == ScheduleLiveActivityState.ENDED || state == ScheduleLiveActivityState.INVALID) {
            return false
        }
        contentRevision = Math.addExact(contentRevision, 1)
        state = ScheduleLiveActivityState.END_PENDING
        endedAt = now
        endReason = reason.take(80)
        return true
    }

    fun replaceTerminalContent(fingerprint: String, contentJson: String) {
        require(state == ScheduleLiveActivityState.END_PENDING)
        contentFingerprint = fingerprint
        contentStateJson = contentJson
    }

    fun authorizeEndedReportForSession(sessionGeneration: Long) {
        require(state == ScheduleLiveActivityState.END_PENDING)
        require(sessionGeneration >= 0)
        lateEndAckSessionGeneration = sessionGeneration
        lateEndAckPushDeviceTokenId = pushDeviceTokenId
        lateEndAckFcmOwnershipVersion = boundFcmOwnershipVersion
    }

    fun hasLateEndAckAuthorization(sessionGeneration: Long): Boolean =
        state == ScheduleLiveActivityState.END_PENDING &&
            lateEndAckSessionGeneration == sessionGeneration &&
            lateEndAckPushDeviceTokenId == pushDeviceTokenId &&
            lateEndAckFcmOwnershipVersion == boundFcmOwnershipVersion

    private fun clearLateEndAckAuthorization() {
        lateEndAckSessionGeneration = null
        lateEndAckPushDeviceTokenId = null
        lateEndAckFcmOwnershipVersion = null
    }

    fun markEnded(now: Instant, reason: String? = endReason) {
        state = ScheduleLiveActivityState.ENDED
        endedAt = endedAt ?: now
        endReason = reason?.take(80)
        dispatchLeaseId = null
        dispatchLeaseUntil = null
    }

    fun markInvalid(now: Instant, reason: String) {
        state = ScheduleLiveActivityState.INVALID
        endedAt = now
        endReason = reason.take(80)
        dispatchLeaseId = null
        dispatchLeaseUntil = null
    }

    fun hasActiveDispatchLease(now: Instant): Boolean =
        dispatchLeaseId != null && dispatchLeaseUntil?.isAfter(now) == true

    fun acquireDispatchLease(leaseId: String, now: Instant, until: Instant): Boolean {
        if (hasActiveDispatchLease(now)) return false
        dispatchLeaseId = leaseId
        dispatchLeaseUntil = until
        return true
    }

    fun releaseDispatchLease(leaseId: String) {
        if (dispatchLeaseId == leaseId) {
            dispatchLeaseId = null
            dispatchLeaseUntil = null
        }
    }

    protected constructor() : this(
        memberId = 0,
        scheduleId = 0,
        pushDeviceTokenId = 0,
        deviceFingerprint = "",
        activityType = "",
        sourceNotificationGeneration = 0,
        sourceCheckCount = 0,
        contentFingerprint = "",
        attributesJson = "{}",
        contentStateJson = "{}",
        expiresAt = Instant.EPOCH,
    )
}

enum class ScheduleLiveActivityState {
    START_PENDING,
    START_SENT,
    ACTIVE,
    /** Remote Activity exists, but ActivityKit must publish a replacement update credential. */
    TOKEN_PENDING,
    END_PENDING,
    ENDED,
    INVALID,
}
