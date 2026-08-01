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

enum class DepartureAlarmScheduleOutcome {
    SCHEDULED,
    CANCELED,
    FAILED,
}

enum class DepartureAlarmScheduleSource {
    PUSH,
    SNAPSHOT,
}

enum class DepartureAlarmDeliveryMode {
    ANDROID_EXACT,
    ANDROID_INEXACT,
    IOS_ALARM_KIT,
    IOS_TIME_SENSITIVE,
    UNKNOWN,
}

/** Append-only, authenticated result of applying one native desired-state command. */
@Entity
@Table(
    name = "departure_alarm_schedule_receipts",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_departure_alarm_receipt_member_client",
            columnNames = ["member_id", "client_receipt_id"],
        ),
        UniqueConstraint(
            name = "uk_departure_alarm_receipt_member_device_command",
            columnNames = ["member_id", "device_fingerprint", "command_receipt_key"],
        ),
    ],
    indexes = [
        Index(
            name = "idx_departure_alarm_receipt_cohort",
            columnList = "outcome, trigger_at, platform, delivery_mode, server_recorded_at",
        ),
        Index(
            name = "idx_departure_alarm_receipt_schedule",
            columnList = "schedule_id, server_recorded_at",
        ),
        Index(
            name = "idx_departure_alarm_receipt_member",
            columnList = "member_id, id",
        ),
    ],
)
class DepartureAlarmScheduleReceipt(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "client_receipt_id", nullable = false, length = 36)
    val clientReceiptId: String,

    @Column(name = "device_fingerprint", nullable = false, length = 64)
    val deviceFingerprint: String,

    @Column(name = "command_receipt_key", nullable = false, length = 64)
    val commandReceiptKey: String,

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

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 16)
    val operation: DepartureAlarmSyncOperation,

    @Column(name = "trigger_at")
    val triggerAt: Instant?,

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    val outcome: DepartureAlarmScheduleOutcome,

    @Column(name = "applied", nullable = false)
    val applied: Boolean,

    @Column(name = "scheduled", nullable = false)
    val scheduled: Boolean,

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20)
    val platform: PushPlatform,

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode", nullable = false, length = 24)
    val deliveryMode: DepartureAlarmDeliveryMode,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    val source: DepartureAlarmScheduleSource,

    @Column(name = "failure_reason", length = 64)
    val failureReason: String?,

    @Column(name = "client_occurred_at", nullable = false)
    val clientOccurredAt: Instant,

    @Column(name = "server_recorded_at", nullable = false)
    val serverRecordedAt: Instant,
) {
    init {
        require(memberId > 0 && scheduleId > 0)
        require(generation in 0..desiredGenerationAtReceipt)
        require(
            (generationRelation == DepartureAlarmGenerationRelation.CURRENT &&
                generation == desiredGenerationAtReceipt) ||
                (generationRelation == DepartureAlarmGenerationRelation.STALE &&
                    generation < desiredGenerationAtReceipt)
        )
        when (outcome) {
            DepartureAlarmScheduleOutcome.SCHEDULED -> require(
                operation == DepartureAlarmSyncOperation.UPSERT &&
                    triggerAt != null && scheduled
            )
            DepartureAlarmScheduleOutcome.CANCELED -> require(
                operation == DepartureAlarmSyncOperation.CANCEL &&
                    triggerAt == null && applied && !scheduled && failureReason == null
            )
            DepartureAlarmScheduleOutcome.FAILED -> require(
                !scheduled && failureReason != null &&
                    !(operation == DepartureAlarmSyncOperation.CANCEL && applied)
            )
        }
    }

    protected constructor() : this(
        memberId = 1,
        clientReceiptId = "00000000-0000-0000-0000-000000000000",
        deviceFingerprint = "0".repeat(64),
        commandReceiptKey = "0".repeat(64),
        alarmId = "schedule:1:member:1",
        scheduleId = 1,
        generation = 0,
        desiredGenerationAtReceipt = 0,
        desiredOperationAtReceipt = DepartureAlarmSyncOperation.UPSERT,
        generationRelation = DepartureAlarmGenerationRelation.CURRENT,
        operation = DepartureAlarmSyncOperation.UPSERT,
        triggerAt = Instant.EPOCH,
        outcome = DepartureAlarmScheduleOutcome.SCHEDULED,
        applied = true,
        scheduled = true,
        platform = PushPlatform.ANDROID,
        deliveryMode = DepartureAlarmDeliveryMode.UNKNOWN,
        source = DepartureAlarmScheduleSource.SNAPSHOT,
        failureReason = null,
        clientOccurredAt = Instant.EPOCH,
        serverRecordedAt = Instant.EPOCH,
    )
}
