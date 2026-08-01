package com.noLate.schedule.infrastructure

import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficProviderClient
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant

class ModeAwareTrafficClientTest {
    private val providerClient = mock<TrafficProviderClient>()
    private val transitClient = mock<TransitRealtimeTrafficClient>()
    private val client = ModeAwareTrafficClient(providerClient, transitClient)

    @Test
    fun `TMAP 활성화 여부와 무관하게 TRANSIT은 실시간 도착 계산기로 보낸다`() {
        val request = request(ScheduleTravelMode.TRANSIT)
        val expected = TrafficResult(
            travelMinutes = 37,
            source = TrafficSource.LIVE_PROVIDER,
            fetchedAt = request.evaluatedAt,
            stale = false,
        )
        whenever(transitClient.getTravelMinutes(request)).thenReturn(expected)

        assertEquals(expected, client.getTravelMinutes(request))
        verify(transitClient).getTravelMinutes(request)
        verifyNoInteractions(providerClient)
    }

    @Test
    fun `CAR WALK 등 기존 모드는 선택된 conditional provider로 보낸다`() {
        val request = request(ScheduleTravelMode.CAR)
        val expected = TrafficResult(
            travelMinutes = 31,
            source = TrafficSource.LIVE_PROVIDER,
            fetchedAt = request.evaluatedAt,
            stale = false,
        )
        whenever(providerClient.getTravelMinutes(request)).thenReturn(expected)

        assertEquals(expected, client.getTravelMinutes(request))
        verify(providerClient).getTravelMinutes(request)
        verifyNoInteractions(transitClient)
    }

    @Test
    fun `route stale 차단 사유가 있으면 어떤 외부 provider도 호출하지 않는다`() {
        val request = request(ScheduleTravelMode.TRANSIT).copy(
            liveRefreshBlockedReason = TrafficFailureReasons.ROUTE_STALE,
        )

        val result = client.getTravelMinutes(request)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertEquals(TrafficFailureReasons.ROUTE_STALE, result.failureReason)
        verify(providerClient, never()).getTravelMinutes(any())
        verify(transitClient, never()).getTravelMinutes(any())
    }

    private fun request(mode: ScheduleTravelMode) = TrafficRequest(
        originLat = 37.1,
        originLng = 127.1,
        destinationLat = 37.2,
        destinationLng = 127.2,
        travelMode = mode,
        fallbackTravelMinutes = 30,
        selectedRouteTravelMinutes = 30,
        evaluatedAt = Instant.parse("2026-07-29T03:00:00Z"),
    )
}
