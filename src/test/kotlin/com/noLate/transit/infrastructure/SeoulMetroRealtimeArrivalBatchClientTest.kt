package com.noLate.transit.infrastructure

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SeoulMetroRealtimeArrivalBatchClientTest {
    private val key = "TESTKEY123"
    private val body = """{"errorMessage":{"status":200,"code":"INFO-000"},"realtimeArrivalList":[
        {"subwayId":"1002","statnId":"1002000222","statnNm":"강남"}]}""".toByteArray()

    @Test fun `only same host key free data redirect can supply the batch`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val dataCalls = AtomicInteger()
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path == "/data/123.json") {
                dataCalls.incrementAndGet(); exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                exchange.responseHeaders.set("Location",
                    "http://127.0.0.1:${server.address.port}/api/../data/123.json")
                exchange.sendResponseHeaders(307, -1)
            }
            exchange.close()
        }
        server.start()
        try {
            val origin = URI.create("http://127.0.0.1:${server.address.port}")
            SeoulMetroRealtimeArrivalBatchClient(key, true, origin).use { client ->
                assertNotNull(client.fetch())
            }
            assertEquals(1, dataCalls.get())
            assertFails { SeoulMetroRealtimeArrivalBatchClient(key, false, origin) }
        } finally { server.stop(0) }
    }

    @Test fun `external and credential bearing redirects are never followed`() {
        for (location in listOf("http://example.com/data/123.json", "http://127.0.0.1:%PORT%/data/123.json?key=$key")) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val calls = AtomicInteger()
            server.createContext("/") { exchange ->
                calls.incrementAndGet()
                exchange.responseHeaders.set("Location", location.replace("%PORT%", server.address.port.toString()))
                exchange.sendResponseHeaders(307, -1); exchange.close()
            }
            server.start()
            try {
                val origin = URI.create("http://127.0.0.1:${server.address.port}")
                SeoulMetroRealtimeArrivalBatchClient(key, true, origin).use { client ->
                    assertEquals("Seoul ALL-arrivals unavailable", assertFails { client.fetch() }.message)
                }
                assertEquals(1, calls.get())
            } finally { server.stop(0) }
        }
    }
}
