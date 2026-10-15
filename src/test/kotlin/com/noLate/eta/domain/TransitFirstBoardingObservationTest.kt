package com.noLate.eta.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant

class TransitFirstBoardingObservationTest {
    private val now = Instant.parse("2026-10-04T06:00:00Z")

    @ParameterizedTest
    @CsvSource("0,true", "120,true", "121,false", "181,false", "-61,false")
    fun `차량 원천시각과 평가시각을 현재 API 조회 시점에서 다시 확인한다`(seconds: Long, expected: Boolean) {
        assertEquals(expected, observation().isFreshAt(now.plusSeconds(seconds), 120))
    }

    @Test
    fun `관측이 신선해도 조회 후 접근시간 때문에 탈 수 없으면 숨긴다`() {
        val observation = observation().copy(expectedBoardingAt = now.plusSeconds(200))

        assertEquals(true, observation.isFreshAt(now.plusSeconds(20), 120))
        assertEquals(false, observation.isFreshAt(now.plusSeconds(21), 120))
        assertEquals(false, observation.isFreshAt(now.plusSeconds(20).plusMillis(1), 120))
    }

    private fun observation() = TransitFirstBoardingObservation(
        provider = "seoul-openapi", stationName = "서울역", lineName = "4호선",
        departureAt = now, readyAt = now.plusSeconds(180), expectedBoardingAt = now.plusSeconds(360),
        sourceUpdatedAt = now, evaluatedAt = now,
    )
}
