package com.noLate.journey.automatic

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import com.noLate.transit.domain.SeoulBusVehicleQuery
import kotlin.math.*

class MixedWalkingRideFixtureTest {
    @Test fun `transfer departure has sufficient distinct fixes for every eleven second poll phase`() {
        for(index in listOf(1,3))for(phase in 0L..10L) {
            val clock=WalkingSnapshotClock(Instant.parse("2026-10-13T00:00:00Z"))
            val ride=MixedWalkingRideFixture(clock,true)
            val start=if(index==1)ride.start() else ride.startSecond()
            val scale=6_371_000*PI/180*cos(Math.toRadians(37.5665))
            val samples=(phase..149L step 11).map { seconds ->
                clock.now=start.plusSeconds(seconds)
                val v=ride.provider.getBusVehicleObservations(SeoulBusVehicleQuery(TransferRideFixture.route(index),44,47)).observations.single()
                val past=(requireNotNull(v.wgs84Coordinate).longitude-TransferRideFixture.longitude(TransferRideFixture.target(index)))*scale
                v.sourceUpdatedAt to past
            }.filter{it.second>=75}
            assertThat(samples.size).describedAs("occurrence %s poll phase %s",index,phase).isGreaterThanOrEqualTo(3)
            assertThat(samples.last().second-samples.first().second)
                .describedAs("departure travel before correlation expiry, occurrence %s phase %s",index,phase).isGreaterThanOrEqualTo(40.0)
            assertThat(samples.all{it.second<300}).isTrue()
        }
    }
    @Test fun `fixture starts empty and clearing removes all observations`() {
        val clock=WalkingSnapshotClock(Instant.parse("2026-10-12T00:00:00Z"));val ride=MixedWalkingRideFixture(clock)
        assertThat(ride.observations().observations).isEmpty()
        ride.start();assertThat(ride.observations().observations).hasSize(1)
        ride.clear();assertThat(ride.observations().observations).isEmpty()
        assertThat(ride.requests).isEqualTo(3)
    }
    @Test fun `independent clock advances vehicle and pauses before departure`() {
        val clock=WalkingSnapshotClock(Instant.parse("2026-10-12T00:00:00Z"));val ride=MixedWalkingRideFixture(clock)
        val start=ride.start()
        fun longitude(seconds:Long):Double {clock.now=start.plusSeconds(seconds);return requireNotNull(ride.observations().observations.single().wgs84Coordinate).longitude}
        val early=longitude(40);val arrived=longitude(90);val paused=longitude(95);val left=longitude(145)
        assertThat(early).isLessThan(arrived);assertThat(arrived).isEqualTo(paused);assertThat(left).isGreaterThan(paused)
    }
    @Test fun `departed vehicle remains before next catalog stop across polling samples`() {
        val clock=WalkingSnapshotClock(Instant.parse("2026-10-12T00:00:00Z"));val ride=MixedWalkingRideFixture(clock)
        val start=ride.start()
        val next=ride.provider.getBusRouteDiagnosticStops("999999999").last().longitude
        val samples=listOf(108L,119L,130L,141L).map { seconds ->
            clock.now=start.plusSeconds(seconds)
            requireNotNull(ride.observations().observations.single().wgs84Coordinate).longitude
        }
        assertThat(samples).allMatch { it > 126.9895 && it < next }
        assertThat(samples[2]).isGreaterThan(samples[0])
        assertThat(ride.provider.getBusRoutePath("999999999").last().longitude).isEqualTo(next)
    }
}
