package com.noLate.journey.domain

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import jakarta.persistence.*
import java.time.Instant

enum class AutomaticJourneyState { ARMED, ACTIVE, STOPPED, EXPIRED }
enum class AutomaticJourneyExecution { SIMULATOR, DEVICE }

data class AutomaticJourneyPrepareCommand(
    val requestId: String,
    val clientOwnerId: String,
    val routeInputDigest: String,
    val execution: AutomaticJourneyExecution,
)

data class AutomaticJourneySavedPrepareCommand(val requestId: String, val clientOwnerId: String,
    val routeInputDigest: String, val execution: AutomaticJourneyExecution, val routeInputJSON: String)

data class AutomaticJourneyDepartureCommand(
    val generation: Long,
    val routeRevision: Long,
    val clientOwnerId: String,
    val routeInputDigest: String,
    val eventKey: String,
    val sequence: Long,
    val type: String,
    val occurredAt: Instant,
    val execution: AutomaticJourneyExecution,
)

data class AutomaticJourneyStopCommand(
    val generation: Long,
    val routeRevision: Long,
    val clientOwnerId: String,
    val routeInputDigest: String,
)

/** A preparation is NOT a manual journey, and must never suppress predeparture notifications. */
@Entity
@Table(name = "automatic_journey_preparations", uniqueConstraints = [
    UniqueConstraint(name = "uk_auto_journey_session", columnNames = ["session_id"]),
    UniqueConstraint(name = "uk_auto_journey_generation", columnNames = ["member_id", "schedule_id", "generation"]),
], indexes = [
    Index(name = "idx_auto_journey_expiry", columnList = "state, expires_at"),
    Index(name = "idx_auto_journey_retention", columnList = "retention_expires_at"),
])
class AutomaticJourneyPreparation(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    @Version var version: Long = 0,
    @Column(name = "session_id", nullable = false, length = 36) val sessionId: String,
    @Column(name = "member_id", nullable = false) val memberId: Long,
    @Column(name = "schedule_id", nullable = false) val scheduleId: Long,
    @Column(name = "login_session_generation", nullable = false) val loginSessionGeneration: Long,
    @Column(name = "client_owner_id", nullable = false, length = 36) val clientOwnerId: String,
    @Column(name = "route_input_digest", nullable = false, length = 64) val routeInputDigest: String,
    @Column(name = "route_fingerprint", nullable = false, length = 64) val routeFingerprint: String,
    @Column(nullable = false) val generation: Long,
    @Column(name = "route_revision", nullable = false) val routeRevision: Long = 1,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12) val execution: AutomaticJourneyExecution,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12) var state: AutomaticJourneyState = AutomaticJourneyState.ARMED,
    @Column(nullable = false) var sequence: Long = 0,
    @Column(name = "prepared_at", nullable = false) val preparedAt: Instant,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "event_key", length = 120) var acceptedEventKey: String? = null,
    @Column(name = "occurred_at") var acceptedOccurredAt: Instant? = null,
    @Column(name = "departed_at") var departedAt: Instant? = null,
    @Column(name = "ended_at") var endedAt: Instant? = null,
    @Column(name = "retention_expires_at", nullable = false) val retentionExpiresAt: Instant = expiresAt.plusSeconds(7 * 24 * 60 * 60),
    @Column(name = "walking_snapshot_id", length = 36) var walkingSnapshotId: String? = null,
) {
    fun fence(generation: Long, routeRevision: Long, owner: String, digest: String) {
        if (this.generation != generation || this.routeRevision != routeRevision || clientOwnerId != owner || routeInputDigest != digest) {
            invalid("자동 출발 세션의 소유자 또는 경로가 변경되었습니다.")
        }
    }

    /** Exact accepted receipts survive expiry/STOPPED, but never reactivate or repeat side effects. */
    fun isDuplicate(command: AutomaticJourneyDepartureCommand): Boolean {
        fence(command.generation, command.routeRevision, command.clientOwnerId, command.routeInputDigest)
        if (command.execution != execution || command.type != "AUTO_DEPARTURE" || command.sequence != 1L ||
            command.eventKey != "$sessionId:$generation:$routeRevision:AUTO_DEPARTURE" || command.occurredAt.nano % 1_000_000 != 0) invalid("자동 출발 이벤트 형식이 올바르지 않습니다.")
        if (acceptedEventKey == null) return false
        if (acceptedEventKey != command.eventKey || acceptedOccurredAt != command.occurredAt) invalid("같은 이벤트의 내용이 변경되었습니다.")
        return true
    }

    fun validateDeparture(command: AutomaticJourneyDepartureCommand, now: Instant) {
        if (isDuplicate(command)) return
        if (state != AutomaticJourneyState.ARMED || sequence != 0L) invalid("자동 출발 준비가 종료되었습니다.")
        if (!now.isBefore(expiresAt) || now.isBefore(preparedAt)) invalid("자동 출발 준비 시간이 만료되었습니다.")
        // Offline outbox delivery shares the preparation TTL; do not impose a shorter receipt-age cutoff.
        if (command.occurredAt.isBefore(preparedAt) || !command.occurredAt.isBefore(expiresAt) ||
            command.occurredAt.isAfter(now.plusSeconds(30))) {
            invalid("자동 출발 감지 시각이 올바르지 않습니다.")
        }
    }

    fun activate(command: AutomaticJourneyDepartureCommand, now: Instant, confirmedDepartedAt: Instant = now) {
        validateDeparture(command, now)
        if (isDuplicate(command)) return
        acceptedEventKey = command.eventKey
        acceptedOccurredAt = command.occurredAt
        departedAt = confirmedDepartedAt
        sequence = 1
        state = AutomaticJourneyState.ACTIVE
    }

    fun stop(now: Instant) {
        if (state == AutomaticJourneyState.STOPPED) return
        state = AutomaticJourneyState.STOPPED
        endedAt = now
    }

    fun expire(now: Instant) {
        if (state !in setOf(AutomaticJourneyState.ARMED, AutomaticJourneyState.ACTIVE) || now.isBefore(expiresAt)) return
        state = AutomaticJourneyState.EXPIRED
        endedAt = now
    }

    companion object {
        private fun invalid(message: String): Nothing = throw BusinessException(ErrorCode.INVALID_STATE, message)
    }
}
