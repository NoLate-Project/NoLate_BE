package com.noLate.eta.simulator

import com.noLate.eta.application.transit.FirstBoardingRealtimeOverlay
import com.noLate.transit.application.TransitArrivalService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ArrivalQualityFixtureContractTest {
    @Test
    fun `fixture timeout은 일반 오류로 바뀌지 않고 원인 체인이 보존된다`() {
        val state = FixtureState().apply { phase = "quality-timeout" }
        val client = FixtureConfiguration().fixtureSeoulArrivalClient(state)
        val service = TransitArrivalService(client, mock())
        val error = assertThrows(RuntimeException::class.java) {
            service.getSubwayArrivalsForEta("노원", "4호선", null, "DOWN", 10)
        }
        val classes = generateSequence<Throwable>(error) { it.cause }.take(16).map { it.javaClass.simpleName }.toList()
        assertTrue(classes.contains("SocketTimeoutException"), classes.toString())
        val overlay = FirstBoardingRealtimeOverlay(service, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        val reason: String? = ReflectionTestUtils.invokeMethod(overlay, "arrivalApiFailureReason", error)
        assertTrue(reason.orEmpty().startsWith("TRANSIT_ARRIVAL_API_TIMEOUT:"), classes.toString())
    }
}
