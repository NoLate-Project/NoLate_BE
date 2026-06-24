package com.noLate.schedule.infrastructure

import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.domain.ScheduleTravelMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FallbackTrafficClientTest {

    private val client = FallbackTrafficClient()

    @Test
    fun `실시간 교통 API가 없는 환경에서는 사용자가 선택한 경로의 ETA를 우선 사용한다`() {
        val request = trafficRequest(
            fallbackTravelMinutes = 30,
            selectedRouteTravelMinutes = 42,
        )

        val result = client.getTravelMinutes(request)

        assertEquals(42, result)
    }

    @Test
    fun `선택 경로 ETA가 없으면 일정에 저장된 이동 시간을 사용한다`() {
        val request = trafficRequest(
            fallbackTravelMinutes = 30,
            selectedRouteTravelMinutes = null,
        )

        val result = client.getTravelMinutes(request)

        assertEquals(30, result)
    }

    private fun trafficRequest(
        fallbackTravelMinutes: Int,
        selectedRouteTravelMinutes: Int?,
    ) = TrafficRequest(
        originLat = 37.1,
        originLng = 127.1,
        destinationLat = 37.2,
        destinationLng = 127.2,
        travelMode = ScheduleTravelMode.CAR,
        fallbackTravelMinutes = fallbackTravelMinutes,
        selectedRouteTravelMinutes = selectedRouteTravelMinutes,
    )
}
