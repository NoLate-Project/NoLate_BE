package com.noLate.eta.infrastructure.odsay

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.application.transit.TransitJourneyTimetableEnricher
import com.noLate.eta.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** 합성 응답/시간표의 연결 회귀다. 실제 공급자·지도 정확도 검증으로 집계하지 않는다. */
class OdsaySeoulTimetableMappingTest {
    private val json = jacksonObjectMapper()
    private val mapper = OdsayTransitJourneyMapper()
    private val now = Instant.parse("2026-10-11T03:00:00Z")
    private val request = TransitJourneySearchRequest(37.65, 127.06, 37.48, 126.92, now, 180)

    private fun leg(code: Int = 4, name: String = "수도권 ${code}호선"): ObjectNode = json.readTree("""
        {"trafficType":1,"duration":20,"startDateTime":"202610111200","endDateTime":"202610111220",
         "startID":411,"startName":"노원","endID":433,"endName":"사당","wayCode":2,
         "lane":{"subwayCityCode":1000,"subwayCode":$code,"name":"$name"}}
    """) as ObjectNode

    private fun map(leg: ObjectNode): TransitJourney {
        val path = json.createObjectNode().put("pathType", 2).put("totalTime", 20)
            .put("startDateTime", "202610111200").put("endDateTime", "202610111220")
        path.set<ObjectNode>("rps", leg)
        val result = json.createObjectNode().set<ObjectNode>("paths", path)
        return mapper.map(json.createObjectNode().set<ObjectNode>("result", result), request, now).single()
    }

    @Test
    fun `공식 수도권 1부터 9호선 코드와 이름이 일치할 때만 표준 노선을 연결한다`() {
        val lines = listOf(SeoulSubwayLine.LINE_1, SeoulSubwayLine.LINE_2, SeoulSubwayLine.LINE_3,
            SeoulSubwayLine.LINE_4, SeoulSubwayLine.LINE_5, SeoulSubwayLine.LINE_6,
            SeoulSubwayLine.LINE_7, SeoulSubwayLine.LINE_8, SeoulSubwayLine.LINE_9)
        lines.forEachIndexed { i, expected ->
            val mapped = map(leg(i + 1)).rideLegs.single()
            assertEquals(expected, mapped.line?.seoulSubwayLine)
            assertEquals("1000:${i + 1}", mapped.line?.providerRouteId)
            assertEquals(if (expected == SeoulSubwayLine.LINE_2) null else "DOWN", mapped.directionCode)
            assertEquals(TransitLegTimingBasis.ROUTE_ESTIMATE, mapped.timingBasis)
            assertNull(mapped.timetable)
            assertNull(mapped.durationSeconds)
        }
        val express = map(leg(9, "수도권 9호선(급행)")).rideLegs.single()
        assertEquals(SeoulSubwayLine.LINE_9, express.line?.seoulSubwayLine)
        assertEquals(TransitServiceClass.EXPRESS, express.line?.serviceClass)
    }

    @Test
    fun `다른 지역 미지원 코드 노선명 충돌 지선 및 대안 노선은 시간표 연결을 닫는다`() {
        val cases = listOf(
            leg().apply { (path("lane") as ObjectNode).put("subwayCityCode", 7000) },
            leg(117, "수도권 신림선"), leg(4, "수도권 2호선"), leg(2, "수도권 2호선 성수지선"),
            leg().apply { (path("lane") as ObjectNode).remove("subwayCityCode") },
            leg().apply { (path("lane") as ObjectNode).put("subwayCode", 4.5) },
            leg().apply { set<ObjectNode>("lane", json.createArrayNode().add(path("lane").deepCopy<ObjectNode>())
                .add(path("lane").deepCopy<ObjectNode>())) },
        )
        cases.forEach { assertNull(map(it).rideLegs.single().line?.seoulSubwayLine, it.toString()) }
    }

    private fun stops(leg: ObjectNode): ObjectNode = leg.apply {
        set<ObjectNode>("passStopList", json.readTree("""{"stations":[
            {"index":2,"stationID":433,"stationName":"사당"},
            {"index":0,"stationID":411,"stationName":"노원"},
            {"index":1,"stationID":412,"stationName":"창동"}] }""") as ObjectNode)
    }

    @Test
    fun `경유역 순서를 정렬하고 출도착 공급자 ID와 이름이 일치한 다음 역만 보존한다`() {
        val mapped = map(stops(leg())).rideLegs.single()
        assertEquals("창동", mapped.nextStopName)
        assertEquals(SeoulSubwayLine.LINE_4, mapped.line?.seoulSubwayLine)
        assertEquals(setOf("PROVIDER:odsay:411"), mapped.from?.stableIds("odsay"))
        val textualDirection = map(leg().put("wayCode", "2")).rideLegs.single()
        assertEquals("DOWN", textualDirection.directionCode)
        assertNull(map(leg().put("wayCode", 1.5)).rideLegs.single().directionCode)
    }

    @Test
    fun `중복 순번 누락 역명충돌 ID충돌이 있는 경유역은 시간표와 연결하지 않는다`() {
        for (mutation in listOf("duplicate", "gap", "id", "name", "fraction")) {
            val input = stops(leg())
            val first = input.path("passStopList").path("stations")[1] as ObjectNode
            when (mutation) {
                "duplicate" -> first.put("index", 1)
                "gap" -> first.put("index", 5)
                "id" -> first.put("stationID", 999)
                "name" -> first.put("stationName", "다른역")
                "fraction" -> first.put("index", 0.5)
            }
            val mapped = map(input).rideLegs.single()
            assertNull(mapped.line?.seoulSubwayLine, mutation)
            assertNull(mapped.nextStopName, mutation)
        }
    }

    @Test
    fun `ODsay 매핑 여정을 시간표 보정기로 넘기며 공급자 ID를 보존한다`() {
        val journey = map(stops(leg()))
        val queries = mutableListOf<TransitTimetableQuery>()
        val provider = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                queries += query
                return listOf(TransitLegTimetable("fixture-timetable", query.line, LocalDate.parse("2026-10-11"),
                    "4012", "DOWN", TransitServiceClass.LOCAL, "0411", "0433", "오이도",
                    now.plusSeconds(120), now.plusSeconds(1500), now))
            }
        }
        val enriched = TransitJourneyTimetableEnricher(listOf(provider)).enrich(journey, now, 180)
        assertEquals(1, queries.size)
        assertEquals("창동", queries.single().nextStopName)
        assertEquals("DOWN", queries.single().directionCode)
        assertEquals(TransitLegTimingBasis.TIMETABLE, enriched.rideLegs.single().timingBasis)
        assertEquals("odsay", enriched.provider)
        assertEquals(journey.rideLegs.single().from, enriched.rideLegs.single().from)
        assertEquals(journey.rideLegs.single().line, enriched.rideLegs.single().line)
        assertEquals(now.plusSeconds(1500), enriched.arrivalAt)
        val rejected = TransitJourneyTimetableEnricher(listOf(provider)).enrich(map(leg(4, "수도권 2호선")), now, 180)
        assertNull(rejected.rideLegs.single().timetable)
        assertEquals(1, queries.size)
    }

    @Test
    fun `2호선은 ODsay 방향 대신 검증한 다음 역을 시간표에 넘기며 미확인 방향은 만들지 않는다`() {
        val input = stops(leg(2)).apply {
            put("startID", 226).put("startName", "사당").put("endID", 230).put("endName", "신림")
            val stations = path("passStopList").path("stations")
            (stations[1] as ObjectNode).put("stationID", 226).put("stationName", "사당")
            (stations[2] as ObjectNode).put("stationID", 227).put("stationName", "낙성대")
            (stations[0] as ObjectNode).put("stationID", 230).put("stationName", "신림")
        }
        val journey = map(input)
        val queries = mutableListOf<TransitTimetableQuery>()
        val provider = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                queries += query
                return listOf(TransitLegTimetable("fixture-timetable", query.line, LocalDate.parse("2026-10-11"),
                    "2012", "UP", TransitServiceClass.LOCAL, "0226", "0230", "성수",
                    now.plusSeconds(120), now.plusSeconds(600), now))
            }
        }
        val enriched = TransitJourneyTimetableEnricher(listOf(provider)).enrich(journey, now, 180)
        assertNull(queries.single().directionCode)
        assertEquals("낙성대", queries.single().nextStopName)
        assertEquals("UP", enriched.rideLegs.single().directionCode)
        assertEquals(TransitLegTimingBasis.TIMETABLE, enriched.rideLegs.single().timingBasis)
        assertNull(map(leg(2)).rideLegs.single().directionCode)
        assertNull(map(leg(2)).rideLegs.single().nextStopName)
    }
}
