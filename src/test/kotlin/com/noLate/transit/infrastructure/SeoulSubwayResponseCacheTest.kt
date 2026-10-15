package com.noLate.transit.infrastructure

import com.noLate.transit.domain.TransitArrivalDto
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SeoulSubwayResponseCacheTest {
    @Test
    fun `같은 역의 노선 방향 limit 필터는 원본 응답 하나를 공유하며 시각을 갱신하지 않는다`() = Fixture().use { f ->
        val down = f.lookup("4호선", "DOWN")
        val up = f.lookup("4호선", "UP")
        val seven = f.lookup("7호선", "DOWN")
        assertEquals(listOf("4012", "4013", "7012"), listOf(down, up, seven).map { it.single().trainNumber })
        assertEquals(1, f.calls.get())
        assertEquals(1L, f.registry.find("nolate.eta.transit.provider.wire.duration").timers().sumOf { it.count() })
        f.clock.now = f.clock.now.plusSeconds(14)
        val cached = f.lookup("4호선", "DOWN").single()
        assertEquals(down.single(), cached)
        assertEquals("2026-10-12T13:30:00Z", cached.sourceUpdatedAt)
        assertEquals("2026-10-12T13:33:00Z", cached.expectedAt)
        assertEquals("2026-10-12T13:30:30Z", cached.observedAt)
        assertEquals(1, f.calls.get())
    }

    @Test
    fun `15초 경계는 실제 HTTP를 다시 조회하지만 원천 시각을 새로 만들지 않는다`() = Fixture().use { f ->
        val first = f.lookup().single()
        f.clock.now = f.clock.now.plusSeconds(15)
        val second = f.lookup().single()
        assertEquals(2, f.calls.get())
        assertEquals(first.sourceUpdatedAt, second.sourceUpdatedAt)
        assertEquals(first.expectedAt, second.expectedAt)
        assertNotEquals(first.observedAt, second.observedAt)
    }

    @Test
    fun `만료 뒤 오류는 이전 성공으로 대체하거나 캐시하지 않고 다음 정상 요청에서 회복한다`() = Fixture().use { f ->
        f.lookup()
        f.clock.now = f.clock.now.plusSeconds(16)
        f.body.set("""{"code":"ERROR-337","message":"private fixture text"}""")
        repeat(2) { assertThrows(TransitProviderApplicationException::class.java) { f.lookup() } }
        assertEquals(3, f.calls.get())
        f.body.set(PAYLOAD)
        assertEquals("4012", f.lookup().single().trainNumber)
        assertEquals(4, f.calls.get())
    }

    @Test
    fun `다른 역은 공유하지 않고 LRU 상한을 넘으면 다시 조회한다`() = Fixture(maxEntries = 2).use { f ->
        f.lookup(station = "노원")
        f.lookup(station = "창동")
        f.lookup(station = "노원") // 창동이 가장 오래된 항목이다.
        f.lookup(station = "쌍문")
        f.lookup(station = "창동")
        assertEquals(4, f.calls.get())
    }

    @Test
    fun `명시적인 빈 성공은 짧게 공유하지만 필터별 결과를 다른 노선으로 채우지 않는다`() = Fixture().use { f ->
        assertTrue(f.lookup("3호선", "DOWN").isEmpty())
        assertEquals("4012", f.lookup().single().trainNumber)
        assertEquals(1, f.calls.get())
        f.clock.now = f.clock.now.plusSeconds(15)
        f.body.set("""{"RESULT":{"CODE":"INFO-200","MESSAGE":"no data"}}""")
        assertTrue(f.lookup().isEmpty())
        val afterAliases = f.calls.get()
        assertTrue(f.lookup("7호선", "DOWN").isEmpty())
        assertEquals(afterAliases, f.calls.get())
    }

    @Test
    fun `동시에 들어온 같은 역 요청도 실제 전송은 한 번이며 각 방향 필터는 독립적이다`() = Fixture().use { f ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.onRequest = { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val down = pool.submit<List<TransitArrivalDto>> { f.lookup("4호선", "DOWN") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val up = pool.submit<List<TransitArrivalDto>> { f.lookup("4호선", "UP") }
            release.countDown()
            assertEquals("4012", down.get(5, TimeUnit.SECONDS).single().trainNumber)
            assertEquals("4013", up.get(5, TimeUnit.SECONDS).single().trainNumber)
            assertEquals(1, f.calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test
    fun `시계가 수신시각 전으로 이동하면 미래 캐시로 재사용하지 않는다`() = Fixture().use { f ->
        f.lookup()
        f.clock.now = f.clock.now.minusSeconds(1)
        f.lookup()
        assertEquals(2, f.calls.get())
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-10-12T13:30:30Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private class Fixture(maxEntries: Int = 512) : AutoCloseable {
        val clock = MutableClock()
        val calls = AtomicInteger()
        val body = AtomicReference(PAYLOAD)
        val registry = SimpleMeterRegistry()
        @Volatile var onRequest: (() -> Unit)? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                calls.incrementAndGet()
                onRequest?.invoke()
                val bytes = body.get().toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        private val base = "http://127.0.0.1:${server.address.port}"
        private val client = SeoulTransitArrivalClient("", "fixture-key", "", base, base,
            wireMetrics = TransitProviderWireMetrics(registry), clock = clock,
            subwayResponseCacheMaxEntries = maxEntries)
        fun lookup(line: String = "4호선", direction: String = "DOWN", station: String = "노원") =
            client.getSubwayArrivals(station, line, null, direction, 1)
        override fun close() { server.stop(0); registry.close() }
    }

    companion object {
        private val PAYLOAD = """{"realtimeArrivalList":[
            {"subwayId":"1004","statnNm":"노원","btrainNo":"4012","btrainSttus":"일반","updnLine":"하행","barvlDt":"180","recptnDt":"2026-10-12 22:30:00"},
            {"subwayId":"1004","statnNm":"노원","btrainNo":"4013","btrainSttus":"일반","updnLine":"상행","barvlDt":"240","recptnDt":"2026-10-12 22:30:00"},
            {"subwayId":"1007","statnNm":"노원","btrainNo":"7012","btrainSttus":"일반","updnLine":"하행","barvlDt":"300","recptnDt":"2026-10-12 22:30:00"}
        ]}"""
    }
}
