package com.noLate.schedule.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.TrafficSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class EffectiveTransitRouteSnapshotTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val fetchedAt = Instant.parse("2026-08-10T01:00:00Z")

    @Test
    fun `first verified alternative is a route change and survives the same check retry`() {
        val result = trafficResult(identity = "a".repeat(64))

        val first = requireNotNull(
            EffectiveTransitRouteSnapshot.from(result, previous = null, evaluationCheckCount = 3)
        )
        val retry = requireNotNull(
            EffectiveTransitRouteSnapshot.from(result, previous = first, evaluationCheckCount = 3)
        )
        val nextCheck = requireNotNull(
            EffectiveTransitRouteSnapshot.from(result, previous = retry, evaluationCheckCount = 4)
        )

        assertTrue(first.routeChanged)
        assertTrue(first.routeTransitioned)
        assertTrue(retry.routeChanged)
        assertTrue(retry.routeTransitioned)
        assertTrue(nextCheck.routeChanged)
        assertFalse(nextCheck.routeTransitioned)
    }

    @Test
    fun `snooze generation change does not replay a check zero route transition`() {
        val job = SchedulePushJob.create(
            memberId = 1,
            scheduleId = 10,
            scheduleAt = fetchedAt.plus(2, ChronoUnit.HOURS),
            departureAt = fetchedAt.plus(1, ChronoUnit.HOURS),
            monitorStartAt = fetchedAt,
            intervalMinutes = 20,
        )
        val first = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult = trafficResult(identity = "a".repeat(64)),
                previous = null,
                evaluationCheckCount = job.checkCount,
                notificationGeneration = job.notificationGeneration,
            )
        )

        job.snoozeUntil(fetchedAt.plus(5, ChronoUnit.MINUTES))
        val afterSnooze = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult = trafficResult(identity = "a".repeat(64)),
                previous = first,
                evaluationCheckCount = job.checkCount,
                notificationGeneration = job.notificationGeneration,
            )
        )

        assertEquals(0, first.evaluationCheckCount)
        assertTrue(first.routeTransitioned)
        assertEquals(0, job.checkCount)
        assertEquals(1, job.notificationGeneration)
        assertTrue(afterSnooze.routeChanged)
        assertFalse(afterSnooze.routeTransitioned)
    }

    @Test
    fun `alternative returning to selected route clears state and emits one transition`() {
        val alternative = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult(
                    identity = "a".repeat(64),
                    provenance = TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
                ),
                previous = null,
                evaluationCheckCount = 1,
            )
        )
        val selected = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult(
                    identity = "b".repeat(64),
                    provenance = TransitRouteProvenance.SELECTED_ROUTE_PRESERVED,
                ),
                previous = alternative,
                evaluationCheckCount = 2,
            )
        )

        assertTrue(alternative.routeChanged)
        assertFalse(selected.routeChanged)
        assertTrue(selected.routeTransitioned)
        val retry = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult(
                    identity = "b".repeat(64),
                    provenance = TransitRouteProvenance.SELECTED_ROUTE_PRESERVED,
                ),
                previous = selected,
                evaluationCheckCount = 2,
            )
        )
        assertFalse(retry.routeChanged)
        assertTrue(retry.routeTransitioned)
    }

    @Test
    fun `degraded evaluation keeps accepted baseline and same route recovery does not notify again`() {
        val acceptedA = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult(identity = "a".repeat(64)),
                previous = null,
                evaluationCheckCount = 1,
            )
        )
        val degraded = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                TrafficResult(
                    travelMinutes = 55,
                    source = TrafficSource.SELECTED_ROUTE,
                    stale = true,
                    failureReason = TrafficFailureReasons.PROVIDER_TIMEOUT,
                ),
                previous = acceptedA,
                evaluationCheckCount = 2,
            )
        )
        val recoveredA = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult(identity = "a".repeat(64)),
                previous = degraded,
                evaluationCheckCount = 3,
            )
        )
        val recoveredB = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult(identity = "b".repeat(64)),
                previous = degraded,
                evaluationCheckCount = 3,
            )
        )

        assertFalse(degraded.accepted)
        assertEquals("a".repeat(64), degraded.lastAcceptedRouteIdentity)
        assertEquals(
            TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
            degraded.lastAcceptedRouteProvenance,
        )
        assertTrue(recoveredA.routeChanged)
        assertFalse(recoveredA.routeTransitioned)
        assertTrue(recoveredB.routeChanged)
        assertTrue(recoveredB.routeTransitioned)
    }

    @Test
    fun `snapshot round trip is fail soft and keeps exact route contract`() {
        val snapshot = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult(identity = "b".repeat(64)),
                previous = null,
                evaluationCheckCount = 0,
            )
        )

        val restored = EffectiveTransitRouteSnapshot.parse(
            objectMapper,
            objectMapper.writeValueAsString(snapshot),
        )

        assertEquals("b".repeat(64), restored?.effectiveTransitRoute?.identity)
        assertEquals(0, restored?.evaluationNotificationGeneration)
        assertEquals(TransitTimingBasis.TIMETABLE_ONLY, restored?.transitTimingBasis)
        assertEquals(6, restored?.firstBoardingWaitMinutes)
        assertEquals("b".repeat(64), restored?.lastAcceptedRouteIdentity)
        assertNull(EffectiveTransitRouteSnapshot.parse(objectMapper, "{not-json"))
        assertNull(EffectiveTransitRouteSnapshot.parse(objectMapper, "{\"version\":99}"))
    }

    @Test
    fun `live activity route bar preserves both ends when itinerary is long`() {
        val segments = (0..6).map { sequence ->
            EffectiveTransitRouteSegment(
                sequence = sequence,
                kind = if (sequence % 2 == 0) {
                    EffectiveTransitRouteSegmentKind.BUS
                } else {
                    EffectiveTransitRouteSegmentKind.SUBWAY
                },
                durationMinutes = 5,
                lineName = "L$sequence",
            )
        }
        val route = effectiveRoute("c".repeat(64), segments)

        val display = route.toRouteDisplaySegments()

        assertEquals(5, display.size)
        assertEquals(listOf("L0", "L1", "3구간 생략", "L5", "L6"), display.map { it.label })
    }

    private fun trafficResult(
        identity: String,
        provenance: TransitRouteProvenance = TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
    ) = TrafficResult(
        travelMinutes = 55,
        source = TrafficSource.TIMETABLE_PROVIDER,
        fetchedAt = fetchedAt,
        stale = false,
        recommendedDepartureAt = Instant.parse("2026-08-10T01:10:00Z"),
        predictedArrivalAt = Instant.parse("2026-08-10T02:05:00Z"),
        transitRouteProvenance = provenance,
        transitTimingBasis = TransitTimingBasis.TIMETABLE_ONLY,
        firstBoardingWaitMinutes = 6,
        effectiveTransitRoute = effectiveRoute(identity),
    )

    private fun effectiveRoute(
        identity: String,
        segments: List<EffectiveTransitRouteSegment> = listOf(
            EffectiveTransitRouteSegment(
                sequence = 0,
                kind = EffectiveTransitRouteSegmentKind.WALK,
                durationMinutes = 4,
                fromName = "집",
                toName = "정류장",
            ),
            EffectiveTransitRouteSegment(
                sequence = 1,
                kind = EffectiveTransitRouteSegmentKind.BUS,
                durationMinutes = 45,
                waitingMinutes = 6,
                lineName = "341",
                fromName = "강남역",
                toName = "회사",
            ),
        ),
    ) = EffectiveTransitRoute(
        provider = "ODSAY",
        identity = identity,
        departureAt = Instant.parse("2026-08-10T01:10:00Z"),
        arrivalAt = Instant.parse("2026-08-10T02:05:00Z"),
        totalMinutes = 55,
        segments = segments,
    )
}
