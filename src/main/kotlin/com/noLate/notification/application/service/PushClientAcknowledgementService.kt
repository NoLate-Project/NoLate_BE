package com.noLate.notification.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.global.observability.PushClientAckMetricOutcome
import com.noLate.global.observability.recordSafely
import com.noLate.global.observability.recordOperationalMetricAfterCommit
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushClientAckStage
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.infrastructure.PushDeliveryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class PushClientAcknowledgementResult(
    val recorded: Boolean,
    val stage: PushClientAckStage,
    val occurredAt: Instant,
    val serverRecordedAt: Instant,
)

/**
 * Authenticated last-mile acknowledgement boundary.
 *
 * The logical event key proves the immutable push event and the installation id is reduced to the
 * same SHA-256 device key captured in the frozen manifest. A client therefore cannot acknowledge
 * another member's event or a device that was never part of that event.
 */
@Service
class PushClientAcknowledgementService(
    private val pushDeliveryRepository: PushDeliveryRepository,
    private val operationalMetrics: NoLateOperationalMetrics? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun acknowledge(
        memberId: Long,
        logicalEventKey: String,
        deviceId: String,
        stage: PushClientAckStage,
        occurredAt: Instant,
        providerMessageId: String? = null,
        alarmId: String? = null,
        actionIdentifier: String? = null,
    ): PushClientAcknowledgementResult {
        val normalizedEventKey = logicalEventKey.takeIf(EVENT_KEY_PATTERN::matches)
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "푸시 이벤트 식별자가 올바르지 않습니다.")
        requireValidDeviceId(deviceId)
        requireOptionalIdentifier(providerMessageId, MAX_PROVIDER_MESSAGE_ID_LENGTH, "providerMessageId")
        requireOptionalIdentifier(alarmId, MAX_ALARM_ID_LENGTH, "alarmId")
        requireOptionalIdentifier(actionIdentifier, MAX_ACTION_IDENTIFIER_LENGTH, "actionIdentifier")

        val recordedAt = Instant.now(clock)
        val deviceKey = "device-sha256:${OpaquePushIdentifier.fingerprint(deviceId)}"
        val delivery = pushDeliveryRepository.findClientAckTargetForUpdate(
            memberId = memberId,
            eventKey = normalizedEventKey,
            deviceKey = deviceKey,
        ) ?: throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        if (
            delivery.status != PushDeliveryStatus.DISPATCHING &&
            delivery.status != PushDeliveryStatus.SUCCESS
        ) {
            // Only a provider call in progress (ambiguous race included) or a committed provider
            // success can have produced a real device callback.
            throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        }

        if (stage == PushClientAckStage.ALARM_SCHEDULED || stage == PushClientAckStage.ALARM_FIRED) {
            if (delivery.payloadType != DEPARTURE_ALARM_SYNC_TYPE) {
                throw BusinessException(ErrorCode.INVALID_INPUT, "알람 ACK 대상이 올바르지 않습니다.")
            }
        }

        val changed = delivery.acknowledgeClientStage(
            stage = stage,
            // Device clocks are not authoritative. Preserve the authenticated server receipt
            // time so skewed/manual clocks cannot reject or distort a real delivery ACK.
            occurredAt = recordedAt,
            recordedAt = recordedAt,
        )
        val latencySeconds = delivery.deliveredAt?.let { deliveredAt ->
            Duration.between(deliveredAt, recordedAt).seconds
        }
        recordOperationalMetricAfterCommit {
            operationalMetrics.recordSafely {
                recordPushClientAck(
                    stage,
                    if (changed) PushClientAckMetricOutcome.RECORDED
                    else PushClientAckMetricOutcome.DUPLICATE,
                )
                if (changed && latencySeconds != null) {
                    recordPushClientAckLatency(stage, latencySeconds)
                }
            }
        }
        return PushClientAcknowledgementResult(
            recorded = changed,
            stage = stage,
            occurredAt = occurredAt,
            serverRecordedAt = recordedAt,
        )
    }

    private fun requireValidDeviceId(deviceId: String) {
        if (deviceId.isEmpty() || deviceId.length > MAX_DEVICE_ID_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "기기 식별자가 올바르지 않습니다.")
        }
    }

    private fun requireOptionalIdentifier(value: String?, maximumLength: Int, fieldName: String) {
        if (value != null && (value.isBlank() || value.length > maximumLength)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "$fieldName 값이 올바르지 않습니다.")
        }
    }

    private companion object {
        const val MAX_DEVICE_ID_LENGTH = 100
        const val MAX_PROVIDER_MESSAGE_ID_LENGTH = 300
        const val MAX_ALARM_ID_LENGTH = 100
        const val MAX_ACTION_IDENTIFIER_LENGTH = 100
        const val DEPARTURE_ALARM_SYNC_TYPE = "DEPARTURE_ALARM_SYNC"
        val EVENT_KEY_PATTERN = Regex("^(?:event:[0-9a-fA-F-]{36}|key:[0-9a-f]{64})$")
    }
}
