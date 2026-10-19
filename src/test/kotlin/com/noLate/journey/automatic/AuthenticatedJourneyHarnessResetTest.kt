package com.noLate.journey.automatic

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthenticatedJourneyHarnessResetTest {
    @Test fun `reset preserves enabled synthetic ride mapping instead of silently replacing the route`() {
        val fixture = AutomaticJourneyFixture().also { it.useRideGuidancePlan() }
        val routeBefore = fixture.plan.route!!.deepCopy<com.fasterxml.jackson.databind.JsonNode>()
        fixture.prepareCount.incrementAndGet()
        resetAuthenticatedHarnessPlan(fixture, rideEnabled = true)
        assertThat(fixture.plan.route).isEqualTo(routeBefore)
        val bus = fixture.plan.route!!.path("transitLegs")[1]
        assertThat(bus.path("localRouteId").asText()).isEqualTo("999999999")
        assertThat(bus.path("navigationEvidence").path("busIdentifiers").path("localRouteId").asText())
            .isEqualTo("999999999")
        assertThat(fixture.prepareCount.get()).isZero()
        assertThat(fixture.state()["lastState"]).isEqualTo("NONE")
    }

    @Test fun `reset without ride opt in restores only the default mapped plan`() {
        val expected = AutomaticJourneyFixture().also { it.useSavedGuidancePlan(mapped = true) }.plan.route
        val fixture = AutomaticJourneyFixture().also { it.useRideGuidancePlan() }
        resetAuthenticatedHarnessPlan(fixture, rideEnabled = false)
        assertThat(fixture.plan.route).isEqualTo(expected)
        assertThat(fixture.plan.route!!.path("transitLegs")[1].path("localRouteId").asText())
            .isNotEqualTo("999999999")
    }
}
