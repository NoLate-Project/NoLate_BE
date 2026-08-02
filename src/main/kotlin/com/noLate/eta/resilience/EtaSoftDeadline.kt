package com.noLate.eta.resilience

import java.time.Duration
import java.util.ArrayDeque

/**
 * Monotonic time source used for elapsed-time budgets.
 *
 * ETA deadlines deliberately do not use wall-clock time: an NTP correction must not make an
 * already-running provider calculation live longer (or expire early).
 */
fun interface EtaMonotonicTicker {
    fun readNanos(): Long

    companion object {
        val SYSTEM: EtaMonotonicTicker = EtaMonotonicTicker(System::nanoTime)
    }
}

class EtaSoftDeadlineExceededException : RuntimeException(
    "ETA calculation exceeded its bounded soft deadline.",
)

/**
 * A cooperative, monotonic deadline. It never interrupts a provider socket by itself; provider
 * connect/read timeouts remain the hard bound. Callers check this deadline between provider steps
 * so a late result cannot start another external call or be published as fresh.
 */
class EtaSoftDeadline internal constructor(
    private val startedAtNanos: Long,
    val budget: Duration,
    private val ticker: EtaMonotonicTicker,
) {
    private val budgetNanos = budget.toNanos()

    fun remaining(): Duration = Duration.ofNanos(remainingNanos())

    fun isExpired(): Boolean = remainingNanos() == 0L

    fun throwIfExpired() {
        if (isExpired()) throw EtaSoftDeadlineExceededException()
    }

    internal fun remainingNanos(): Long {
        // A custom/test ticker can move backwards. Treat that as zero elapsed rather than granting
        // more than the original budget. System.nanoTime wraparound is safe for practical budgets.
        val elapsed = (ticker.readNanos() - startedAtNanos).coerceAtLeast(0L)
        return (budgetNanos - elapsed).coerceAtLeast(0L)
    }
}

/**
 * Thread-confined scope for one complete ETA calculation.
 *
 * Nested scopes inherit the parent's remaining budget and can only shorten it. The current scope
 * is visible to every [EtaProviderGuard] invoked synchronously on the calculation thread.
 */
class EtaCalculationDeadline(
    val defaultBudget: Duration = DEFAULT_BUDGET,
    private val ticker: EtaMonotonicTicker = EtaMonotonicTicker.SYSTEM,
) {
    private val deadlines = ThreadLocal<ArrayDeque<EtaSoftDeadline>>()

    init {
        requireValidBudget(defaultBudget)
    }

    fun current(): EtaSoftDeadline? = deadlines.get()?.peekLast()

    fun <T> within(block: (EtaSoftDeadline) -> T): T = within(defaultBudget, block)

    fun <T> within(
        requestedBudget: Duration,
        block: (EtaSoftDeadline) -> T,
    ): T {
        requireValidBudget(requestedBudget)
        val parentRemaining = current()?.also(EtaSoftDeadline::throwIfExpired)?.remaining()
        val effectiveBudget = parentRemaining
            ?.takeIf { it < requestedBudget }
            ?: requestedBudget
        val deadline = EtaSoftDeadline(
            startedAtNanos = ticker.readNanos(),
            budget = effectiveBudget,
            ticker = ticker,
        )
        val stack = deadlines.get() ?: ArrayDeque<EtaSoftDeadline>().also(deadlines::set)
        stack.addLast(deadline)
        return try {
            deadline.throwIfExpired()
            val result = block(deadline)
            deadline.throwIfExpired()
            result
        } finally {
            check(stack.removeLast() === deadline) {
                "ETA deadline scopes must close in LIFO order."
            }
            if (stack.isEmpty()) deadlines.remove()
        }
    }

    private fun requireValidBudget(value: Duration) {
        require(!value.isZero && !value.isNegative && value <= MAX_BUDGET) {
            "ETA soft deadline must be positive and at most $MAX_BUDGET."
        }
        // Fail during construction rather than during a production request if conversion overflows.
        value.toNanos()
    }

    companion object {
        val DEFAULT_BUDGET: Duration = Duration.ofSeconds(8)
        val MAX_BUDGET: Duration = Duration.ofMinutes(5)
    }
}
