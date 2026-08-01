package com.noLate.notification.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.DepartureAlarmGenerationRelation
import com.noLate.notification.domain.DepartureAlarmDeliveryMode
import com.noLate.notification.domain.DepartureAlarmScheduleOutcome
import com.noLate.notification.domain.DepartureAlarmScheduleReceipt
import com.noLate.notification.domain.DepartureAlarmScheduleSource
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.DepartureAlarmScheduleReceiptRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import com.noLate.schedule.domain.MAX_DEPARTURE_ALARM_GENERATION
import com.noLate.schedule.domain.departureAlarmId
import com.noLate.schedule.infrastructure.DepartureAlarmSyncStateRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class DepartureAlarmScheduleReceiptResult(
    val recorded: Boolean,
    val receiptId: String,
    val outcome: DepartureAlarmScheduleOutcome,
    val generationRelation: DepartureAlarmGenerationRelation,
    val serverRecordedAt: Instant,
)

/** Persists the measurable denominator for native alarm scheduling and cancellation. */
@Service
class DepartureAlarmScheduleReceiptService(
    private val memberRepository: MemberRepository,
    private val deviceTokenRepository: NotificationDeviceTokenRepository,
    private val syncStateRepository: DepartureAlarmSyncStateRepository,
    private val receiptRepository: DepartureAlarmScheduleReceiptRepository,
    private val scheduleRepository: ScheduleRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun record(
        memberId: Long,
        receiptId: String,
        alarmId: String,
        scheduleId: Long,
        generation: Long,
        recipientMemberId: Long,
        operation: DepartureAlarmSyncOperation,
        triggerAt: Instant?,
        outcome: DepartureAlarmScheduleOutcome,
        applied: Boolean,
        scheduled: Boolean,
        platform: PushPlatform,
        deliveryMode: DepartureAlarmDeliveryMode,
        source: DepartureAlarmScheduleSource,
        reason: String?,
        occurredAt: Instant,
        deviceId: String,
    ): DepartureAlarmScheduleReceiptResult {
        val canonicalReceiptId = canonicalUuid(receiptId)
        requireIdentity(memberId, recipientMemberId, alarmId, scheduleId, generation, deviceId)
        if (platform != PushPlatform.ANDROID && platform != PushPlatform.IOS) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "알람 기기 플랫폼이 올바르지 않습니다.")
        }
        requireCompatibleDeliveryMode(platform, deliveryMode)
        requireDatabaseSafeInstant(occurredAt, "occurredAt")
        triggerAt?.let { requireDatabaseSafeInstant(it, "triggerAt") }
        val recordedAt = Instant.now(clock)
        if (
            occurredAt.isAfter(recordedAt.plusSeconds(MAX_FUTURE_SKEW_SECONDS)) ||
            occurredAt.isBefore(recordedAt.minusSeconds(MAX_REPORT_AGE_SECONDS))
        ) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "알람 예약 결과 시각이 허용 범위를 벗어났습니다.")
        }
        val failureReason = normalizeAndValidateShape(
            operation = operation,
            triggerAt = triggerAt,
            outcome = outcome,
            applied = applied,
            scheduled = scheduled,
            reason = reason,
        )
        val commandReceiptKey = commandReceiptKey(
            alarmId = alarmId,
            generation = generation,
            operation = operation,
            triggerAt = triggerAt,
            outcome = outcome,
        )

        if (memberRepository.findActiveNotificationRecipientForUpdate(memberId) == null) {
            throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        }
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val activeDeviceTokens = deviceTokenRepository
            .findAllByMemberIdAndDeviceFingerprintForUpdate(memberId, deviceFingerprint)
            .filterNot { it.retirementRequested }
        if (activeDeviceTokens.none { it.platform == platform }) {
            throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        }

        val state = syncStateRepository.findByMemberIdAndScheduleIdForUpdate(memberId, scheduleId)
            ?: throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        if (
            state.alarmId != alarmId ||
            generation > state.generation ||
            !scheduleRepository.existsByIdAndDeletedFalse(scheduleId)
        ) {
            throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        }
        if (
            generation == state.generation &&
            (operation != state.operation || triggerAt != state.triggerAt)
        ) {
            throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        }

        receiptRepository.findByMemberIdAndClientReceiptId(memberId, canonicalReceiptId)?.let {
            requireSameReceipt(
                existing = it,
                alarmId = alarmId,
                scheduleId = scheduleId,
                generation = generation,
                deviceFingerprint = deviceFingerprint,
                operation = operation,
                triggerAt = triggerAt,
                outcome = outcome,
                applied = applied,
                scheduled = scheduled,
                platform = platform,
                deliveryMode = deliveryMode,
                source = source,
                failureReason = failureReason,
                occurredAt = occurredAt,
            )
            return it.toResult(recorded = false)
        }
        receiptRepository.findByMemberIdAndDeviceFingerprintAndCommandReceiptKey(
            memberId = memberId,
            deviceFingerprint = deviceFingerprint,
            commandReceiptKey = commandReceiptKey,
        )?.let { return it.toResult(recorded = false) }

        val relation = if (generation == state.generation) {
            DepartureAlarmGenerationRelation.CURRENT
        } else {
            DepartureAlarmGenerationRelation.STALE
        }
        return receiptRepository.save(
            DepartureAlarmScheduleReceipt(
                memberId = memberId,
                clientReceiptId = canonicalReceiptId,
                deviceFingerprint = deviceFingerprint,
                commandReceiptKey = commandReceiptKey,
                alarmId = alarmId,
                scheduleId = scheduleId,
                generation = generation,
                desiredGenerationAtReceipt = state.generation,
                desiredOperationAtReceipt = state.operation,
                generationRelation = relation,
                operation = operation,
                triggerAt = triggerAt,
                outcome = outcome,
                applied = applied,
                scheduled = scheduled,
                platform = platform,
                deliveryMode = deliveryMode,
                source = source,
                failureReason = failureReason,
                clientOccurredAt = occurredAt,
                serverRecordedAt = recordedAt,
            )
        ).toResult(recorded = true)
    }

    private fun normalizeAndValidateShape(
        operation: DepartureAlarmSyncOperation,
        triggerAt: Instant?,
        outcome: DepartureAlarmScheduleOutcome,
        applied: Boolean,
        scheduled: Boolean,
        reason: String?,
    ): String? = when (outcome) {
        DepartureAlarmScheduleOutcome.SCHEDULED -> {
            if (
                operation != DepartureAlarmSyncOperation.UPSERT ||
                triggerAt == null || !scheduled
            ) invalidShape()
            normalizeOptionalReason(reason)
        }
        DepartureAlarmScheduleOutcome.CANCELED -> {
            if (
                operation != DepartureAlarmSyncOperation.CANCEL ||
                triggerAt != null || !applied || scheduled || reason != null
            ) invalidShape()
            null
        }
        DepartureAlarmScheduleOutcome.FAILED -> {
            if (
                scheduled ||
                (operation == DepartureAlarmSyncOperation.CANCEL && applied)
            ) invalidShape()
            normalizeReason(reason)
        }
    }

    private fun normalizeReason(reason: String?): String {
        return normalizeOptionalReason(reason) ?: "NATIVE_APPLY_FAILED"
    }

    private fun normalizeOptionalReason(reason: String?): String? {
        return reason
            ?.trim()
            ?.uppercase()
            ?.replace(Regex("[^A-Z0-9_]+"), "_")
            ?.trim('_')
            ?.take(MAX_REASON_LENGTH)
            ?.takeIf(String::isNotEmpty)
    }

    private fun requireCompatibleDeliveryMode(
        platform: PushPlatform,
        deliveryMode: DepartureAlarmDeliveryMode,
    ) {
        val compatible = when (platform) {
            PushPlatform.ANDROID -> deliveryMode in setOf(
                DepartureAlarmDeliveryMode.ANDROID_EXACT,
                DepartureAlarmDeliveryMode.ANDROID_INEXACT,
                DepartureAlarmDeliveryMode.UNKNOWN,
            )
            PushPlatform.IOS -> deliveryMode in setOf(
                DepartureAlarmDeliveryMode.IOS_ALARM_KIT,
                DepartureAlarmDeliveryMode.IOS_TIME_SENSITIVE,
                DepartureAlarmDeliveryMode.UNKNOWN,
            )
            else -> false
        }
        if (!compatible) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "알람 전달 방식이 플랫폼과 일치하지 않습니다.")
        }
    }

    private fun commandReceiptKey(
        alarmId: String,
        generation: Long,
        operation: DepartureAlarmSyncOperation,
        triggerAt: Instant?,
        outcome: DepartureAlarmScheduleOutcome,
    ): String {
        val canonical = listOf(
            alarmId,
            generation.toString(),
            operation.name,
            triggerAt?.toString().orEmpty(),
            outcome.name,
        ).joinToString("|") { value ->
            "${value.toByteArray(StandardCharsets.UTF_8).size}:$value"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun invalidShape(): Nothing = throw BusinessException(
        ErrorCode.INVALID_INPUT,
        "알람 예약 결과 조합이 올바르지 않습니다.",
    )

    private fun requireSameReceipt(
        existing: DepartureAlarmScheduleReceipt,
        alarmId: String,
        scheduleId: Long,
        generation: Long,
        deviceFingerprint: String,
        operation: DepartureAlarmSyncOperation,
        triggerAt: Instant?,
        outcome: DepartureAlarmScheduleOutcome,
        applied: Boolean,
        scheduled: Boolean,
        platform: PushPlatform,
        deliveryMode: DepartureAlarmDeliveryMode,
        source: DepartureAlarmScheduleSource,
        failureReason: String?,
        occurredAt: Instant,
    ) {
        if (
            existing.alarmId != alarmId || existing.scheduleId != scheduleId ||
            existing.generation != generation || existing.deviceFingerprint != deviceFingerprint ||
            existing.operation != operation || existing.triggerAt != triggerAt ||
            existing.outcome != outcome || existing.applied != applied ||
            existing.scheduled != scheduled || existing.platform != platform ||
            existing.deliveryMode != deliveryMode ||
            existing.source != source || existing.failureReason != failureReason ||
            existing.clientOccurredAt != occurredAt
        ) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "알람 예약 receipt 식별자가 충돌합니다.")
        }
    }

    private fun requireIdentity(
        memberId: Long,
        recipientMemberId: Long,
        alarmId: String,
        scheduleId: Long,
        generation: Long,
        deviceId: String,
    ) {
        if (
            memberId <= 0 || recipientMemberId != memberId || scheduleId <= 0 ||
            generation !in 0..MAX_DEPARTURE_ALARM_GENERATION ||
            alarmId != departureAlarmId(memberId, scheduleId)
        ) throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        if (deviceId.isEmpty() || deviceId.length > MAX_DEVICE_ID_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "기기 식별자가 올바르지 않습니다.")
        }
    }

    private fun canonicalUuid(value: String): String {
        val trimmed = value.trim()
        return runCatching { UUID.fromString(trimmed).toString() }
            .getOrNull()
            ?.takeIf { it == trimmed.lowercase() }
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "알람 예약 receipt 식별자가 올바르지 않습니다.")
    }

    private fun requireDatabaseSafeInstant(value: Instant, fieldName: String) {
        if (value < MIN_DATABASE_INSTANT || value >= MAX_DATABASE_INSTANT) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "$fieldName 값이 올바르지 않습니다.")
        }
    }

    private fun DepartureAlarmScheduleReceipt.toResult(recorded: Boolean) =
        DepartureAlarmScheduleReceiptResult(
            recorded = recorded,
            receiptId = clientReceiptId,
            outcome = outcome,
            generationRelation = generationRelation,
            serverRecordedAt = serverRecordedAt,
        )

    private companion object {
        const val MAX_DEVICE_ID_LENGTH = 100
        const val MAX_REASON_LENGTH = 64
        const val MAX_FUTURE_SKEW_SECONDS = 5 * 60L
        const val MAX_REPORT_AGE_SECONDS = 30 * 24 * 60 * 60L
        val MIN_DATABASE_INSTANT: Instant = Instant.parse("2000-01-01T00:00:00Z")
        val MAX_DATABASE_INSTANT: Instant = Instant.parse("2100-01-01T00:00:00Z")
    }
}
