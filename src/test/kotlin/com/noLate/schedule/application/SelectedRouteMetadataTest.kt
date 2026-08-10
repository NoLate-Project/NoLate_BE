package com.noLate.schedule.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.schedule.domain.ScheduleTravelMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SelectedRouteMetadataTest {
    private val objectMapper = ObjectMapper()

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
    }
}
