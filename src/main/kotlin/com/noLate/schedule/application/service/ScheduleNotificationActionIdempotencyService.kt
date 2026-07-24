package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleNotificationActionReceipt
import com.noLate.schedule.domain.ScheduleNotificationActionType
import com.noLate.schedule.infrastructure.ScheduleNotificationActionReceiptRepository
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

data class ValidatedScheduleNotificationActionKey(
    val fingerprint: String,
    val encodedAction: ScheduleNotificationActionType,
)

/**
 * action key의 원문을 로그/DB에 남기지 않는 HTTP 경계 검증.
 *
 * FE가 push payload의 opaque logicalEventKey만 suffix로 사용할 수 있게 형식을 제한한다.
 * 따라서 이메일·전화번호 같은 PII나 자유 입력이 멱등 저장소/오류에 섞이지 않는다.
 */
object ScheduleNotificationActionKeyValidator {
    private const val MIN_LENGTH = 48
    private const val MAX_LENGTH = 120
    private val format = Regex(
        "^(departNow|snooze):(key:[0-9a-f]{64}|event:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12})$"
    )

    fun validate(rawKey: String): ValidatedScheduleNotificationActionKey {
        if (rawKey.length !in MIN_LENGTH..MAX_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_IDEMPOTENCY_KEY)
        }
        val match = format.matchEntire(rawKey)
            ?: throw BusinessException(ErrorCode.INVALID_IDEMPOTENCY_KEY)
        val encodedAction = when (match.groupValues[1]) {
            ScheduleNotificationActionType.DEPART_NOW.keyPrefix ->
                ScheduleNotificationActionType.DEPART_NOW
            ScheduleNotificationActionType.SNOOZE.keyPrefix ->
                ScheduleNotificationActionType.SNOOZE
            else -> throw BusinessException(ErrorCode.INVALID_IDEMPOTENCY_KEY)
        }
        return ValidatedScheduleNotificationActionKey(
            fingerprint = OpaquePushIdentifier.fingerprint(rawKey),
            encodedAction = encodedAction,
        )
    }
}

@Service
class ScheduleNotificationActionIdempotencyService(
    private val writer: ScheduleNotificationActionIdempotencyWriter,
) {
    fun departNow(
        memberId: Long,
        scheduleId: Long,
        rawKey: String,
    ): ScheduleDto {
        val key = ScheduleNotificationActionKeyValidator.validate(rawKey)
        return retryUniqueRace {
            writer.departNow(memberId, scheduleId, key)
        }
    }

    fun snooze(
        memberId: Long,
        scheduleId: Long,
        rawKey: String,
    ): Instant? {
        val key = ScheduleNotificationActionKeyValidator.validate(rawKey)
        return retryUniqueRace {
            writer.snooze(memberId, scheduleId, key)
        }
    }

    private fun <T> retryUniqueRace(block: () -> T): T {
        repeat(3) { attempt ->
            try {
                return block()
            } catch (failure: RuntimeException) {
                if (!failure.isExpectedActionReceiptCollision() &&
                    failure !is TransientDataAccessException
                ) {
                    throw failure
                }
                if (attempt == 2) {
                    throw ConcurrencyFailureException(
                        "Idempotency receipt registration did not converge.",
                    )
                }
            }
        }
        throw ConcurrencyFailureException(
            "Idempotency receipt registration did not converge.",
        )
    }
}

private fun RuntimeException.isExpectedActionReceiptCollision(): Boolean {
    if (this is DuplicateKeyException) return true

    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        val constraintName = (current as? ConstraintViolationException)?.constraintName
        if (constraintName?.contains(ACTION_RECEIPT_UNIQUE_CONSTRAINT, ignoreCase = true) == true) {
            return true
        }
        current = current.cause
    }
    return false
}

private const val ACTION_RECEIPT_UNIQUE_CONSTRAINT =
    "uk_schedule_notification_action_key_fingerprint"

@Service
class ScheduleNotificationActionIdempotencyWriter(
    private val receiptRepository: ScheduleNotificationActionReceiptRepository,
    private val scheduleService: ScheduleService,
    private val departureStatusService: ScheduleDepartureStatusService,
    private val pushJobService: SchedulePushJobService,
    private val travelPlanService: ScheduleTravelPlanService? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * receipt insert를 먼저 flush해 concurrent winner를 하나로 정하지만, receipt 완료와
     * mutation은 같은 transaction에서만 commit된다. 중간 예외/프로세스 종료는 둘 다 rollback한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun departNow(
        memberId: Long,
        scheduleId: Long,
        key: ValidatedScheduleNotificationActionKey,
    ): ScheduleDto {
        val existing = receiptRepository.findByKeyFingerprintForUpdate(key.fingerprint)
        if (existing != null) {
            validateScope(existing, memberId, scheduleId, ScheduleNotificationActionType.DEPART_NOW)
            return currentDepartedResult(memberId, scheduleId)
        }
        validateEncodedAction(key, ScheduleNotificationActionType.DEPART_NOW)

        val receipt = receiptRepository.saveAndFlush(
            ScheduleNotificationActionReceipt(
                keyFingerprint = key.fingerprint,
                memberId = memberId,
                scheduleId = scheduleId,
                actionType = ScheduleNotificationActionType.DEPART_NOW,
                createdAt = Instant.now(clock),
            )
        )

        val detail = scheduleService.getScheduleDetail(memberId, scheduleId)
        val departureStatus = departureStatusService.markDeparted(memberId, scheduleId)
        travelPlanService?.disableNotification(memberId, scheduleId)
        pushJobService.cancelByScheduleIdAndMemberId(scheduleId, memberId)
        val updated = if (detail.ownerMemberId == memberId) {
            scheduleService.markDeparted(memberId, scheduleId)
        } else {
            scheduleService.getScheduleDetail(memberId, scheduleId)
        }
        val result = departureStatusService.attachDepartureParticipants(memberId, updated)
        receipt.complete(
            completedAt = Instant.now(clock),
            departedAt = departureStatus.departedAt,
        )
        return result
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun snooze(
        memberId: Long,
        scheduleId: Long,
        key: ValidatedScheduleNotificationActionKey,
    ): Instant? {
        val existing = receiptRepository.findByKeyFingerprintForUpdate(key.fingerprint)
        if (existing != null) {
            validateScope(existing, memberId, scheduleId, ScheduleNotificationActionType.SNOOZE)
            return existing.resultSnoozedUntil
        }
        validateEncodedAction(key, ScheduleNotificationActionType.SNOOZE)

        val receipt = receiptRepository.saveAndFlush(
            ScheduleNotificationActionReceipt(
                keyFingerprint = key.fingerprint,
                memberId = memberId,
                scheduleId = scheduleId,
                actionType = ScheduleNotificationActionType.SNOOZE,
                createdAt = Instant.now(clock),
            )
        )
        val snoozedUntil = pushJobService.snoozeDepartureReminder(memberId, scheduleId)
        receipt.complete(
            completedAt = Instant.now(clock),
            snoozedUntil = snoozedUntil,
        )
        return snoozedUntil
    }

    private fun currentDepartedResult(memberId: Long, scheduleId: Long): ScheduleDto =
        departureStatusService.attachDepartureParticipants(
            memberId,
            scheduleService.getScheduleDetail(memberId, scheduleId),
        )

    private fun validateScope(
        receipt: ScheduleNotificationActionReceipt,
        memberId: Long,
        scheduleId: Long,
        actionType: ScheduleNotificationActionType,
    ) {
        if (!receipt.belongsTo(memberId, scheduleId, actionType)) {
            throw BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT)
        }
        if (receipt.completedAt == null) {
            // 정상 transaction 경계에서는 commit된 미완료 row가 존재하지 않는다.
            throw BusinessException(ErrorCode.INVALID_STATE, "알림 action 완료 상태가 올바르지 않습니다.")
        }
    }

    private fun validateEncodedAction(
        key: ValidatedScheduleNotificationActionKey,
        requestedAction: ScheduleNotificationActionType,
    ) {
        if (key.encodedAction != requestedAction) {
            throw BusinessException(ErrorCode.INVALID_IDEMPOTENCY_KEY)
        }
    }
}
