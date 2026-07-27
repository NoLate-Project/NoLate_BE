package com.noLate.notification.application.service

import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class PushOutboxDispatchWorkerMetricsTest {

    @Test
    fun `stale recovery claim and completion are counted without identity tags`() {
        val now = Instant.parse("2026-07-26T00:00:00Z")
        val useCase = mock<NotificationUseCase>()
        val coordinator = mock<PushOutboxDispatchCoordinator>()
        val registry = SimpleMeterRegistry()
        val metrics = NoLateOperationalMetrics(registry)
        val lease = PushOutboxDispatchLease(
            notificationId = 11,
            memberId = 22,
            logicalEventKey = "event:opaque",
            manifestRecipientCount = 0,
            attemptCount = 1,
            workerId = "worker",
        )
        whenever(
            coordinator.recoverStale(
                now = eq(now),
                processingTimeoutSeconds = eq(600),
                batchSize = eq(50),
            )
        ).thenReturn(2)
        whenever(coordinator.claimNextDue(eq(now), any()))
            .thenReturn(lease, null)
        whenever(
            useCase.redrivePersistedEvent(
                memberId = 22,
                logicalEventKey = "event:opaque",
                sourceLease = lease,
            )
        ).thenReturn(NotificationSendResult())
        whenever(coordinator.complete(lease, now)).thenReturn(true)
        val worker = PushOutboxDispatchWorker(
            notificationUseCase = useCase,
            coordinator = coordinator,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            operationalMetrics = metrics,
        )

        assertEquals(1, worker.runDueEvents(now))

        assertCounter(registry, "stale_lease_recovered", 2.0)
        assertCounter(registry, "claimed", 1.0)
        assertCounter(registry, "completed", 1.0)
        val tagKeys = registry.find("nolate.push.outbox.events")
            .meters()
            .flatMap { it.id.tags }
            .map { it.key }
            .toSet()
        assertEquals(setOf("outcome"), tagKeys)
    }

    private fun assertCounter(
        registry: SimpleMeterRegistry,
        outcome: String,
        expected: Double,
    ) {
        assertEquals(
            expected,
            registry.get("nolate.push.outbox.events")
                .tag("outcome", outcome)
                .counter()
                .count(),
        )
    }
}
