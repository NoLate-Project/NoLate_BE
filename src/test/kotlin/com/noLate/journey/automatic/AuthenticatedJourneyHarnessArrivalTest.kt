package com.noLate.journey.automatic

import com.noLate.journey.application.JourneyBusRouteBindingResolver
import com.noLate.journey.application.JourneyRouteSnapshot
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import java.time.Instant
import kotlin.test.*

class AuthenticatedJourneyHarnessArrivalTest {
    @Test fun `synthetic arrival route identity follows the selected owned fixture in both modes`() {
        val now = Instant.parse("2026-10-12T00:00:00Z")
        for (rideEnabled in listOf(false, true)) {
            val f = AutomaticJourneyFixture()
            resetAuthenticatedHarnessPlan(f, rideEnabled)
            val query = assertNotNull(JourneyBusRouteBindingResolver().resolve(JourneyRouteSnapshot.from(f.plan, f.mapper)).query)
            val arrival = authenticatedHarnessArrival(now, rideEnabled)
            assertEquals(query.providerRouteId, arrival.providerRouteId)
            assertEquals(query.arsId, arrival.arsId)
            assertEquals(query.directionLabel, arrival.direction)
        }
    }
    @Test fun `two minute arrival has an independent source timestamp and never identifies the tracked bus`() {
        val now = Instant.parse("2026-10-12T00:00:00Z")
        val value = authenticatedHarnessArrival(now, true)
        assertEquals(now.toString(), value.sourceUpdatedAt)
        assertEquals(now.plusSeconds(120).toString(), value.expectedAt)
        assertEquals(120, value.waitSeconds)
        assertEquals(TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP, value.freshnessEvidence)
        assertNotEquals(rideFixtureVehicle(now).vehicleId, value.vehicleId)
    }
}
