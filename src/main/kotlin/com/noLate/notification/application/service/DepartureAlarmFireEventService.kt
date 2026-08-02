package com.noLate.notification.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.global.observability.PushClientAckMetricOutcome
import com.noLate.global.observability.recordOperationalMetricAfterCommit
import com.noLate.global.observability.recordSafely
import com.noLate.notification.domain.DepartureAlarmFireEvent
import com.noLate.notification.domain.DepartureAlarmGenerationRelation
import com.noLate.notification.domain.DepartureAlarmFireTimingBasis
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushClientAckStage
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.DepartureAlarmFireEventRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.MAX_DEPARTURE_ALARM_GENERATION
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import com.noLate.schedule.domain.departureAlarmId
import com.noLate.schedule.infrastructure.DepartureAlarmSyncStateRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class DepartureAlarmFireEventResult(
    val recorded: Boolean,
    val eventId: String,
    val generationRelation: DepartureAlarmGenerationRelation,
    val scheduledFor: Instant,
    val occurredAt: Instant,
    val timingBasis: DepartureAlarmFireTimingBasis,
    val serverRecordedAt: Instant,
    val fireDelaySeconds: Long,
)

/**
 * Persists authenticated, at-least-once native alarm fire evidence.
 *
 * The desired-state row is locked first. Besides validating ownership and generation, that lock
 * serializes duplicate uploads for one alarm so retries converge without relying on a database
 * constraint exception that would poison the transaction.
 */
@Service
class DepartureAlarmFireEventService(
    private val memberRepository: MemberRepository,
    private val deviceTokenRepository: NotificationDeviceTokenRepository,
    private val syncStateRepository: DepartureAlarmSyncStateRepository,
    private val fireEventRepository: DepartureAlarmFireEventRepository,
    private val scheduleRepository: ScheduleRepository,
    private val operationalMetrics: NoLateOperationalMetrics? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun record(
        memberId: Long,
        eventId: String,
        alarmId: String,
        scheduleId: Long,
        generation: Long,
        recipientMemberId: Long,
        scheduledFor: Instant,
        occurredAt: Instant,
        timingBasis: DepartureAlarmFireTimingBasis = DepartureAlarmFireTimingBasis.EXACT_CALLBACK,
        sourceTriggerAt: Instant? = null,
        deviceId: String,
    ): DepartureAlarmFireEventResult {
        val canonicalEventId = canonicalEventId(eventId)
        requireValidIdentity(
            memberId = memberId,
            recipientMemberId = recipientMemberId,
            alarmId = alarmId,
            scheduleId = scheduleId,
            generation = generation,
            deviceId = deviceId,
        )
        requireDatabaseSafeInstant(scheduledFor, "scheduledFor")
        requireDatabaseSafeInstant(occurredAt, "occurredAt")
        sourceTriggerAt?.let { requireDatabaseSafeInstant(it, "sourceTriggerAt") }
        requirePlausibleExecutionWindow(scheduledFor, occurredAt)
        val recordedAt = Instant.now(clock)
        requirePlausibleReportWindow(occurredAt, recordedAt)

        // Account withdrawal uses the same member-first lock order and deletes this evidence
        // before committing. A stale authenticated request therefore cannot recreate telemetry
        // after the member has been anonymized.
        if (memberRepository.findActiveNotificationRecipientForUpdate(memberId) == null) {
            throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        }
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val activeDeviceTokens = deviceTokenRepository
            .findAllByMemberIdAndDeviceFingerprintForUpdate(memberId, deviceFingerprint)
            .filterNot { it.retirementRequested }
        if (activeDeviceTokens.isEmpty()) {
            throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        }
        requireCompatibleTimingBasis(activeDeviceTokens.map { it.platform }.toSet(), timingBasis)
        val state = syncStateRepository.findByMemberIdAndScheduleIdForUpdate(memberId, scheduleId)
            ?: throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        if (state.alarmId != alarmId || generation > state.generation) {
            throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        }
        if (!scheduleRepository.existsByIdAndDeletedFalse(scheduleId)) {
            throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        }
        if (generation == state.generation) {
            val originalTrigger = sourceTriggerAt ?: scheduledFor
            if (
                state.operation != DepartureAlarmSyncOperation.UPSERT ||
                state.triggerAt != originalTrigger
            ) {
                throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
            }
        }

        fireEventRepository.findByMemberIdAndClientEventId(memberId, canonicalEventId)?.let {
            requireSameEvent(
                existing = it,
                alarmId = alarmId,
                scheduleId = scheduleId,
                generation = generation,
                deviceFingerprint = deviceFingerprint,
                scheduledFor = scheduledFor,
                occurredAt = occurredAt,
                timingBasis = timingBasis,
                sourceTriggerAt = sourceTriggerAt,
            )
            return duplicateResult(it)
        }
        fireEventRepository
            .findByMemberIdAndDeviceFingerprintAndAlarmIdAndGenerationAndScheduledFor(
                memberId = memberId,
                deviceFingerprint = deviceFingerprint,
                alarmId = alarmId,
                generation = generation,
                scheduledFor = scheduledFor,
            )
            ?.let { return duplicateResult(it) }

        val relation = if (generation == state.generation) {
            DepartureAlarmGenerationRelation.CURRENT
        } else {
            DepartureAlarmGenerationRelation.STALE
        }
        val fireDelaySeconds = Duration.between(scheduledFor, occurredAt).seconds
        val event = fireEventRepository.save(
            DepartureAlarmFireEvent(
                memberId = memberId,
                clientEventId = canonicalEventId,
                deviceFingerprint = deviceFingerprint,
                alarmId = alarmId,
                scheduleId = scheduleId,
                generation = generation,
                desiredGenerationAtReceipt = state.generation,
                desiredOperationAtReceipt = state.operation,
                generationRelation = relation,
                scheduledFor = scheduledFor,
                sourceTriggerAt = sourceTriggerAt,
                clientOccurredAt = occurredAt,
                timingBasis = timingBasis,
                fireDelaySeconds = fireDelaySeconds,
                serverRecordedAt = recordedAt,
            )
        )
        recordMetric(event, PushClientAckMetricOutcome.RECORDED)
        return result(event, recorded = true)
    }

    private fun duplicateResult(event: DepartureAlarmFireEvent): DepartureAlarmFireEventResult {
        recordMetric(event, PushClientAckMetricOutcome.DUPLICATE)
        return result(event, recorded = false)
    }

    private fun recordMetric(
        event: DepartureAlarmFireEvent,
        outcome: PushClientAckMetricOutcome,
    ) {
        recordOperationalMetricAfterCommit {
            operationalMetrics.recordSafely {
                recordPushClientAck(PushClientAckStage.ALARM_FIRED, outcome)
                recordDepartureAlarmFire(
                    generationRelation = event.generationRelation,
                    outcome = outcome,
                    signedDelaySeconds = event.fireDelaySeconds,
                    timingBasis = event.timingBasis,
                )
            }
        }
    }

    private fun result(
        event: DepartureAlarmFireEvent,
        recorded: Boolean,
    ) = DepartureAlarmFireEventResult(
        recorded = recorded,
        eventId = event.clientEventId,
        generationRelation = event.generationRelation,
        scheduledFor = event.scheduledFor,
        occurredAt = event.clientOccurredAt,
        timingBasis = event.timingBasis,
        serverRecordedAt = event.serverRecordedAt,
        fireDelaySeconds = event.fireDelaySeconds,
    )

    private fun requireSameEvent(
        existing: DepartureAlarmFireEvent,
        alarmId: String,
        scheduleId: Long,
        generation: Long,
        deviceFingerprint: String,
        scheduledFor: Instant,
        occurredAt: Instant,
        timingBasis: DepartureAlarmFireTimingBasis,
        sourceTriggerAt: Instant?,
    ) {
        if (
            existing.alarmId != alarmId ||
            existing.scheduleId != scheduleId ||
            existing.generation != generation ||
            existing.deviceFingerprint != deviceFingerprint ||
            existing.scheduledFor != scheduledFor ||
            existing.clientOccurredAt != occurredAt ||
            existing.timingBasis != timingBasis ||
            existing.sourceTriggerAt != sourceTriggerAt
        ) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "알람 발화 이벤트 식별자가 충돌합니다.")
        }
    }

    private fun canonicalEventId(value: String): String {
        val trimmed = value.trim()
        return runCatching { UUID.fromString(trimmed).toString() }
            .getOrNull()
            ?.takeIf { it == trimmed.lowercase() }
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "알람 발화 이벤트 식별자가 올바르지 않습니다.")
    }

    private fun requireValidIdentity(
        memberId: Long,
        recipientMemberId: Long,
        alarmId: String,
        scheduleId: Long,
        generation: Long,
        deviceId: String,
    ) {
        if (
            memberId <= 0 ||
            recipientMemberId != memberId ||
            scheduleId <= 0 ||
            generation !in 0..MAX_DEPARTURE_ALARM_GENERATION ||
            alarmId != departureAlarmId(memberId, scheduleId)
        ) {
            throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        }
        if (deviceId.isEmpty() || deviceId.length > MAX_DEVICE_ID_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "기기 식별자가 올바르지 않습니다.")
        }
    }

    private fun requireDatabaseSafeInstant(value: Instant, fieldName: String) {
        if (value < MIN_DATABASE_INSTANT || value >= MAX_DATABASE_INSTANT) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "$fieldName 값이 올바르지 않습니다.")
        }
    }

    private fun requirePlausibleExecutionWindow(scheduledFor: Instant, occurredAt: Instant) {
        if (
            occurredAt.isBefore(scheduledFor.minusSeconds(MAX_EARLY_FIRE_SECONDS)) ||
            occurredAt.isAfter(scheduledFor.plusSeconds(MAX_LATE_FIRE_SECONDS))
        ) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "알람 실행시각이 예약시각 범위를 벗어났습니다.")
        }
    }

    /**
     * Stale generations may arrive after desired state advances, but a client cannot backfill
     * arbitrary historic or future evidence and corrupt the operational cohort. The durable
     * client journal can retry for thirty days; the small future allowance only absorbs clock
     * skew between the device and server.
     */
    private fun requirePlausibleReportWindow(occurredAt: Instant, recordedAt: Instant) {
        if (
            occurredAt.isAfter(recordedAt.plusSeconds(MAX_FUTURE_SKEW_SECONDS)) ||
            occurredAt.isBefore(recordedAt.minusSeconds(MAX_REPORT_AGE_SECONDS))
        ) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "알람 실행 보고 시각이 허용 범위를 벗어났습니다.")
        }
    }

    private fun requireCompatibleTimingBasis(
        platforms: Set<PushPlatform>,
        timingBasis: DepartureAlarmFireTimingBasis,
    ) {
        val compatible = when (timingBasis) {
            DepartureAlarmFireTimingBasis.EXACT_CALLBACK -> PushPlatform.ANDROID in platforms
            DepartureAlarmFireTimingBasis.OBSERVED_ALERTING,
            DepartureAlarmFireTimingBasis.INFERRED_OS_DELIVERY -> PushPlatform.IOS in platforms
        }
        if (!compatible) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "알람 실행시각 근거가 등록 기기 플랫폼과 일치하지 않습니다.",
            )
        }
    }

    private companion object {
        const val MAX_DEVICE_ID_LENGTH = 100
        const val MAX_EARLY_FIRE_SECONDS = 5 * 60L
        const val MAX_LATE_FIRE_SECONDS = 24 * 60 * 60L
        const val MAX_FUTURE_SKEW_SECONDS = 5 * 60L
        const val MAX_REPORT_AGE_SECONDS = 30 * 24 * 60 * 60L
        val MIN_DATABASE_INSTANT: Instant = Instant.parse("2000-01-01T00:00:00Z")
        val MAX_DATABASE_INSTANT: Instant = Instant.parse("2100-01-01T00:00:00Z")
    }
}
