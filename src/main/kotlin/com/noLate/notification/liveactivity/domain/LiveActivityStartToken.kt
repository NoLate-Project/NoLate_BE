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
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "live_activity_start_tokens",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_live_activity_start_token_device_type",
            columnNames = ["member_id", "device_fingerprint", "activity_type"],
        ),
        UniqueConstraint(
            name = "uk_live_activity_start_token_fingerprint",
            columnNames = ["token_fingerprint"],
        ),
    ],
    indexes = [
        Index(
            name = "idx_live_activity_start_token_device",
            columnList = "push_device_token_id, active, id",
        ),
        Index(
            name = "idx_live_activity_start_token_lease",
            columnList = "dispatch_lease_until, id",
        ),
    ],
)
class LiveActivityStartToken(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "member_id", nullable = false)
    var memberId: Long,

    @Column(name = "push_device_token_id", nullable = false)
    var pushDeviceTokenId: Long,

    @Column(name = "device_fingerprint", nullable = false, length = 64)
    var deviceFingerprint: String,

    @Column(name = "activity_type", nullable = false, length = 120)
    var activityType: String,

    /** ActivityKit credential. Never include this value in logs, responses, or exception text. */
    @Column(name = "push_to_start_token", nullable = false, length = 500)
    var pushToStartToken: String,

    @Column(name = "token_fingerprint", nullable = false, length = 64)
    var tokenFingerprint: String = OpaquePushIdentifier.fingerprint(pushToStartToken),

    @Column(name = "token_generation", nullable = false)
    var tokenGeneration: Long = 1,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant,

    @Column(name = "dispatch_lease_id", length = 64)
    var dispatchLeaseId: String? = null,

    @Column(name = "dispatch_lease_until")
    var dispatchLeaseUntil: Instant? = null,

    @Column(name = "retirement_requested", nullable = false)
    var retirementRequested: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "appearance", length = 10)
    var appearance: LiveActivityAppearance? = null,
) : BaseEntity() {
    fun rotate(
        memberId: Long,
        pushDeviceTokenId: Long,
        deviceFingerprint: String,
        token: String,
        tokenFingerprint: String,
        seenAt: Instant,
        appearance: LiveActivityAppearance? = null,
    ): Boolean {
        val changed = this.tokenFingerprint != tokenFingerprint ||
            this.pushDeviceTokenId != pushDeviceTokenId ||
            this.memberId != memberId ||
            this.deviceFingerprint != deviceFingerprint
        this.memberId = memberId
        this.pushDeviceTokenId = pushDeviceTokenId
        this.deviceFingerprint = deviceFingerprint
        this.pushToStartToken = token
        this.tokenFingerprint = tokenFingerprint
        this.lastSeenAt = seenAt
        if (appearance != null) {
            this.appearance = appearance
        }
        this.active = true
        this.retirementRequested = false
        if (changed) {
            tokenGeneration = Math.addExact(tokenGeneration, 1)
        }
        return changed
    }

    fun hasActiveDispatchLease(now: Instant): Boolean =
        dispatchLeaseId != null && dispatchLeaseUntil?.isAfter(now) == true

    fun acquireDispatchLease(leaseId: String, now: Instant, until: Instant): Boolean {
        if (!active || retirementRequested || hasActiveDispatchLease(now)) return false
        dispatchLeaseId = leaseId
        dispatchLeaseUntil = until
        return true
    }

    fun releaseDispatchLease(leaseId: String): Boolean {
        if (dispatchLeaseId == leaseId) {
            dispatchLeaseId = null
            dispatchLeaseUntil = null
            return true
        }
        return false
    }

    fun requestRetirement(now: Instant): Boolean {
        active = false
        retirementRequested = true
        // Expiry only makes the provider attempt recoverable. Keep the credential row until the
        // stale-dispatch transaction clears its exact lease, so a late completion cannot target a
        // rotated/deleted identity.
        return dispatchLeaseId == null
    }

    protected constructor() : this(
        memberId = 0,
        pushDeviceTokenId = 0,
        deviceFingerprint = "",
        activityType = "",
        pushToStartToken = "",
        lastSeenAt = Instant.EPOCH,
    )
}
