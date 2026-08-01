package com.noLate.notification.domain

import com.noLate.schedule.domain.DepartureAlarmSyncOperation
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

enum class DepartureAlarmGenerationRelation {
    CURRENT,
    STALE,
}

/** Whether execution timing was exact, observed while alerting, or inferred from OS persistence. */
enum class DepartureAlarmFireTimingBasis {
    EXACT_CALLBACK,
    OBSERVED_ALERTING,
    INFERRED_OS_DELIVERY,
}

/**
 * Native alarm execution evidence that does not depend on a particular push delivery.
 *
 * A desired-state snapshot can schedule an alarm without a logical push event, so those alarms
 * cannot be represented by [PushDelivery]. The device id is irreversibly fingerprinted before it
 * reaches this entity and the client wall clock is kept separate from the authoritative receipt
 * time.
 */
@Entity
@Table(
    name = "departure_alarm_fire_events",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_departure_alarm_fire_member_event",
            columnNames = ["member_id", "client_event_id"],
        ),
        UniqueConstraint(
            name = "uk_departure_alarm_fire_member_device_trigger",
            columnNames = [
                "member_id",
                "device_fingerprint",
                "alarm_id",
                "generation",
                "scheduled_for",
            ],
        ),
    ],
    indexes = [
        Index(
            name = "idx_departure_alarm_fire_recorded_at",
            columnList = "server_recorded_at, id",
        ),
        Index(
            name = "idx_departure_alarm_fire_member",
            columnList = "member_id, id",
        ),
        Index(
            name = "idx_departure_alarm_fire_schedule",
            columnList = "schedule_id, server_recorded_at",
        ),
    ],
)
class DepartureAlarmFireEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "client_event_id", nullable = false, length = 36)
    val clientEventId: String,

    @Column(name = "device_fingerprint", nullable = false, length = 64)
    val deviceFingerprint: String,

    @Column(name = "alarm_id", nullable = false, length = 100)
    val alarmId: String,

    @Column(name = "schedule_id", nullable = false)
    val scheduleId: Long,

    @Column(name = "generation", nullable = false)
    val generation: Long,

    @Column(name = "desired_generation_at_receipt", nullable = false)
    val desiredGenerationAtReceipt: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "desired_operation_at_receipt", nullable = false, length = 16)
    val desiredOperationAtReceipt: DepartureAlarmSyncOperation,

    @Enumerated(EnumType.STRING)
    @Column(name = "generation_relation", nullable = false, length = 16)
    val generationRelation: DepartureAlarmGenerationRelation,

    /** Effective native trigger, including a local snooze when one was applied. */
    @Column(name = "scheduled_for", nullable = false)
    val scheduledFor: Instant,

    /** Original server trigger when the native platform can preserve it. */
    @Column(name = "source_trigger_at")
    val sourceTriggerAt: Instant? = null,

    /** Diagnostic client time. It is never treated as an authentication or ordering fence. */
    @Column(name = "client_occurred_at", nullable = false)
    val clientOccurredAt: Instant,

    /** Quality of [clientOccurredAt]; every non-exact basis is excluded from delay cohorts. */
    @Enumerated(EnumType.STRING)
    @Column(name = "timing_basis", nullable = false, length = 24)
    val timingBasis: DepartureAlarmFireTimingBasis = DepartureAlarmFireTimingBasis.EXACT_CALLBACK,

    /** Signed device-observed delay; diagnostic-only when [timingBasis] is not exact. */
    @Column(name = "fire_delay_seconds", nullable = false)
    val fireDelaySeconds: Long,

    /** Authoritative server receipt time. */
    @Column(name = "server_recorded_at", nullable = false)
    val serverRecordedAt: Instant,
) {
    protected constructor() : this(
        memberId = 0,
        clientEventId = "",
        deviceFingerprint = "",
        alarmId = "",
        scheduleId = 0,
        generation = 0,
        desiredGenerationAtReceipt = 0,
        desiredOperationAtReceipt = DepartureAlarmSyncOperation.CANCEL,
        generationRelation = DepartureAlarmGenerationRelation.CURRENT,
        scheduledFor = Instant.EPOCH,
        clientOccurredAt = Instant.EPOCH,
        timingBasis = DepartureAlarmFireTimingBasis.EXACT_CALLBACK,
        fireDelaySeconds = 0,
        serverRecordedAt = Instant.EPOCH,
    )
}
