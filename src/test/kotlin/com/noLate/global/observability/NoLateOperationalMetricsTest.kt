package com.noLate.global.observability

import com.noLate.notification.application.service.PushDeliveryClaimOutcome
import com.noLate.notification.application.service.PushTokenProviderLeaseOutcome
import com.noLate.schedule.domain.TrafficSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoLateOperationalMetricsTest {

    @Test
    fun `counters timers and gauges use only bounded tags`() {
        val registry = SimpleMeterRegistry()
        val metrics = NoLateOperationalMetrics(registry)

        metrics.recordPushDeliveryClaim(PushDeliveryClaimOutcome.AMBIGUOUS, 2)
        metrics.recordPushUncertain(PushUncertainMetricReason.PROVIDER_OUTCOME_UNKNOWN)
        metrics.recordPushTokenLease(PushTokenProviderLeaseOutcome.BUSY)
        metrics.recordPushProviderCall(PushProviderMetricOutcome.CONFIRMED_FAILURE, 42)
        metrics.recordPushOutbox(PushOutboxMetricOutcome.STALE_LEASE_RECOVERED, 3)
        metrics.recordEtaJob(EtaJobMetricOutcome.UNCERTAIN_DELIVERY, 4)
        metrics.recordEtaResolution(TrafficSource.SAVED_FALLBACK, degraded = true)
        metrics.recordEtaProviderCall(EtaProviderMetricOutcome.TIMEOUT, 84)
        metrics.updateBacklog(
            OperationalBacklogSnapshot(
                etaDueJobs = 5,
                etaOldestDueDelaySeconds = 120,
                pushOutboxDueEvents = 6,
                pushOutboxOldestDueDelaySeconds = 180,
                pushOutboxStaleLeases = 2,
                ambiguousPushDeliveries = 3,
                expiredPushTokenLeases = 4,
            )
        )

        assertEquals(
            2.0,
            registry.get("nolate.push.delivery.claims")
                .tag("outcome", "ambiguous")
                .counter()
                .count(),
        )
        assertEquals(
            1L,
            registry.get("nolate.push.provider.duration")
                .tag("outcome", "confirmed_failure")
                .timer()
                .count(),
        )
        assertEquals(
            1L,
            registry.get("nolate.eta.provider.duration")
                .tag("outcome", "timeout")
                .timer()
                .count(),
        )
        assertEquals(5.0, registry.get("nolate.eta.jobs.due").gauge().value())
        assertEquals(
            180.0,
            registry.get("nolate.push.outbox.oldest.delay").gauge().value(),
        )

        val allowedTagKeys = setOf("outcome", "reason", "source", "quality", "le")
        registry.meters
            .filter { it.id.name.startsWith("nolate.") }
            .flatMap { it.id.tags }
            .forEach { tag ->
                assertTrue(tag.key in allowedTagKeys, "Unexpected tag key: ${tag.key}")
            }
    }

    @Test
    fun `snapshot failure preserves the last good gauges and increments a failure counter`() {
        val registry = SimpleMeterRegistry()
        val metrics = NoLateOperationalMetrics(registry)
        metrics.updateBacklog(
            OperationalBacklogSnapshot(1, 2, 4, 5, 6, 7, 8)
        )
        val now = Instant.parse("2026-07-26T00:00:00Z")
        val sampler = OperationalBacklogSampler(
            reader = OperationalBacklogSnapshotReader { _, _, _ ->
                throw IllegalStateException("database unavailable")
            },
            metrics = metrics,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        sampler.sample(now)

        assertEquals(1.0, registry.get("nolate.eta.jobs.due").gauge().value())
        assertEquals(
            1.0,
            registry.get("nolate.observability.snapshot.failures").counter().count(),
        )
    }

    @Test
    fun `reading a backlog gauge never invokes the database snapshot reader`() {
        val registry = SimpleMeterRegistry()
        val metrics = NoLateOperationalMetrics(registry)
        val reads = AtomicInteger()
        OperationalBacklogSampler(
            reader = OperationalBacklogSnapshotReader { _, _, _ ->
                reads.incrementAndGet()
                OperationalBacklogSnapshot(1, 2, 3, 4, 5, 6, 7)
            },
            metrics = metrics,
            clock = Clock.systemUTC(),
        )

        assertEquals(0.0, registry.get("nolate.eta.jobs.due").gauge().value())
        assertEquals(0, reads.get())
    }
}
