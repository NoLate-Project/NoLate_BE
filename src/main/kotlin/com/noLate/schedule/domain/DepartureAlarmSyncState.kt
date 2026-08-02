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

    @Column(name = "command_fingerprint", nullable = false, length = 64)
    lateinit var commandFingerprint: String
        protected set

    fun upsert(
        triggerAt: Instant,
        title: String?,
        snoozeMinutes: Int,
    ): Boolean {
        val normalizedTriggerAt = normalizeTriggerAt(triggerAt)
        val normalizedTitle = normalizeTitle(title)
        require(snoozeMinutes in 1..60) {
            "출발 알람 snoozeMinutes는 1~60 사이여야 합니다."
        }
        val nextFingerprint = DepartureAlarmSyncFingerprint.calculate(
            operation = DepartureAlarmSyncOperation.UPSERT,
            alarmId = alarmId,
            scheduleId = scheduleId,
            triggerAt = normalizedTriggerAt,
            title = normalizedTitle,
            snoozeMinutes = snoozeMinutes,
        )
        if (commandFingerprint == nextFingerprint) return false

        advanceGeneration()
        operation = DepartureAlarmSyncOperation.UPSERT
        this.triggerAt = normalizedTriggerAt
        this.title = normalizedTitle
        this.snoozeMinutes = snoozeMinutes
        commandFingerprint = nextFingerprint
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
        )
        if (commandFingerprint == nextFingerprint) return false

        advanceGeneration()
        operation = DepartureAlarmSyncOperation.CANCEL
        triggerAt = null
        title = null
        snoozeMinutes = null
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
        ): DepartureAlarmSyncState {
            require(memberId > 0)
            require(scheduleId > 0)
            require(snoozeMinutes in 1..60)
            val alarmId = departureAlarmId(memberId, scheduleId)
            val normalizedTriggerAt = normalizeTriggerAt(triggerAt)
            val normalizedTitle = normalizeTitle(title)
            return DepartureAlarmSyncState().apply {
                this.memberId = memberId
                this.scheduleId = scheduleId
                this.alarmId = alarmId
                this.generation = 0
                this.operation = DepartureAlarmSyncOperation.UPSERT
                this.triggerAt = normalizedTriggerAt
                this.title = normalizedTitle
                this.snoozeMinutes = snoozeMinutes
                this.commandFingerprint = DepartureAlarmSyncFingerprint.calculate(
                    operation = DepartureAlarmSyncOperation.UPSERT,
                    alarmId = alarmId,
                    scheduleId = scheduleId,
                    triggerAt = normalizedTriggerAt,
                    title = normalizedTitle,
                    snoozeMinutes = snoozeMinutes,
                )
            }
        }

        private fun normalizeTitle(value: String?): String =
            value?.trim()?.takeIf(String::isNotEmpty)?.take(100)
                ?: DEFAULT_DEPARTURE_ALARM_TITLE

        private fun normalizeTriggerAt(value: Instant): Instant =
            value.truncatedTo(ChronoUnit.MICROS)
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
    ): String {
        val canonical = listOf(
            operation.name,
            alarmId,
            scheduleId.toString(),
            triggerAt?.toString().orEmpty(),
            title.orEmpty(),
            snoozeMinutes?.toString().orEmpty(),
        ).joinToString("|") { value ->
            "${value.toByteArray(StandardCharsets.UTF_8).size}:$value"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
