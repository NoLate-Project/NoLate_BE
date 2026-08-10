package com.noLate.notification.liveactivity.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.TreeSet
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A non-sensitive hint that a durable
 * [com.noLate.notification.liveactivity.domain.LiveActivityDelivery] row became dispatchable. The
 * database row remains the source of truth; losing this in-memory event only delays work until the
 * fallback scan.
 */
data class LiveActivityDispatchRequested(val availableAt: Instant)

fun interface LiveActivityDispatchWakeup {
    fun requestDispatch(availableAt: Instant)
}

/**
 * Event-driven Live Activity dispatcher with a low-frequency durable-outbox safety scan.
 *
 * Provider I/O always runs on this private executor, never on the source transaction thread. Retry
 * instants are retained in a small in-memory ordered set so the worker wakes at `available_at`; the
 * database and the fallback scan still recover every row after a process crash or lost event.
 */
@Component
class LiveActivityDispatchScheduler(
    private val worker: LiveActivityDispatchWorker,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${notification.live-activity.dispatch-enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${notification.live-activity.fallback-scan-millis:30000}")
    private val fallbackScanMillis: Long = 30_000,
    @Value("\${notification.live-activity.stale-recovery-millis:60000}")
    private val staleRecoveryMillis: Long = 60_000,
) : SmartLifecycle, LiveActivityDispatchWakeup {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var currentGeneration: ExecutorGeneration? = null
    private var lastGenerationId: Long = 0

    init {
        require(fallbackScanMillis in 5_000..3_600_000) {
            "notification.live-activity.fallback-scan-millis must be between 5s and 1h."
        }
        require(staleRecoveryMillis in 10_000..3_600_000) {
            "notification.live-activity.stale-recovery-millis must be between 10s and 1h."
        }
    }

    @Synchronized
    override fun start() {
        if (!enabled || currentGeneration != null) return
        lastGenerationId = Math.addExact(lastGenerationId, 1)
        val executor = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
            continueExistingPeriodicTasksAfterShutdownPolicy = false
        }
        val generation = ExecutorGeneration(lastGenerationId, executor)
        currentGeneration = generation
        try {
            executor.scheduleWithFixedDelay(
                { requestWake(generation) },
                fallbackScanMillis,
                fallbackScanMillis,
                TimeUnit.MILLISECONDS,
            )
            executor.scheduleWithFixedDelay(
                { recoverStaleSafely(generation) },
                staleRecoveryMillis,
                staleRecoveryMillis,
                TimeUnit.MILLISECONDS,
            )
            // Recover rows committed before this process started without waiting for the fallback.
            requestWake(generation)
        } catch (failure: Exception) {
            currentGeneration = null
            executor.shutdownNow()
            throw failure
        }
    }

    /** Constant-time AFTER_COMMIT boundary: only coalesced executor state is touched here. */
    override fun requestDispatch(availableAt: Instant) {
        val generation = currentGeneration ?: return
        if (availableAt.isAfter(Instant.now(clock))) {
            scheduleRetry(generation, availableAt)
        } else {
            requestWake(generation)
        }
    }

    fun wakeUp() {
        currentGeneration?.let(::requestWake)
    }

    @Synchronized
    override fun stop() {
        val generation = currentGeneration ?: return
        currentGeneration = null
        generation.wakeRequested.set(false)
        synchronized(generation.retryMonitor) {
            generation.retryFuture?.cancel(false)
            generation.retryFuture = null
            generation.retryFutureAt = null
            generation.retryTimes.clear()
        }
        generation.executor.shutdownNow()
        try {
            if (!generation.executor.awaitTermination(STOP_AWAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                log.warn(
                    "Live Activity dispatcher did not terminate within shutdown bound. generation={}",
                    generation.id,
                )
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(
                "Live Activity dispatcher shutdown wait was interrupted. generation={}",
                generation.id,
            )
        } finally {
            generation.wakeTaskScheduled.set(false)
        }
    }

    override fun isRunning(): Boolean = currentGeneration != null

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE - 90

    private fun requestWake(generation: ExecutorGeneration) {
        if (!isCurrent(generation)) return
        generation.wakeRequested.set(true)
        scheduleWakeTask(generation)
    }

    private fun scheduleWakeTask(generation: ExecutorGeneration) {
        if (
            !isCurrent(generation) ||
            !generation.wakeTaskScheduled.compareAndSet(false, true)
        ) {
            return
        }
        try {
            generation.executor.execute {
                try {
                    // Bound one queued task while retaining a trailing pass for concurrent events
                    // and for batches that still contain more immediately-due rows.
                    repeat(MAX_COALESCED_PASSES) {
                        if (!isCurrent(generation)) return@execute
                        generation.wakeRequested.set(false)
                        dispatchSafely(generation)
                        if (!isCurrent(generation) || !generation.wakeRequested.get()) {
                            return@execute
                        }
                    }
                } finally {
                    generation.wakeTaskScheduled.set(false)
                    if (isCurrent(generation) && generation.wakeRequested.get()) {
                        scheduleWakeTask(generation)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            generation.wakeTaskScheduled.set(false)
            if (isCurrent(generation)) {
                log.warn("Live Activity dispatch wake signal rejected during executor transition.")
            }
        }
    }

    private fun dispatchSafely(generation: ExecutorGeneration) {
        if (!isCurrent(generation)) return
        try {
            val result = worker.dispatchDue()
            result.retryTimes.forEach { scheduleRetry(generation, it) }
            if (result.batchExhausted) generation.wakeRequested.set(true)
        } catch (failure: Exception) {
            if (isCurrent(generation)) {
                log.warn(
                    "Live Activity dispatch iteration failed. failureType={}",
                    failure.javaClass.simpleName,
                )
            }
        }
    }

    private fun recoverStaleSafely(generation: ExecutorGeneration) {
        if (!isCurrent(generation)) return
        try {
            val result = worker.recoverStaleDispatches()
            result.retryTimes.forEach { scheduleRetry(generation, it) }
        } catch (failure: Exception) {
            if (isCurrent(generation)) {
                log.warn(
                    "Live Activity stale-dispatch recovery failed. failureType={}",
                    failure.javaClass.simpleName,
                )
            }
        }
    }

    private fun scheduleRetry(generation: ExecutorGeneration, retryAt: Instant) {
        if (!isCurrent(generation)) return
        synchronized(generation.retryMonitor) {
            if (!isCurrent(generation)) return
            generation.retryTimes.add(retryAt)
            scheduleEarliestRetryLocked(generation)
        }
    }

    private fun scheduleEarliestRetryLocked(generation: ExecutorGeneration) {
        val retryAt = generation.retryTimes.firstOrNull() ?: return
        if (generation.retryFutureAt == retryAt && generation.retryFuture?.isDone == false) return
        generation.retryFuture?.cancel(false)
        generation.retryFuture = null
        generation.retryFutureAt = retryAt
        val delay = Duration.between(Instant.now(clock), retryAt)
        val delayNanos = runCatching { delay.toNanos() }
            .getOrDefault(Long.MAX_VALUE)
            .coerceAtLeast(0)
        try {
            generation.retryFuture = generation.executor.schedule(
                { retryTimerFired(generation, retryAt) },
                delayNanos,
                TimeUnit.NANOSECONDS,
            )
        } catch (_: RejectedExecutionException) {
            generation.retryFutureAt = null
            if (isCurrent(generation)) {
                log.warn("Live Activity retry wake signal rejected during executor transition.")
            }
        }
    }

    private fun retryTimerFired(generation: ExecutorGeneration, retryAt: Instant) {
        synchronized(generation.retryMonitor) {
            if (generation.retryFutureAt != retryAt) return
            val now = Instant.now(clock)
            generation.retryTimes.removeIf { !it.isAfter(now) }
            generation.retryFuture = null
            generation.retryFutureAt = null
            scheduleEarliestRetryLocked(generation)
        }
        requestWake(generation)
    }

    private fun isCurrent(generation: ExecutorGeneration): Boolean =
        currentGeneration === generation

    private class ExecutorGeneration(
        val id: Long,
        val executor: ScheduledExecutorService,
        val wakeRequested: AtomicBoolean = AtomicBoolean(false),
        val wakeTaskScheduled: AtomicBoolean = AtomicBoolean(false),
        val retryMonitor: Any = Any(),
        val retryTimes: TreeSet<Instant> = TreeSet(),
        var retryFuture: ScheduledFuture<*>? = null,
        var retryFutureAt: Instant? = null,
    )

    private companion object {
        const val THREAD_NAME = "nolate-live-activity-dispatch"
        const val MAX_COALESCED_PASSES = 2
        const val STOP_AWAIT_MILLIS = 1_000L
    }
}

/** The transaction can return immediately; all DB/provider work starts only after commit. */
@Component
class LiveActivityDispatchWakeupListener(
    private val wakeup: LiveActivityDispatchWakeup,
) {
    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true,
    )
    fun afterCommit(event: LiveActivityDispatchRequested) {
        wakeup.requestDispatch(event.availableAt)
    }
}
