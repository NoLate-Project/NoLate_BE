package com.noLate.transit.qa

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.transit.domain.SeoulBusVehicleObservationBatch
import com.noLate.transit.domain.SeoulBusVehicleObservationStatus
import com.noLate.transit.domain.SeoulBusVehicleQuery
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.transit.infrastructure.SeoulBusVehicleTransportException
import com.noLate.transit.infrastructure.TransitProviderApplicationException
import org.springframework.web.client.RestClientResponseException

/** No application context/config files: the operator injects a key into this process only. */
internal fun seoulBusVehicleReadiness(
    enabled: Boolean,
    key: String?,
    allowInsecureHttp: Boolean = false,
    fetch: (String, SeoulBusVehicleQuery) -> SeoulBusVehicleObservationBatch,
): Map<String, Any> {
    val base = mapOf<String, Any>(
        "probe" to "SEOUL_7016_VEHICLE_CONTRACT", "navigationReady" to false,
        "passengerVehicleAssociation" to false, "stopOrderCrosswalkVerified" to false,
        "geographicCoordinatesVerified" to false,
        "transport" to if (allowInsecureHttp) "HTTP_EXPLICIT_TEST" else "HTTPS",
    )
    if (!enabled) return base + mapOf("status" to "EXPLICIT_OPT_IN_REQUIRED", "networkAttempted" to false)
    if (key.isNullOrBlank()) return base + mapOf("status" to "CREDENTIAL_UNAVAILABLE", "networkAttempted" to false)
    return try {
        val batch = fetch(key, SeoulBusVehicleQuery("100100447", 44, 48))
        base + mapOf(
            "status" to batch.status.name, "networkAttempted" to true,
            "usableObservations" to batch.observations.size, "rejectedObservations" to batch.rejectedCount,
            "rejectionCounts" to batch.rejectionCounts.mapKeys { it.key.name },
            "sourceAgeSummary" to (batch.sourceAgeSummary?.let {
                mapOf("count" to it.count, "minAgeMillis" to it.minAgeMillis, "maxAgeMillis" to it.maxAgeMillis)
            } ?: emptyMap<String, Any>()),
            "freshSourceObservationAvailable" to (batch.status == SeoulBusVehicleObservationStatus.AVAILABLE),
        )
    } catch (failure: Exception) {
        // Even an untrusted failure message/cause may contain a service key or raw response.
        val safeFailure = when (failure) {
            is SeoulBusVehicleTransportException -> mapOf("failureCategory" to "TRANSPORT_UNAVAILABLE")
            is TransitProviderApplicationException -> mapOf(
                "failureCategory" to "PROVIDER_APPLICATION_REJECTED",
                "providerCode" to failure.code.takeIf { it.matches(Regex("[0-9]{1,2}")) }.orEmpty().ifEmpty { "UNKNOWN" },
            )
            is RestClientResponseException -> mapOf("failureCategory" to "HTTP_REJECTED", "httpStatus" to failure.statusCode.value())
            is IllegalArgumentException -> mapOf("failureCategory" to "INVALID_RESPONSE")
            else -> mapOf("failureCategory" to "UNCLASSIFIED")
        }
        base + mapOf("status" to "PROVIDER_CHECK_FAILED", "networkAttempted" to true) + safeFailure
    }
}

/** Fixed, bounded sampling, not an engine polling facility or a retry policy. */
internal fun sampleSeoulBusVehicles(
    enabled: Boolean,
    key: String?,
    allowInsecureHttp: Boolean,
    monotonicNanos: () -> Long = System::nanoTime,
    sleepMillis: (Long) -> Unit = Thread::sleep,
    emit: (Map<String, Any>) -> Unit,
    fetch: (String, SeoulBusVehicleQuery) -> SeoulBusVehicleObservationBatch,
) {
    val started = monotonicNanos()
    var previousStart: Long? = null
    repeat(6) { index ->
        previousStart?.let { previous ->
            while (true) {
                val remaining = 10_000_000_000L - (monotonicNanos() - previous)
                if (remaining <= 0) break
                sleepMillis((remaining + 999_999L) / 1_000_000L)
            }
        }
        val callStarted = monotonicNanos()
        if (callStarted - started >= 60_000_000_000L) return
        previousStart = callStarted
        val result = seoulBusVehicleReadiness(enabled, key, allowInsecureHttp, fetch)
        emit(result + mapOf(
            "sampleIndex" to index + 1,
            "elapsedMillis" to (callStarted - started) / 1_000_000L,
            "requestDurationMillis" to (monotonicNanos() - callStarted) / 1_000_000L,
        ))
        // Any failed sample ends the run; no retry even with remaining call budget.
        if (result["networkAttempted"] != true || result["status"] == "PROVIDER_CHECK_FAILED") return
    }
}

fun main() {
    // Test-source-only, process-local opt-in. Never changes production/Spring configuration.
    val allowInsecureHttp = System.getenv("NOLATE_JOURNEY_SEOUL_BUS_HTTP_TEST") == "1"
    val enabled = System.getenv("NOLATE_JOURNEY_SEOUL_BUS_PROBE") == "1"
    val key = System.getenv("SEOUL_BUS_API_KEY")
    val client = SeoulTransitArrivalClient(
        commonApiKey = "", subwayApiKey = "", busApiKey = key.orEmpty(),
        subwayBaseUrl = "https://swopenAPI.seoul.go.kr/api/subway",
        busBaseUrl = if (allowInsecureHttp) "http://ws.bus.go.kr/api/rest" else "https://ws.bus.go.kr/api/rest",
        allowInsecureHttp = allowInsecureHttp,
    )
    val fetch: (String, SeoulBusVehicleQuery) -> SeoulBusVehicleObservationBatch = { _, query ->
        client.getBusVehicleObservations(query)
    }
    if (System.getenv("NOLATE_JOURNEY_SEOUL_BUS_SAMPLE") == "1") {
        var freshObserved = false
        var providerFailed = false
        sampleSeoulBusVehicles(enabled, key, allowInsecureHttp,
            emit = {
                freshObserved = freshObserved || it["freshSourceObservationAvailable"] == true
                providerFailed = providerFailed || it["status"] == "PROVIDER_CHECK_FAILED"
                println(jacksonObjectMapper().writeValueAsString(it))
            }, fetch = fetch)
        if (!freshObserved || providerFailed) kotlin.system.exitProcess(2)
        return
    }
    val result = seoulBusVehicleReadiness(enabled, key, allowInsecureHttp, fetch)
    println(jacksonObjectMapper().writeValueAsString(result))
    // Successful transport is not route/boarding certification. Shell callers must read the gate.
    if (result["freshSourceObservationAvailable"] != true) kotlin.system.exitProcess(2)
}
