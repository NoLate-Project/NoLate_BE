package com.noLate.transit.infrastructure

import com.noLate.eta.resilience.EtaCalculationDeadline
import com.noLate.eta.resilience.EtaProviderCircuitState
import com.noLate.eta.resilience.EtaProviderGuard
import com.noLate.eta.resilience.EtaProviderResiliencePolicy
import com.noLate.eta.resilience.StaticEtaProviderResiliencePolicyResolver
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TagoTransitArrivalClientTest {
    @Test
    fun `TAGO resultCode 03은 빈 성공으로 다음 도시 후보를 조회하고 circuit을 열지 않는다`() {
        val stationCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/BusSttnInfoInqireService/getSttnNoList") { exchange ->
                stationCalls.incrementAndGet()
                val body = """
                    {"response":{"header":{"resultCode":"03","resultMsg":"NODATA_ERROR"}}}
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val registry = SimpleMeterRegistry()
        val deadline = EtaCalculationDeadline()
        val guard = EtaProviderGuard(
            policyResolver = StaticEtaProviderResiliencePolicyResolver(
                policies = mapOf(
                    "tago_bus" to EtaProviderResiliencePolicy(
                        maxConcurrentCalls = 1,
                        maxQueuedCalls = 0,
                        maxQueueWait = Duration.ZERO,
                        failureThreshold = 1,
                        openDuration = Duration.ofSeconds(30),
                    )
                )
            ),
            calculationDeadline = deadline,
        )
        try {
            val client = TagoTransitArrivalClient(
                commonApiKey = "",
                busApiKey = "key",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                cityCodeCandidatesValue = "25,26",
                calculationDeadline = deadline,
                operationalMetrics = NoLateOperationalMetrics(registry),
                providerGuard = guard,
                wireMetrics = TransitProviderWireMetrics(registry),
            )

            val arrivals = client.getBusArrivals(null, null, null, "대전역", "30", 1)

            assertTrue(arrivals.isEmpty())
            assertEquals(2, stationCalls.get())
            assertEquals(EtaProviderCircuitState.CLOSED, guard.snapshot("tago_bus").circuitState)
            assertEquals(0, guard.snapshot("tago_bus").consecutiveFailures)
            assertEquals(
                2L,
                registry.get("nolate.eta.transit.provider.wire.duration")
                    .tag("provider", "tago_bus")
                    .tag("operation", "station_lookup")
                    .tag("outcome", "empty")
                    .timer()
                    .count(),
            )
            assertEquals(
                1L,
                registry.get("nolate.eta.transit.provider.duration")
                    .tag("provider", "tago_bus")
                    .tag("outcome", "empty")
                    .timer()
                    .count(),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `TAGO HTTP 200 resultCode 오류는 wire application error와 circuit failure가 된다`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList") { exchange ->
                calls.incrementAndGet()
                val body = """
                    {"response":{"header":{"resultCode":"30","resultMsg":"SERVICE KEY IS NOT REGISTERED"}}}
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val registry = SimpleMeterRegistry()
        val deadline = EtaCalculationDeadline()
        val guard = EtaProviderGuard(
            policyResolver = StaticEtaProviderResiliencePolicyResolver(
                policies = mapOf(
                    "tago_bus" to EtaProviderResiliencePolicy(
                        maxConcurrentCalls = 1,
                        maxQueuedCalls = 0,
                        maxQueueWait = Duration.ZERO,
                        failureThreshold = 1,
                        openDuration = Duration.ofSeconds(30),
                    )
                )
            ),
            calculationDeadline = deadline,
        )
        try {
            val client = TagoTransitArrivalClient(
                commonApiKey = "",
                busApiKey = "key",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                cityCodeCandidatesValue = "25",
                calculationDeadline = deadline,
                operationalMetrics = NoLateOperationalMetrics(registry),
                providerGuard = guard,
                wireMetrics = TransitProviderWireMetrics(registry),
            )

            assertThrows(TransitProviderApplicationException::class.java) {
                client.getBusArrivals(null, "DJB8001412", "25", "대전역", "30", 1)
            }

            assertEquals(1, calls.get())
            assertEquals(EtaProviderCircuitState.OPEN, guard.snapshot("tago_bus").circuitState)
            assertEquals(
                1L,
                registry.get("nolate.eta.transit.provider.wire.duration")
                    .tag("provider", "tago_bus")
                    .tag("operation", "arrival")
                    .tag("outcome", "application_error")
                    .timer()
                    .count(),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `TAGO 정상 빈 station lookup은 negative cache로 고정되지 않는다`() {
        val stationCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/BusSttnInfoInqireService/getSttnNoList") { exchange ->
                val attempt = stationCalls.incrementAndGet()
                val item = if (attempt == 1) {
                    "{}"
                } else {
                    "{\"item\":{\"nodeid\":\"DJB8001412\",\"nodenm\":\"대전역\",\"nodeno\":\"12345\"}}"
                }
                val body = """
                    {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
                    "body":{"items":$item}}}
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList") { exchange ->
                val body = """
                    {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
                    "body":{"items":{"item":{"routeno":"30","nodenm":"대전역","arrtime":120}}}}}
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val client = TagoTransitArrivalClient(
                commonApiKey = "",
                busApiKey = "key",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                cityCodeCandidatesValue = "25",
            )

            assertTrue(client.getBusArrivals(null, null, "25", "대전역", "30", 1).isEmpty())
            assertEquals(1, client.getBusArrivals(null, null, "25", "대전역", "30", 1).size)
            assertEquals(2, stationCalls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `공공데이터포털 decoding key의 plus 문자를 percent encode한다`() {
        val rawQuery = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList") { exchange ->
                rawQuery.set(exchange.requestURI.rawQuery)
                val body = """
                    {
                      "response": {
                        "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
                        "body": {
                          "items": {
                            "item": [{
                              "routeno": "30",
                              "nodenm": "대전역/역전시장",
                              "arrtime": 240,
                              "arrprevstationcnt": 4
                            }]
                          }
                        }
                      }
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        try {
            val apiKey = "decoded+key/with=="
            val registry = SimpleMeterRegistry()
            val client = TagoTransitArrivalClient(
                commonApiKey = "",
                busApiKey = apiKey,
                baseUrl = "http://127.0.0.1:${server.address.port}",
                cityCodeCandidatesValue = "",
                operationalMetrics = NoLateOperationalMetrics(registry),
            )

            val arrivals = client.getBusArrivals(
                arsId = null,
                nodeId = "DJB8001412",
                cityCode = "25",
                stationName = "대전역/역전시장",
                routeName = "30",
                limit = 3,
            )

            val encodedKey = requireNotNull(rawQuery.get())
                .substringAfter("serviceKey=")
                .substringBefore("&")
            assertTrue(encodedKey.contains("%2B", ignoreCase = true))
            assertEquals(apiKey, URLDecoder.decode(encodedKey, StandardCharsets.UTF_8))
            assertEquals(1, arrivals.size)
            assertEquals("30", arrivals.single().routeName)
            assertEquals(null, arrivals.single().sourceUpdatedAt)
            assertEquals(
                TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY,
                arrivals.single().freshnessEvidence,
            )
            assertEquals(
                1L,
                registry.get("nolate.eta.transit.provider.duration")
                    .tag("provider", "tago_bus")
                    .tag("outcome", "success")
                    .timer()
                    .count(),
            )
        } finally {
            server.stop(0)
        }
    }
}
