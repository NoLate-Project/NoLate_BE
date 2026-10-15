package com.noLate.eta.simulator

import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.client.RestClient

class SeoulArrivalRequestTraceTest {
    private fun client(): SeoulTransitArrivalClient = SeoulTransitArrivalClient(
        "", "private-test-secret", "", "https://provider.example.test/api/subway", "https://bus.example.test")

    private fun transport(client: SeoulTransitArrivalClient, status: HttpStatus = HttpStatus.OK,
        body: String = "{}", onRequest: () -> Unit = {}): RestClient {
        val replacement = RestClient.builder().baseUrl("https://provider.example.test/api/subway")
            .requestFactory { uri, method ->
                onRequest()
                MockClientHttpRequest(method, uri).apply {
                    setResponse(MockClientHttpResponse(body.toByteArray(), status))
                }
            }.build()
        ReflectionTestUtils.setField(client, "subwayClient", replacement)
        return replacement
    }

    private fun send(client: SeoulTransitArrivalClient, path: String = "/private-test-secret/json/realtimeStationArrival/0/40/노원") =
        (ReflectionTestUtils.getField(client, "subwayClient") as RestClient).get().uri(path).retrieve().body(String::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun attempts(trace: SeoulArrivalRequestTrace) = trace.snapshot()["attempts"] as List<Map<String, Any?>>

    @Test
    fun `관찰만 하며 실제 응답 본문을 보존하고 단독과 Worker 요청을 비교한다`() {
        val client = client()
        var calls = 0
        val original = transport(client, body = "original-response") { calls++ }
        SeoulArrivalRequestTrace(client).use { trace ->
            assertEquals("original-response", send(client))
            trace.begin(SeoulArrivalRequestTrace.Phase.WORKER)
            assertEquals("original-response", send(client))
            assertEquals(listOf("DIRECT", "WORKER"), attempts(trace).map { it["phase"] })
            assertEquals(listOf(1, 2), attempts(trace).map { it["order"] })
            assertTrue(attempts(trace).all { it["exactWireRequestMatchesDirect"] == true })
            assertEquals(2, calls)
            assertFalse(trace.snapshot().toString().contains("private-test-secret"))
            assertFalse(trace.snapshot().toString().contains("provider.example.test"))
            assertFalse(trace.snapshot().toString().contains("original-response"))
        }
        assertSame(original, ReflectionTestUtils.getField(client, "subwayClient"))
    }

    @Test
    fun `역명 범위 키 endpoint 변경을 원문 없이 탐지한다`() {
        val client = client()
        transport(client)
        SeoulArrivalRequestTrace(client).use { trace ->
            send(client)
            trace.begin(SeoulArrivalRequestTrace.Phase.WORKER)
            send(client, "https://other.example.test/api/subway/other-secret/json/realtimeStationArrival/0/10/사당")
            val changed = attempts(trace).last()
            for (key in listOf("exactWireRequestMatchesDirect", "credentialMatchesDirect", "endpointMatchesDirect",
                "stationMatchesDirect", "rangeMatchesDirect", "expectedPublicStationAndRange")) assertEquals(false, changed[key], key)
            assertEquals(true, changed["serviceMatchesDirect"])
            assertFalse(trace.snapshot().toString().contains("other-secret"))
            assertFalse(trace.snapshot().toString().contains("사당"))
        }
    }

    @Test
    fun `HTTP 오류도 그대로 전달하고 상태만 남긴다`() {
        val client = client()
        transport(client, HttpStatus.TOO_MANY_REQUESTS, "sensitive-provider-body")
        SeoulArrivalRequestTrace(client).use { trace ->
            assertThrows(org.springframework.web.client.HttpClientErrorException::class.java) { send(client) }
            assertEquals(429, attempts(trace).single()["httpStatus"])
            assertFalse(trace.snapshot().toString().contains("sensitive-provider-body"))
        }
    }

    @Test
    fun `메모리 기록은 제한하되 실제 요청 횟수나 전송을 바꾸지 않는다`() {
        val client = client()
        var calls = 0
        transport(client) { calls++ }
        SeoulArrivalRequestTrace(client).use { trace ->
            repeat(22) { send(client) }
            assertEquals(22, calls)
            assertEquals(20, attempts(trace).size)
            assertEquals(2, trace.snapshot()["omittedAttempts"])
        }
    }

    @Test
    fun `단독 요청 없이 Worker 단계부터 시작할 수 없다`() {
        val client = client()
        transport(client)
        SeoulArrivalRequestTrace(client).use { trace ->
            assertThrows(IllegalStateException::class.java) { trace.begin(SeoulArrivalRequestTrace.Phase.WORKER) }
            assertTrue(attempts(trace).isEmpty())
        }
    }
}
