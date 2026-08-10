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
import jakarta.persistence.Table
import java.time.Instant

/**
 * Source-transaction-owned lifecycle outbox. The schedule/member mutation and this intent commit
 * atomically; projection is idempotent and a scheduled recovery worker retries every unfinished
 * row after process death or a transient projection failure.
 */
@Entity
@Table(
    name = "live_activity_lifecycle_intents",
    indexes = [
        Index(
            name = "idx_live_activity_lifecycle_intent_pending",
            columnList = "completed_at, dead_lettered_at, available_at, id",
        ),
    ],
)
class LiveActivityLifecycleIntent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 30)
    val scopeType: LiveActivityLifecycleScopeType,

    @Column(name = "member_id")
    val memberId: Long? = null,

    @Column(name = "schedule_id")
    val scheduleId: Long? = null,

    @Column(name = "reason", nullable = false, length = 80)
    val reason: String,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "available_at", nullable = false)
    var availableAt: Instant = Instant.EPOCH,

    @Column(name = "last_attempted_at")
    var lastAttemptedAt: Instant? = null,

    @Column(name = "last_error_code", length = 120)
    var lastErrorCode: String? = null,

    @Column(name = "dead_lettered_at")
    var deadLetteredAt: Instant? = null,
) : BaseEntity() {
    fun markCompleted(now: Instant) {
        completedAt = completedAt ?: now
        lastErrorCode = null
    }

    fun markFailed(now: Instant, errorCode: String, maxAttempts: Int, baseSeconds: Long): Boolean {
        if (completedAt != null || deadLetteredAt != null) return deadLetteredAt != null
        attemptCount = Math.addExact(attemptCount, 1)
        lastAttemptedAt = now
        lastErrorCode = errorCode.take(120)
        if (attemptCount >= maxAttempts) {
            deadLetteredAt = now
            return true
        }
        val multiplier = 1L shl (attemptCount - 1).coerceIn(0, 10)
        availableAt = now.plusSeconds((baseSeconds * multiplier).coerceAtMost(3_600))
        return false
    }

    protected constructor() : this(
        scopeType = LiveActivityLifecycleScopeType.MEMBER,
        reason = "",
    )
}

enum class LiveActivityLifecycleScopeType {
    MEMBER_SCHEDULE,
    SCHEDULE,
    MEMBER,
}
