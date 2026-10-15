package com.noLate.eta.infrastructure.seoul

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitServiceClass
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** 로컬 HTTP 합성 명세. 같은 열차의 다음 역 순서가 방향의 증거인지 검증한다. */
class SeoulCircularTimetableDirectionTest {
    private val json = jacksonObjectMapper()
    private val now = Instant.parse("2026-10-11T03:00:00Z")
    private val query = TransitTimetableQuery(SeoulSubwayLine.LINE_2, "사당", "신림", "낙성대",
        TransitServiceClass.LOCAL, now, null)

    @Test
    fun `순환선은 동일 열차가 승차 다음역 하차 순서로 통과하는 유일한 방향을 선택한다`() {
        withServer("") { provider ->
            val ride = provider.findRides(query).single()
            assertEquals("UP", ride.directionCode)
            assertEquals("0226", ride.fromStationCode)
            assertEquals("0230", ride.toStationCode)
            assertEquals("2012", ride.trainNumber)
        }
    }

    @Test
    fun `양방향 동일 순서나 다른 다음역 열차 또는 잘못된 시간순서는 거절한다`() {
        for (scenario in listOf("both", "wrong-train", "wrong-order")) {
            withServer(scenario) { provider -> assertTrue(provider.findRides(query).isEmpty(), scenario) }
        }
    }

    private fun withServer(scenario: String, check: (SeoulTransitTimetableProvider) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val codes = mapOf("사당" to "0226", "낙성대" to "0227", "신림" to "0230")
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path.split('/').filter(String::isNotBlank)
            val service = path[2]
            val rows = json.createArrayNode()
            if (service == "SearchInfoBySubwayNameService") {
                val name = path.last()
                rows.add(json.createObjectNode().put("STATION_CD", codes.getValue(name))
                    .put("STATION_NM", name).put("LINE_NUM", "02호선"))
            } else if (path.last() == "1" || scenario == "both") {
                val code = path[5]
                val name = codes.entries.single { it.value == code }.key
                val arrival = when (name) {
                    "사당" -> "12:02:40"
                    "낙성대" -> if (scenario == "wrong-order") "12:02:00" else "12:05:00"
                    else -> "12:11:00"
                }
                rows.add(json.createObjectNode().put("STATION_CD", code).put("STATION_NM", name)
                    .put("LINE_NUM", "02호선").put("WEEK_TAG", "3").put("INOUT_TAG", path.last())
                    .put("TRAIN_NO", if (name == "낙성대" && scenario == "wrong-train") "9999" else "2012")
                    .put("ORIGINSTATION", "0201").put("DESTSTATION", "0211").put("SUBWAYENAME", "성수")
                    .put("BRANCH_LINE", "0").put("EXPRESS_YN", "G").put("ARRIVETIME", arrival)
                    .put("LEFTTIME", if (name == "사당") "12:03:00" else arrival))
            }
            val body = json.createObjectNode().apply {
                set<com.fasterxml.jackson.databind.JsonNode>(service, json.createObjectNode().apply {
                    set<com.fasterxml.jackson.databind.JsonNode>("RESULT", json.createObjectNode().put("CODE", "INFO-000"))
                    put("list_total_count", rows.size())
                    set<com.fasterxml.jackson.databind.JsonNode>("row", rows)
                })
            }.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            check(SeoulTransitTimetableProvider("fixture-key", "http://127.0.0.1:${server.address.port}",
                SeoulTimetableServiceDay { "3" }, Clock.fixed(now, ZoneOffset.UTC), allowInsecureHttp = true))
        } finally { server.stop(0) }
    }
}
