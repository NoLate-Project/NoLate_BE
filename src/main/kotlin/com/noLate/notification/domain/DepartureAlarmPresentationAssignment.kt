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
import java.time.Instant

enum class DepartureAlarmPresentationMode {
    NATIVE_ALARM,
    VISIBLE_FALLBACK,
}

/**
 * Immutable per-token reminder assignment frozen with the fallback/warning outbox manifest.
 * Provider tokens and raw installation ids are intentionally never persisted here.
 */
@Entity
@Table(
    name = "departure_alarm_presentation_assignments",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_departure_alarm_assignment_event_ownership",
            columnNames = [
                "member_id", "logical_event_key", "device_token_id", "token_ownership_version",
            ],
        ),
    ],
    indexes = [
        Index(
            name = "idx_departure_alarm_assignment_occurrence",
            columnList = "schedule_id, alarm_generation, occurrence_id, trigger_at, assigned_at",
        ),
        Index(
            name = "idx_departure_alarm_assignment_member",
            columnList = "member_id, id",
        ),
        Index(
            name = "idx_departure_alarm_assignment_measurement",
            columnList =
                "trigger_at, platform, occurrence_id, presentation_mode, semantic_warning_visible, id",
        ),
    ],
)
class DepartureAlarmPresentationAssignment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "logical_event_key", nullable = false, length = 100)
    val logicalEventKey: String,

    @Column(name = "schedule_id", nullable = false)
    val scheduleId: Long,

    @Column(name = "alarm_generation")
    val alarmGeneration: Long?,

    @Column(name = "occurrence_id", nullable = false, length = 16)
    val occurrenceId: String,

    @Column(name = "trigger_at", nullable = false)
    val triggerAt: Instant,

    @Column(name = "device_token_id", nullable = false)
    val deviceTokenId: Long,

    @Column(name = "token_ownership_version", nullable = false)
    val tokenOwnershipVersion: Long,

    @Column(name = "device_fingerprint", length = 64)
    val deviceFingerprint: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20)
    val platform: PushPlatform,

    @Enumerated(EnumType.STRING)
    @Column(name = "presentation_mode", nullable = false, length = 24)
    val presentationMode: DepartureAlarmPresentationMode,

    @Column(name = "semantic_warning_visible", nullable = false)
    val semanticWarningVisible: Boolean = false,

    @Column(name = "assigned_at", nullable = false)
    val assignedAt: Instant,
) {
    init {
        require(memberId > 0 && scheduleId > 0 && deviceTokenId > 0)
        require(tokenOwnershipVersion >= 0)
        alarmGeneration?.let { require(it >= 0) }
        require(logicalEventKey.isNotBlank() && logicalEventKey.length <= 100)
        require(occurrenceId in setOf("M15", "M10", "M5", "M0"))
    }

    protected constructor() : this(
        memberId = 1,
        logicalEventKey = "event:00000000-0000-0000-0000-000000000000",
        scheduleId = 1,
        alarmGeneration = null,
        occurrenceId = "M0",
        triggerAt = Instant.EPOCH,
        deviceTokenId = 1,
        tokenOwnershipVersion = 0,
        deviceFingerprint = null,
        platform = PushPlatform.UNKNOWN,
        presentationMode = DepartureAlarmPresentationMode.VISIBLE_FALLBACK,
        semanticWarningVisible = false,
        assignedAt = Instant.EPOCH,
    )
}
