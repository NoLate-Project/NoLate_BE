package com.noLate.transit.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransitArrivalStatusTest {
    @Test
    fun `서울 지하철 도착 코드를 사용자 상태로 변환한다`() {
        assertEquals(TransitArrivalStatus.APPROACHING, seoulSubwayArrivalStatus("0", null))
        assertEquals(TransitArrivalStatus.ARRIVED, seoulSubwayArrivalStatus("1", null))
        assertEquals(TransitArrivalStatus.DEPARTED, seoulSubwayArrivalStatus("2", null))
        assertEquals(TransitArrivalStatus.PREVIOUS_STOP, seoulSubwayArrivalStatus("5", null))
        assertEquals(TransitArrivalStatus.IN_TRANSIT, seoulSubwayArrivalStatus("99", null))
    }

    @Test
    fun `버스 남은 정류장과 초 단위로 진입 상태를 판정한다`() {
        assertEquals(
            TransitArrivalStatus.APPROACHING,
            estimatedTransitArrivalStatus(waitSeconds = 45, remainingStops = 1, message = null),
        )
        assertEquals(
            TransitArrivalStatus.APPROACHING,
            estimatedTransitArrivalStatus(waitSeconds = 180, remainingStops = 0, message = null),
        )
        assertEquals(
            TransitArrivalStatus.IN_TRANSIT,
            estimatedTransitArrivalStatus(waitSeconds = 300, remainingStops = 3, message = null),
        )
    }
}
