package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.global.error.BusinessException
import com.noLate.journey.application.AutomaticJourneyService
import com.noLate.journey.application.JourneyRerouteDestination
import com.noLate.journey.application.JourneyRerouteRouteProvider
import com.noLate.journey.application.JourneyRerouteSearchService
import com.noLate.journey.domain.JourneyRerouteSearchCommand
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class JourneyRerouteSearchServiceTest {
    private val now = Instant.parse("2026-11-19T01:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val mapper = ObjectMapper()
    private val session = "d8f53664-36bd-4db4-8fde-b256f8c5ea5c"
    private fun command(at: Instant = now) = JourneyRerouteSearchCommand(3, 7,
        "80e3d778-b983-48d2-b9ad-5d2f987e41d3", "a".repeat(64),
        37.565, 126.990, 8.0, at)

    @Test fun `fresh active lookup uses server owned destination and ODsay only`() {
        val automatic = mock<AutomaticJourneyService>()
        whenever(automatic.requireActiveRerouteDestination(eq(7), eq(41), eq(2), eq(session), any()))
            .thenReturn(JourneyRerouteDestination(37.576, 126.978))
        var requested: TransitJourneySearchRequest? = null
        var calls = 0
        val odsay = object : JourneyRerouteRouteProvider {
            override val providerId = "odsay"
            override fun searchRaw(request: TransitJourneySearchRequest): JsonNode {
                calls++
                requested = request
                return mapper.readTree("""{"result":{"paths":[
                    {"pathType":2,"totalTime":21,"rps":[{},{}]},
                    {"pathType":1,"totalTime":8,"rps":[{},{}]},
                    {"pathType":2,"totalTime":23,"rps":[{},{}]},
                    {"pathType":2,"totalTime":26,"rps":[{},{}]},
                    {"pathType":2,"totalTime":30,"rps":[{},{}]}]}}""")
            }
        }
        val service = JourneyRerouteSearchService(automatic, listOf(odsay), mapper, clock, true)
        val response = service.search(7, 41, 2, session, command())
        assertThat(requested?.originLat).isEqualTo(37.565)
        assertThat(requested?.originLng).isEqualTo(126.990)
        assertThat(requested?.destinationLat).isEqualTo(37.576)
        assertThat(requested?.destinationLng).isEqualTo(126.978)
        assertThat(response.path("provider").asText()).isEqualTo("odsay")
        assertThat(response.path("result").path("paths").size()).isEqualTo(3)
        assertThatThrownBy { service.search(7, 41, 2, session, command()) }
            .isInstanceOf(BusinessException::class.java)
        assertThat(calls).isEqualTo(1)
    }

    @Test fun `stale fix cannot reach authority or provider`() {
        val automatic = mock<AutomaticJourneyService>()
        val provider = mock<JourneyRerouteRouteProvider>()
        assertThatThrownBy { JourneyRerouteSearchService(automatic, listOf(provider), mapper, clock, true)
            .search(7, 41, 2, session, command(now.minusSeconds(16))) }
            .isInstanceOf(BusinessException::class.java)
        verifyNoInteractions(automatic, provider)
    }

    @Test fun `TMAP candidate provider cannot replace ODsay`() {
        val automatic = mock<AutomaticJourneyService>()
        whenever(automatic.requireActiveRerouteDestination(eq(7), eq(41), eq(2), eq(session), any()))
            .thenReturn(JourneyRerouteDestination(37.576, 126.978))
        val provider = mock<JourneyRerouteRouteProvider>()
        whenever(provider.providerId).thenReturn("tmap")
        assertThatThrownBy { JourneyRerouteSearchService(automatic, listOf(provider), mapper, clock, true)
            .search(7, 41, 2, session, command()) }
            .isInstanceOf(BusinessException::class.java)
        verify(provider, never()).searchRaw(any())
    }
}
