package com.noLate.global.observability

import com.noLate.notification.application.service.PushDeliveryClaimOutcome
import com.noLate.notification.application.service.PushTokenProviderLeaseOutcome
import com.noLate.schedule.domain.TrafficSource
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong

enum class PushProviderMetricOutcome {
    SUCCESS,
    INVALID_TOKEN,
    CONFIRMED_FAILURE,
    UNKNOWN,
}

enum class PushUncertainMetricReason {
    PREEXISTING_DISPATCH,
    PROVIDER_OUTCOME_UNKNOWN,
    LOCAL_SUCCESS_NOT_RECORDED,
    LOCAL_FAILURE_NOT_RECORDED,
}

enum class PushOutboxMetricOutcome {
    CLAIMED,
    COMPLETED,
    RETRY_SCHEDULED,
    TERMINAL_FAILURE,
    DEFERRED,
    STALE_LEASE_RECOVERED,
    LEASE_LOST,
}

enum class EtaJobMetricOutcome {
    CLAIMED,
    PROCESSED,
    RETRY_SCHEDULED,
    TERMINAL_FAILURE,
    STALE_LEASE_RECOVERED,
    UNCERTAIN_DELIVERY,
}

enum class EtaWorkerMetricEvent {
    PROCESSING_EXCEPTION,
}

enum class EtaProviderMetricOutcome {
    SUCCESS,
    TIMEOUT,
    HTTP_ERROR,
    INVALID_RESPONSE,
    UNAVAILABLE,
}

data class OperationalBacklogSnapshot(
    val etaDueJobs: Long,
    val etaOldestDueDelaySeconds: Long,
    val pushOutboxDueEvents: Long,
    val pushOutboxOldestDueDelaySeconds: Long,
    val pushOutboxStaleLeases: Long,
    val ambiguousPushDeliveries: Long,
    val expiredPushTokenLeases: Long,
)

@Component
class NoLateOperationalMetrics(
    registry: MeterRegistry,
) {
    private val pushDeliveryClaims = PushDeliveryClaimOutcome.entries.associateWith { outcome ->
        counter(
            registry,
            "nolate.push.delivery.claims",
            "Durable push delivery claims by bounded outcome.",
            "outcome",
            outcome.metricTag(),
        )
    }
    private val pushUncertain = PushUncertainMetricReason.entries.associateWith { reason ->
        counter(
            registry,
            "nolate.push.delivery.uncertain",
            "Push deliveries whose local or provider result cannot be proven.",
            "reason",
            reason.metricTag(),
        )
    }
    private val pushTokenLeases = PushTokenProviderLeaseOutcome.entries.associateWith { outcome ->
        counter(
            registry,
            "nolate.push.token.lease",
            "Provider ownership lease decisions by bounded outcome.",
            "outcome",
            outcome.metricTag(),
        )
    }
    private val pushProviderDuration = PushProviderMetricOutcome.entries.associateWith { outcome ->
        Timer.builder("nolate.push.provider.duration")
            .description("Push provider call duration by bounded result.")
            .tag("outcome", outcome.metricTag())
            .serviceLevelObjectives(
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
            )
            .register(registry)
    }
    private val pushOutboxEvents = PushOutboxMetricOutcome.entries.associateWith { outcome ->
        counter(
            registry,
            "nolate.push.outbox.events",
            "Durable push outbox transitions by bounded outcome.",
            "outcome",
            outcome.metricTag(),
        )
    }
    private val etaJobs = EtaJobMetricOutcome.entries.associateWith { outcome ->
        counter(
            registry,
            "nolate.eta.jobs",
            "Durably committed ETA job transitions by bounded outcome.",
            "outcome",
            outcome.metricTag(),
        )
    }
    private val etaWorkerEvents = EtaWorkerMetricEvent.entries.associateWith { event ->
        counter(
            registry,
            "nolate.eta.worker.events",
            "ETA worker observations that are not durable job transitions.",
            "event",
            event.metricTag(),
        )
    }
    private val etaResolutions =
        TrafficSource.entries.flatMap { source ->
            listOf(false, true).map { degraded ->
                (source to degraded) to counter(
                    registry,
                    "nolate.eta.resolutions",
                    "ETA resolutions by source and freshness.",
                    "source",
                    source.metricTag(),
                    "quality",
                    if (degraded) "degraded" else "fresh",
                )
            }
        }.toMap()
    private val etaProviderDuration = EtaProviderMetricOutcome.entries.associateWith { outcome ->
        Timer.builder("nolate.eta.provider.duration")
            .description("Live ETA provider call duration by bounded result.")
            .tag("outcome", outcome.metricTag())
            .serviceLevelObjectives(
                Duration.ofMillis(250),
                Duration.ofSeconds(1),
                Duration.ofSeconds(3),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
            )
            .register(registry)
    }

    private val snapshotFailures = Counter.builder("nolate.observability.snapshot.failures")
        .description("Failures while sampling operational backlog gauges.")
        .register(registry)
    private val snapshotLastSuccessEpochSeconds = gauge(
        registry,
        "nolate.observability.snapshot.last.success",
        "Epoch time of the last successful operational backlog snapshot.",
        "seconds",
    )

    // Scrapes read only these in-memory values. Database sampling runs independently below so a
    // slow or unavailable database cannot make the Prometheus endpoint slow or unavailable.
    private val etaDueJobs = gauge(registry, "nolate.eta.jobs.due", "Currently overdue ETA jobs.")
    private val etaOldestDueDelaySeconds = gauge(
        registry,
        "nolate.eta.jobs.oldest.delay",
        "Delay of the oldest overdue ETA job.",
        "seconds",
    )
    private val pushOutboxDueEvents = gauge(
        registry,
        "nolate.push.outbox.events.due",
        "Currently overdue push outbox events.",
    )
    private val pushOutboxOldestDueDelaySeconds = gauge(
        registry,
        "nolate.push.outbox.oldest.delay",
        "Delay of the oldest overdue push outbox event.",
        "seconds",
    )
    private val pushOutboxStaleLeases = gauge(
        registry,
        "nolate.push.outbox.leases.stale",
        "Push outbox processing leases older than the configured timeout.",
    )
    private val ambiguousPushDeliveries = gauge(
        registry,
        "nolate.push.deliveries.ambiguous",
        "DISPATCHING push deliveries older than the bounded provider call.",
    )
    private val expiredPushTokenLeases = gauge(
        registry,
        "nolate.push.token.leases.expired",
        "Expired provider ownership leases awaiting cleanup.",
    )

    fun recordPushDeliveryClaim(outcome: PushDeliveryClaimOutcome, count: Int = 1) {
        pushDeliveryClaims.getValue(outcome).incrementPositive(count)
    }

    fun recordPushUncertain(reason: PushUncertainMetricReason, count: Int = 1) {
        pushUncertain.getValue(reason).incrementPositive(count)
    }

    fun recordPushTokenLease(outcome: PushTokenProviderLeaseOutcome, count: Int = 1) {
        pushTokenLeases.getValue(outcome).incrementPositive(count)
    }

    fun recordPushProviderCall(
        outcome: PushProviderMetricOutcome,
        durationNanos: Long,
    ) {
        pushProviderDuration.getValue(outcome)
            .record(durationNanos.coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }

    fun recordPushOutbox(outcome: PushOutboxMetricOutcome, count: Int = 1) {
        pushOutboxEvents.getValue(outcome).incrementPositive(count)
    }

    fun recordEtaJob(outcome: EtaJobMetricOutcome, count: Int = 1) {
        etaJobs.getValue(outcome).incrementPositive(count)
    }

    fun recordEtaWorkerEvent(event: EtaWorkerMetricEvent, count: Int = 1) {
        etaWorkerEvents.getValue(event).incrementPositive(count)
    }

    fun recordEtaResolution(source: TrafficSource, degraded: Boolean) {
        etaResolutions.getValue(source to degraded).increment()
    }

    fun recordEtaProviderCall(
        outcome: EtaProviderMetricOutcome,
        durationNanos: Long,
    ) {
        etaProviderDuration.getValue(outcome)
            .record(durationNanos.coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }

    fun updateBacklog(snapshot: OperationalBacklogSnapshot) {
        etaDueJobs.set(snapshot.etaDueJobs.coerceAtLeast(0))
        etaOldestDueDelaySeconds.set(snapshot.etaOldestDueDelaySeconds.coerceAtLeast(0))
        pushOutboxDueEvents.set(snapshot.pushOutboxDueEvents.coerceAtLeast(0))
        pushOutboxOldestDueDelaySeconds.set(snapshot.pushOutboxOldestDueDelaySeconds.coerceAtLeast(0))
        pushOutboxStaleLeases.set(snapshot.pushOutboxStaleLeases.coerceAtLeast(0))
        ambiguousPushDeliveries.set(snapshot.ambiguousPushDeliveries.coerceAtLeast(0))
        expiredPushTokenLeases.set(snapshot.expiredPushTokenLeases.coerceAtLeast(0))
    }

    fun recordSnapshotFailure() {
        snapshotFailures.increment()
    }

    fun recordSnapshotSuccess(sampledAt: Instant) {
        snapshotLastSuccessEpochSeconds.set(sampledAt.epochSecond.coerceAtLeast(0))
    }
}

fun interface OperationalBacklogSnapshotReader {
    fun read(
        now: Instant,
        outboxStaleBefore: Instant,
        ambiguousBefore: Instant,
    ): OperationalBacklogSnapshot
}

@Service
class JpaOperationalBacklogSnapshotReader(
    private val schedulePushJobRepository:
        com.noLate.schedule.infrastructure.SchedulePushJobRepository,
    private val appNotificationRepository:
        com.noLate.notification.infrastructure.AppNotificationRepository,
    private val pushDeliveryRepository:
        com.noLate.notification.infrastructure.PushDeliveryRepository,
    private val notificationDeviceTokenRepository:
        com.noLate.notification.infrastructure.NotificationDeviceTokenRepository,
) : OperationalBacklogSnapshotReader {

    @Transactional(readOnly = true)
    override fun read(
        now: Instant,
        outboxStaleBefore: Instant,
        ambiguousBefore: Instant,
    ): OperationalBacklogSnapshot {
        val etaOldest = schedulePushJobRepository.findOldestDueAt(
            com.noLate.schedule.domain.SchedulePushJobStatus.ACTIVE,
            now,
        )
        val outboxOldest = appNotificationRepository.findOldestDueDispatchAt(
            com.noLate.notification.domain.PushOutboxDispatchStatus.PENDING,
            now,
        )
        return OperationalBacklogSnapshot(
            etaDueJobs = schedulePushJobRepository.countDue(
                com.noLate.schedule.domain.SchedulePushJobStatus.ACTIVE,
                now,
            ),
            etaOldestDueDelaySeconds = etaOldest.delaySecondsUntil(now),
            pushOutboxDueEvents = appNotificationRepository.countDueDispatches(
                com.noLate.notification.domain.PushOutboxDispatchStatus.PENDING,
                now,
            ),
            pushOutboxOldestDueDelaySeconds = outboxOldest.delaySecondsUntil(now),
            pushOutboxStaleLeases = appNotificationRepository.countStaleDispatchLeases(
                com.noLate.notification.domain.PushOutboxDispatchStatus.PROCESSING,
                outboxStaleBefore,
            ),
            ambiguousPushDeliveries = pushDeliveryRepository.countAmbiguousBefore(
                com.noLate.notification.domain.PushDeliveryStatus.DISPATCHING,
                ambiguousBefore,
            ),
            expiredPushTokenLeases =
                notificationDeviceTokenRepository.countExpiredDispatchLeases(now),
        )
    }
}

@Component
class OperationalBacklogSampler(
    private val reader: OperationalBacklogSnapshotReader,
    private val metrics: NoLateOperationalMetrics,
    private val clock: Clock,
    @Value("\${observability.snapshot.enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${notification.push-outbox.processing-timeout-seconds:600}")
    private val outboxProcessingTimeoutSeconds: Long = 600,
    @Value("\${notification.push-token.provider-max-call-seconds:60}")
    private val providerMaxCallSeconds: Long = 60,
    @Value("\${observability.snapshot.fixed-delay-ms:30000}")
    private val fixedDelayMillis: Long = 30_000,
    @Value("\${observability.snapshot.initial-delay-ms:30000}")
    private val initialDelayMillis: Long = 30_000,
    @Value("\${observability.snapshot.shutdown-wait-ms:5000}")
    private val shutdownWaitMillis: Long = 5_000,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lifecycleMonitor = Any()
    @Volatile
    private var running = false
    @Volatile
    private var lifecycleGeneration = 0L
    private var executor: ScheduledExecutorService? = null

    fun sample() {
        sample(Instant.now(clock))
    }

    fun sample(now: Instant) {
        sample(now) { publish ->
            publish()
            true
        }
    }

    private fun sample(
        now: Instant,
        publishIfCurrent: (() -> Unit) -> Boolean,
    ) {
        if (!enabled) return
        runCatching {
            reader.read(
                now = now,
                outboxStaleBefore = now.minusSeconds(
                    outboxProcessingTimeoutSeconds.coerceAtLeast(1),
                ),
                ambiguousBefore = now.minusSeconds(providerMaxCallSeconds.coerceAtLeast(1)),
            )
        }.onSuccess { snapshot ->
            publishIfCurrent {
                metrics.recordSafely {
                    updateBacklog(snapshot)
                    recordSnapshotSuccess(now)
                }
            }
        }
            .onFailure { failure ->
                publishIfCurrent {
                    metrics.recordSafely { recordSnapshotFailure() }
                    log.warn(
                        "Operational backlog sampling failed. errorCode={}",
                        failure.javaClass.simpleName,
                    )
                }
            }
    }

    override fun start() {
        synchronized(lifecycleMonitor) {
            if (!enabled || running) return
            val scheduledExecutor = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "nolate-operational-snapshot").apply { isDaemon = true }
            }
            val generation = lifecycleGeneration + 1
            lifecycleGeneration = generation
            executor = scheduledExecutor
            running = true
            try {
                scheduledExecutor.scheduleWithFixedDelay(
                    {
                        if (isCurrentGeneration(generation)) {
                            sample(Instant.now(clock)) { publish ->
                                publishForGeneration(generation, publish)
                            }
                        }
                    },
                    initialDelayMillis.coerceAtLeast(0),
                    fixedDelayMillis.coerceAtLeast(1_000),
                    TimeUnit.MILLISECONDS,
                )
            } catch (failure: RuntimeException) {
                running = false
                lifecycleGeneration += 1
                executor = null
                scheduledExecutor.shutdownNow()
                throw failure
            }
        }
    }

    override fun stop() {
        val stoppingExecutor = synchronized(lifecycleMonitor) {
            running = false
            lifecycleGeneration += 1
            executor.also { executor = null }
        } ?: return
        stoppingExecutor.shutdownNow()
        try {
            if (!stoppingExecutor.awaitTermination(
                    shutdownWaitMillis.coerceAtLeast(0),
                    TimeUnit.MILLISECONDS,
                )
            ) {
                log.warn("Operational backlog sampler did not stop within the shutdown bound.")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun isRunning(): Boolean = running

    private fun isCurrentGeneration(generation: Long): Boolean =
        running && lifecycleGeneration == generation

    private fun publishForGeneration(
        generation: Long,
        publish: () -> Unit,
    ): Boolean =
        synchronized(lifecycleMonitor) {
            if (!isCurrentGeneration(generation)) {
                false
            } else {
                publish()
                true
            }
        }
}

inline fun NoLateOperationalMetrics?.recordSafely(
    record: NoLateOperationalMetrics.() -> Unit,
) {
    val metrics = this ?: return
    try {
        metrics.record()
    } catch (_: RuntimeException) {
        // Telemetry must never change a provider result or a durable state transition.
    }
}

private fun counter(
    registry: MeterRegistry,
    name: String,
    description: String,
    vararg tags: String,
): Counter =
    Counter.builder(name)
        .description(description)
        .tags(*tags)
        .register(registry)

private fun gauge(
    registry: MeterRegistry,
    name: String,
    description: String,
    baseUnit: String? = null,
): AtomicLong {
    val value = AtomicLong()
    val builder = Gauge.builder(name, value) { it.get().toDouble() }
        .description(description)
    if (baseUnit != null) builder.baseUnit(baseUnit)
    builder.register(registry)
    return value
}

private fun Counter.incrementPositive(count: Int) {
    if (count > 0) increment(count.toDouble())
}

private fun Enum<*>.metricTag(): String = name.lowercase()

private fun Instant?.delaySecondsUntil(now: Instant): Long =
    this?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0
