package com.noLate.schedule.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** Groq 어댑터의 429 재시도와 사용자 경고를 실제 HTTP 왕복으로 검증한다. */
class GroqScheduleAiParserTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `retries once after rate limit and returns parsed result`() {
        val calls = AtomicInteger()
        withServer { exchange ->
            if (calls.incrementAndGet() == 1) {
                exchange.responseHeaders.add("Retry-After", "0")
                respond(exchange, 429, """{"error":{"message":"rate limit"}}""")
            } else {
                respond(exchange, 200, successfulGroqResponse())
            }
        }.use { server ->
            val parser = parserFor(server, maxAttempts = 2, maxBackoffMs = 250)

            val outcome = parser.parse("금요일 7시 술약속 신촌역", "2026-07-16")

            assertEquals(2, calls.get())
            assertNotNull(outcome.result)
            assertEquals("신촌역", outcome.result?.destinationName)
            assertEquals(null, outcome.warning)
        }
    }

    @Test
    fun `returns rate limit specific warning when retries are exhausted`() {
        withServer { exchange ->
            respond(exchange, 429, """{"error":{"message":"rate limit"}}""")
        }.use { server ->
            val parser = parserFor(server, maxAttempts = 1, maxBackoffMs = 250)

            val outcome = parser.parse("금요일 7시 술약속 신촌역", "2026-07-16")

            assertTrue(outcome.attempted)
            assertEquals(null, outcome.result)
            assertTrue(outcome.warning.orEmpty().contains("AI 요청이 잠시 몰려"))
        }
    }

    private fun parserFor(
        server: TestHttpServer,
        maxAttempts: Int,
        maxBackoffMs: Long,
    ): GroqScheduleAiParser = GroqScheduleAiParser(
        objectMapper = objectMapper,
        enabled = true,
        apiKey = "test-key",
        model = "test-model",
        baseUrl = server.baseUrl,
        maxAttempts = maxAttempts,
        maxBackoffMs = maxBackoffMs,
    )

    private fun successfulGroqResponse(): String {
        val scheduleResult = objectMapper.writeValueAsString(
            mapOf(
                "date" to "2026-07-17",
                "dateConfidence" to 0.99,
                "time" to "19:00",
                "timeConfidence" to 0.99,
                "originName" to null,
                "originAddress" to null,
                "originConfidence" to 0.0,
                "destinationName" to "신촌역",
                "destinationAddress" to null,
                "destinationConfidence" to 0.99,
                "summary" to "술약속",
                "summaryConfidence" to 0.95,
            ),
        )
        return objectMapper.writeValueAsString(
            mapOf(
                "choices" to listOf(
                    mapOf("message" to mapOf("content" to scheduleResult)),
                ),
            ),
        )
    }

    private fun withServer(handler: (HttpExchange) -> Unit): TestHttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            handler(exchange)
        }
        server.start()
        return TestHttpServer(server)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private class TestHttpServer(
        private val server: HttpServer,
    ) : AutoCloseable {
        val baseUrl: String = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
        }
    }
}
