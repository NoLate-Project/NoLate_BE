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
}
