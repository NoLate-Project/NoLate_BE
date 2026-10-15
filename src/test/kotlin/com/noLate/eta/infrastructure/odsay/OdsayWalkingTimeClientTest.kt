package com.noLate.eta.infrastructure.odsay

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.domain.*
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.*
import java.util.concurrent.atomic.AtomicInteger

class OdsayWalkingTimeClientTest {
    private val json = jacksonObjectMapper()
    private val now = Instant.parse("2026-10-13T00:00:00Z")
    private fun response() = javaClass.getResourceAsStream("/eta/odsay-walking-v2-20261013.json")!!.use { json.readTree(it) }
    private fun geometry(): TransitWalkingGeometry {
        val raw = javaClass.getResourceAsStream("/eta/odsay-maas-walking-20261012.json")!!.use { json.readTree(it) }
        return requireNotNull(OdsayWalkingDecoder.decode(raw.path("walks")[1], 3).geometry)
    }
    private fun client() = OdsayWalkingTimeClient("test-key", "http://127.0.0.1:1", clock = Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `기록된 동일 전체 경로의 157초만 채택하고 승강장 포함은 확정하지 않는다`() {
        val geometry = geometry()
        assertNotNull(geometry.providerQueryEndpoints)
        assertEquals(157L, client().verifiedSeconds(response(), geometry, 3))
        assertNull(client().verifiedSeconds(response(), geometry, 2))
        assertEquals(TransitStationInteriorCoverage.UNKNOWN, geometry.pathEvidence?.stationInteriorCoverage)
    }

    @ParameterizedTest
    @ValueSource(strings = ["start", "end", "distance", "duration", "coordinates", "order", "missing", "error", "multiple", "hasPath"])
    fun `끝점 링크 순서 거리 누락 오류가 있으면 전체 초를 채택하지 않는다`(fault: String) {
        val response = response()
        val path = response.path("result").path("path")[0] as ObjectNode
        val recommend = path.path("recommend") as ObjectNode
        val summary = recommend.path("summary") as ObjectNode
        when (fault) {
            "start", "end" -> (summary.path(fault) as ObjectNode).put("x", 126.0)
            "distance" -> summary.put("distance", 169)
            "duration" -> summary.put("duration", 120)
            "coordinates" -> (recommend.path("routes")[0].path("coordinate")[0] as ObjectNode).put("x", 126.0)
            "order" -> (recommend.path("routes") as com.fasterxml.jackson.databind.node.ArrayNode).let { a ->
                val first = a[0]; a.set(0, a[1]); a.set(1, first)
            }
            "missing" -> recommend.remove("summary")
            "error" -> (response as ObjectNode).putObject("error").put("code", "-100")
            "multiple" -> (response.path("result").path("path") as com.fasterxml.jackson.databind.node.ArrayNode).add(path.deepCopy())
            "hasPath" -> path.put("hasPathResult", false)
        }
        assertNull(client().verifiedSeconds(response, geometry(), 3))
        val expected = when (fault) {
            "start", "end" -> "ENDPOINT_MISMATCH"
            "distance" -> "DISTANCE_MISMATCH"
            "duration" -> "DURATION_MISMATCH"
            "coordinates" -> "LINK_COORDINATES_MISMATCH"
            "order" -> "LINK_DISTANCE_MISMATCH"
            "missing" -> "INVALID_RESPONSE"
            "error" -> "PROVIDER_APPLICATION_ERROR"
            "multiple" -> "PATH_COUNT_MISMATCH"
            else -> "NO_PATH"
        }
        val server = server { response.toString() }
        try {
            val client = OdsayWalkingTimeClient("private-key", "http://127.0.0.1:${server.address.port}")
            assertNull(client.refine(geometry(), 3))
            assertEquals(mapOf(expected to 1L), client.diagnostics()["failureCounts"])
            assertFalse(client.diagnostics().toString().contains("private-key"))
        } finally { server.stop(0) }
    }

    @Test
    fun `성공은 동일 경로 캐시를 재사용하고 원래 취득시각을 보존하며 만료 뒤 다시 읽는다`() {
        val calls = AtomicInteger()
        val clock = MutableClock(now)
        val key = "encoded+key/=="
        val server = server { exchange ->
            calls.incrementAndGet()
            val query = exchange.requestURI.rawQuery.split('&').associate {
                val pair = it.split('=', limit = 2)
                pair[0] to URLDecoder.decode(pair[1], Charsets.UTF_8)
            }
            assertEquals(key, query["apiKey"])
            assertEquals("reco", query["opt"])
            assertEquals("/searchWalkPathV2", exchange.requestURI.path)
            response().toString()
        }
        try {
            val client = OdsayWalkingTimeClient(key, "http://127.0.0.1:${server.address.port}", clock = clock)
            val first = client.refine(geometry(), 3)!!
            clock.value = now.plusSeconds(60)
            assertEquals(first, client.refine(geometry(), 3))
            assertEquals(now, first.fetchedAt)
            assertEquals(1, calls.get())
            clock.value = now.plusSeconds(86400)
            assertEquals(clock.value, client.refine(geometry(), 3)?.fetchedAt)
            assertEquals(2, calls.get())
        } finally { server.stop(0) }
    }

    @Test
    fun `한도 인증 및 잘못된 응답은 반복 호출하지 않고 오래된 성공도 재사용하지 않는다`() {
        val calls = AtomicInteger()
        val clock = MutableClock(now)
        val server = server {
            if (calls.incrementAndGet() == 1) response().toString() else """{"error":{"code":"-100","message":"raw-secret"}}"""
        }
        try {
            val client = OdsayWalkingTimeClient("private-key", "http://127.0.0.1:${server.address.port}", clock = clock)
            assertNotNull(client.refine(geometry(), 3))
            clock.value = now.plusSeconds(86400)
            assertNull(client.refine(geometry(), 3))
            repeat(3) { assertNull(client.refine(geometry(), 3)) }
            assertEquals(2, calls.get())
        } finally { server.stop(0) }
    }

    @Test
    fun `설정을 끄거나 끝점이 없으면 HTTP 없이 기존 계산을 유지한다`() {
        assertNull(OdsayWalkingTimeClient("key", "http://127.0.0.1:1", enabled = false).refine(geometry(), 3))
        assertNull(client().refine(geometry().copy(providerQueryEndpoints = null), 3))
        assertThrows(IllegalArgumentException::class.java) { OdsayWalkingTimeClient("key", "https://attacker.example") }
    }

    @Test
    fun `한 계산의 여러 후보는 새 도보 HTTP를 한 번만 소비한다`() {
        val calls = AtomicInteger()
        val budget = com.noLate.eta.resilience.EtaCalculationDeadline()
        val server = server { calls.incrementAndGet(); response().toString() }
        try {
            val client = OdsayWalkingTimeClient("key", "http://127.0.0.1:${server.address.port}",
                clock = Clock.fixed(now, ZoneOffset.UTC), calculationDeadline = budget)
            val original = geometry()
            val endpoints = original.providerQueryEndpoints!!
            val another = original.copy(providerQueryEndpoints = endpoints.copy(end = endpoints.end.copy(lat = endpoints.end.lat + 1e-8)))
            budget.within {
                assertNotNull(client.refine(original, 3))
                assertNull(client.refine(another, 3))
                assertNotNull(client.refine(original, 3)) // 캐시는 같은 평가에서도 재사용한다.
            }
            assertEquals(1, calls.get())
            budget.within { assertNotNull(client.refine(another, 3)) }
            assertEquals(2, calls.get())
        } finally { server.stop(0) }
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403, 429, 500, 400])
    fun `HTTP 상태만 안전하게 기록하고 인증 한도 오류를 재호출하지 않는다`(status: Int) {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                calls.incrementAndGet()
                val bytes = "private-key provider-secret raw-url".toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val client = OdsayWalkingTimeClient("private-key", "http://127.0.0.1:${server.address.port}")
            repeat(3) { assertNull(client.refine(geometry(), 3)) }
            assertEquals(1, calls.get())
            val last = client.diagnostics()["lastFailure"] as Map<*, *>
            assertEquals(status, last["httpStatus"])
            assertEquals(when (status) { 401, 403 -> "HTTP_AUTH"; 429 -> "HTTP_QUOTA"; 500 -> "HTTP_SERVER"; else -> "HTTP_CLIENT" }, last["reason"])
            assertFalse(client.diagnostics().toString().contains("private-key"))
            assertFalse(client.diagnostics().toString().contains("provider-secret"))
        } finally { server.stop(0) }
    }

    @Test
    fun `도보 경로 오류 401은 인증 HTTP 401로 오인하지 않고 숫자 코드만 보존한다`() {
        val server = server { """{"result":{"path":[{"hasPathResult":false,"errorCode":"401","message":"private-key"}]}}""" }
        try {
            val client = OdsayWalkingTimeClient("private-key", "http://127.0.0.1:${server.address.port}")
            assertNull(client.refine(geometry(), 3))
            val last = client.diagnostics()["lastFailure"] as Map<*, *>
            assertEquals("NO_PATH", last["reason"])
            assertEquals("401", last["providerCode"])
            assertNull(last["httpStatus"])
        } finally { server.stop(0) }
    }

    @Test
    fun `시간초과 통신 deadline 회로 차단을 오류 원문 없이 구분한다`() {
        val client = client()
        assertEquals(WalkingRefinementFailure.TIMEOUT, client.classifyFailure(org.springframework.web.client.ResourceAccessException(
            "private-url", java.net.SocketTimeoutException("private-key"))))
        assertEquals(WalkingRefinementFailure.TRANSPORT, client.classifyFailure(org.springframework.web.client.ResourceAccessException(
            "private-url", java.net.ConnectException("private-key"))))
        assertEquals(WalkingRefinementFailure.DEADLINE, client.classifyFailure(com.noLate.eta.resilience.EtaSoftDeadlineExceededException()))
        assertEquals(WalkingRefinementFailure.CIRCUIT_OPEN, client.classifyFailure(com.noLate.eta.resilience.EtaProviderCircuitOpenException("odsay-walking")))
    }

    @Test
    fun `공급자가 오류 코드 자리에 URL이나 키를 넣어도 진단에 전달하지 않는다`() {
        val server = server { """{"error":[{"code":"https://private.example?apiKey=private-key","message":"private-message"}]}""" }
        try {
            val client = OdsayWalkingTimeClient("private-key", "http://127.0.0.1:${server.address.port}")
            assertNull(client.refine(geometry(), 3))
            val last = client.diagnostics()["lastFailure"] as Map<*, *>
            assertEquals("PROVIDER_APPLICATION_ERROR", last["reason"])
            assertNull(last["providerCode"])
            assertFalse(client.diagnostics().toString().contains("private"))
        } finally { server.stop(0) }
    }

    private fun server(body: (com.sun.net.httpserver.HttpExchange) -> String): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val bytes = body(exchange).toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private class MutableClock(var value: Instant) : Clock() {
        override fun instant() = value
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
