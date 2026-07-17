package com.noLate.route.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TmapTransitTimeTest {
    @ParameterizedTest
    @CsvSource(
        "900, 15",
        "1000, 17",
        "1001, 17",
    )
    fun `TMAP transit totalTime is always converted from seconds`(
        totalTimeSeconds: Double,
        expectedMinutes: Int,
    ) {
        assertEquals(expectedMinutes, tmapTransitSecondsToMinutes(totalTimeSeconds))
    }
}
