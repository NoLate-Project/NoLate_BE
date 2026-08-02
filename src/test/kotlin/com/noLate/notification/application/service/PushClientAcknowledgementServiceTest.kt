package com.noLate.notification.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushClientAckStage
import com.noLate.notification.domain.PushDelivery
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.PushDeliveryRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class PushClientAcknowledgementServiceTest {
    @Mock lateinit var repository: PushDeliveryRepository

    @Test
    fun `matching member event and installation records one idempotent ACK`() {
        val registry = SimpleMeterRegistry()
        val delivery = delivery(payloadType = "SCHEDULE_DEPARTURE_REMINDER")
        delivery.beginDispatch(NOW.minusSeconds(10))
        delivery.markSuccess(NOW.minusSeconds(8), "provider-message-1")
        whenever(repository.findClientAckTargetForUpdate(7L, EVENT_KEY, DEVICE_KEY))
            .thenReturn(delivery)
        val service = service(registry)

        val first = service.acknowledge(
            memberId = 7L,
            logicalEventKey = EVENT_KEY,
            deviceId = DEVICE_ID,
            stage = PushClientAckStage.RECEIVED,
            occurredAt = NOW.minusSeconds(2),
        )
        val duplicate = service.acknowledge(
            memberId = 7L,
            logicalEventKey = EVENT_KEY,
            deviceId = DEVICE_ID,
            stage = PushClientAckStage.RECEIVED,
            occurredAt = NOW,
        )

        assertTrue(first.recorded)
        assertFalse(duplicate.recorded)
        assertEquals(NOW, delivery.clientReceivedAt)
        assertEquals(NOW, delivery.clientAckRecordedAt)
        assertEquals(
            1.0,
            registry.get("nolate.push.client.acks")
                .tag("stage", "received")
                .tag("outcome", "recorded")
                .counter().count(),
        )
        assertEquals(
            1L,
            registry.get("nolate.push.client.ack.latency.seconds")
                .tag("stage", "received")
                .summary().count(),
        )
        assertEquals(
            8.0,
            registry.get("nolate.push.client.ack.latency.seconds")
                .tag("stage", "received")
                .summary().totalAmount(),
        )
        assertEquals(
            1.0,
            registry.get("nolate.push.client.acks")
                .tag("stage", "received")
                .tag("outcome", "duplicate")
                .counter().count(),
        )
    }

    @Test
    fun `unknown device cannot acknowledge another frozen manifest`() {
        whenever(repository.findClientAckTargetForUpdate(7L, EVENT_KEY, DEVICE_KEY))
            .thenReturn(null)

        assertFailsWith<BusinessException> {
            service().acknowledge(
                memberId = 7L,
                logicalEventKey = EVENT_KEY,
                deviceId = DEVICE_ID,
                stage = PushClientAckStage.PRESENTED,
                occurredAt = NOW,
            )
        }
    }

    @Test
    fun `alarm ACK is accepted only for alarm control payload`() {
        val delivered = delivered(payloadType = "SCHEDULE_TRAFFIC")
        whenever(repository.findClientAckTargetForUpdate(7L, EVENT_KEY, DEVICE_KEY))
            .thenReturn(delivered)

        assertFailsWith<BusinessException> {
            service().acknowledge(
                memberId = 7L,
                logicalEventKey = EVENT_KEY,
                deviceId = DEVICE_ID,
                stage = PushClientAckStage.ALARM_SCHEDULED,
                occurredAt = NOW,
            )
        }
    }

    @Test
    fun `unsent terminal delivery cannot create false client evidence`() {
        whenever(repository.findClientAckTargetForUpdate(7L, EVENT_KEY, DEVICE_KEY))
            .thenReturn(delivery(payloadType = "SCHEDULE_TRAFFIC"))

        assertFailsWith<BusinessException> {
            service().acknowledge(
                memberId = 7L,
                logicalEventKey = EVENT_KEY,
                deviceId = DEVICE_ID,
                stage = PushClientAckStage.RECEIVED,
                occurredAt = NOW,
            )
        }
    }

    @Test
    fun `skewed client clock does not discard a real delivered ACK`() {
        val delivered = delivered(payloadType = "SCHEDULE_TRAFFIC")
        whenever(repository.findClientAckTargetForUpdate(7L, EVENT_KEY, DEVICE_KEY))
            .thenReturn(delivered)

        val result = service().acknowledge(
            memberId = 7L,
            logicalEventKey = EVENT_KEY,
            deviceId = DEVICE_ID,
            stage = PushClientAckStage.RECEIVED,
            occurredAt = Instant.parse("2040-01-01T00:00:00Z"),
        )

        assertTrue(result.recorded)
        assertEquals(NOW, result.serverRecordedAt)
        assertEquals(NOW, delivered.clientReceivedAt)
    }

    @Test
    fun `ACK racing a later confirmed provider failure is removed from delivery evidence`() {
        val dispatching = delivery(payloadType = "SCHEDULE_TRAFFIC").apply {
            beginDispatch(NOW.minusSeconds(1))
        }
        whenever(repository.findClientAckTargetForUpdate(7L, EVENT_KEY, DEVICE_KEY))
            .thenReturn(dispatching)
        service().acknowledge(
            memberId = 7L,
            logicalEventKey = EVENT_KEY,
            deviceId = DEVICE_ID,
            stage = PushClientAckStage.RECEIVED,
            occurredAt = NOW,
        )

        dispatching.markFailure(NOW.plusSeconds(1), "CONFIRMED_FAILURE", null)

        assertEquals(null, dispatching.clientReceivedAt)
        assertEquals(null, dispatching.clientAckRecordedAt)
    }

    private fun service(registry: SimpleMeterRegistry = SimpleMeterRegistry()) =
        PushClientAcknowledgementService(
            pushDeliveryRepository = repository,
            operationalMetrics = NoLateOperationalMetrics(registry),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

    private fun delivery(payloadType: String) = PushDelivery(
        id = 9L,
        memberId = 7L,
        eventKey = EVENT_KEY,
        deviceKey = DEVICE_KEY,
        deviceTokenId = 11L,
        tokenFingerprint = "a".repeat(64),
        tokenOwnershipVersion = 1,
        deviceFingerprint = OpaquePushIdentifier.fingerprint(DEVICE_ID),
        platform = PushPlatform.ANDROID,
        scheduleId = 3L,
        payloadType = payloadType,
    )

    private fun delivered(payloadType: String) = delivery(payloadType).apply {
        beginDispatch(NOW.minusSeconds(2))
        markSuccess(NOW.minusSeconds(1), "provider-message")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-31T03:00:00Z")
        const val EVENT_KEY = "event:550e8400-e29b-41d4-a716-446655440000"
        const val DEVICE_ID = "installation-123"
        val DEVICE_KEY = "device-sha256:${OpaquePushIdentifier.fingerprint(DEVICE_ID)}"
    }
}
