package com.noLate.eta.infrastructure.seoul

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.domain.*
import com.noLate.transit.infrastructure.TransitProviderApplicationException
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.*
import java.util.concurrent.atomic.AtomicInteger

class SeoulTrainPositionClientTest {
    private val now = Instant.parse("2026-10-13T06:00:30Z")
    private fun client() = SeoulTrainPositionClient("", "", "http://127.0.0.1:9")
    private fun payload(row: String = ROW, total: Int = 1) = jacksonObjectMapper().readTree(
        """{"errorMessage":{"code":"INFO-000","total":$total},"realtimePositionList":[$row]}""")

    @Test fun `원본 날짜 시각 열차 방향 상태를 보존하며 물리 도착초로 가장하지 않는다`() {
        val result = client().parse(payload(), SeoulSubwayLine.LINE_4, now)
        val row = result.positions.single()
        assertEquals("4615", row.trainNumber)
        assertEquals("DOWN", row.directionCode)
        assertEquals(TransitTrainPositionState.ARRIVED, row.state)
        assertEquals(Instant.parse("2026-10-13T06:00:00Z"), row.sourceUpdatedAt)
        assertEquals(now, row.receivedAt)
        assertTrue(row.isFresh())
    }

    @Test fun `날짜 시각 노선 방향 누락을 수신시각이나 기본값으로 대체하지 않는다`() {
        for (changed in listOf(ROW.replace("20261013", ""), ROW.replace("15:00:00", ""),
            ROW.replace("1004", "1002"), ROW.replace("\"updnLine\":\"1\"", "\"updnLine\":\"9\""))) {
            val result = client().parse(payload(changed), SeoulSubwayLine.LINE_4, now)
            assertTrue(result.positions.isEmpty()); assertEquals(1, result.rejectedRows)
        }
        val stale = client().parse(payload(ROW.replace("15:00:00", "14:50:00")), SeoulSubwayLine.LINE_4, now).positions.single()
        assertFalse(stale.isFresh())
    }

    @Test fun `공급자 날짜 시각의 전체 timestamp 형식도 날짜 일치를 확인한다`() {
        val full = ROW.replace("20261013", "2026-10-13 15:00:00.0").replace("\"15:00:00\"", "\"2026-10-13 15:00:00\"")
        assertEquals(Instant.parse("2026-10-13T06:00:00Z"), client().parse(payload(full), SeoulSubwayLine.LINE_4, now).positions.single().sourceUpdatedAt)
        assertTrue(client().parse(payload(full.replace("2026-10-13 15:00:00\"", "2026-10-14 15:00:00\"")),
            SeoulSubwayLine.LINE_4, now).positions.isEmpty())
    }

    @Test fun `한도 인증 불완전 페이지는 성공이나 빈 관측으로 바꾸지 않는다`() {
        assertThrows(TransitProviderApplicationException::class.java) {
            client().parse(jacksonObjectMapper().readTree("""{"code":"ERROR-337"}"""), SeoulSubwayLine.LINE_4, now)
        }
        assertThrows(IllegalStateException::class.java) { client().parse(payload(total = 2), SeoulSubwayLine.LINE_4, now) }
    }

    @Test fun `호선 캐시와 요청 예산을 공유하고 공급자 실패 뒤 재호출하지 않는다`() {
        val calls = AtomicInteger()
        var body = payload().toString()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                calls.incrementAndGet()
                val bytes = body.toByteArray(); exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }; start()
        }
        val clock = object : Clock() {
            var current = now
            override fun instant() = current
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
        }
        try {
            val client = SeoulTrainPositionClient("", "fixture-key", "http://127.0.0.1:${server.address.port}", clock = clock, maxDailyCalls = 2)
            val first = client.positions(SeoulSubwayLine.LINE_4)
            clock.current = now.plusSeconds(59)
            assertEquals(first, client.positions(SeoulSubwayLine.LINE_4)); assertEquals(1, calls.get())
            clock.current = now.plusSeconds(60); body = """{"code":"ERROR-337"}"""
            assertThrows(TransitProviderApplicationException::class.java) { client.positions(SeoulSubwayLine.LINE_4) }
            assertThrows(IllegalStateException::class.java) { client.positions(SeoulSubwayLine.LINE_4) }
            assertEquals(2, calls.get())
        } finally { server.stop(0) }
    }

    companion object {
        private const val ROW = """{"subwayId":"1004","trainNo":"4615","statnNm":"노원","updnLine":"1","statnTnm":"오이도","directAt":"0","trainSttus":"1","lastRecptnDt":"20261013","recptnDt":"15:00:00"}"""
    }
}
