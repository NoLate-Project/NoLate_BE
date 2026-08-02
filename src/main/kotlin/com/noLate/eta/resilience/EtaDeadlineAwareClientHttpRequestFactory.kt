package com.noLate.eta.resilience

import com.noLate.global.config.externalHttpRequestFactory
import org.springframework.http.HttpMethod
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import java.net.URI
import java.time.Duration

/**
 * Creates a fresh blocking request factory for every request and caps its connect + read phase
 * budgets by the current whole-calculation deadline.
 *
 * A shared [org.springframework.http.client.SimpleClientHttpRequestFactory] cannot safely have its
 * timeout fields mutated by concurrent ETA calls. Per-request delegates avoid that race and let a
 * second/third provider request observe the budget consumed by earlier requests.
 */
class EtaDeadlineAwareClientHttpRequestFactory(
    private val calculationDeadline: EtaCalculationDeadline,
    private val configuredConnectTimeout: Duration,
    private val configuredReadTimeout: Duration,
    private val delegateFactory: (Duration, Duration) -> ClientHttpRequestFactory =
        ::externalHttpRequestFactory,
) : ClientHttpRequestFactory {
    init {
        requireValidConfiguredTimeout(configuredConnectTimeout, "connect")
        requireValidConfiguredTimeout(configuredReadTimeout, "read")
    }

    override fun createRequest(uri: URI, httpMethod: HttpMethod): ClientHttpRequest {
        val deadline = calculationDeadline.current()
        deadline?.throwIfExpired()
        val (connectTimeout, readTimeout) = deadline
            ?.remaining()
            ?.let(::fitWithinRemainingBudget)
            ?: (configuredConnectTimeout to configuredReadTimeout)

        val request = delegateFactory(connectTimeout, readTimeout)
            .createRequest(uri, httpMethod)
        // Creating the request must not silently consume the remaining calculation budget and
        // leave a request that will only be executed after the quality boundary.
        deadline?.throwIfExpired()
        return request
    }

    internal fun fitWithinRemainingBudget(remaining: Duration): Pair<Duration, Duration> {
        if (remaining < MIN_TOTAL_IO_BUDGET) throw EtaSoftDeadlineExceededException()
        val configuredTotal = configuredConnectTimeout.plus(configuredReadTimeout)
        if (remaining >= configuredTotal) {
            return configuredConnectTimeout to configuredReadTimeout
        }

        val remainingNanos = remaining.toNanos()
        val minimumNanos = MIN_PHASE_TIMEOUT.toNanos()
        val configuredTotalNanos = configuredTotal.toNanos()
        val proportionalConnectNanos = (
            remainingNanos.toDouble() *
                configuredConnectTimeout.toNanos().toDouble() /
                configuredTotalNanos.toDouble()
            ).toLong()
        val connectNanos = proportionalConnectNanos.coerceIn(
            minimumNanos,
            remainingNanos - minimumNanos,
        )
        val readNanos = remainingNanos - connectNanos
        return Duration.ofNanos(connectNanos) to Duration.ofNanos(readNanos)
    }

    private fun requireValidConfiguredTimeout(value: Duration, phase: String) {
        require(value >= MIN_PHASE_TIMEOUT && value <= MAX_CONFIGURED_TIMEOUT) {
            "ETA provider $phase timeout must be between " +
                "$MIN_PHASE_TIMEOUT and $MAX_CONFIGURED_TIMEOUT."
        }
        value.toNanos()
    }

    companion object {
        val MIN_PHASE_TIMEOUT: Duration = Duration.ofMillis(1)
        val MIN_TOTAL_IO_BUDGET: Duration = MIN_PHASE_TIMEOUT.multipliedBy(2)
        val MAX_CONFIGURED_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
