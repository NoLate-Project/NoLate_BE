package com.noLate.eta.infrastructure.odsay

import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.global.observability.NoLateOperationalMetrics
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

class OdsayTransitJourneyClientTest {
    private val fetchedAt = Instant.parse("2026-07-29T14:59:30Z")
    private val clock = Clock.fixed(fetchedAt, ZoneOffset.UTC)

    @Test
    fun `서버 키를 percent encode하고 출발 Instant를 한국 SearchTime으로 조회한다`() {
        val requestedMethod = AtomicReference<String>()
        val requestedPath = AtomicReference<String>()
        val rawQuery = AtomicReference<String>()
        val server = httpServer(
            body = """
                {
                  "result": {
                    "paths": {
                      "pathType": 2,
                      "totalTime": 20,
                      "startDateTime": "202607300005",
                      "endDateTime": "202607300025",
                      "rps": {
                        "trafficType": 2,
                        "duration": 20,
                        "waitingTime": 5,
                        "startID": 100,
                        "startName": "서울역버스환승센터",
                        "endID": 200,
                        "endName": "강남역",
                        "lane": {"busID": "12018", "busNo": "402"}
                      }
                    }
                  }
                }
            """.trimIndent(),
            onRequest = { method, path, query ->
                requestedMethod.set(method)
                requestedPath.set(path)
                rawQuery.set(query)
            },
        )

        try {
            val apiKey = "decoded+key/with=="
            val registry = SimpleMeterRegistry()
            val client = OdsayTransitJourneyClient(
                apiKey = apiKey,
                baseUrl = server.baseUrl(),
                mapper = OdsayTransitJourneyMapper(),
                clock = clock,
                operationalMetrics = NoLateOperationalMetrics(registry),
            )

            val journeys = client.search(request())

            assertEquals("GET", requestedMethod.get())
            assertEquals("/maasRP", requestedPath.get())
            val raw = requireNotNull(rawQuery.get())
            assertTrue(raw.contains("%2B", ignoreCase = true), raw)
            assertTrue(raw.contains("%2F", ignoreCase = true), raw)
            assertTrue(raw.contains("%3D", ignoreCase = true), raw)
            assertFalse(raw.contains(apiKey), raw)

            val parameters = decodeQuery(raw)
            assertEquals(apiKey, parameters["apiKey"])
            assertEquals("126.9726", parameters["SX"])
            assertEquals("37.5547", parameters["SY"])
            assertEquals("127.0276", parameters["EX"])
            assertEquals("37.4979", parameters["EY"])
            assertEquals("202607300005", parameters["SearchTime"])
            assertEquals("2", parameters["SearchMethod"])
            assertEquals("0", parameters["lang"])
            assertEquals("json", parameters["output"])

            assertEquals(1, journeys.size)
            assertEquals(fetchedAt, journeys.single().fetchedAt)
            assertEquals(Instant.parse("2026-07-29T15:05:00Z"), journeys.single().requestedDepartureAt)
            assertEquals(
                1L,
                registry.get("nolate.eta.transit.provider.duration")
                    .tag("provider", "odsay_route")
                    .tag("outcome", "success")
                    .timer()
                    .count(),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `HTTP 200 ODsay 오류 envelope를 빈 경로로 숨기지 않고 안전한 예외로 전달한다`() {
        val server = httpServer(
            body = """{"error":[{"code":"-99","message":"검색결과가 없습니다."}]}""",
        )

        try {
            val secret = "must-not-leak+key"
            val registry = SimpleMeterRegistry()
            val client = OdsayTransitJourneyClient(
                apiKey = secret,
                baseUrl = server.baseUrl(),
                mapper = OdsayTransitJourneyMapper(),
                clock = clock,
                operationalMetrics = NoLateOperationalMetrics(registry),
            )

            val error = assertThrows(IllegalStateException::class.java) {
                client.search(request())
            }

            assertEquals("ODsay가 경로 조회 오류를 반환했습니다.", error.message)
            assertFalse(error.message.orEmpty().contains(secret))
            assertFalse(error.message.orEmpty().contains("검색결과가 없습니다"))
            assertEquals(
                1.0,
                registry.get("nolate.eta.transit.provider.events")
                    .tag("provider", "odsay_route")
                    .tag("outcome", "invalid")
                    .counter()
                    .count(),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `빈 서버 키는 네트워크 클라이언트를 만들기 전에 거절한다`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            OdsayTransitJourneyClient(
                apiKey = " ",
                baseUrl = "http://127.0.0.1:1",
                mapper = OdsayTransitJourneyMapper(),
                clock = clock,
            )
        }

        assertTrue(error.message.orEmpty().contains("서버용 API 키"))
    }

    @Test
    fun `ODsay endpoint는 HTTPS host를 강제하고 거절 메시지에 API key와 OD 좌표를 노출하지 않는다`() {
        val secret = "server-api-key-must-not-leak"
        val search = request()
        val sensitiveValues = listOf(
            secret,
            search.originLng.toString(),
            search.originLat.toString(),
            search.destinationLng.toString(),
            search.destinationLat.toString(),
        )
        val invalidBaseUrls = listOf(
            "http://api.odsay.com/v1/api",
            "http://127.attacker.example/v1/api",
            "https://attacker.example/v1/api",
            "https://api.odsay.com.attacker.example/v1/api",
            "https://$secret@api.odsay.com/v1/api",
            "https://api.odsay.com/v1/api?apiKey=$secret" +
                "&SX=${search.originLng}&SY=${search.originLat}" +
                "&EX=${search.destinationLng}&EY=${search.destinationLat}",
            "https:///v1/api#${search.destinationLat}",
        )

        invalidBaseUrls.forEach { invalidBaseUrl ->
            val error = assertThrows(RuntimeException::class.java) {
                OdsayTransitJourneyClient(
                    apiKey = secret,
                    baseUrl = invalidBaseUrl,
                    mapper = OdsayTransitJourneyMapper(),
                    clock = clock,
                )
            }

            val message = error.message.orEmpty()
            sensitiveValues.forEach { sensitive ->
                assertFalse(message.contains(sensitive))
            }
            assertFalse(message.contains(invalidBaseUrl))
        }

        assertDoesNotThrow {
            OdsayTransitJourneyClient(
                apiKey = secret,
                baseUrl = "https://api.odsay.com/v1/api",
                mapper = OdsayTransitJourneyMapper(),
                clock = clock,
            )
        }
        assertDoesNotThrow {
            OdsayTransitJourneyClient(
                apiKey = secret,
                baseUrl = "https://reviewed-egress-proxy.example/v1/api",
                allowCustomEndpoint = true,
                mapper = OdsayTransitJourneyMapper(),
                clock = clock,
            )
        }
    }

    private fun request() = TransitJourneySearchRequest(
        originLat = 37.5547,
        originLng = 126.9726,
        destinationLat = 37.4979,
        destinationLng = 127.0276,
        departureAt = Instant.parse("2026-07-29T15:05:00Z"),
        maxTravelMinutes = 180,
    )

    private fun httpServer(
        body: String,
        onRequest: (method: String, path: String, rawQuery: String?) -> Unit = { _, _, _ -> },
    ): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            onRequest(exchange.requestMethod, exchange.requestURI.path, exchange.requestURI.rawQuery)
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private fun HttpServer.baseUrl(): String = "http://127.0.0.1:${address.port}"

    private fun decodeQuery(rawQuery: String): Map<String, String> =
        rawQuery
            .split("&")
            .associate { pair ->
                val name = pair.substringBefore("=")
                val value = pair.substringAfter("=", "")
                URLDecoder.decode(name, StandardCharsets.UTF_8) to
                    URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
}
