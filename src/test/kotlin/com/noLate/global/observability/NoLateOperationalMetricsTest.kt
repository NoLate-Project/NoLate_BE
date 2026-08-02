package com.noLate.global.observability

import com.noLate.notification.application.service.PushDeliveryClaimOutcome
import com.noLate.notification.application.service.PushTokenProviderLeaseOutcome
import com.noLate.notification.domain.CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.domain.PushOutboxDispatchStatus
import com.noLate.notification.domain.PushClientAckStage
import com.noLate.notification.domain.DepartureAlarmGenerationRelation
import com.noLate.notification.domain.DepartureAlarmFireTimingBasis
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.schedule.domain.EtaPredictionBasis
import com.noLate.schedule.domain.EtaAccuracyEligibilityReason
import com.noLate.schedule.domain.EtaAlgorithmVersion
import com.noLate.schedule.domain.EtaOnTimeOutcome
import com.noLate.schedule.domain.EtaProviderId
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronization
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoLateOperationalMetricsTest {

    @Test
    fun `transactional metric callback is discarded on rollback`() {
        val calls = AtomicInteger()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.initSynchronization()
        try {
            recordOperationalMetricAfterCommit { calls.incrementAndGet() }

            TransactionSynchronizationManager.getSynchronizations().forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
            }

            assertEquals(0, calls.get())
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
    }

    @Test
    fun `transactional metric callback runs only after commit`() {
        val calls = AtomicInteger()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.initSynchronization()
        try {
            recordOperationalMetricAfterCommit { calls.incrementAndGet() }

            assertEquals(0, calls.get())
            val synchronization = TransactionSynchronizationManager.getSynchronizations().single()
            synchronization.afterCommit()
            assertEquals(1, calls.get())
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
    }

    @Test
    fun `counters timers and gauges use only bounded tags`() {
        val registry = SimpleMeterRegistry()
        val metrics = NoLateOperationalMetrics(registry)

        metrics.recordPushDeliveryClaim(PushDeliveryClaimOutcome.AMBIGUOUS, 2)
        metrics.recordPushUncertain(PushUncertainMetricReason.PROVIDER_OUTCOME_UNKNOWN)
        metrics.recordPushTokenLease(PushTokenProviderLeaseOutcome.BUSY)
        metrics.recordPushProviderCall(PushProviderMetricOutcome.CONFIRMED_FAILURE, 42)
        metrics.recordPushOutbox(PushOutboxMetricOutcome.STALE_LEASE_RECOVERED, 3)
        metrics.recordPushClientAck(
            PushClientAckStage.PRESENTED,
            PushClientAckMetricOutcome.RECORDED,
        )
        metrics.recordPushClientAckLatency(PushClientAckStage.PRESENTED, 9)
        metrics.recordDepartureAlarmFire(
            generationRelation = DepartureAlarmGenerationRelation.STALE,
            outcome = PushClientAckMetricOutcome.RECORDED,
            signedDelaySeconds = -12,
        )
        metrics.recordDepartureAlarmFire(
            generationRelation = DepartureAlarmGenerationRelation.STALE,
            outcome = PushClientAckMetricOutcome.RECORDED,
            signedDelaySeconds = 300,
            timingBasis = DepartureAlarmFireTimingBasis.OBSERVED_ALERTING,
        )
        metrics.recordEtaJob(EtaJobMetricOutcome.UNCERTAIN_DELIVERY, 4)
        metrics.recordEtaWorkerEvent(EtaWorkerMetricEvent.PROCESSING_EXCEPTION)
        metrics.recordEtaResolution(TrafficSource.SAVED_FALLBACK, degraded = true)
        metrics.recordEtaProviderCall(EtaProviderMetricOutcome.TIMEOUT, 84)
        metrics.recordTransitEtaProviderCall(
            provider = TransitEtaProviderMetricId.ODSAY_ROUTE,
            outcome = TransitEtaProviderMetricOutcome.SUCCESS,
            durationNanos = 125,
        )
        metrics.recordTransitEtaProviderResult(
            provider = TransitEtaProviderMetricId.SEOUL_SUBWAY,
            outcome = TransitEtaProviderMetricOutcome.REJECTED_STALE,
        )
        metrics.recordEtaArrivalError(
            source = TrafficSource.LIVE_PROVIDER,
            travelMode = ScheduleTravelMode.TRANSIT,
            providerId = EtaProviderId.ODSAY_TRANSIT,
            predictionBasis = EtaPredictionBasis.PROVIDER_ABSOLUTE,
            algorithmVersion = EtaAlgorithmVersion.TRANSIT_REALTIME_V2,
            signedErrorSeconds = 125,
        )
        metrics.recordEtaOnTimeOutcome(
            travelMode = ScheduleTravelMode.TRANSIT,
            providerId = EtaProviderId.ODSAY_TRANSIT,
            algorithmVersion = EtaAlgorithmVersion.TRANSIT_REALTIME_V2,
            outcome = EtaOnTimeOutcome.PREDICTED_ON_TIME_ACTUAL_LATE,
        )
        metrics.recordEtaObservationFunnel(EtaObservationFunnelStage.PROMPT_OPENED)
        metrics.recordEtaObservationEligibility(EtaAccuracyEligibilityReason.UNVERIFIED_GEOFENCE)
        metrics.updateBacklog(
            OperationalBacklogSnapshot(
                etaDueJobs = 5,
                etaOldestDueDelaySeconds = 120,
                pushOutboxDueEvents = 6,
                pushOutboxOldestDueDelaySeconds = 180,
                pushOutboxStaleLeases = 2,
                ambiguousPushDeliveries = 3,
                expiredPushTokenLeases = 4,
                agedPushProviderSuccessDeliveries = 30,
                agedPushAckEligibleDeliveries = 20,
                agedPushClientReceivedDeliveries = 17,
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
            1.0,
            registry.get("nolate.eta.observation.funnel")
                .tag("stage", "prompt_opened")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            registry.get("nolate.eta.observation.eligibility")
                .tag("reason", "unverified_geofence")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            registry.get("nolate.push.client.acks")
                .tag("stage", "presented")
                .tag("outcome", "recorded")
                .counter()
                .count(),
        )
        assertEquals(
            9.0,
            registry.get("nolate.push.client.ack.latency.seconds")
                .tag("stage", "presented")
                .summary()
                .totalAmount(),
        )
        assertEquals(
            12.0,
            registry.get("nolate.departure.alarm.fire.delay.seconds")
                .tag("generation_relation", "stale")
                .tag("direction", "early")
                .summary()
                .totalAmount(),
        )
        assertEquals(
            1.0,
            registry.get("nolate.departure.alarm.fire.events")
                .tag("generation_relation", "stale")
                .tag("timing_basis", "observed_alerting")
                .tag("outcome", "recorded")
                .counter()
                .count(),
        )
        assertEquals(
            1L,
            registry.get("nolate.eta.arrival.error.seconds")
                .tag("source", "live_provider")
                .tag("direction", "late")
                .tag("travel_mode", "transit")
                .tag("provider", "odsay_transit")
                .tag("prediction_basis", "provider_absolute")
                .summary()
                .count(),
        )
        assertEquals(
            1L,
            registry.get("nolate.eta.provider.duration")
                .tag("outcome", "timeout")
                .timer()
                .count(),
        )
        assertEquals(
            1L,
            registry.get("nolate.eta.transit.provider.duration")
                .tag("provider", "odsay_route")
                .tag("outcome", "success")
                .timer()
                .count(),
        )
        assertEquals(
            1.0,
            registry.get("nolate.eta.transit.provider.events")
                .tag("provider", "seoul_subway")
                .tag("outcome", "rejected_stale")
                .counter()
                .count(),
        )
        assertEquals(5.0, registry.get("nolate.eta.jobs.due").gauge().value())
        assertEquals(
            180.0,
            registry.get("nolate.push.outbox.oldest.delay").gauge().value(),
        )
        assertEquals(
            30.0,
            registry.get("nolate.push.delivery.cohort.provider.success").gauge().value(),
        )
        assertEquals(
            20.0,
            registry.get("nolate.push.delivery.cohort.ack.eligible").gauge().value(),
        )
        assertEquals(
            17.0,
            registry.get("nolate.push.delivery.cohort.client.received").gauge().value(),
        )

        val allowedTagKeys = setOf(
            "outcome",
            "event",
            "reason",
            "source",
            "quality",
            "stage",
            "direction",
            "travel_mode",
            "provider",
            "prediction_basis",
            "algorithm_version",
            "generation_relation",
            "timing_basis",
            "le",
        )
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
    fun `database snapshot reads the fourteen day cohort only after the ten minute grace`() {
        val scheduleJobs = mock<SchedulePushJobRepository>()
        val outbox = mock<AppNotificationRepository>()
        val deliveries = mock<PushDeliveryRepository>()
        val tokens = mock<NotificationDeviceTokenRepository>()
        val now = Instant.parse("2026-08-01T00:00:00Z")
        val cohortFrom = Instant.parse("2026-07-18T00:00:00Z")
        val agedBefore = Instant.parse("2026-07-31T23:50:00Z")

        whenever(scheduleJobs.findOldestDueAt(SchedulePushJobStatus.ACTIVE, now))
            .thenReturn(null)
        whenever(scheduleJobs.countDue(SchedulePushJobStatus.ACTIVE, now)).thenReturn(0)
        whenever(outbox.findOldestDueDispatchAt(PushOutboxDispatchStatus.PENDING, now))
            .thenReturn(null)
        whenever(outbox.countDueDispatches(PushOutboxDispatchStatus.PENDING, now)).thenReturn(0)
        whenever(
            outbox.countStaleDispatchLeases(
                PushOutboxDispatchStatus.PROCESSING,
                now.minusSeconds(600),
            )
        ).thenReturn(0)
        whenever(
            deliveries.countAmbiguousBefore(
                PushDeliveryStatus.DISPATCHING,
                now.minusSeconds(60),
            )
        ).thenReturn(0)
        whenever(tokens.countExpiredDispatchLeases(now)).thenReturn(0)
        whenever(
            deliveries.countProviderSuccessCohort(
                PushDeliveryStatus.SUCCESS,
                cohortFrom,
                agedBefore,
            )
        ).thenReturn(30)
        whenever(
            deliveries.countAckEligibleProviderSuccessCohort(
                PushDeliveryStatus.SUCCESS,
                cohortFrom,
                agedBefore,
                CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION,
            )
        ).thenReturn(20)
        whenever(
            deliveries.countAckEligibleClientReceivedCohort(
                PushDeliveryStatus.SUCCESS,
                cohortFrom,
                agedBefore,
                CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION,
            )
        ).thenReturn(17)
        val reader = JpaOperationalBacklogSnapshotReader(
            schedulePushJobRepository = scheduleJobs,
            appNotificationRepository = outbox,
            pushDeliveryRepository = deliveries,
            notificationDeviceTokenRepository = tokens,
            pushDeliveryCohortWindowDays = 14,
            pushDeliveryCohortGraceMinutes = 10,
        )

        val snapshot = reader.read(
            now = now,
            outboxStaleBefore = now.minusSeconds(600),
            ambiguousBefore = now.minusSeconds(60),
        )

        assertEquals(30, snapshot.agedPushProviderSuccessDeliveries)
        assertEquals(20, snapshot.agedPushAckEligibleDeliveries)
        assertEquals(17, snapshot.agedPushClientReceivedDeliveries)
        verify(deliveries).countProviderSuccessCohort(
            PushDeliveryStatus.SUCCESS,
            cohortFrom,
            agedBefore,
        )
        verify(deliveries).countAckEligibleProviderSuccessCohort(
            PushDeliveryStatus.SUCCESS,
            cohortFrom,
            agedBefore,
            CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION,
        )
        verify(deliveries).countAckEligibleClientReceivedCohort(
            PushDeliveryStatus.SUCCESS,
            cohortFrom,
            agedBefore,
            CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION,
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

    @Test
    fun `successful sampling publishes an epoch freshness gauge`() {
        val registry = SimpleMeterRegistry()
        val metrics = NoLateOperationalMetrics(registry)
        val now = Instant.parse("2026-07-26T00:00:00Z")
        val sampler = OperationalBacklogSampler(
            reader = OperationalBacklogSnapshotReader { _, _, _ ->
                OperationalBacklogSnapshot(1, 2, 3, 4, 5, 6, 7)
            },
            metrics = metrics,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        sampler.sample(now)

        assertEquals(
            now.epochSecond.toDouble(),
            registry.get("nolate.observability.snapshot.last.success").gauge().value(),
        )
    }

    @Test
    fun `an in-flight reader from a stopped generation cannot publish stale gauges`() {
        val registry = SimpleMeterRegistry()
        val metrics = NoLateOperationalMetrics(registry)
        val readerStarted = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val readerFinished = CountDownLatch(1)
        val sampler = OperationalBacklogSampler(
            reader = OperationalBacklogSnapshotReader { _, _, _ ->
                readerStarted.countDown()
                try {
                    while (releaseReader.count > 0) {
                        try {
                            releaseReader.await()
                        } catch (_: InterruptedException) {
                            // Simulate a JDBC driver that ignores interruption during shutdown.
                        }
                    }
                    OperationalBacklogSnapshot(9, 9, 9, 9, 9, 9, 9)
                } finally {
                    readerFinished.countDown()
                }
            },
            metrics = metrics,
            clock = Clock.systemUTC(),
            initialDelayMillis = 0,
            shutdownWaitMillis = 10,
        )

        sampler.start()
        assertTrue(readerStarted.await(1, TimeUnit.SECONDS))
        sampler.stop()
        assertFalse(sampler.isRunning)
        releaseReader.countDown()
        assertTrue(readerFinished.await(1, TimeUnit.SECONDS))

        assertEquals(0.0, registry.get("nolate.eta.jobs.due").gauge().value())
        assertEquals(
            0.0,
            registry.get("nolate.observability.snapshot.last.success").gauge().value(),
        )
    }
}
