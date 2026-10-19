package com.noLate.transit.infrastructure

import com.noLate.eta.resilience.EtaMonotonicTicker
import com.noLate.eta.resilience.EtaProviderBulkheadRejectedException
import com.noLate.eta.resilience.validateEtaProviderEndpoint
import com.noLate.transit.domain.TransitCityCodeNamespace
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Fixed provider identities used by wire-call telemetry and the process-wide quota guard. */
enum class TransitWireProvider(
    val metricTag: String,
    val guardId: String,
) {
    SEOUL_SUBWAY("seoul_subway", "seoul_subway"),
    SEOUL_BUS("seoul_bus", "seoul_bus"),
    TAGO_BUS("tago_bus", "tago_bus"),
    SEOUL_TIMETABLE("seoul_timetable", "seoul_timetable"),
}

enum class TransitWireOperation(val metricTag: String) {
    ARRIVAL("arrival"),
    POSITION("position"),
    STATION_LOOKUP("station_lookup"),
    TIMETABLE("timetable"),
    VEHICLE_POSITION("vehicle_position"),
    ROUTE_GEOMETRY("route_geometry"),
}

enum class TransitWireOutcome(val metricTag: String) {
    SUCCESS("success"),
    EMPTY("empty"),
    APPLICATION_ERROR("application_error"),
    RATE_LIMITED("rate_limited"),
    LOCAL_RATE_LIMITED("local_rate_limited"),
    ERROR("error"),
}

/**
 * Provider messages and payloads are deliberately excluded: only a bounded provider and code are
 * retained, so circuit failure and metrics never expose credentials or unbounded response text.
 */
class TransitProviderApplicationException(
    val provider: TransitWireProvider,
    rawCode: String?,
) : IllegalStateException(
    "${provider.metricTag} returned an application-level error: " + boundedProviderCode(rawCode),
) {
    val code: String = boundedProviderCode(rawCode)
}

/** Last failure per fixed wire slot, not a request log or evidence of the current request. */
data class TransitProviderWireFailureDiagnostic(
    val provider: TransitWireProvider,
    val operation: TransitWireOperation,
    val outcome: TransitWireOutcome,
    val code: String,
    val observedAt: Instant,
    val failureCount: Long,
)

/**
 * A physical HTTP request can be much more numerous than a logical arrival lookup, especially for
 * TAGO station discovery. These fixed enums keep the metric cardinality independent of user input.
 */
@Component
class TransitProviderWireMetrics(
    registry: MeterRegistry,
) {
    // At most provider-count × operation-count slots. Never key this map by a station,
    // URL, user, exception message or provider-supplied string.
    private val lastFailures = ConcurrentHashMap<Pair<TransitWireProvider, TransitWireOperation>, TransitProviderWireFailureDiagnostic>()
    private val timers = TransitWireProvider.entries.flatMap { provider ->
        TransitWireOperation.entries.flatMap { operation ->
            TransitWireOutcome.entries.map { outcome ->
                WireMetricKey(provider, operation, outcome) to Timer.builder(WIRE_DURATION_METRIC)
                    .description("Physical transit provider HTTP calls by bounded operation and outcome.")
                    .tag("provider", provider.metricTag)
                    .tag("operation", operation.metricTag)
                    .tag("outcome", outcome.metricTag)
                    .serviceLevelObjectives(
                        Duration.ofMillis(250),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(8),
                    )
                    .register(registry)
            }
        }
    }.toMap()

    private val unsupportedMappings = TransitCityCodeNamespace.entries.associateWith { namespace ->
        Counter.builder(UNSUPPORTED_MAPPING_METRIC)
            .description("Transit city-code mappings rejected before a provider call.")
            .tag("namespace", namespace.name.lowercase(Locale.ROOT))
            .register(registry)
    }

    fun <T> observe(
        provider: TransitWireProvider,
        operation: TransitWireOperation,
        isEmpty: (T) -> Boolean,
        call: () -> T,
    ): T {
        val startedAt = System.nanoTime()
        return try {
            val result = call()
            record(
                provider = provider,
                operation = operation,
                outcome = if (isEmpty(result)) TransitWireOutcome.EMPTY else TransitWireOutcome.SUCCESS,
                durationNanos = System.nanoTime() - startedAt,
            )
            result
        } catch (failure: RuntimeException) {
            val outcome = failure.toWireOutcome()
            record(
                provider = provider,
                operation = operation,
                outcome = outcome,
                durationNanos = System.nanoTime() - startedAt,
            )
            // Capture before TransitArrivalService safely converts provider failure to an
            // empty list. Do not change fallback, retry, cache or circuit-breaker policy.
            lastFailures.compute(provider to operation) { _, previous ->
                TransitProviderWireFailureDiagnostic(provider, operation, outcome,
                    failure.diagnosticCode(), Instant.now(), (previous?.failureCount ?: 0) + 1)
            }
            throw failure
        }
    }

    fun failureDiagnostics(): List<TransitProviderWireFailureDiagnostic> =
        TransitWireProvider.entries.flatMap { provider ->
            TransitWireOperation.entries.mapNotNull { operation -> lastFailures[provider to operation] }
        }

    fun recordUnsupportedMapping(namespace: TransitCityCodeNamespace) {
        unsupportedMappings.getValue(namespace).increment()
    }

    private fun record(
        provider: TransitWireProvider,
        operation: TransitWireOperation,
        outcome: TransitWireOutcome,
        durationNanos: Long,
    ) {
        timers.getValue(WireMetricKey(provider, operation, outcome))
            .record(durationNanos.coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }

    private data class WireMetricKey(
        val provider: TransitWireProvider,
        val operation: TransitWireOperation,
        val outcome: TransitWireOutcome,
    )

    private companion object {
        const val WIRE_DURATION_METRIC = "nolate.eta.transit.provider.wire.duration"
        const val UNSUPPORTED_MAPPING_METRIC = "nolate.eta.transit.mapping.unsupported"
    }
}

/**
 * Minimal process-wide quota guard. It bounds burst amplification inside one replica without
 * sleeping past the ETA deadline. Account-wide/multi-replica quotas still require an external
 * distributed budget, but this fail-fast fence prevents one logical TAGO lookup from issuing an
 * unbounded city scan in a single second.
 */
@Component
class TransitProviderWireRateLimiter(
    @Value("\${transit.wire-rate-limit.seoul-subway-per-second:10}")
    seoulSubwayPerSecond: Int = 10,
    @Value("\${transit.wire-rate-limit.seoul-bus-per-second:20}")
    seoulBusPerSecond: Int = 20,
    @Value("\${transit.wire-rate-limit.tago-bus-per-second:20}")
    tagoBusPerSecond: Int = 20,
    private val ticker: EtaMonotonicTicker = EtaMonotonicTicker.SYSTEM,
    @Value("\${transit.wire-rate-limit.seoul-timetable-per-second:20}")
    seoulTimetablePerSecond: Int = 20,
) {
    private val limits = mapOf(
        TransitWireProvider.SEOUL_SUBWAY to validatedLimit(seoulSubwayPerSecond),
        TransitWireProvider.SEOUL_BUS to validatedLimit(seoulBusPerSecond),
        TransitWireProvider.TAGO_BUS to validatedLimit(tagoBusPerSecond),
        TransitWireProvider.SEOUL_TIMETABLE to validatedLimit(seoulTimetablePerSecond),
    )
    private val windows = TransitWireProvider.entries.associateWith {
        FixedWindow(startedAtNanos = ticker.readNanos())
    }

    fun requirePermit(provider: TransitWireProvider) {
        val now = ticker.readNanos()
        val allowed = synchronized(windows.getValue(provider)) {
            val window = windows.getValue(provider)
            if (elapsedNanos(window.startedAtNanos, now) >= WINDOW_NANOS) {
                window.startedAtNanos = now
                window.used = 0
            }
            if (window.used >= limits.getValue(provider)) {
                false
            } else {
                window.used += 1
                true
            }
        }
        if (!allowed) throw EtaProviderBulkheadRejectedException(provider.guardId)
    }

    private data class FixedWindow(
        var startedAtNanos: Long,
        var used: Int = 0,
    )

    private companion object {
        const val WINDOW_NANOS = 1_000_000_000L
        const val MAX_CALLS_PER_SECOND = 10_000

        fun validatedLimit(value: Int): Int {
            require(value in 1..MAX_CALLS_PER_SECOND) {
                "Transit provider wire rate limit must be between 1 and $MAX_CALLS_PER_SECOND."
            }
            return value
        }

        fun elapsedNanos(startedAt: Long, now: Long): Long =
            (now - startedAt).coerceAtLeast(0L)
    }
}

/** Reject malformed endpoints and secret-bearing plaintext remote URLs unless explicitly allowed. */
fun validateTransitProviderEndpoint(
    provider: TransitWireProvider,
    baseUrl: String,
    credentialConfigured: Boolean,
    allowInsecureHttp: Boolean,
) = validateEtaProviderEndpoint(
    providerId = provider.metricTag,
    baseUrl = baseUrl,
    credentialConfigured = credentialConfigured,
    allowInsecureHttp = allowInsecureHttp,
)

private fun Throwable.toWireOutcome(): TransitWireOutcome {
    if (applicationFailure() != null) return TransitWireOutcome.APPLICATION_ERROR
    if (this is EtaProviderBulkheadRejectedException) return TransitWireOutcome.LOCAL_RATE_LIMITED
    val responseFailure = generateSequence(this as Throwable?) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .filterIsInstance<RestClientResponseException>()
        .firstOrNull()
    return if (responseFailure?.statusCode?.value() == 429) {
        TransitWireOutcome.RATE_LIMITED
    } else {
        TransitWireOutcome.ERROR
    }
}

private fun Throwable.applicationFailure(): TransitProviderApplicationException? =
    generateSequence(this as Throwable?) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .filterIsInstance<TransitProviderApplicationException>()
        .firstOrNull()

private fun Throwable.diagnosticCode(): String {
    applicationFailure()?.let { return it.code }
    if (this is EtaProviderBulkheadRejectedException) return "LOCAL_RATE_LIMITED"
    val http = generateSequence(this as Throwable?) { it.cause }
        .take(MAX_CAUSE_DEPTH).filterIsInstance<RestClientResponseException>().firstOrNull()
    return http?.statusCode?.value()?.takeIf { it in 100..599 }?.let { "HTTP_$it" } ?: "UNKNOWN"
}

private fun boundedProviderCode(rawCode: String?): String =
    rawCode
        ?.trim()
        ?.uppercase(Locale.ROOT)
        // Only finite numeric code formats, never arbitrary alphanumeric text or a
        // truncated prefix that might contain credentials echoed by an upstream server.
        ?.takeIf { it.matches(Regex("(?:ERROR|INFO)-[0-9]{3}|[0-9]{1,3}")) }
        ?: "UNKNOWN"

private const val MAX_CAUSE_DEPTH = 16
