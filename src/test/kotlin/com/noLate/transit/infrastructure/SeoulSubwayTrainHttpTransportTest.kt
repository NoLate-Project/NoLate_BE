package com.noLate.transit.infrastructure

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SeoulSubwayTrainHttpTransportTest {
    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): Pair<HttpServer, ExecutorService> {
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/", handler); this.executor = executor; start()
        }
        return server to executor
    }
    private fun uri(server: HttpServer) = URI.create("http://127.0.0.1:${server.address.port}/PRIVATE-KEY-MARKER")
    private fun cleanFailure(failure: Throwable) {
        assertFalse(failure.toString().contains("PRIVATE-KEY-MARKER")); assertNull(failure.cause)
    }

    @Test fun `streaming body deadline covers repeated small chunks not just idle socket reads`() {
        val (server, executor) = server { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output -> repeat(40) { output.write('x'.code); output.flush(); Thread.sleep(30) } }
            } catch (_: Exception) { exchange.close() }
        }
        try {
            SeoulSubwayTrainHttpTransport(Duration.ofMillis(200)).use { transport ->
                val started = System.nanoTime()
                cleanFailure(assertFailsWith<IllegalStateException> { transport.fetch(uri(server)) })
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
            }
        } finally { server.stop(0); executor.shutdownNow() }
    }

    @Test fun `chunked oversized body is cancelled without keeping payload text`() {
        val (server, executor) = server { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output -> repeat(130) { output.write(ByteArray(8192) { 'x'.code.toByte() }) } }
            } catch (_: Exception) { exchange.close() }
        }
        try { SeoulSubwayTrainHttpTransport().use { cleanFailure(assertFailsWith<IllegalStateException> { it.fetch(uri(server)) }) } }
        finally { server.stop(0); executor.shutdownNow() }
    }

    @Test fun `oversized declared length and encoded body are rejected at headers`() {
        for (compressed in listOf(false, true)) {
            val (server, executor) = server { exchange ->
                if (compressed) exchange.responseHeaders.add("Content-Encoding", "gzip")
                exchange.sendResponseHeaders(200, if (compressed) 10 else 1_048_577)
                exchange.close()
            }
            try { SeoulSubwayTrainHttpTransport().use { cleanFailure(assertFailsWith<IllegalStateException> { it.fetch(uri(server)) }) } }
            finally { server.stop(0); executor.shutdownNow() }
        }
    }

    @Test fun `parallel streaming requests have no third queued HTTP request`() {
        val arrived = CountDownLatch(2); val release = CountDownLatch(1); val calls = AtomicInteger()
        val (server, executor) = server { exchange ->
            calls.incrementAndGet(); arrived.countDown()
            try {
                release.await(2, TimeUnit.SECONDS)
                val bytes = "{}".toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: Exception) { exchange.close() }
        }
        try {
            SeoulSubwayTrainHttpTransport().use { transport ->
                val first = CompletableFuture.supplyAsync { transport.fetch(uri(server)) }
                val second = CompletableFuture.supplyAsync { transport.fetch(uri(server)) }
                try {
                    assertTrue(arrived.await(2, TimeUnit.SECONDS))
                    assertEquals("Subway observation busy", assertFailsWith<IllegalStateException> { transport.fetch(uri(server)) }.message)
                    assertEquals(2, calls.get()); release.countDown()
                    assertContentEquals("{}".toByteArray(), first.get(2, TimeUnit.SECONDS))
                    assertContentEquals("{}".toByteArray(), second.get(2, TimeUnit.SECONDS))
                } finally { release.countDown(); first.cancel(true); second.cancel(true) }
            }
        } finally { server.stop(0); executor.shutdownNow() }
    }

    @Test fun `non-success status exposes status only not body header or destination`() {
        val (server, executor) = server { exchange ->
            val bytes = "PRIVATE-BODY-MARKER".toByteArray()
            exchange.responseHeaders.add("PRIVATE-HEADER-MARKER", "PRIVATE-KEY-MARKER")
            exchange.sendResponseHeaders(429, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        try {
            SeoulSubwayTrainHttpTransport().use { transport ->
                val failure = assertFailsWith<org.springframework.web.client.RestClientResponseException> { transport.fetch(uri(server)) }
                assertEquals(429, failure.statusCode.value()); cleanFailure(failure)
                assertContentEquals(byteArrayOf(), failure.responseBodyAsByteArray)
                assertNull(failure.responseHeaders)
                assertFalse(failure.toString().contains("PRIVATE-BODY-MARKER"))
            }
        } finally { server.stop(0); executor.shutdownNow() }
    }

    @Test fun `closed transport rejects subsequent use without raw exception`() {
        val transport = SeoulSubwayTrainHttpTransport(); transport.close()
        cleanFailure(assertFailsWith<IllegalStateException> { transport.fetch(URI.create("http://127.0.0.1:1/PRIVATE-KEY-MARKER")) })
    }

    @Test fun `partial and no content HTTP responses cannot become complete train observations`() {
        for (status in listOf(204, 206)) {
            val (server, executor) = server { exchange ->
                val bytes = "{}".toByteArray()
                exchange.sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
                if (status != 204) exchange.responseBody.write(bytes)
                exchange.close()
            }
            try { SeoulSubwayTrainHttpTransport().use { transport ->
                val failure = assertFailsWith<org.springframework.web.client.RestClientResponseException> { transport.fetch(uri(server)) }
                assertEquals(status, failure.statusCode.value()); cleanFailure(failure)
            } } finally { server.stop(0); executor.shutdownNow() }
        }
    }
}
