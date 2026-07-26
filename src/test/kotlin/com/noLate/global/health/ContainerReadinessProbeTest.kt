package com.noLate.global.health

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class ContainerReadinessProbeTest {

    @Test
    fun `uses the application port by default and accepts a valid override`() {
        assertEquals(
            URI.create("http://127.0.0.1:5522/health/readiness"),
            ContainerReadinessProbe.readinessUri(null),
        )
        assertEquals(
            URI.create("http://127.0.0.1:15522/health/readiness"),
            ContainerReadinessProbe.readinessUri("15522"),
        )
        assertThrows(NumberFormatException::class.java) {
            ContainerReadinessProbe.readinessUri("not-a-port")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContainerReadinessProbe.readinessUri("65536")
        }
    }

    @Test
    fun `only an exact 200 readiness response is healthy`() {
        assertProbeResult(status = 200, expected = true)
        assertProbeResult(status = 503, expected = false)
        assertProbeResult(status = 302, expected = false)
    }

    @Test
    fun `connection failures are unhealthy without escaping`() {
        assertFalse(
            ContainerReadinessProbe.isReady(
                URI.create("http://127.0.0.1:1/health/readiness"),
            ),
        )
    }

    @Test
    fun `production image packages and runs the readiness probe`() {
        val dockerfile = Files.readString(Path.of("Dockerfile"))

        assertTrue(
            dockerfile.contains(
                "--main-class com.noLate.global.health.ContainerReadinessProbe",
            ),
        )
        assertTrue(
            dockerfile.contains(
                "COPY --from=build --chown=nolate:nolate " +
                    "/workspace/readiness-probe.jar /app/readiness-probe.jar",
            ),
        )
        assertTrue(
            dockerfile.contains(
                "CMD [\"java\", \"-jar\", \"/app/readiness-probe.jar\"]",
            ),
        )
    }

    private fun assertProbeResult(status: Int, expected: Boolean) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health/readiness") { exchange ->
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        try {
            val result = ContainerReadinessProbe.isReady(
                URI.create(
                    "http://127.0.0.1:${server.address.port}/health/readiness",
                ),
            )
            if (expected) {
                assertTrue(result)
            } else {
                assertFalse(result)
            }
        } finally {
            server.stop(0)
        }
    }
}
