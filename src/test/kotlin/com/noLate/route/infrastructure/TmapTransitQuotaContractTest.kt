package com.noLate.route.infrastructure

import com.noLate.route.domain.TransitRouteRequest
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.web.client.HttpClientErrorException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Loopback HTTP contract, not a live Tmap call or journey-accuracy proof. */
class TmapTransitQuotaContractTest {
    @Test
    fun `429는 경로 성공으로 바꾸지 않고 공급자가 회복되면 같은 클라이언트로 다시 조회한다`() {
        val exhausted = AtomicBoolean(true)
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/transit/routes") { exchange ->
                calls.incrementAndGet()
                val status = if (exhausted.get()) 429 else 200
                val body = if (status == 429) """{"error":"synthetic quota"}"""
                    else """{"metaData":{"plan":{"itineraries":[]}}}"""
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val client = TmapTransitRouteClient("synthetic-not-a-real-key", "http://127.0.0.1:${server.address.port}")
            val request = TransitRouteRequest(startX = 127.061, startY = 37.6551, endX = 126.9297, endY = 37.4842)
            assertThrows(HttpClientErrorException.TooManyRequests::class.java) { client.getRoutes(request) }
            assertEquals(1, calls.get())
            exhausted.set(false)
            assertTrue(client.getRoutes(request).path("metaData").path("plan").path("itineraries").isArray)
            assertEquals(2, calls.get())
        } finally {
            server.stop(0)
        }
    }
}
