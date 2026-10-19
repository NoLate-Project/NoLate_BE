package com.noLate.journey.domain

import jakarta.persistence.*
import java.time.Instant

/** Journey wire contract only; never share credentials/content with the departure widget. */
@Entity
@Table(name = "journey_activity_lanes", uniqueConstraints = [
    UniqueConstraint(name = "uk_journey_activity_session", columnNames = ["session_id"]),
    UniqueConstraint(name = "uk_journey_activity_token", columnNames = ["token_fingerprint"]),
    UniqueConstraint(name = "uk_journey_activity_id", columnNames = ["activity_id"]),
], indexes = [Index(name = "idx_journey_activity_terminal", columnList = "end_state, id"),
    Index(name = "idx_journey_activity_retention", columnList = "retention_expires_at")])
class JourneyActivityLane(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    @Version var version: Long = 0,
    @Column(name = "session_id", nullable = false, length = 36) val sessionId: String,
    @Column(name = "member_id", nullable = false) val memberId: Long,
    @Column(name = "schedule_id", nullable = false) val scheduleId: Long,
    @Column(name = "login_generation", nullable = false) val loginGeneration: Long,
    @Column(name = "client_owner_id", nullable = false, length = 36) val clientOwnerId: String,
    @Column(name = "route_input_digest", nullable = false, length = 64) val routeInputDigest: String,
    @Column(nullable = false) val generation: Long,
    @Column(name = "route_revision", nullable = false) val routeRevision: Long,
    @Column(name = "activity_id", nullable = false, length = 128) val activityId: String,
    @Column(name = "token_fingerprint", nullable = false, length = 64) val tokenFingerprint: String,
    @Column(name = "fcm_fingerprint", nullable = false, length = 64) val fcmFingerprint: String,
    @Column(name = "push_token_id", nullable = false) val pushTokenId: Long,
    @Column(name = "fcm_ownership_version", nullable = false) val fcmOwnershipVersion: Long,
    @Column(name = "device_fingerprint", nullable = false, length = 64) val deviceFingerprint: String,
    @Column(name = "encrypted_credentials", nullable = false, length = 12000) val encryptedCredentials: String,
    @Column(name = "last_timestamp", nullable = false) var lastTimestamp: Long,
    @Column(name = "content_json", nullable = false, columnDefinition = "TEXT") var contentJson: String,
    @Enumerated(EnumType.STRING) @Column(name = "end_state", nullable = false, length = 16) var endState: JourneyActivityEndState = JourneyActivityEndState.NONE,
    @Column(name = "terminal_reason", length = 12) var terminalReason: String? = null,
    @Column(name = "terminal_at") var terminalAt: Instant? = null,
    @Column(name = "claimed_at") var claimedAt: Instant? = null,
    @Column(name = "failure_code", nullable = false, length = 48) var failureCode: String = "NONE",
    @Column(name = "retention_expires_at", nullable = false) val retentionExpiresAt: Instant,
) {
    fun binding() = AutomaticJourneyStopCommand(generation, routeRevision, clientOwnerId, routeInputDigest)
    override fun toString() = "JourneyActivityLane(endState=$endState, failureCode=$failureCode)"
}

/** A single durable END intent per immutable token lane. UNKNOWN is not a retry ticket. */
enum class JourneyActivityEndState { NONE, READY, IN_FLIGHT, ACCEPTED, UNKNOWN, REJECTED, BLOCKED }
