package com.noLate.eta.application.transit

import com.noLate.eta.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class TransitRideArrivalPredictorTest {
    private val table = TransitLegTimetable("seoul-timetable", SeoulSubwayLine.LINE_4, LocalDate.parse("2026-10-13"),
        "4012", "DOWN", TransitServiceClass.LOCAL, "0411", "0433", "사당", Instant.parse("2026-10-13T06:00:00Z"),
        Instant.parse("2026-10-13T06:48:00Z"), Instant.parse("2026-10-13T05:55:00Z"))

    @Test fun `기존 지연 전파를 독립 비교 기준으로 보존하고 학습 없이 지연을 감산하지 않는다`() {
        for (shift in listOf(-115L, 0, 215L)) {
            val prediction = TransitRideArrivalPredictor().predict(table, table.boardingAt.plusSeconds(shift))
            assertEquals(table.arrivalAt.plusSeconds(shift), prediction.arrivalAt)
            assertEquals(shift, prediction.shiftSeconds)
            assertEquals("timetable-shift-v1", prediction.modelVersion)
        }
        val fractional = TransitRideArrivalPredictor().predict(table, table.boardingAt.plusNanos(500_000_000))
        assertEquals(table.arrivalAt.plusNanos(500_000_000), fractional.arrivalAt)
    }
}
