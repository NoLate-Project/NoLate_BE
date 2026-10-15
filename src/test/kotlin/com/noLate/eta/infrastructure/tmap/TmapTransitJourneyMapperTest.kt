package com.noLate.eta.infrastructure.tmap

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLegTimingBasis
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.transit.domain.TransitCityCodeNamespace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant

class TmapTransitJourneyMapperTest {
    private val objectMapper = jacksonObjectMapper()
    private val mapper = TmapTransitJourneyMapper()
    private val departureAt = Instant.parse("2026-10-04T03:00:37.123Z")
    private val fetchedAt = Instant.parse("2026-10-04T02:59:41Z")

    @Test
    fun `복수 후보의 도보 메타데이터는 여정별로 보존한다`() {
        val payload = response()
        val itineraries = payload.path("metaData").path("plan").path("itineraries") as ArrayNode
        val first = itineraries[0] as ObjectNode
        val walk = first.path("legs")[0] as ObjectNode
        walk.put("distance", 128)
        walk.set<ArrayNode>("steps", objectMapper.readTree("""[{"distance":128,
            "linestring":"127.001,37.501 127.002,37.502"}]""") as ArrayNode)
        itineraries.add(first.deepCopy())
        itineraries.add(first.deepCopy())

        val journeys = mapper.map(payload, request(), fetchedAt)

        // 공급자가 여러 후보를 반환하는 것은 정상이다. 도보 개수/증거는 후보별로 확인한다.
        assertEquals(3, journeys.size)
        journeys.forEach { journey ->
            assertEquals(2, journey.legs.count { it.mode == TransitLegMode.WALK })
            assertEquals(128L, journey.legs.first().walkingGeometry?.distanceMeters)
            assertEquals(com.noLate.eta.domain.TransitWalkingPathStatus.PROVIDED,
                journey.legs.first().walkingGeometry?.pathEvidence?.status)
            assertEquals(listOf(31L, 65L, 29L), journey.legs.map { it.durationSeconds })
        }
    }

    @Test
    fun `상세 도보 증거를 보존하지만 명목 경계와 소요초를 대체하지 않는다`() {
        val payload = response()
        val walk = itinerary(payload).path("legs")[0] as ObjectNode
        walk.put("distance", 128)
        walk.set<ObjectNode>("start", objectMapper.createObjectNode().put("lat", 37.5).put("lon",127.0))
        walk.set<ArrayNode>("steps", objectMapper.readTree("""[{"distance":128,"description":"노원역 10번출구 경유",
            "linestring":"127.001,37.501 127.002,37.502"}]""") as ArrayNode)
        val route = mapper.map(payload, request(), fetchedAt).single()
        val geometry = requireNotNull(route.legs[0].walkingGeometry)
        assertEquals(com.noLate.eta.domain.TransitWalkingEndpointBasis.PROVIDER_LEG_ENDPOINTS, geometry.endpointBasis)
        assertEquals(com.noLate.eta.domain.TransitWalkingCoordinate(37.5,127.0), geometry.from)
        assertEquals(com.noLate.eta.domain.TransitWalkingCoordinate(37.501,127.001),
            geometry.pathEvidence?.steps?.single()?.coordinates?.first())
        assertEquals(com.noLate.eta.domain.TransitStationInteriorCoverage.UNKNOWN, geometry.pathEvidence?.stationInteriorCoverage)
        assertEquals(listOf(31L,65L,29L),route.legs.map { it.durationSeconds })
        assertEquals(departureAt.plusSeconds(125),route.arrivalAt)
        // 선택 진단 메타데이터 오류 때문에 정상 경로를 버리거나 도보를 0으로 만들지 않는다.
        walk.set<ArrayNode>("steps", objectMapper.readTree("""[{"linestring":"bad"}]""") as ArrayNode)
        val invalid = mapper.map(payload,request(),fetchedAt).single()
        assertEquals(com.noLate.eta.domain.TransitWalkingPathStatus.INVALID, invalid.legs[0].walkingGeometry?.pathEvidence?.status)
        assertEquals(route.arrivalAt, invalid.arrivalAt)
        assertEquals(route.legs.map { it.durationSeconds }, invalid.legs.map { it.durationSeconds })
    }

    @Test
    fun `도보 거리와 경계 좌표는 원문만 보존하고 승차 좌표나 요청 좌표로 메우지 않는다`() {
        val payload = response()
        val walk = itinerary(payload).path("legs")[0] as ObjectNode
        walk.put("distance", 25)
        walk.set<ObjectNode>("start", objectMapper.createObjectNode().put("lat", "37.5").put("lon", 127.0))
        walk.set<ObjectNode>("end", objectMapper.createObjectNode().put("lat", 37.501).put("lon", "127.001"))
        val route = mapper.map(payload, request(), fetchedAt).single()
        assertEquals(25L, route.legs[0].walkingGeometry?.distanceMeters)
        assertEquals(com.noLate.eta.domain.TransitWalkingCoordinate(37.5, 127.0), route.legs[0].walkingGeometry?.from)
        assertEquals(com.noLate.eta.domain.TransitWalkingCoordinate(37.501, 127.001), route.legs[0].walkingGeometry?.to)
        assertNull(route.rideLegs.single().walkingGeometry)
        assertNull(route.legs[2].walkingGeometry)
        assertEquals(listOf(31L, 65L, 29L), route.legs.map { it.durationSeconds })
        assertEquals(departureAt.plusSeconds(125), route.arrivalAt)

        walk.put("distance", -1)
        (walk.path("start") as ObjectNode).put("lat", 91)
        (walk.path("end") as ObjectNode).put("lon", "NaN")
        assertNull(mapper.map(payload, request(), fetchedAt).single().legs[0].walkingGeometry)
    }

    @ParameterizedTest
    @CsvSource("4,수도권 4호선,1004", "2,수도권 2호선,1002", "120,수도권 9호선(급행),1009", "4,부산 4호선,NONE", "4,수도권 2호선,NONE", "6,수도권 6호선,NONE", "10,수도권 2호선,NONE", "74,4호선,NONE", "126,수도권 4호선,NONE")
    fun `서울 실시간 노선 연결은 공식 type과 노선명이 모두 맞아야 한다`(type: Int, name: String, expected: String) {
        val response = response()
        ride(response).put("mode", "SUBWAY").put("type", type).put("route", name)

        val line = mapper.map(response, request(), fetchedAt).single().rideLegs.single().line

        assertEquals(expected.takeUnless { it == "NONE" }, line?.seoulSubwayLine?.realtimeId)
    }

    @Test
    fun `짧은 경로도 초 단위 원문으로 예상 시각을 만들고 분 올림을 누적하지 않는다`() {
        val response = response()
        ride(response).apply {
            put("waitingTime", 9)
            put("scheduledDepartureAt", "2026-10-04T06:00:00Z")
            put("directionName", "양재역 방면")
            put("directionCode", "DOWN")
        }

        val journey = mapper.map(response, request(), fetchedAt).single()

        assertEquals("tmap", journey.provider)
        assertEquals(departureAt, journey.requestedDepartureAt)
        assertEquals(departureAt, journey.departureAt)
        assertEquals(departureAt.plusSeconds(125), journey.arrivalAt)
        assertEquals(fetchedAt, journey.fetchedAt)
        assertEquals(3, journey.totalMinutes)
        assertEquals(listOf(1, 2, 1), journey.legs.map { it.durationMinutes })
        assertEquals(listOf(31L, 65L, 29L), journey.legs.map { it.durationSeconds })
        assertEquals(125L, journey.legs.sumOf { it.movementDurationSeconds })
        assertEquals(listOf(0, 1, 2), journey.legs.map { it.sequence })
        assertEquals(
            listOf(TransitLegMode.WALK, TransitLegMode.BUS, TransitLegMode.WALK),
            journey.legs.map { it.mode },
        )
        assertEquals(
            listOf(departureAt, departureAt.plusSeconds(31), departureAt.plusSeconds(96)),
            journey.legs.map { it.scheduledDepartureAt },
        )
        assertEquals(
            listOf(departureAt.plusSeconds(31), departureAt.plusSeconds(96), departureAt.plusSeconds(125)),
            journey.legs.map { it.scheduledArrivalAt },
        )
        assertTrue(journey.legs.all { it.timingBasis == TransitLegTimingBasis.ROUTE_ESTIMATE })
        assertTrue(journey.legs.all { it.waitingMinutes == null })

        val bus = journey.rideLegs.single()
        assertEquals("11504001", bus.line?.providerRouteId)
        assertEquals("간선:400", bus.line?.name)
        assertEquals(TransitServiceClass.UNKNOWN, bus.line?.serviceClass)
        assertEquals("지하철2호선강남역(중)", bus.from?.name)
        assertEquals("일동제약사거리", bus.to?.name)
        assertEquals(setOf("PROVIDER:tmap:772608"), bus.from?.stableIds(journey.provider))
        assertEquals(setOf("PROVIDER:tmap:771820"), bus.to?.stableIds(journey.provider))
        assertEquals(TransitCityCodeNamespace.UNKNOWN, bus.from?.cityCodeNamespace)
        assertNull(bus.from?.localStopId)
        assertNull(bus.from?.cityCode)
        assertNull(bus.from?.arsId)
        assertEquals("양재역 방면", bus.directionName)
        assertEquals("DOWN", bus.directionCode)
    }

    @Test
    fun `대체 Lane의 첫 노선 ID 이름 운행여부를 주 노선에 섞지 않는다`() {
        val response = response()
        ride(response).set<ArrayNode>("Lane", objectMapper.createArrayNode().add(
            objectMapper.createObjectNode()
                .put("routeId", "2247001")
                .put("route", "간선:542")
                .put("service", 0)
                .put("type", 120),
        ))

        val bus = mapper.map(response, request(), fetchedAt).single().rideLegs.single()

        assertEquals("11504001", bus.line?.providerRouteId)
        assertEquals("간선:400", bus.line?.name)
        assertEquals(TransitServiceClass.UNKNOWN, bus.line?.serviceClass)
        assertNull(bus.directionName)
        assertNull(bus.directionCode)
    }

    @Test
    fun `미운행 또는 운행 여부 미확인 구간은 다른 Lane이 운행해도 제외한다`() {
        val invalidServices = listOf("0", "null", "true", "2", "0.5", "\"running\"", "{}")
        invalidServices.forEach { service ->
            val response = response()
            ride(response).set<JsonNode>("service", objectMapper.readTree(service))
            ride(response).set<ArrayNode>("lane", objectMapper.createArrayNode().add(
                objectMapper.createObjectNode().put("routeId", "alternative").put("service", 1),
            ))
            assertTrue(mapper.map(response, request(), fetchedAt).isEmpty(), service)
        }
        val response = response()
        ride(response).remove("service")
        assertTrue(mapper.map(response, request(), fetchedAt).isEmpty())
    }

    @Test
    fun `지하철 종별은 명시된 공식 type만 분류하고 누락 예약 코드는 UNKNOWN이다`() {
        val classes = listOf(
            2 to TransitServiceClass.LOCAL,
            9 to TransitServiceClass.LOCAL,
            101 to TransitServiceClass.LOCAL,
            125 to TransitServiceClass.LOCAL,
            117 to TransitServiceClass.EXPRESS,
            118 to TransitServiceClass.EXPRESS,
            120 to TransitServiceClass.EXPRESS,
            124 to TransitServiceClass.EXPRESS,
            12 to TransitServiceClass.UNKNOWN,
            126 to TransitServiceClass.UNKNOWN,
            -1 to TransitServiceClass.UNKNOWN,
        )
        classes.forEach { (type, expected) ->
            val response = response()
            ride(response).put("mode", "SUBWAY").put("route", "수도권 9호선").put("type", type)
            assertEquals(
                expected,
                mapper.map(response, request(), fetchedAt).single().rideLegs.single().line?.serviceClass,
                "type=$type",
            )
        }
        val response = response()
        ride(response).put("mode", "SUBWAY").put("route", "수도권 9호선(급행)").remove("type")
        assertEquals(
            TransitServiceClass.UNKNOWN,
            mapper.map(response, request(), fetchedAt).single().rideLegs.single().line?.serviceClass,
        )
    }

    @Test
    fun `정류장 배열은 명시 index로 정렬하고 문서의 stations 표기도 허용한다`() {
        val response = response()
        val passStops = ride(response).path("passStopList") as ObjectNode
        val original = passStops.remove("stationList") as ArrayNode
        passStops.set<ArrayNode>("stations", objectMapper.createArrayNode().add(original[1]).add(original[0]))

        val bus = mapper.map(response, request(), fetchedAt).single().rideLegs.single()

        assertEquals("772608", bus.from?.providerStopId)
        assertEquals("771820", bus.to?.providerStopId)
    }

    @Test
    fun `정류장 ID가 없으면 이름만 보존하고 ID나 방향을 만들지 않는다`() {
        val response = response()
        ride(response).remove("passStopList")
        val bus = mapper.map(response, request(), fetchedAt).single().rideLegs.single()

        assertEquals("강남역", bus.from?.name)
        assertEquals("일동제약사거리", bus.to?.name)
        assertNull(bus.from?.providerStopId)
        assertNull(bus.to?.providerStopId)
        assertNull(bus.directionName)
        assertNull(bus.directionCode)
    }

    @Test
    fun `숫자 ID는 문자열로 보존하지만 비문자 이름 방향은 추정하지 않는다`() {
        val response = response()
        val ride = ride(response)
        ride.put("routeId", 11504001).put("route", 400).put("directionName", 1)
        val firstStop = ride.path("passStopList").path("stationList")[0] as ObjectNode
        firstStop.put("stationID", 772608).put("stationName", 123)
        (ride.path("start") as ObjectNode).put("name", 456)

        val bus = mapper.map(response, request(), fetchedAt).single().rideLegs.single()

        assertEquals("11504001", bus.line?.providerRouteId)
        assertEquals("772608", bus.from?.providerStopId)
        assertNull(bus.line?.name)
        assertNull(bus.from?.name)
        assertNull(bus.directionName)
    }

    @Test
    fun `시간 누락 음수 소수 비수치 overflow와 전체 시간 불일치를 제외한다`() {
        val invalidNumbers = listOf("null", "-1", "1.5", "true", "{}", "\"NaN\"", "9223372036854775808")
        invalidNumbers.forEach { number ->
            val invalidTotal = response()
            itinerary(invalidTotal).set<JsonNode>("totalTime", objectMapper.readTree(number))
            assertTrue(mapper.map(invalidTotal, request(), fetchedAt).isEmpty(), "total=$number")
            val invalidSection = response()
            ride(invalidSection).set<JsonNode>("sectionTime", objectMapper.readTree(number))
            assertTrue(mapper.map(invalidSection, request(), fetchedAt).isEmpty(), "section=$number")
        }
        listOf(0, 124, 126, 601).forEach { total ->
            val response = response()
            itinerary(response).put("totalTime", total)
            assertTrue(mapper.map(response, request(), fetchedAt).isEmpty(), "total=$total")
        }
        val response = response()
        ride(response).remove("sectionTime")
        assertTrue(mapper.map(response, request(), fetchedAt).isEmpty())
        assertTrue(mapper.map(response(), request().copy(maxTravelMinutes = 2), fetchedAt).isEmpty())
        assertTrue(mapper.map(response(), request().copy(departureAt = Instant.MAX), fetchedAt).isEmpty())
    }

    @Test
    fun `정수 문자열도 단위를 바꾸지 않고 읽으며 최대시간 경계를 지킨다`() {
        val response = response()
        itinerary(response).put("totalTime", "125")
        ride(response).put("sectionTime", "65").put("service", "1")
        val journey = mapper.map(response, request().copy(maxTravelMinutes = 3), fetchedAt).single()

        assertEquals(3, journey.totalMinutes)
        assertEquals(departureAt.plusSeconds(125), journey.arrivalAt)
    }

    @Test
    fun `승차 구간의 식별정보 손상과 지원하지 않는 교통수단은 후보 전체를 제외한다`() {
        val missingLine = response()
        ride(missingLine).remove(listOf("routeId", "route"))
        assertTrue(mapper.map(missingLine, request(), fetchedAt).isEmpty())

        val missingStop = response()
        ride(missingStop).remove(listOf("passStopList", "start"))
        assertTrue(mapper.map(missingStop, request(), fetchedAt).isEmpty())

        val modes = listOf("AIRPLANE", "FERRY", "OTHER", "WALK")
        modes.forEach { mode ->
            val response = response()
            ride(response).put("mode", mode)
            assertTrue(mapper.map(response, request(), fetchedAt).isEmpty(), mode)
        }

        val duplicateIndex = response()
        val stations = ride(duplicateIndex).path("passStopList").path("stationList") as ArrayNode
        (stations[1] as ObjectNode).put("index", 0)
        assertTrue(mapper.map(duplicateIndex, request(), fetchedAt).isEmpty())

        val malformedStations = response()
        (ride(malformedStations).path("passStopList") as ObjectNode).put("stationList", "invalid")
        assertTrue(mapper.map(malformedStations, request(), fetchedAt).isEmpty())
    }

    @Test
    fun `정상 후보만 예상 도착순으로 반환하고 손상된 envelope는 빈 결과다`() {
        val response = response()
        val itineraries = response.path("metaData").path("plan").path("itineraries") as ArrayNode
        val faster = itinerary(response).deepCopy()
        faster.put("totalTime", 120)
        ((faster.path("legs") as ArrayNode)[1] as ObjectNode).put("sectionTime", 60)
        itineraries.add(faster)
        itineraries.add(objectMapper.createObjectNode().put("totalTime", 1))

        assertEquals(
            listOf(departureAt.plusSeconds(120), departureAt.plusSeconds(125)),
            mapper.map(response, request(), fetchedAt).map { it.arrivalAt },
        )
        listOf("{}", "null", "[]", "{\"metaData\":{\"plan\":{\"itineraries\":{}}}}").forEach { payload ->
            assertTrue(mapper.map(objectMapper.readTree(payload), request(), fetchedAt).isEmpty())
        }
    }

    @Test
    fun `공급자 오류 응답은 경로로 취급하거나 공급자 메시지를 예외에 노출하지 않는다`() {
        listOf(
            """{"result":{"status":14,"message":"sensitive provider response"}}""",
            """{"result":{"status":31,"message":"sensitive provider response"}}""",
            """{"error":{"message":"sensitive provider response"}}""",
        ).forEach { payload ->
            val error = assertThrows(IllegalStateException::class.java) {
                mapper.map(objectMapper.readTree(payload), request(), fetchedAt)
            }
            assertEquals("TMAP이 경로 조회 오류를 반환했습니다.", error.message)
        }
    }

    @Test
    fun `열차와 고속 시외버스는 수단을 보존하되 시간표로 승격하지 않는다`() {
        listOf("TRAIN" to TransitLegMode.TRAIN, "EXPRESSBUS" to TransitLegMode.COACH).forEach { (mode, expected) ->
            val payload = response()
            ride(payload).put("mode", mode).put("type", 999)
            val leg = mapper.map(payload, request(), fetchedAt).single().legs[1]
            assertEquals(expected, leg.mode)
            assertEquals(TransitLegTimingBasis.ROUTE_ESTIMATE, leg.timingBasis)
            assertTrue(leg.isRide)
        }
        listOf(16 to TransitLegMode.INTERCITY_BUS, 22 to TransitLegMode.INTERCITY_BUS,
            23 to TransitLegMode.EXPRESS_BUS).forEach { (type, expected) ->
            val payload = response()
            ride(payload).put("mode", "EXPRESSBUS").put("type", type)
            assertEquals(expected, mapper.map(payload, request(), fetchedAt).single().legs[1].mode)
        }
    }

    @Test
    fun `실제 수집한 서울 공주 Tmap 응답은 열차와 고속버스 추정 구간을 빠뜨리지 않는다`() {
        val payload = objectMapper.readTree(requireNotNull(javaClass.getResourceAsStream("/eta/intercity-tmap-20261012.json")))
        val journeys = mapper.map(payload, request().copy(maxTravelMinutes = 1440), fetchedAt)
        // One captured candidate has a city bus with service=0 and remains excluded.
        assertEquals(9, journeys.size)
        assertTrue(journeys.any { it.legs.any { leg -> leg.mode == TransitLegMode.TRAIN } })
        assertTrue(journeys.any { it.legs.any { leg -> leg.mode == TransitLegMode.EXPRESS_BUS } })
        assertTrue(journeys.flatMap { it.legs }.all { it.timingBasis == TransitLegTimingBasis.ROUTE_ESTIMATE })
    }

    private fun response(): ObjectNode = objectMapper.readTree(
        """
            {
              "metaData": {
                "plan": {
                  "itineraries": [{
                    "totalTime": 125,
                    "legs": [
                      {"mode":"WALK","sectionTime":31},
                      {
                        "mode":"BUS","sectionTime":65,"service":1,
                        "route":"간선:400","routeId":"11504001","type":11,
                        "start":{"name":"강남역"},
                        "end":{"name":"일동제약사거리"},
                        "passStopList":{"stationList":[
                          {"index":0,"stationID":"772608","stationName":"지하철2호선강남역(중)"},
                          {"index":1,"stationID":"771820","stationName":"일동제약사거리"}
                        ]}
                      },
                      {"mode":"WALK","sectionTime":29}
                    ]
                  }]
                }
              }
            }
        """.trimIndent(),
    ) as ObjectNode

    private fun itinerary(response: JsonNode): ObjectNode =
        response.path("metaData").path("plan").path("itineraries")[0] as ObjectNode

    private fun ride(response: JsonNode): ObjectNode = itinerary(response).path("legs")[1] as ObjectNode

    private fun request() = TransitJourneySearchRequest(
        originLat = 37.50458,
        originLng = 127.02479,
        destinationLat = 37.47910,
        destinationLng = 127.03747,
        departureAt = departureAt,
        maxTravelMinutes = 10,
    )
}
