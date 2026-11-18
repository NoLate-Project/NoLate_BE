package com.noLate.schedule.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.schedule.domain.ScheduleTravelMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class SelectedRouteMetadataTest {
    @Test
    fun `이전 일정에 관측된 버스는 다음 날짜의 출발시각에 재사용하지 않는다`() {
        val original = Instant.parse("2026-11-17T21:50:00Z")
        val oldDeparture = original.minusSeconds(663)
        val next = original.plusSeconds(86_400)
        val route = objectMapper.readTree("""
            {"provider":"odsay","minutes":15,"transitLegs":[{"kind":"WALK"},{"kind":"BUS"},{"kind":"WALK"}],
             "routeInfo":{"provider":"odsay","timeBasis":"estimated","providerTimeBasis":"route_estimate",
               "totalDurationMinutes":12,"totalDurationSeconds":663,"departureTime":"$oldDeparture",
               "arrivalTime":"$original","busPreviewObservation":{"targetArrivalAt":"$original",
                 "sourceUpdatedAt":"${oldDeparture.minusSeconds(60)}"}}}
        """.trimIndent())
        assertEquals(oldDeparture, SelectedRouteMetadata.guardSavedDeparture(
            original, oldDeparture, 12, ScheduleTravelMode.TRANSIT, route))
        assertEquals(next.minusSeconds(900), SelectedRouteMetadata.guardSavedDeparture(
            next, oldDeparture, 12, ScheduleTravelMode.TRANSIT, route))
    }

    private val objectMapper = ObjectMapper()

    @Test
    fun `저장 출발 보호는 안전한 이른 시각과 다른 이동수단 시각을 변경하지 않는다`() {
        val target = Instant.parse("2026-11-15T01:00:00Z")
        val early = target.minusSeconds(80 * 60)
        val late = target.minusSeconds(4 * 60)
        assertEquals(early, SelectedRouteMetadata.guardSavedDeparture(target, early, 65, ScheduleTravelMode.TRANSIT, null))
        assertEquals(target.minusSeconds(65 * 60), SelectedRouteMetadata.guardSavedDeparture(target, late, 65, ScheduleTravelMode.TRANSIT, null))
        assertEquals(late, SelectedRouteMetadata.guardSavedDeparture(target, late, 65, ScheduleTravelMode.CAR, null))
        assertNull(SelectedRouteMetadata.guardSavedDeparture(target, target, null, ScheduleTravelMode.TRANSIT, null))
    }

    @Test
    fun `일정과 어긋난 ODsay 조회를 일정 추정으로 재구성하고 이전 열차 시각은 제거한다`() {
        val cases = listOf(
            Triple("2026-11-07T01:00:00Z", "2026-11-07T00:56:00Z", 65),
            Triple("2026-11-01T06:20:00Z", "2026-11-03T08:35:00Z", 73),
        )
        for ((targetText, oldDepartureText, minutes) in cases) {
            val target = Instant.parse(targetText)
            val oldDeparture = Instant.parse(oldDepartureText)
            val oldArrival = oldDeparture.plusSeconds(minutes * 60L)
            val raw = objectMapper.readTree("""
                {"provider":"odsay","providerDepartureAt":"$oldDeparture","providerArrivalAt":"$oldArrival",
                 "transitDepartureAt":"$oldDeparture","odsayTimetablePreview":{"rides":[]},
                 "transitLegs":[{"kind":"SUBWAY","startID":"433","startDateTime":"$oldDeparture"}],
                 "routeInfo":{"provider":"odsay","timeBasis":"provider_schedule","departureTime":"$oldDeparture",
                    "arrivalTime":"$oldArrival","totalDurationMinutes":$minutes,
                    "steps":[{"type":"SUBWAY","scheduledRide":{"boardingAt":"$oldDeparture"}}]}}
            """.trimIndent())
            val departure = SelectedRouteMetadata.guardSavedDeparture(target, oldDeparture, minutes, ScheduleTravelMode.TRANSIT, raw)
            val aligned = requireNotNull(SelectedRouteMetadata.alignOdsayRouteTiming(
                target, departure, minutes, ScheduleTravelMode.TRANSIT, raw,
            ))
            assertEquals(target.minusSeconds(minutes * 60L), departure)
            assertEquals(departure.toString(), aligned.at("/routeInfo/departureTime").asText())
            assertEquals(targetText, aligned.at("/routeInfo/arrivalTime").asText())
            assertEquals("estimated", aligned.at("/routeInfo/timeBasis").asText())
            assertTrue(aligned.at("/routeInfo/steps/0/scheduledRide").isMissingNode)
            assertTrue(aligned.at("/transitLegs/0/startDateTime").isMissingNode)
            assertTrue(aligned.path("providerDepartureAt").isMissingNode)
            assertEquals("433", aligned.at("/transitLegs/0/startID").asText())
            assertEquals(oldDepartureText, raw.at("/routeInfo/departureTime").asText())
            assertTrue(aligned === SelectedRouteMetadata.alignOdsayRouteTiming(
                target, departure, minutes, ScheduleTravelMode.TRANSIT, aligned,
            ))
        }
    }

    @Test
    fun `일치하는 ODsay 추정 초가 있으면 출발을 분 단위로 되돌리지 않는다`() {
        val target = Instant.parse("2026-11-15T13:40:00Z")
        val departure = target.minusSeconds(877)
        val early = departure.minusSeconds(90)
        val json = """{"provider":"odsay","routeInfo":{"provider":"odsay","timeBasis":"estimated",
            "providerTimeBasis":"route_estimate","totalDurationMinutes":15,"totalDurationSeconds":877,
            "departureTime":"$departure","arrivalTime":"$target","steps":[]}}"""
        val metadata = SelectedRouteMetadata.parse(objectMapper, json, ScheduleTravelMode.TRANSIT)
        val route = objectMapper.readTree(json)

        assertEquals(877L, metadata.odsayEstimatedDurationSeconds)
        assertNull(metadata.odsayTimetable)
        assertEquals(departure, SelectedRouteMetadata.guardSavedDeparture(target, departure, 15, ScheduleTravelMode.TRANSIT, route))
        assertEquals(departure, SelectedRouteMetadata.guardSavedDeparture(target, null, 15, ScheduleTravelMode.TRANSIT, route))
        assertEquals(early, SelectedRouteMetadata.guardSavedDeparture(target, early, 15, ScheduleTravelMode.TRANSIT, route))
        assertEquals(target.minusSeconds(900), SelectedRouteMetadata.guardSavedDeparture(target, departure, 15,
            ScheduleTravelMode.TRANSIT, objectMapper.readTree(json.replace("877", "876"))))
    }

    @Test
    fun `ODsay 시간표는 원시 소요시간 대신 일치하는 초와 열차 시각을 사용한다`() {
        val target = Instant.parse("2026-11-16T09:06:00Z")
        val departure = target.minusSeconds(1771)
        val json = savedOdsayTimetableFixture(departure, target)
        val metadata = SelectedRouteMetadata.parse(objectMapper, json, ScheduleTravelMode.TRANSIT)

        assertEquals(30, metadata.travelMinutes)
        val validRoute = objectMapper.readTree(json)
        assertTrue(validRoute === SelectedRouteMetadata.alignOdsayRouteTiming(
            target, departure, 30, ScheduleTravelMode.TRANSIT, validRoute,
        ))
        assertEquals(departure, SelectedRouteMetadata.guardSavedDeparture(
            target, departure, 30, ScheduleTravelMode.TRANSIT, objectMapper.readTree(json),
        ))
        assertEquals(departure, SelectedRouteMetadata.guardSavedDeparture(
            target, null, 30, ScheduleTravelMode.TRANSIT, objectMapper.readTree(json),
        ))
        assertNull(SelectedRouteMetadata.guardSavedDeparture(
            target.minusSeconds(1), null, 30, ScheduleTravelMode.TRANSIT, objectMapper.readTree(json),
        ))
        assertNull(SelectedRouteMetadata.guardSavedDeparture(
            target, null, 31, ScheduleTravelMode.TRANSIT, objectMapper.readTree(json),
        ))
        assertEquals(target.minusSeconds(1800), SelectedRouteMetadata.guardSavedDeparture(
            target, departure, 30, ScheduleTravelMode.TRANSIT, objectMapper.readTree(json.replace("1771", "1772")),
        ))
        assertEquals(target.minusSeconds(1801), SelectedRouteMetadata.guardSavedDeparture(
            target.minusSeconds(1), departure, 30, ScheduleTravelMode.TRANSIT, objectMapper.readTree(json),
        ))
        assertNull(SelectedRouteMetadata.parse(objectMapper,
            json.replace("\"serviceDate\":\"2026-11-16\"", "\"serviceDate\":\"2026-11-15\""),
            ScheduleTravelMode.TRANSIT).odsayTimetable)
    }

    @Test
    fun `저장된 마지막 하차와 목적지 도보가 맞을 때만 초 단위 출발을 보호한다`() {
        val target = Instant.parse("2026-11-16T10:26:00Z")
        val departure = Instant.parse("2026-11-16T09:33:30Z")
        val arrival = Instant.parse("2026-11-16T10:25:38Z")
        val json = savedOdsayTimetableFixture(departure, arrival)
            .replace("1771", "3128").replace("\"totalDurationMinutes\":30", "\"totalDurationMinutes\":53")
            .replace(arrival.minusSeconds(60).toString(), arrival.minusSeconds(188).toString())
            .replace("}}]", "}},{\"type\":\"WALK\",\"durationSeconds\":188},{\"type\":\"DESTINATION\"}]")
        val valid = objectMapper.readTree(json)
        assertEquals(departure, SelectedRouteMetadata.guardSavedDeparture(target, departure, 53, ScheduleTravelMode.TRANSIT, valid))
        assertEquals(arrival, SelectedRouteMetadata.parse(objectMapper, json, ScheduleTravelMode.TRANSIT).odsayTimetable?.arrivalAt)

        for (invalid in listOf("187", "240", "-1", "\"188\"")) {
            val invalidJson = json.replace("\"durationSeconds\":188", "\"durationSeconds\":$invalid")
            assertNull(SelectedRouteMetadata.parse(objectMapper, invalidJson, ScheduleTravelMode.TRANSIT).odsayTimetable)
            assertEquals(target.minusSeconds(53 * 60), SelectedRouteMetadata.guardSavedDeparture(
                target, departure, 53, ScheduleTravelMode.TRANSIT, objectMapper.readTree(invalidJson),
            ))
        }
    }

    @Test
    fun `저장된 시간표는 접근과 환승 도보를 끝내기 전에 출발하는 열차를 인정하지 않는다`() {
        val departure = Instant.parse("2026-11-22T09:30:00Z")
        val arrival = departure.plusSeconds(1771)
        fun route(accessSeconds: Int, transferSeconds: Int) = savedOdsayTimetableFixture(departure, arrival)
            .replace("\"steps\":[", "\"steps\":[{\"type\":\"WALK\",\"durationSeconds\":$accessSeconds},")
            .replace(arrival.minusSeconds(60).toString(), departure.plusSeconds(600).toString())
            .replace("}}]", """ }},{"type":"WALK","durationSeconds":$transferSeconds},
                {"type":"SUBWAY","scheduledRide":{"provider":"seoul-dated-path","serviceDate":"2026-11-22",
                "boardingAt":"${departure.plusSeconds(757)}","arrivalAt":"${arrival.minusSeconds(60)}"}}]""")
        assertEquals(arrival, SelectedRouteMetadata.parse(
            objectMapper, route(120, 157), ScheduleTravelMode.TRANSIT,
        ).odsayTimetable?.arrivalAt)
        for ((access, transfer) in listOf(121 to 157, 120 to 158)) {
            assertNull(SelectedRouteMetadata.parse(
                objectMapper, route(access, transfer), ScheduleTravelMode.TRANSIT,
            ).odsayTimetable)
        }
    }

    @Test
    fun `상세 JSON이 없어도 이동수단 경로바 구간은 유지한다`() {
        val metadata = SelectedRouteMetadata.parse(
            objectMapper,
            null,
            ScheduleTravelMode.CAR,
        )

        assertEquals(listOf("drive"), metadata.routeSegments.map { it.kind })
        assertEquals("자동차", metadata.routeSegments.single().label)
    }

    @Test
    fun `searchOption과 선택 대중교통 여정을 저장 JSON에서 복원한다`() {
        val json = """
            {
              "minutes": 41,
              "searchOption": "2",
              "selectedItinerary": {
                "totalTime": 2460,
                "transferCount": 1,
                "legs": [{"mode": "SUBWAY", "route": "2호선"}]
              }
            }
        """.trimIndent()

        val metadata = SelectedRouteMetadata.parse(objectMapper, json, ScheduleTravelMode.TRANSIT)

        assertEquals(41, metadata.travelMinutes)
        assertEquals("2", metadata.routeOption)
        assertTrue(metadata.transitItineraryJson.orEmpty().contains("\"route\":\"2호선\""))
    }

    @Test
    fun `잘못된 JSON은 선택 경로 메타데이터가 없는 것으로 안전하게 처리한다`() {
        val metadata = SelectedRouteMetadata.parse(
            objectMapper,
            """{"minutes":""",
            ScheduleTravelMode.CAR,
        )

        assertNull(metadata.travelMinutes)
        assertNull(metadata.routeOption)
        assertNull(metadata.transitItineraryJson)
    }

    @Test
    fun `routeInfo 전체 시간만 사용하고 하위 step durationMinutes는 선택하지 않는다`() {
        val metadata = SelectedRouteMetadata.parse(
            objectMapper,
            """
                {
                  "routeInfo": {
                    "totalDurationMinutes": 40,
                    "steps": [{"durationMinutes": 5}]
                  }
                }
            """.trimIndent(),
            ScheduleTravelMode.WALK,
        )

        assertEquals(40, metadata.travelMinutes)
    }

    @Test
    fun `route-level 시간이 없으면 하위 step 시간은 전체 ETA로 승격하지 않는다`() {
        val metadata = SelectedRouteMetadata.parse(
            objectMapper,
            """{"routeInfo":{"steps":[{"durationMinutes":5}]}}""",
            ScheduleTravelMode.WALK,
        )

        assertNull(metadata.travelMinutes)
    }

    @Test
    fun `과대값과 NaN selected ETA는 제품 상한 밖이므로 거부한다`() {
        val huge = SelectedRouteMetadata.parse(
            objectMapper,
            """{"minutes":2000}""",
            ScheduleTravelMode.CAR,
            maxTravelMinutes = 1_440,
        )
        val nan = SelectedRouteMetadata.parse(
            objectMapper,
            """{"minutes":"NaN"}""",
            ScheduleTravelMode.CAR,
            maxTravelMinutes = 1_440,
        )

        assertNull(huge.travelMinutes)
        assertNull(nan.travelMinutes)
    }

    @Test
    fun `fraction routeInfo 전체 시간은 올림하고 명시 경로만 읽는다`() {
        val metadata = SelectedRouteMetadata.parse(
            objectMapper,
            """
                {
                  "routeInfo": {
                    "totalDurationMinutes": 29.01,
                    "steps": [{"durationMinutes": 999}]
                  }
                }
            """.trimIndent(),
            ScheduleTravelMode.WALK,
            maxTravelMinutes = 1_440,
        )

        assertEquals(30, metadata.travelMinutes)
    }

    @Test
    fun `긴 대중교통 여정은 앞뒤 환승 순서와 단일 생략 구간을 보존한다`() {
        val metadata = SelectedRouteMetadata.parse(
            objectMapper,
            """
                {
                  "selectedItinerary": {
                    "legs": [
                      {"mode":"WALK"},
                      {"mode":"BUS", "route":"341"},
                      {"mode":"WALK"},
                      {"mode":"SUBWAY", "route":"2호선"},
                      {"mode":"WALK"},
                      {"mode":"BUS", "route":"146"}
                    ]
                  }
                }
            """.trimIndent(),
            ScheduleTravelMode.TRANSIT,
        )

        assertEquals(5, metadata.routeSegments.size)
        assertEquals(
            listOf("walk", "bus", "transfer", "walk", "bus"),
            metadata.routeSegments.map { it.kind },
        )
        assertEquals("341", metadata.routeSegments[1].label)
        assertEquals("2구간 생략", metadata.routeSegments[2].label)
        assertEquals("#9CA3AF", metadata.routeSegments[2].colorHex)
        assertEquals("146", metadata.routeSegments[4].label)
    }    @Test
    fun `only coherent anchored journey supplies a fixed departure`() {
        val journey = objectMapper.readTree("""{"version":1,"departureAt":"2026-10-14T12:00:00+09:00","arrivalAt":"2026-10-14T15:00:00+09:00","service":{"kind":"TRAIN","timingBasis":"TIMETABLE"}}""")
        val root = objectMapper.createObjectNode().put("schemaVersion", 3)
        root.set<com.fasterxml.jackson.databind.node.ObjectNode>("intercityJourney", journey)
        val info = objectMapper.createObjectNode().put("timeBasis", "anchored_journey").put("departureTime", journey.path("departureAt").asText())
        info.set<com.fasterxml.jackson.databind.node.ObjectNode>("intercityJourney", journey.deepCopy())
        root.set<com.fasterxml.jackson.databind.node.ObjectNode>("routeInfo", info)
        assertEquals(java.time.Instant.parse("2026-10-14T03:00:00Z"), SelectedRouteMetadata.parse(objectMapper, root.toString(), ScheduleTravelMode.TRANSIT).anchoredDepartureAt)
        info.put("departureTime", "2026-10-14T12:01:00+09:00")
        assertNull(SelectedRouteMetadata.parse(objectMapper, root.toString(), ScheduleTravelMode.TRANSIT).anchoredDepartureAt)
    }

}

internal fun savedOdsayTimetableFixture(departure: Instant, arrival: Instant): String {
    val date = departure.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
    return """
        {
          "provider":"odsay", "minutes":28,
          "routeInfo":{
            "provider":"odsay","timeBasis":"provider_schedule","providerTimeBasis":"timetable",
            "departureTime":"$departure","arrivalTime":"$arrival",
            "totalDurationMinutes":30,"totalDurationSeconds":1771,
            "steps":[{"type":"SUBWAY","scheduledRide":{
                "provider":"seoul-dated-path","serviceDate":"$date",
                "boardingAt":"${departure.plusSeconds(120)}","arrivalAt":"${arrival.minusSeconds(60)}"
            }}]
          }
        }
    """.trimIndent()
}
