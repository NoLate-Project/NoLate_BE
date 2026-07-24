package com.noLate.schedule.infrastructure

import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FallbackTrafficClientTest {

    private val client = FallbackTrafficClient()

    @Test
    fun `selected ETA가 canonical fallback과 다르면 canonical을 우선한다`() {
        val request = trafficRequest(
            fallbackTravelMinutes = 30,
            selectedRouteTravelMinutes = 42,
        )

        val result = client.getTravelMinutes(request)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertTrue(result.stale)
        assertNull(result.fetchedAt)
        assertTrue(result.failureReason.orEmpty().contains("비활성화"))
    }

    @Test
    fun `selected ETA가 canonical과 일치하면 선택 경로 provenance를 유지한다`() {
        val request = trafficRequest(
            fallbackTravelMinutes = 42,
            selectedRouteTravelMinutes = 42,
        )

        val result = client.getTravelMinutes(request)

        assertEquals(42, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
    }

    @Test
    fun `선택 경로 ETA가 없으면 일정에 저장된 이동 시간을 사용한다`() {
        val request = trafficRequest(
            fallbackTravelMinutes = 30,
            selectedRouteTravelMinutes = null,
        )

        val result = client.getTravelMinutes(request)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertTrue(result.stale)
    }

    @Test
    fun `fallback 사유의 provider 원문은 저장 가능한 안정 코드로 치환한다`() {
        val request = trafficRequest(
            fallbackTravelMinutes = 30,
            selectedRouteTravelMinutes = null,
            liveRefreshBlockedReason =
                "GET http://provider.internal/routes?startX=127.1&startY=37.1 failed",
        )

        val result = client.getTravelMinutes(request)

        assertTrue(result.failureReason.orEmpty().startsWith("ETA_FALLBACK:"))
        assertTrue(!result.failureReason.orEmpty().contains("provider.internal"))
        assertTrue(!result.failureReason.orEmpty().contains("127.1"))
    }

    @Test
    fun `TrafficRequest는 canonical과 selected ETA에 공통 제품 상한을 적용한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            trafficRequest(
                fallbackTravelMinutes = 2_000,
                selectedRouteTravelMinutes = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            trafficRequest(
                fallbackTravelMinutes = 30,
                selectedRouteTravelMinutes = 2_000,
            )
        }
    }

    private fun trafficRequest(
        fallbackTravelMinutes: Int,
        selectedRouteTravelMinutes: Int?,
        liveRefreshBlockedReason: String? = null,
    ) = TrafficRequest(
        originLat = 37.1,
        originLng = 127.1,
        destinationLat = 37.2,
        destinationLng = 127.2,
        travelMode = ScheduleTravelMode.CAR,
        fallbackTravelMinutes = fallbackTravelMinutes,
        selectedRouteTravelMinutes = selectedRouteTravelMinutes,
        liveRefreshBlockedReason = liveRefreshBlockedReason,
    )
}
