package com.noLate.schedule.infrastructure

import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.client.SimpleClientHttpRequestFactory
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class TmapTrafficClientTest {
    private val fetchedAt = Instant.parse("2026-07-24T03:00:00Z")
    private val clock = Clock.fixed(fetchedAt, ZoneOffset.UTC)

    @Test
    fun `BIKE는 자동차 API를 호출하지 않고 명시적 fallback을 반환한다`() {
        val client = TmapTrafficClient("key", "http://127.0.0.1:1", clock)

        val bike = client.getTravelMinutes(
            request(
                mode = ScheduleTravelMode.BIKE,
                selectedRouteJson = """{"minutes":24}""",
                selectedRouteTravelMinutes = 24,
            )
        )
        assertEquals(24, bike.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, bike.source)
        assertTrue(bike.stale)
        assertNull(bike.fetchedAt)
        assertTrue(bike.failureReason.orEmpty().startsWith("UNSUPPORTED_TRAVEL_MODE:"))
    }

    @Test
    fun `ETC는 FE driving 계약에 따라 자동차 provider endpoint를 명시적으로 사용한다`() {
        var requestedPath = ""
        val server = httpServer { exchange ->
            requestedPath = exchange.requestURI.path
            val body = """{"features":[{"properties":{"totalTime":1200}}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        try {
            val result = TmapTrafficClient("key", server.baseUrl(), clock)
                .getTravelMinutes(request(mode = ScheduleTravelMode.ETC))

            assertEquals("/tmap/routes", requestedPath)
            assertEquals(20, result.travelMinutes)
            assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
            assertFalse(result.stale)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `선택 대중교통 여정은 다른 추천 여정을 LIVE로 위장하지 않고 snapshot을 사용한다`() {
        val client = TmapTrafficClient("key", "http://127.0.0.1:1", clock)

        val result = client.getTravelMinutes(
            request(
                mode = ScheduleTravelMode.TRANSIT,
                selectedRouteJson = """{"minutes":37,"itinerary":{"legs":[{"mode":"BUS"}]}}""",
                selectedRouteTravelMinutes = 37,
                selectedTransitItineraryJson = """{"legs":[{"mode":"BUS"}]}""",
            )
        )

        assertEquals(37, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_ITINERARY_REFRESH_UNSUPPORTED, result.failureReason)
    }

    @Test
    fun `route JSON이 없는 TRANSIT도 다른 추천 여정을 LIVE로 표시하지 않는다`() {
        val client = TmapTrafficClient("key", "http://127.0.0.1:1", clock)

        val result = client.getTravelMinutes(request(mode = ScheduleTravelMode.TRANSIT))

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertTrue(result.stale)
        assertNull(result.fetchedAt)
        assertEquals(TrafficFailureReasons.TRANSIT_ITINERARY_REFRESH_UNSUPPORTED, result.failureReason)
    }

    @Test
    fun `WALK 선택 경로에 searchOption이 없으면 동일 경로 갱신 불가로 네트워크를 호출하지 않는다`() {
        val client = TmapTrafficClient("key", "http://127.0.0.1:1", clock)

        val result = client.getTravelMinutes(
            request(
                mode = ScheduleTravelMode.WALK,
                selectedRouteJson = """{"id":"walk-selected","minutes":21}""",
                selectedRouteTravelMinutes = 21,
            )
        )

        assertEquals(21, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.SELECTED_ROUTE_OPTION_MISSING, result.failureReason)
    }

    @Test
    fun `WALK 선택 경로의 searchOption을 provider 재조회에 유지한다`() {
        val requestCount = AtomicInteger()
        var receivedBody = ""
        val server = httpServer { exchange ->
            requestCount.incrementAndGet()
            receivedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            val body = """{"features":[{"properties":{"totalTime":1250}}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        try {
            val client = TmapTrafficClient("key", server.baseUrl(), clock)
            val result = client.getTravelMinutes(
                request(
                    mode = ScheduleTravelMode.WALK,
                    selectedRouteJson = """{"minutes":25,"providerRouteOption":"4"}""",
                    selectedRouteTravelMinutes = 25,
                    selectedRouteOption = "4",
                )
            )

            assertEquals(TrafficSource.LIVE_PROVIDER, result.source, result.toString())
            assertEquals(21, result.travelMinutes)
            assertEquals(fetchedAt, result.fetchedAt)
            assertFalse(result.stale)
            assertEquals(1, requestCount.get())
            assertTrue(receivedBody.contains("searchOption=4"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `provider timeout은 원문을 노출하지 않는 안정된 실패 사유와 fallback으로 반환한다`() {
        val server = httpServer { exchange ->
            Thread.sleep(250)
            val body = """{"features":[{"properties":{"totalTime":1200}}]}""".toByteArray()
            runCatching {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofMillis(100))
            setReadTimeout(Duration.ofMillis(30))
        }

        try {
            val client = TmapTrafficClient("key", server.baseUrl(), clock, requestFactory)
            val result = client.getTravelMinutes(
                request(
                    mode = ScheduleTravelMode.CAR,
                    selectedRouteJson = """{"minutes":32,"searchOption":"2"}""",
                    selectedRouteTravelMinutes = 32,
                    selectedRouteOption = "2",
                )
            )

            assertEquals(32, result.travelMinutes)
            assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
            assertTrue(result.stale)
            assertNull(result.fetchedAt)
            assertEquals(TrafficFailureReasons.PROVIDER_TIMEOUT, result.failureReason)
            assertFalse(result.failureReason.orEmpty().contains(server.address.port.toString()))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `0초 도로 응답은 LIVE로 승격하지 않고 invalid response fallback으로 내린다`() {
        val server = httpServer { exchange ->
            val body = """{"features":[{"properties":{"totalTime":0}}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        try {
            val result = TmapTrafficClient("key", server.baseUrl(), clock)
                .getTravelMinutes(request(mode = ScheduleTravelMode.CAR))

            assertEquals(30, result.travelMinutes)
            assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
            assertTrue(result.stale)
            assertNull(result.fetchedAt)
            assertEquals(TrafficFailureReasons.PROVIDER_INVALID_RESPONSE, result.failureReason)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `제품 상한을 넘는 도보 응답은 선택 경로 fallback으로 내린다`() {
        val server = httpServer { exchange ->
            val body = """{"features":[{"properties":{"totalTime":7260}}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        try {
            val result = TmapTrafficClient(
                "key",
                server.baseUrl(),
                clock,
                SimpleClientHttpRequestFactory(),
                maxTravelMinutes = 120,
            ).getTravelMinutes(
                request(
                    mode = ScheduleTravelMode.WALK,
                    selectedRouteJson = """{"minutes":25,"searchOption":"4"}""",
                    selectedRouteTravelMinutes = 25,
                    selectedRouteOption = "4",
                )
            )

            assertEquals(25, result.travelMinutes)
            assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
            assertTrue(result.stale)
            assertEquals(TrafficFailureReasons.PROVIDER_INVALID_RESPONSE, result.failureReason)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `도로 도보 대중교통 provider 시간은 유한 양수와 제품 상한을 공통 검증한다`() {
        val modes = listOf(
            ScheduleTravelMode.CAR,
            ScheduleTravelMode.WALK,
            ScheduleTravelMode.TRANSIT,
        )

        modes.forEach { mode ->
            listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalidSeconds ->
                assertThrows(IllegalStateException::class.java) {
                    validatedTmapTravelMinutes(invalidSeconds, 120, mode)
                }
            }
            assertThrows(IllegalStateException::class.java) {
                validatedTmapTravelMinutes(7_260.0, 120, mode)
            }
            assertEquals(120, validatedTmapTravelMinutes(7_200.0, 120, mode))
        }
    }

    private fun request(
        mode: ScheduleTravelMode,
        selectedRouteJson: String? = null,
        selectedRouteTravelMinutes: Int? = null,
        selectedRouteOption: String? = null,
        selectedTransitItineraryJson: String? = null,
    ) = TrafficRequest(
        originLat = 37.1,
        originLng = 127.1,
        destinationLat = 37.2,
        destinationLng = 127.2,
        travelMode = mode,
        fallbackTravelMinutes = 30,
        selectedRouteJson = selectedRouteJson,
        selectedRouteTravelMinutes = selectedRouteTravelMinutes,
        selectedRouteOption = selectedRouteOption,
        selectedTransitItineraryJson = selectedTransitItineraryJson,
    )

    private fun httpServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handler(exchange) }
            start()
        }

    private fun HttpServer.baseUrl(): String = "http://127.0.0.1:${address.port}"
}
