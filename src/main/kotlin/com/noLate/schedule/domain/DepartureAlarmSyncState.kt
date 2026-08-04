package com.noLate.schedule.domain

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
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.Comment
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

const val DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE = "DEPARTURE_ALARM_SYNC"
const val DEPARTURE_ALARM_SYNC_SCHEMA_VERSION = "1"
const val MAX_DEPARTURE_ALARM_GENERATION = 9_007_199_254_740_991L
const val MAX_DEPARTURE_ALARM_VALIDATION_REVISION = 9_007_199_254_740_991L
const val DEFAULT_DEPARTURE_ALARM_TITLE = "출발 알림"

enum class DepartureAlarmSyncOperation {
    UPSERT,
    CANCEL,
}

/**
 * 기기별 전달 성공 여부와 독립적인 서버의 출발 알람 desired state다.
 *
 * schedule/job이 삭제된 뒤에도 CANCEL tombstone과 generation을 보존해야 지연된 UPSERT가
 * 기기 알람을 되살리지 못하므로 schedule FK를 의도적으로 두지 않는다.
 */
@Entity
@Table(
    name = "departure_alarm_sync_state",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_departure_alarm_sync_member_schedule",
            columnNames = ["member_id", "schedule_id"],
        ),
        UniqueConstraint(
            name = "uk_departure_alarm_sync_alarm_id",
            columnNames = ["alarm_id"],
        ),
    ],
    indexes = [
        Index(
            name = "idx_departure_alarm_sync_member_id",
            columnList = "member_id, id",
        ),
        Index(
            name = "idx_departure_alarm_sync_expiry",
            columnList = "operation, trigger_at, id",
        ),
        Index(
            name = "idx_departure_alarm_sync_validation",
            columnList =
                "operation, alarm_plan_schema_version, validation_requested_at, trigger_at, id",
        ),
    ],
)
@Comment("기기 출발 알람의 최신 desired state와 CANCEL tombstone")
class DepartureAlarmSyncState protected constructor() : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0
        protected set

    @Column(name = "schedule_id", nullable = false)
    var scheduleId: Long = 0
        protected set

    @Column(name = "alarm_id", nullable = false, length = 100)
    lateinit var alarmId: String
        protected set

    @Column(name = "generation", nullable = false)
    var generation: Long = 0
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 16)
    var operation: DepartureAlarmSyncOperation = DepartureAlarmSyncOperation.CANCEL
        protected set

    @Column(name = "trigger_at")
    var triggerAt: Instant? = null
        protected set

    @Column(name = "title", length = 100)
    var title: String? = null
        protected set

    @Column(name = "snooze_minutes")
    var snoozeMinutes: Int? = null
        protected set

    @Column(name = "alarm_plan_schema_version", length = 8)
    var alarmPlanSchemaVersion: String? = null
        protected set

    @Column(name = "alarm_occurrences_json", columnDefinition = "LONGTEXT")
    var alarmOccurrencesJson: String? = null
        protected set

    @Column(name = "validation_requested_at")
    var validationRequestedAt: Instant? = null
        protected set

    /** Same-generation idempotent validation command nonce. Desired-state changes use generation. */
    @Column(name = "validation_revision", nullable = false)
    var validationRevision: Long = 0
        protected set

    @Column(name = "command_fingerprint", nullable = false, length = 64)
    lateinit var commandFingerprint: String
        protected set

    fun upsert(
        triggerAt: Instant,
        title: String?,
        snoozeMinutes: Int,
        alarmPlanSchemaVersion: String? = null,
        alarmOccurrencesJson: String? = null,
    ): Boolean {
        val normalizedTriggerAt = normalizeTriggerAt(triggerAt)
        val normalizedTitle = normalizeTitle(title)
        require(snoozeMinutes in 1..60) {
            "출발 알람 snoozeMinutes는 1~60 사이여야 합니다."
        }
        validateAlarmPlan(alarmPlanSchemaVersion, alarmOccurrencesJson, normalizedTriggerAt)
        val nextFingerprint = DepartureAlarmSyncFingerprint.calculate(
            operation = DepartureAlarmSyncOperation.UPSERT,
            alarmId = alarmId,
            scheduleId = scheduleId,
            triggerAt = normalizedTriggerAt,
            title = normalizedTitle,
            snoozeMinutes = snoozeMinutes,
            alarmPlanSchemaVersion = alarmPlanSchemaVersion,
            alarmOccurrencesJson = alarmOccurrencesJson,
        )
        if (commandFingerprint == nextFingerprint) return false

        advanceGeneration()
        operation = DepartureAlarmSyncOperation.UPSERT
        this.triggerAt = normalizedTriggerAt
        this.title = normalizedTitle
        this.snoozeMinutes = snoozeMinutes
        this.alarmPlanSchemaVersion = alarmPlanSchemaVersion
        this.alarmOccurrencesJson = alarmOccurrencesJson
        if (alarmPlanSchemaVersion == null) validationRequestedAt = null
        commandFingerprint = nextFingerprint
        return true
    }

    fun recordValidationRequested(at: Instant) {
        check(
            operation == DepartureAlarmSyncOperation.UPSERT &&
                alarmPlanSchemaVersion == DEPARTURE_ALARM_PLAN_SCHEMA_VERSION
        ) { "Only a complete UPSERT plan can request native validation." }
        validationRequestedAt = normalizeTriggerAt(at)
    }

    /** Re-publishes an identical desired plan without invalidating existing generation receipts. */
    fun reissueValidation(at: Instant): Boolean {
        check(
            operation == DepartureAlarmSyncOperation.UPSERT &&
                alarmPlanSchemaVersion == DEPARTURE_ALARM_PLAN_SCHEMA_VERSION &&
                alarmOccurrencesJson != null
        ) { "Only a complete UPSERT plan can be revalidated." }
        check(validationRevision < MAX_DEPARTURE_ALARM_VALIDATION_REVISION) {
            "출발 알람 validation revision이 JavaScript safe integer 범위를 초과했습니다."
        }
        validationRevision = Math.addExact(validationRevision, 1L)
        validationRequestedAt = normalizeTriggerAt(at)
        return true
    }

    fun cancel(): Boolean {
        val nextFingerprint = DepartureAlarmSyncFingerprint.calculate(
            operation = DepartureAlarmSyncOperation.CANCEL,
            alarmId = alarmId,
            scheduleId = scheduleId,
            triggerAt = null,
            title = null,
            snoozeMinutes = null,
            alarmPlanSchemaVersion = null,
            alarmOccurrencesJson = null,
        )
        if (commandFingerprint == nextFingerprint) return false

        advanceGeneration()
        operation = DepartureAlarmSyncOperation.CANCEL
        triggerAt = null
        title = null
        snoozeMinutes = null
        alarmPlanSchemaVersion = null
        alarmOccurrencesJson = null
        validationRequestedAt = null
        commandFingerprint = nextFingerprint
        return true
    }

    private fun advanceGeneration() {
        check(generation < MAX_DEPARTURE_ALARM_GENERATION) {
            "출발 알람 generation이 JavaScript safe integer 범위를 초과했습니다."
        }
        generation = Math.addExact(generation, 1L)
    }

    companion object {
        fun createUpsert(
            memberId: Long,
            scheduleId: Long,
            triggerAt: Instant,
            title: String?,
            snoozeMinutes: Int,
            alarmPlanSchemaVersion: String? = null,
            alarmOccurrencesJson: String? = null,
            validationRequestedAt: Instant? = null,
            validationRevision: Long = 0,
        ): DepartureAlarmSyncState {
            require(memberId > 0)
            require(scheduleId > 0)
            require(snoozeMinutes in 1..60)
            require(validationRevision in 0..MAX_DEPARTURE_ALARM_VALIDATION_REVISION)
            val alarmId = departureAlarmId(memberId, scheduleId)
            val normalizedTriggerAt = normalizeTriggerAt(triggerAt)
            val normalizedTitle = normalizeTitle(title)
            validateAlarmPlan(alarmPlanSchemaVersion, alarmOccurrencesJson, normalizedTriggerAt)
            return DepartureAlarmSyncState().apply {
                this.memberId = memberId
                this.scheduleId = scheduleId
                this.alarmId = alarmId
                this.generation = 0
                this.operation = DepartureAlarmSyncOperation.UPSERT
                this.triggerAt = normalizedTriggerAt
                this.title = normalizedTitle
                this.snoozeMinutes = snoozeMinutes
                this.alarmPlanSchemaVersion = alarmPlanSchemaVersion
                this.alarmOccurrencesJson = alarmOccurrencesJson
                this.validationRequestedAt = validationRequestedAt
                    ?.takeIf { alarmPlanSchemaVersion != null }
                    ?.let(::normalizeTriggerAt)
                this.validationRevision = validationRevision
                this.commandFingerprint = DepartureAlarmSyncFingerprint.calculate(
                    operation = DepartureAlarmSyncOperation.UPSERT,
                    alarmId = alarmId,
                    scheduleId = scheduleId,
                    triggerAt = normalizedTriggerAt,
                    title = normalizedTitle,
                    snoozeMinutes = snoozeMinutes,
                    alarmPlanSchemaVersion = alarmPlanSchemaVersion,
                    alarmOccurrencesJson = alarmOccurrencesJson,
                )
            }
        }

        private fun normalizeTitle(value: String?): String =
            value?.trim()?.takeIf(String::isNotEmpty)?.take(100)
                ?: DEFAULT_DEPARTURE_ALARM_TITLE

        private fun normalizeTriggerAt(value: Instant): Instant =
            value.truncatedTo(ChronoUnit.MILLIS)

        private fun validateAlarmPlan(
            schemaVersion: String?,
            occurrencesJson: String?,
            departureTriggerAt: Instant,
        ) {
            require((schemaVersion == null) == (occurrencesJson == null)) {
                "출발 알람 plan schema와 occurrences는 함께 저장해야 합니다."
            }
            if (schemaVersion == null) return
            require(schemaVersion == DEPARTURE_ALARM_PLAN_SCHEMA_VERSION) {
                "지원하지 않는 출발 알람 plan schema입니다."
            }
            val plan = DepartureAlarmPlanCodec.decode(requireNotNull(occurrencesJson))
            require(plan.departureOccurrence().triggerInstant() == departureTriggerAt) {
                "출발 알람 M0와 top-level triggerAt이 일치해야 합니다."
            }
        }
    }
}

fun departureAlarmId(memberId: Long, scheduleId: Long): String {
    require(memberId > 0)
    require(scheduleId > 0)
    return "schedule:$scheduleId:member:$memberId"
}

object DepartureAlarmSyncFingerprint {
    fun calculate(
        operation: DepartureAlarmSyncOperation,
        alarmId: String,
        scheduleId: Long,
        triggerAt: Instant?,
        title: String?,
        snoozeMinutes: Int?,
        alarmPlanSchemaVersion: String? = null,
        alarmOccurrencesJson: String? = null,
    ): String {
        val legacyCanonical = listOf(
            operation.name,
            alarmId,
            scheduleId.toString(),
            triggerAt?.toString().orEmpty(),
            title.orEmpty(),
            snoozeMinutes?.toString().orEmpty(),
        )
        val planCanonical = if (alarmPlanSchemaVersion == null && alarmOccurrencesJson == null) {
            emptyList()
        } else {
            listOf(
                "departure-alarm-plan",
                alarmPlanSchemaVersion.orEmpty(),
                alarmOccurrencesJson.orEmpty(),
            )
        }
        val canonical = (legacyCanonical + planCanonical).joinToString("|") { value ->
            "${value.toByteArray(StandardCharsets.UTF_8).size}:$value"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
