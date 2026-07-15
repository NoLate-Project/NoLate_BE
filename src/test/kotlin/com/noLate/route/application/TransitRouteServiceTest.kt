package com.noLate.route.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.noLate.route.domain.TransitRouteRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TransitRouteServiceTest {
    private val objectMapper = ObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-07-13T01:00:00Z"), ZoneOffset.UTC)
    private val request = TransitRouteRequest(
        startX = 127.1001,
        startY = 37.5133,
        endX = 126.9254,
        endY = 37.5572,
        searchDttm = "202607131000",
    )

    @Test
    fun `동일한 경로 요청은 짧게 캐시한다`() {
        var callCount = 0
        val provider = provider("tmap") {
            callCount += 1
            routeResponse(minutes = 42)
        }
        val service = TransitRouteService(listOf(provider), clock)

        service.getRoutes(request)
        service.getRoutes(request)

        assertEquals(1, callCount)
    }

    @Test
    fun `첫 공급자가 실패하면 다음 공급자를 사용한다`() {
        val service = TransitRouteService(
            listOf(
                provider("primary") { error("first provider failed") },
                provider("fallback") { routeResponse(minutes = 42) },
            ),
            clock,
        )

        val response = service.getRoutes(request)
        assertEquals("fallback", response.path("_noLateRouting").path("provider").asText())
        assertEquals(true, response.path("_noLateRouting").path("fallbackUsed").asBoolean())
    }

    @Test
    fun `성공 응답이어도 후보가 비어 있으면 다음 공급자를 사용한다`() {
        val service = TransitRouteService(
            listOf(
                provider("empty") { objectMapper.readTree("""{"metaData":{"plan":{"itineraries":[]}}}""") },
                provider("usable") { routeResponse(minutes = 44) },
            ),
            clock,
        )

        val diagnostics = service.getRoutes(request).path("_noLateRouting")
        assertEquals("usable", diagnostics.path("provider").asText())
        assertEquals("rejected", diagnostics.path("evaluatedProviders").path(0).path("status").asText())
    }

    @Test
    fun `여러 공급자가 유효하면 가장 빠른 후보군을 선택한다`() {
        val service = TransitRouteService(
            listOf(
                provider("tmap") { routeResponse(minutes = 58, candidateCount = 3) },
                provider("secondary") { routeResponse(minutes = 38, candidateCount = 2) },
            ),
            clock,
        )

        val response = service.getRoutes(request)
        val diagnostics = response.path("_noLateRouting")
        assertEquals("secondary", diagnostics.path("provider").asText())
        assertEquals(38, diagnostics.path("fastestMinutes").asInt())
        assertEquals(2, diagnostics.path("candidateCount").asInt())
    }

    @Test
    fun `조금 느려도 승차 형상이 완전한 공급자를 우선한다`() {
        val service = TransitRouteService(
            listOf(
                provider("fast-incomplete") { routeResponse(minutes = 38, includeGeometry = false) },
                provider("complete") { routeResponse(minutes = 42, includeGeometry = true) },
            ),
            clock,
        )

        val diagnostics = service.getRoutes(request).path("_noLateRouting")
        assertEquals("complete", diagnostics.path("provider").asText())
        assertEquals(1, diagnostics.path("geometryCompleteCandidateCount").asInt())
        assertEquals(1, diagnostics.path("rideShapeLegCount").asInt())
        assertEquals(1, diagnostics.path("rideLegCount").asInt())
    }

    @Test
    fun `대중교통 레그가 없는 후보는 거절한다`() {
        val invalid = objectMapper.readTree(
            """{"metaData":{"plan":{"itineraries":[{"totalTime":2400,"legs":[{"mode":"WALK"}]}]}}}"""
        )
        val service = TransitRouteService(listOf(provider("invalid") { invalid }), clock)

        assertThrows(ResponseStatusException::class.java) {
            service.getRoutes(request)
        }
    }

    @Test
    fun `TMAP 허용 범위를 넘는 후보 개수를 거절한다`() {
        val service = TransitRouteService(listOf(provider("tmap") { routeResponse(minutes = 42) }), clock)

        assertThrows(ResponseStatusException::class.java) {
            service.getRoutes(request.copy(count = 11))
        }
    }

    private fun provider(id: String, block: () -> JsonNode): TransitRouteProvider =
        object : TransitRouteProvider {
            override fun providerId(): String = id
            override fun getRoutes(request: TransitRouteRequest): JsonNode = block()
        }

    private fun routeResponse(
        minutes: Int,
        candidateCount: Int = 1,
        includeGeometry: Boolean = true,
    ): JsonNode {
        val geometry = if (includeGeometry) {
            ""","passShape":{"linestring":"127.1001,37.5133 126.9254,37.5572"}"""
        } else {
            ""
        }
        val itineraries = (0 until candidateCount).joinToString(",") { index ->
            """{"totalTime":${(minutes + index) * 60},"totalDistance":18000,"legs":[{"mode":"SUBWAY"$geometry}]}"""
        }
        return objectMapper.readTree("""{"metaData":{"plan":{"itineraries":[$itineraries]}}}""")
    }
}
