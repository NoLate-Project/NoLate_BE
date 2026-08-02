package com.noLate.schedule.infrastructure

import com.noLate.eta.application.TransitEtaCalculationService
import com.noLate.eta.resilience.EtaCalculationDeadline
import com.noLate.eta.resilience.MutableEtaTicker
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant

class TransitRealtimeTrafficClientDeadlineTest {
    @Test
    fun `전체 대중교통 계산이 soft deadline을 넘으면 늦은 live 값을 폐기하고 저장 ETA로 fallback한다`() {
        val ticker = MutableEtaTicker()
        val service = mock<TransitEtaCalculationService>()
        whenever(service.calculate(any())).thenAnswer {
            ticker.advance(Duration.ofMillis(101))
            TrafficResult(
                travelMinutes = 60,
                source = TrafficSource.TIMETABLE_PROVIDER,
                fetchedAt = Instant.parse("2026-08-01T00:00:00Z"),
                stale = false,
            )
        }
        val client = TransitRealtimeTrafficClient(
            transitEtaCalculationService = service,
            calculationDeadline = EtaCalculationDeadline(Duration.ofMillis(100), ticker),
        )

        val result = client.getTravelMinutes(request())

        assertEquals(40, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.PROVIDER_TIMEOUT, result.failureReason)
        assertNull(result.fetchedAt)
        assertNull(result.predictedArrivalAt)
    }

    private fun request() = TrafficRequest(
        originLat = 37.5,
        originLng = 127.0,
        destinationLat = 37.6,
        destinationLng = 127.1,
        travelMode = ScheduleTravelMode.TRANSIT,
        fallbackTravelMinutes = 40,
        evaluatedAt = Instant.parse("2026-08-01T00:00:00Z"),
    )
}
