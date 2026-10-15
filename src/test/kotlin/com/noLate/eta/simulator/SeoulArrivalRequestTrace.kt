package com.noLate.eta.simulator

import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Instant

/** Test-only passive observation. Forwards the original request/body/response without reading it. */
internal class SeoulArrivalRequestTrace(private val client: SeoulTransitArrivalClient) : AutoCloseable {
    enum class Phase { DIRECT, WORKER }
    private val original = ReflectionTestUtils.getField(client, "subwayClient") as RestClient
    private var reference: URI? = null // Secret-bearing URI stays in this private process only.
    private var phase = Phase.DIRECT
    private val attempts = mutableListOf<Map<String, Any?>>()
    private var omitted = 0

    init {
        ReflectionTestUtils.setField(client, "subwayClient", original.mutate().requestInterceptor { request, body, execution ->
            check(reference != null || phase == Phase.DIRECT) { "Direct reference request is required" }
            if (reference == null) reference = request.uri
            val baseline = requireNotNull(reference)
            val started = Instant.now()
            val startNanos = System.nanoTime()
            var status: Int? = null
            try {
                execution.execute(request, body).also { status = it.statusCode.value() }
            } finally {
                // Never retain URI, body, headers, exceptions, key hashes or arbitrary labels.
                val safe = linkedMapOf<String, Any?>(
                    "order" to (attempts.size + omitted + 1), "phase" to phase.name,
                    "startedAt" to started.toString(), "finishedAt" to Instant.now().toString(),
                    "durationMillis" to ((System.nanoTime() - startNanos) / 1_000_000), "httpStatus" to status,
                    "exactWireRequestMatchesDirect" to (request.uri == baseline),
                    "credentialMatchesDirect" to (credential(request.uri) == credential(baseline)),
                    "endpointMatchesDirect" to sameEndpoint(request.uri, baseline),
                    "stationMatchesDirect" to (servicePath(request.uri).lastOrNull() == servicePath(baseline).lastOrNull()),
                    "serviceMatchesDirect" to (servicePath(request.uri).firstOrNull() == servicePath(baseline).firstOrNull()),
                    "rangeMatchesDirect" to (servicePath(request.uri).drop(1).take(2) == servicePath(baseline).drop(1).take(2)),
                    "expectedPublicStationAndRange" to (servicePath(request.uri) == listOf("realtimeStationArrival", "0", "40", "노원")),
                )
                if (attempts.size < 20) attempts += safe else omitted++
            }
        }.build())
    }

    fun begin(next: Phase) {
        check(next != Phase.WORKER || reference != null) { "Direct request must precede Worker" }
        phase = next
    }

    fun snapshot(): Map<String, Any> = linkedMapOf(
        "classification" to "PASSIVE_SAME_PROCESS_SEOUL_ARRIVAL_WIRE_TRACE",
        "transport" to "ORIGINAL_REAL_TRANSPORT_UNCHANGED", "attempts" to attempts.toList(),
        "omittedAttempts" to omitted, "rawCredentialsUrlsOrHashesRetained" to false,
    )

    override fun close() {
        ReflectionTestUtils.setField(client, "subwayClient", original)
        reference = null
    }

    private fun servicePath(uri: URI) = uri.path.substringAfter("/json/", "").split('/')
    private fun credential(uri: URI) = uri.rawPath.substringBefore("/json/").substringAfterLast('/')
    private fun sameEndpoint(a: URI, b: URI) = a.scheme == b.scheme && a.rawAuthority == b.rawAuthority &&
        a.rawPath.substringBefore("/json/").substringBeforeLast('/') ==
        b.rawPath.substringBefore("/json/").substringBeforeLast('/') && a.rawQuery == b.rawQuery
}
