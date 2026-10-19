package com.noLate.journey.automatic

import com.noLate.journey.application.JourneyBusRouteBindingResolver
import com.noLate.journey.application.JourneyRouteSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verifyNoInteractions

class Seoul7016PreviewFixtureTest {
    @Test fun `public stop preview preserves ordered occurrence and is not certified ODsay navigation`() {
        val f = AutomaticJourneyFixture(); f.useSeoul7016Preview()
        val route = f.plan.route!!; val bus = route.path("transitLegs")[1]
        val stops = bus.path("navigationEvidence").path("stopOccurrences")
        assertThat(stops.map { it.path("providerSequence").asInt() }).containsExactly(44, 45, 46, 47, 48)
        assertThat(stops.first().path("arsID").asText()).isEqualTo("02007")
        assertThat(stops.last().path("arsID").asText()).isEqualTo("01116")
        assertThat(stops[3].path("name").asText()).isEqualTo("광화문역2번출구.KT광화문지사")
        assertThat(bus.has("providerRouteId")).isFalse()
        assertThat(bus.has("pathCoords")).isFalse()
        assertThat(route.path("transitLegs")[0].has("pathCoords")).isFalse()
        assertThat(JourneyBusRouteBindingResolver().resolve(JourneyRouteSnapshot.from(f.plan, f.mapper)).status)
            .isEqualTo("UNSUPPORTED_PROVIDER")
        assertThat(f.prepareCount.get()).isZero(); assertThat(f.departureCount.get()).isZero()
        verifyNoInteractions(f.departure, f.jobs, f.schedules)
    }
}
