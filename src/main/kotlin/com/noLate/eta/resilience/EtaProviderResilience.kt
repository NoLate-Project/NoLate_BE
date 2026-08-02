package com.noLate.eta.resilience

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

data class EtaProviderResiliencePolicy(
    val maxConcurrentCalls: Int,
    val maxQueuedCalls: Int,
    val maxQueueWait: Duration,
    val failureThreshold: Int,
    val openDuration: Duration,
) {
    init {
        require(maxConcurrentCalls in 1..MAX_CONCURRENT_CALLS) {
            "ETA provider maxConcurrentCalls must be between 1 and $MAX_CONCURRENT_CALLS."
        }
        require(maxQueuedCalls in 0..MAX_QUEUED_CALLS) {
            "ETA provider maxQueuedCalls must be between 0 and $MAX_QUEUED_CALLS."
        }
        require(!maxQueueWait.isNegative && maxQueueWait <= MAX_QUEUE_WAIT) {
            "ETA provider maxQueueWait must be between zero and $MAX_QUEUE_WAIT."
        }
        require(failureThreshold in 1..MAX_FAILURE_THRESHOLD) {
            "ETA provider failureThreshold must be between 1 and $MAX_FAILURE_THRESHOLD."
        }
        require(
            !openDuration.isZero &&
                !openDuration.isNegative &&
                openDuration <= MAX_OPEN_DURATION
        ) {
            "ETA provider openDuration must be positive and at most $MAX_OPEN_DURATION."
        }
        maxQueueWait.toNanos()
        openDuration.toNanos()
    }

    companion object {
        const val MAX_CONCURRENT_CALLS = 64
        const val MAX_QUEUED_CALLS = 256
        const val MAX_FAILURE_THRESHOLD = 100
        val MAX_QUEUE_WAIT: Duration = Duration.ofSeconds(5)
        val MAX_OPEN_DURATION: Duration = Duration.ofMinutes(10)

        val DEFAULT = EtaProviderResiliencePolicy(
            maxConcurrentCalls = 8,
            maxQueuedCalls = 16,
            maxQueueWait = Duration.ofMillis(100),
            failureThreshold = 5,
            openDuration = Duration.ofSeconds(30),
        )
    }
}

fun interface EtaProviderResiliencePolicyResolver {
    fun resolve(providerId: String): EtaProviderResiliencePolicy
}

class StaticEtaProviderResiliencePolicyResolver(
    private val defaultPolicy: EtaProviderResiliencePolicy = EtaProviderResiliencePolicy.DEFAULT,
    policies: Map<String, EtaProviderResiliencePolicy> = emptyMap(),
) : EtaProviderResiliencePolicyResolver {
    private val policies = policies.mapKeys { canonicalEtaProviderId(it.key) }

    override fun resolve(providerId: String): EtaProviderResiliencePolicy =
        policies[canonicalEtaProviderId(providerId)] ?: defaultPolicy
}

enum class EtaProviderCircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN,
}

enum class EtaProviderGuardOutcome {
    SUCCESS,
    PROVIDER_FAILURE,
    BULKHEAD_REJECTED,
    CIRCUIT_OPEN,
    DEADLINE_EXCEEDED,
    INTERRUPTED,
}

data class EtaProviderGuardEvent(
    val providerId: String,
    val outcome: EtaProviderGuardOutcome,
    val elapsedNanos: Long,
)

fun interface EtaProviderGuardObserver {
    fun record(event: EtaProviderGuardEvent)
}

data class EtaProviderResilienceSnapshot(
    val providerId: String,
    val circuitState: EtaProviderCircuitState,
    val consecutiveFailures: Int,
    val openUntil: Instant?,
    val activeCalls: Int,
    val queuedCalls: Int,
)

sealed class EtaProviderResilienceException(message: String) : RuntimeException(message)

class EtaProviderBulkheadRejectedException(providerId: String) :
    EtaProviderResilienceException("ETA provider bulkhead rejected a call: $providerId")

class EtaProviderCircuitOpenException(providerId: String) :
    EtaProviderResilienceException("ETA provider circuit is open: $providerId")

class EtaProviderCallInterruptedException(providerId: String) :
    EtaProviderResilienceException("ETA provider call was interrupted: $providerId")

/**
 * Provider-isolated synchronous resilience guard.
 *
 * Each provider receives its own fair concurrency semaphore, bounded waiting slots, and circuit.
 * Queue waits are capped by both policy and the current whole-calculation deadline. The guard does
 * not create background work, so a timed-out caller cannot leave an untracked provider task behind.
 */
class EtaProviderGuard(
    private val policyResolver: EtaProviderResiliencePolicyResolver,
    private val calculationDeadline: EtaCalculationDeadline,
    private val clock: Clock = Clock.systemUTC(),
    private val ticker: EtaMonotonicTicker = EtaMonotonicTicker.SYSTEM,
    private val observers: List<EtaProviderGuardObserver> = emptyList(),
) {
    private val providers = ConcurrentHashMap<String, ProviderState>()

    fun <T> execute(
        providerId: String,
        operation: () -> T,
    ): T {
        val canonicalProviderId = canonicalEtaProviderId(providerId)
        val startedAtNanos = ticker.readNanos()
        val state = providers.computeIfAbsent(canonicalProviderId) {
            ProviderState(policyResolver.resolve(canonicalProviderId))
        }
        var bulkheadAcquired = false
        var circuitPermit: CircuitPermit? = null
        var circuitCompletionRecorded = false

        try {
            currentDeadline().throwIfExpiredWhenPresent()
            state.rejectIfCircuitOpen(clock.instant(), canonicalProviderId)
            acquireBulkhead(state, canonicalProviderId)
            bulkheadAcquired = true
            circuitPermit = state.acquireCircuitPermit(clock.instant(), canonicalProviderId)
            currentDeadline().throwIfExpiredWhenPresent()

            val result = operation()
            state.recordSuccess(circuitPermit)
            circuitCompletionRecorded = true

            // The provider may have returned after the cooperative budget. Its circuit is healthy,
            // but this late value must not be used and no following provider step may start.
            currentDeadline().throwIfExpiredWhenPresent()
            observe(canonicalProviderId, EtaProviderGuardOutcome.SUCCESS, startedAtNanos)
            return result
        } catch (failure: Throwable) {
            if (circuitPermit != null && !circuitCompletionRecorded) {
                if (failure.countsAsProviderFailure()) {
                    state.recordFailure(circuitPermit, clock.instant())
                } else {
                    // Cancellation/deadline/bulkhead failures do not prove provider health. A
                    // half-open probe must still be released or the circuit would stay stuck.
                    state.abandon(circuitPermit, clock.instant())
                }
            }
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            observe(canonicalProviderId, failure.guardOutcome(), startedAtNanos)
            throw failure
        } finally {
            if (bulkheadAcquired) state.releaseBulkhead()
        }
    }

    fun snapshot(providerId: String): EtaProviderResilienceSnapshot {
        val canonicalProviderId = canonicalEtaProviderId(providerId)
        val state = providers[canonicalProviderId]
            ?: return EtaProviderResilienceSnapshot(
                providerId = canonicalProviderId,
                circuitState = EtaProviderCircuitState.CLOSED,
                consecutiveFailures = 0,
                openUntil = null,
                activeCalls = 0,
                queuedCalls = 0,
            )
        return state.snapshot(canonicalProviderId)
    }

    private fun acquireBulkhead(state: ProviderState, providerId: String) {
        val acquiredImmediately = try {
            state.tryAcquireImmediately()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw EtaProviderCallInterruptedException(providerId)
        }
        if (acquiredImmediately) return
        if (!state.tryAcquireQueueSlot()) throw EtaProviderBulkheadRejectedException(providerId)

        try {
            val deadline = currentDeadline()
            deadline.throwIfExpiredWhenPresent()
            val waitNanos = minOf(
                state.policy.maxQueueWait.toNanos(),
                deadline?.remainingNanos() ?: Long.MAX_VALUE,
            )
            if (waitNanos <= 0L) {
                deadline.throwIfExpiredWhenPresent()
                throw EtaProviderBulkheadRejectedException(providerId)
            }
            val acquired = try {
                state.tryAcquireWithin(waitNanos)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw EtaProviderCallInterruptedException(providerId)
            }
            if (!acquired) {
                deadline.throwIfExpiredWhenPresent()
                throw EtaProviderBulkheadRejectedException(providerId)
            }
            try {
                deadline.throwIfExpiredWhenPresent()
            } catch (failure: Throwable) {
                state.releaseBulkhead()
                throw failure
            }
        } finally {
            state.releaseQueueSlot()
        }
    }

    private fun currentDeadline(): EtaSoftDeadline? = calculationDeadline.current()

    private fun observe(
        providerId: String,
        outcome: EtaProviderGuardOutcome,
        startedAtNanos: Long,
    ) {
        if (observers.isEmpty()) return
        val event = EtaProviderGuardEvent(
            providerId = providerId,
            outcome = outcome,
            elapsedNanos = (ticker.readNanos() - startedAtNanos).coerceAtLeast(0L),
        )
        observers.forEach { observer ->
            try {
                observer.record(event)
            } catch (_: RuntimeException) {
                // Telemetry must never change a provider result or circuit transition.
            }
        }
    }

    private class ProviderState(
        val policy: EtaProviderResiliencePolicy,
    ) {
        private val concurrency = Semaphore(policy.maxConcurrentCalls, true)
        private val queueSlots = Semaphore(policy.maxQueuedCalls, true)
        private val circuitLock = Any()

        private var circuitState = EtaProviderCircuitState.CLOSED
        private var consecutiveFailures = 0
        private var openUntil: Instant? = null
        private var generation = 0L
        private var halfOpenProbeInFlight = false

        @Throws(InterruptedException::class)
        fun tryAcquireImmediately(): Boolean =
            // Unlike untimed tryAcquire(), the zero-time timed form respects fair semaphore order.
            concurrency.tryAcquire(0L, TimeUnit.NANOSECONDS)

        fun tryAcquireQueueSlot(): Boolean = queueSlots.tryAcquire()

        @Throws(InterruptedException::class)
        fun tryAcquireWithin(waitNanos: Long): Boolean =
            concurrency.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)

        fun releaseBulkhead() = concurrency.release()

        fun releaseQueueSlot() = queueSlots.release()

        fun rejectIfCircuitOpen(now: Instant, providerId: String) = synchronized(circuitLock) {
            if (
                circuitState == EtaProviderCircuitState.OPEN &&
                now.isBefore(requireNotNull(openUntil))
            ) {
                throw EtaProviderCircuitOpenException(providerId)
            }
            if (circuitState == EtaProviderCircuitState.HALF_OPEN && halfOpenProbeInFlight) {
                throw EtaProviderCircuitOpenException(providerId)
            }
        }

        fun acquireCircuitPermit(now: Instant, providerId: String): CircuitPermit =
            synchronized(circuitLock) {
                when (circuitState) {
                    EtaProviderCircuitState.CLOSED -> CircuitPermit(generation, halfOpen = false)
                    EtaProviderCircuitState.OPEN -> {
                        if (now.isBefore(requireNotNull(openUntil))) {
                            throw EtaProviderCircuitOpenException(providerId)
                        }
                        circuitState = EtaProviderCircuitState.HALF_OPEN
                        halfOpenProbeInFlight = true
                        CircuitPermit(generation, halfOpen = true)
                    }
                    EtaProviderCircuitState.HALF_OPEN ->
                        throw EtaProviderCircuitOpenException(providerId)
                }
            }

        fun recordSuccess(permit: CircuitPermit) = synchronized(circuitLock) {
            if (permit.generation != generation) return@synchronized
            if (permit.halfOpen) {
                if (circuitState != EtaProviderCircuitState.HALF_OPEN) return@synchronized
                circuitState = EtaProviderCircuitState.CLOSED
                halfOpenProbeInFlight = false
                openUntil = null
                consecutiveFailures = 0
            } else if (circuitState == EtaProviderCircuitState.CLOSED) {
                consecutiveFailures = 0
            }
        }

        fun recordFailure(permit: CircuitPermit, now: Instant) = synchronized(circuitLock) {
            if (permit.generation != generation) return@synchronized
            if (permit.halfOpen) {
                if (circuitState == EtaProviderCircuitState.HALF_OPEN) open(now)
                return@synchronized
            }
            if (circuitState != EtaProviderCircuitState.CLOSED) return@synchronized
            consecutiveFailures += 1
            if (consecutiveFailures >= policy.failureThreshold) open(now)
        }

        fun abandon(permit: CircuitPermit, now: Instant) = synchronized(circuitLock) {
            if (
                permit.generation == generation &&
                permit.halfOpen &&
                circuitState == EtaProviderCircuitState.HALF_OPEN
            ) {
                circuitState = EtaProviderCircuitState.OPEN
                halfOpenProbeInFlight = false
                // This was not a provider failure. Permit an immediate replacement probe without
                // pretending the circuit recovered or extending its failure-open duration.
                openUntil = now
            }
        }

        fun snapshot(providerId: String): EtaProviderResilienceSnapshot =
            synchronized(circuitLock) {
                EtaProviderResilienceSnapshot(
                    providerId = providerId,
                    circuitState = circuitState,
                    consecutiveFailures = consecutiveFailures,
                    openUntil = openUntil,
                    activeCalls = policy.maxConcurrentCalls - concurrency.availablePermits(),
                    queuedCalls = policy.maxQueuedCalls - queueSlots.availablePermits(),
                )
            }

        private fun open(now: Instant) {
            circuitState = EtaProviderCircuitState.OPEN
            consecutiveFailures = policy.failureThreshold
            openUntil = runCatching { now.plus(policy.openDuration) }.getOrDefault(Instant.MAX)
            halfOpenProbeInFlight = false
            generation += 1
        }
    }
}

private data class CircuitPermit(
    val generation: Long,
    val halfOpen: Boolean,
)

private fun EtaSoftDeadline?.throwIfExpiredWhenPresent() {
    this?.throwIfExpired()
}

private fun Throwable.countsAsProviderFailure(): Boolean =
    this !is EtaProviderResilienceException &&
        this !is EtaSoftDeadlineExceededException &&
        this !is InterruptedException &&
        this !is CancellationException

private fun Throwable.guardOutcome(): EtaProviderGuardOutcome = when (this) {
    is EtaProviderBulkheadRejectedException -> EtaProviderGuardOutcome.BULKHEAD_REJECTED
    is EtaProviderCircuitOpenException -> EtaProviderGuardOutcome.CIRCUIT_OPEN
    is EtaSoftDeadlineExceededException -> EtaProviderGuardOutcome.DEADLINE_EXCEEDED
    is EtaProviderCallInterruptedException,
    is InterruptedException -> EtaProviderGuardOutcome.INTERRUPTED
    else -> EtaProviderGuardOutcome.PROVIDER_FAILURE
}

internal fun canonicalEtaProviderId(providerId: String): String {
    val canonical = providerId.trim().lowercase(Locale.ROOT)
    require(canonical.matches(Regex("[a-z0-9][a-z0-9_-]{0,31}"))) {
        "ETA provider id must be a bounded lowercase identifier."
    }
    return canonical
}
