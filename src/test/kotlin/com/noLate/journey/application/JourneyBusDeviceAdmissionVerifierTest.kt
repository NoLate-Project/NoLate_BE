package com.noLate.journey.application

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.automatic.*
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import kotlin.test.*

class JourneyBusDeviceAdmissionVerifierTest {
    private fun fixture() = AutomaticJourneyFixture().also { it.useRideGuidancePlan() }
    private fun snapshot(f: AutomaticJourneyFixture) = JourneyRouteSnapshot.from(f.plan, f.mapper)

    @Test fun `device proof uses only official ordered catalog when ODsay omits way and count`() {
        val f = fixture()
        val evidence = f.plan.route!!.path("transitLegs")[1].path("navigationEvidence") as ObjectNode
        evidence.remove(listOf("directionName", "declaredStationCount"))
        val provider = mock<SeoulTransitArrivalClient>()
        whenever(provider.getBusRouteDiagnosticStops("999999999")).thenReturn(rideFixtureStops())
        whenever(provider.getBusRoutePath("999999999")).thenReturn(rideFixtureShape())
        val result = JourneyBusDeviceAdmissionVerifier(provider, true).verify(snapshot(f))
        assertEquals("OFFICIAL_STOP_SHAPE_CROSSWALK", result.identityEvidence)
        assertEquals("999999999", result.routeId)
        assertEquals("합성 동쪽 방면", result.direction)
        assertEquals(2, result.stationCount)
        assertEquals(44, result.firstStopSequence)
        assertEquals(46, result.lastStopSequence)
        assertFalse(result.toString().contains("999999999"))
        verify(provider).getBusRouteDiagnosticStops("999999999")
        verify(provider).getBusRoutePath("999999999")
        verifyNoMoreInteractions(provider)
    }

    @Test fun `disabled malformed and contradictory routes do not acquire device proof`() {
        val f = fixture()
        val provider = mock<SeoulTransitArrivalClient>()
        assertFails { JourneyBusDeviceAdmissionVerifier(provider, false).verify(snapshot(f)) }
        verifyNoInteractions(provider)
        val bus = f.plan.route!!.path("transitLegs")[1] as ObjectNode
        bus.put("localRouteId", "7016")
        assertFails { JourneyBusDeviceAdmissionVerifier(provider, true).verify(snapshot(f)) }
        verifyNoInteractions(provider)
        bus.put("localRouteId", "999999999")
        whenever(provider.getBusRouteDiagnosticStops("999999999")).thenReturn(rideFixtureStops().map { it.copy(direction = "반대 방면") })
        whenever(provider.getBusRoutePath("999999999")).thenReturn(rideFixtureShape())
        assertFails { JourneyBusDeviceAdmissionVerifier(provider, true).verify(snapshot(f)) }
    }
}
