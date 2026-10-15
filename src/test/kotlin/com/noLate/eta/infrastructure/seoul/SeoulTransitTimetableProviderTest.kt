package com.noLate.eta.infrastructure.seoul

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitServiceClass
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetSocketAddress
import java.time.*
import java.util.concurrent.atomic.AtomicInteger

/** 로컬 HTTP 명세 fixture다. 실제 서울 API 성공을 증명하는 테스트가 아니다. */
class SeoulTransitTimetableProviderTest {
    private val now = Instant.parse("2026-10-04T06:00:00Z")
    private val mapper = jacksonObjectMapper()

    @ParameterizedTest
    @ValueSource(strings = ["quota-code", "quota-http"])
    fun `시간표 한도 오류는 HTTP 경계에서도 정상 빈 시간표가 아니다`(scenario: String) = withServer(scenario) { provider, calls ->
        assertThrows(RuntimeException::class.java) { provider.findRides(query()) }
        assertTrue(calls.get() > 0)
    }

    @Test
    fun `서울 4호선 K열차는 원본 번호를 보존하고 중복 없는 숫자 실시간 번호만 연결한다`() = withServer("korailTrain") { provider, _ ->
        val ride = provider.findRides(query()).single()
        assertEquals("K4012", ride.trainNumber)
        assertEquals("4012", ride.realtimeTrainNumber)
    }

    @Test
    fun `접두사 제거 후 다른 시간표 열차와 충돌하면 실시간 번호를 연결하지 않는다`() = withServer("korailCollision") { provider, _ ->
        val rides = provider.findRides(query())
        assertEquals(2, rides.size)
        assertTrue(rides.all { it.realtimeTrainNumber == null })
    }

    @Test
    fun `동일 열차 승하차 시각으로 방향을 정하고 같은 조회는 bounded cache로 재사용한다`() = withServer { provider, calls ->
        val first = provider.findRides(query())
        val repeated = provider.findRides(query())

        val ride = first.single()
        assertEquals("DOWN", ride.directionCode)
        assertEquals("4012", ride.trainNumber)
        assertEquals(now.plusSeconds(180), ride.boardingAt)
        assertEquals(now.plusSeconds(1500), ride.arrivalAt)
        assertEquals("오이도", ride.terminalName)
        assertEquals("FROM_CODE", ride.fromStationCode)
        assertEquals("TO_CODE", ride.toStationCode)
        assertEquals(first, repeated)
        assertEquals(6, calls.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["wrongTrain", "wrongOrigin", "wrongTerminal", "wrongClass", "unknownClass", "wrongDirection", "wrongWeek", "wrongStation", "reverseTime", "duplicateRun", "ambiguousStation", "bothDirections"])
    fun `식별자 종별 날짜 방향 순서가 맞지 않거나 모호하면 시간표를 채택하지 않는다`(mismatch: String) = withServer(mismatch) { provider, _ ->
        assertTrue(provider.findRides(query()).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["2026-10-05T06:00:00Z", "2026-10-03T16:00:00Z"])
    fun `유효기간 없는 시간표를 내일 일정이나 모호한 새벽 영업일로 사용하지 않는다`(time: String) = withServer { provider, calls ->
        assertTrue(provider.findRides(query().copy(notBefore = Instant.parse(time))).isEmpty())
        assertEquals(0, calls.get())
    }

    @Test
    fun `휴일 미확인 또는 키 미설정은 외부 요청 없이 닫힌다`() = withServer { _, calls ->
        assertThrows(IllegalArgumentException::class.java) {
            SeoulTransitTimetableProvider("", "http://127.0.0.1", SeoulTimetableServiceDay { "3" }, Clock.fixed(now, ZoneOffset.UTC))
        }
        val unknownDay = SeoulTransitTimetableProvider("fixture-key", "http://127.0.0.1", SeoulTimetableServiceDay { null }, Clock.fixed(now, ZoneOffset.UTC))
        assertTrue(unknownDay.findRides(query()).isEmpty())
        assertEquals(0, calls.get())
    }

    @Test
    fun `순환선은 방향과 다음 역 증거가 없으면 조회로 방향을 만들지 않는다`() = withServer { provider, calls ->
        assertTrue(provider.findRides(query().copy(line = SeoulSubwayLine.LINE_2)).isEmpty())
        assertEquals(0, calls.get())
    }

    @Test
    fun `인증오류와 전체 건수 불일치는 정상 빈 응답이나 완전한 시간표로 취급하지 않는다`() = withServer { provider, _ ->
        assertThrows(RuntimeException::class.java) {
            provider.parsePayload(mapper.readTree("""{"RESULT":{"CODE":"INFO-100"}}"""), "SearchSTNTimeTableByIDService", now)
        }
        assertThrows(RuntimeException::class.java) {
            provider.parsePayload(mapper.readTree("""{"SearchSTNTimeTableByIDService":{"RESULT":{"CODE":"INFO-000"},"list_total_count":1001,"row":[]}}"""), "SearchSTNTimeTableByIDService", now)
        }
    }

    @Test
    fun `명시적 24시는 다음 날짜로 변환하지만 00시와 sentinel 및 잘못된 시각은 추정하지 않는다`() = withServer { provider, _ ->
        val date = LocalDate.of(2026, 10, 4)
        assertEquals(Instant.parse("2026-10-04T15:12:34Z"), provider.parseTime("24:12:34", date))
        listOf("00:12:34", "00:00:00", "27:00:00", "24:60:00", "24:00:60", "bad").forEach {
            assertNull(provider.parseTime(it, date))
        }
    }

    private fun query() = TransitTimetableQuery(SeoulSubwayLine.LINE_4, "서울역", "사당", null,
        TransitServiceClass.LOCAL, now.plusSeconds(171))

    @Test
    fun `오늘 여정의 자정 이후 환승은 같은 운행일의 명시적 24시 행으로 연결한다`() = withServer("overnight") { provider, calls ->
        val serviceDate = LocalDate.of(2026, 10, 4)
        val ride = provider.findRides(query().copy(notBefore = Instant.parse("2026-10-04T15:02:51Z"),
            directionCode = "DOWN", serviceDate = serviceDate)).single()
        assertEquals(serviceDate, ride.serviceDate)
        assertEquals(Instant.parse("2026-10-04T15:03:00Z"), ride.boardingAt)
        assertEquals(Instant.parse("2026-10-04T15:25:00Z"), ride.arrivalAt)
        assertEquals(4, calls.get())
    }

    @Test
    fun `운행일을 전달해도 00시 행을 24시로 바꿔 만들지 않는다`() = withServer("overnight-zero") { provider, _ ->
        assertTrue(provider.findRides(query().copy(notBefore = Instant.parse("2026-10-04T15:02:51Z"),
            serviceDate = LocalDate.of(2026, 10, 4))).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["2026-10-04T18:00:00Z", "2026-10-05T15:02:51Z", "2026-10-03T15:02:51Z"])
    fun `명시 운행일도 새벽 범위 밖이나 다른 날짜 조회를 허용하지 않는다`(time: String) = withServer { provider, calls ->
        assertTrue(provider.findRides(query().copy(notBefore = Instant.parse(time),
            serviceDate = LocalDate.of(2026, 10, 4))).isEmpty())
        assertEquals(0, calls.get())
    }

    @Test
    fun `운행일을 지정해도 내일 시간표의 유효성을 보장하지 않는다`() = withServer { provider, calls ->
        assertTrue(provider.findRides(query().copy(notBefore = Instant.parse("2026-10-05T06:02:51Z"),
            serviceDate = LocalDate.of(2026, 10, 5))).isEmpty())
        assertEquals(0, calls.get())
    }

    private fun withServer(mismatch: String = "", block: (SeoulTransitTimetableProvider, AtomicInteger) -> Unit) {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                calls.incrementAndGet()
                if (mismatch in setOf("quota-code", "quota-http")) {
                    val body = """{"RESULT":{"CODE":"ERROR-337","MESSAGE":"synthetic quota"}}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(if (mismatch == "quota-http") 429 else 200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                    return@createContext
                }
                val path = exchange.requestURI.path.split('/').filter(String::isNotBlank)
                val service = path[2]
                val rows = if (service == "SearchInfoBySubwayNameService") {
                    val from = path.last() == "서울"
                    val station = """{"STATION_CD":"${if (from) "FROM_CODE" else "TO_CODE"}","STATION_NM":"${if (from) "서울역" else "사당"}","LINE_NUM":"04호선"}"""
                    if (mismatch == "ambiguousStation") listOf(station, station.replace("_CODE", "_OTHER")) else listOf(station)
                } else {
                    val station = path[5]
                    val direction = path.last()
                    if (direction == "1" && mismatch != "bothDirections") emptyList() else {
                        val end = station == "TO_CODE"
                        val row = row(station, end, mismatch, direction).let {
                            if (mismatch.startsWith("overnight")) it.replace("15:", if (mismatch == "overnight-zero") "00:" else "24:") else it
                        }
                        if (mismatch == "korailCollision") listOf(row, row.replace("K4012", "4012"))
                        else if (mismatch == "duplicateRun" && end) listOf(row, row.replace("15:25:00", "15:26:00")) else listOf(row)
                    }
                }
                val body = """{"$service":{"RESULT":{"CODE":"INFO-000"},"list_total_count":${rows.size},"row":[${rows.joinToString(",")}]}}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            block(SeoulTransitTimetableProvider("fixture-key", "http://127.0.0.1:${server.address.port}",
                SeoulTimetableServiceDay { "3" }, Clock.fixed(now, ZoneOffset.UTC), allowInsecureHttp = true), calls)
        } finally { server.stop(0) }
    }

    private fun row(station: String, end: Boolean, mismatch: String, direction: String): String = """
        {"STATION_CD":"${if (end && mismatch == "wrongStation") "OTHER_CODE" else station}",
        "STATION_NM":"${if (end) "사당" else "서울역"}","LINE_NUM":"04호선",
        "WEEK_TAG":"${if (end && mismatch == "wrongWeek") "1" else "3"}",
        "INOUT_TAG":"${if (end && mismatch == "wrongDirection") "1" else direction}",
        "TRAIN_NO":"${if (end && mismatch == "wrongTrain") "4999" else if (mismatch in setOf("korailTrain", "korailCollision")) "K4012" else "4012"}",
        "ORIGINSTATION":"${if (end && mismatch == "wrongOrigin") "OTHER_ORIGIN" else "ORIGIN_CODE"}",
        "DESTSTATION":"${if (end && mismatch == "wrongTerminal") "OTHER_TERM" else "TERM_CODE"}",
        "SUBWAYENAME":"오이도","BRANCH_LINE":"0",
        "EXPRESS_YN":"${if (end && mismatch == "wrongClass") "D" else if (mismatch == "unknownClass") "?" else "G"}",
        "ARRIVETIME":"${if (!end) "15:02:40" else if (mismatch == "reverseTime") "15:02:00" else "15:25:00"}",
        "LEFTTIME":"${if (end) "15:25:20" else "15:03:00"}"}
    """.trimIndent()
}
