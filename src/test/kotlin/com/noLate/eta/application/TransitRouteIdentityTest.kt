package com.noLate.eta.application

import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLine
import com.noLate.eta.domain.TransitStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class TransitRouteIdentityTest {
    private val evaluatedAt = Instant.parse("2026-08-10T01:00:00Z")

    @Test
    fun `stable topology ids ignore display renames and walk metadata changes`() {
        val original = journey()
        val renamed = original.copy(
            legs = listOf(
                TransitJourneyLeg(
                    sequence = 0,
                    mode = TransitLegMode.WALK,
                    durationMinutes = 19,
                    from = TransitStop(providerStopId = "unrelated-walk-origin", name = "새 집"),
                    to = TransitStop(providerStopId = "unrelated-walk-destination", name = "새 입구"),
                    directionName = "도보 표시 문구 변경",
                ),
                original.legs[1].copy(
                    sequence = 99,
                    line = original.legs[1].line?.copy(name = "간선 402 (표시명 변경)"),
                    from = original.legs[1].from?.copy(name = "서울역 환승센터 새 이름"),
                    to = original.legs[1].to?.copy(name = "강남역 12번 출구"),
                    directionName = "신논현 쪽 표시 문구",
                ),
                TransitJourneyLeg(
                    sequence = 100,
                    mode = TransitLegMode.WALK,
                    durationMinutes = 1,
                    from = TransitStop(name = "출구"),
                    to = TransitStop(name = "건물"),
                ),
            ),
        )

        assertEquals(original.stableRouteIdentity(), renamed.stableRouteIdentity())
    }

    @Test
    fun `route stop and direction topology changes produce distinct identities`() {
        val original = journey()
        val ride = original.legs[1]
        val changedRoute = original.withRide(
            ride.copy(line = ride.line?.copy(providerRouteId = "bus-500"))
        )
        val changedStop = original.withRide(
            ride.copy(to = ride.to?.copy(providerStopId = "stop-3001"))
        )
        val changedDirection = original.withRide(
            ride.copy(directionCode = "UP", directionName = ride.directionName)
        )

        val identity = original.stableRouteIdentity()
        assertNotEquals(identity, changedRoute.stableRouteIdentity())
        assertNotEquals(identity, changedStop.stableRouteIdentity())
        assertNotEquals(identity, changedDirection.stableRouteIdentity())
    }

    @Test
    fun `name fallback remains scoped and collision resistant when topology ids are absent`() {
        val withoutIds = journey().withRide(
            journey().legs[1].copy(
                line = TransitLine(
                    cityCode = "11",
                    providerCode = "seoul-bus",
                    name = "402",
                ),
                from = TransitStop(cityCode = "11", providerCode = "seoul-bus", name = "서울역"),
                to = TransitStop(cityCode = "11", providerCode = "seoul-bus", name = "강남역"),
                directionCode = null,
                directionName = "강남역 방면",
            )
        )
        val otherLine = withoutIds.withRide(
            withoutIds.legs[1].copy(line = withoutIds.legs[1].line?.copy(name = "500"))
        )
        val otherDirection = withoutIds.withRide(
            withoutIds.legs[1].copy(directionName = "서울역 방면")
        )

        assertNotEquals(withoutIds.stableRouteIdentity(), otherLine.stableRouteIdentity())
        assertNotEquals(withoutIds.stableRouteIdentity(), otherDirection.stableRouteIdentity())
    }

    private fun TransitJourney.withRide(ride: TransitJourneyLeg): TransitJourney = copy(
        legs = listOf(legs.first(), ride, legs.last())
    )

    private fun journey(): TransitJourney = TransitJourney(
        provider = "odsay",
        requestedDepartureAt = evaluatedAt,
        departureAt = evaluatedAt,
        arrivalAt = evaluatedAt.plusSeconds(40 * 60),
        totalMinutes = 40,
        fetchedAt = evaluatedAt,
        legs = listOf(
            TransitJourneyLeg(
                sequence = 0,
                mode = TransitLegMode.WALK,
                durationMinutes = 5,
                from = TransitStop(name = "집"),
                to = TransitStop(name = "서울역 입구"),
            ),
            TransitJourneyLeg(
                sequence = 1,
                mode = TransitLegMode.BUS,
                durationMinutes = 27,
                from = TransitStop(providerStopId = "stop-1001", name = "서울역"),
                to = TransitStop(providerStopId = "stop-2001", name = "강남역"),
                line = TransitLine(providerRouteId = "bus-402", name = "402"),
                directionCode = "DOWN",
                directionName = "강남역 방면",
            ),
            TransitJourneyLeg(
                sequence = 2,
                mode = TransitLegMode.WALK,
                durationMinutes = 8,
                from = TransitStop(name = "강남역 출구"),
                to = TransitStop(name = "회사"),
            ),
        ),
    )
}
