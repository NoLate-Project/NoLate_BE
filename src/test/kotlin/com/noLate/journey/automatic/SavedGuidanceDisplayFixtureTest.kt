package com.noLate.journey.automatic

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test

class SavedGuidanceDisplayFixtureTest {
    @Test fun `long name display keeps the original first walking and bus navigation evidence`() {
        val f = AutomaticJourneyFixture(); f.useSavedGuidancePlan(mapped = true)
        val before = f.plan.route!!.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        f.useSavedGuidanceDisplayPlan("long-name")
        val legs = f.plan.route!!.path("transitLegs")
        assertThat(legs.size()).isEqualTo(3)
        assertThat(legs[0]).isEqualTo(before.path("transitLegs")[0])
        assertThat(legs[1].path("navigationEvidence")).isEqualTo(before.path("transitLegs")[1].path("navigationEvidence"))
        assertThat(legs[1].path("lineName").asText().length).isBetween(40, 64)
        assertThat(legs[1].path("lineColor").asText()).isEqualTo("#FFF5A0")
    }
    @Test fun `32 display legs cannot invent later navigation readiness and invalid variant is rejected`() {
        val f = AutomaticJourneyFixture(); f.useSavedGuidanceDisplayPlan("32-legs")
        val legs = f.plan.route!!.path("transitLegs")
        assertThat(legs.size()).isEqualTo(32)
        for (index in 3..31) {
            assertThat(legs[index].has("navigationEvidence")).isFalse()
            assertThat(legs[index].path("pathCoords").size()).isEqualTo(2)
            assertThat(legs[index].has("providerRouteId")).isFalse()
            assertThat(legs[index].has("directionName")).isFalse()
            assertThat(legs[index].path("startCoord")).isEqualTo(legs[index - 1].path("endCoord"))
        }
        assertThatThrownBy { f.useSavedGuidanceDisplayPlan("production") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
