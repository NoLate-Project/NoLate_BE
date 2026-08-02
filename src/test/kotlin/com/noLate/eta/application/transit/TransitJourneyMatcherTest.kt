package com.noLate.eta.application.transit

import com.noLate.eta.domain.SelectedTransitRoute
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLine
import com.noLate.eta.domain.TransitRideSignature
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.eta.domain.TransitStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class TransitJourneyMatcherTest {
    private val searchedAt = Instant.parse("2026-07-29T00:00:00Z")
    private val matcher = TransitJourneyMatcher()
    private val selected = SelectedTransitRoute(
        provider = "odsay",
        rides = listOf(
            TransitRideSignature(
                mode = TransitLegMode.BUS,
                providerRouteId = "bus-402",
                lineName = "402",
                fromIds = setOf("ODsay:1001", "ARS:02005"),
                fromName = "서울역버스환승센터",
                toIds = setOf("ODsay:2001", "ARS:22009"),
                toName = "강남역",
                directionName = "강남역 방면",
                directionCode = "DOWN",
            )
        ),
        legacyBoardingPlan = null,
    )

    @Test
    fun `저장한 ODsay 노선과 승하차 정류장이 같은 후보를 응답 순서와 ETA가 달라도 찾는다`() {
        val fasterDifferentRoute = journey(
            provider = "odsay",
            routeId = "bus-421",
            departureAt = searchedAt,
            arrivalAt = searchedAt.plus(35, ChronoUnit.MINUTES),
        )
        val refreshedSelectedRoute = journey(
            provider = "odsay",
            routeId = "BUS-402",
            departureAt = searchedAt.plus(3, ChronoUnit.MINUTES),
            arrivalAt = searchedAt.plus(43, ChronoUnit.MINUTES),
        )

        val result = matcher.findSelected(
            selected = selected,
            candidates = listOf(fasterDifferentRoute, refreshedSelectedRoute),
        )

        assertEquals(refreshedSelectedRoute, result)
    }

    @Test
    fun `동일한 노선 정보여도 다른 공급자 후보는 선택한 ODsay 경로로 취급하지 않는다`() {
        val tmapCandidate = journey(
            provider = "tmap",
            routeId = "bus-402",
            departureAt = searchedAt,
            arrivalAt = searchedAt.plus(40, ChronoUnit.MINUTES),
        )

        assertNull(matcher.findSelected(selected, listOf(tmapCandidate)))
    }

    @Test
    fun `저장 경로에 방향이 있는데 후보 방향 메타데이터가 없으면 일치시키지 않는다`() {
        val candidate = journey(
            provider = "odsay",
            routeId = "bus-402",
            departureAt = searchedAt,
            arrivalAt = searchedAt.plus(40, ChronoUnit.MINUTES),
            directionName = null,
            directionCode = null,
        )

        assertNull(matcher.findSelected(selected, listOf(candidate)))
    }

    @Test
    fun `방향 코드가 반대인 후보는 같은 노선과 정류장이어도 일치시키지 않는다`() {
        val candidate = journey(
            provider = "odsay",
            routeId = "bus-402",
            departureAt = searchedAt,
            arrivalAt = searchedAt.plus(40, ChronoUnit.MINUTES),
            directionName = "서울역 방면",
            directionCode = "UP",
        )

        assertNull(matcher.findSelected(selected, listOf(candidate)))
    }

    @Test
    fun `방향 코드가 없어도 양쪽 방향명이 같으면 이름으로 일치시킨다`() {
        val selectedWithoutCode = selected.copy(
            rides = listOf(selected.rides.single().copy(directionCode = null))
        )
        val candidate = journey(
            provider = "odsay",
            routeId = "bus-402",
            departureAt = searchedAt,
            arrivalAt = searchedAt.plus(40, ChronoUnit.MINUTES),
            directionName = "강남역행",
            directionCode = null,
        )

        assertEquals(candidate, matcher.findSelected(selectedWithoutCode, listOf(candidate)))
    }

    @Test
    fun `저장 legacy 경로에 방향 자체가 없으면 후보 방향 누락을 허용한다`() {
        val legacySelected = selected.copy(
            rides = listOf(
                selected.rides.single().copy(
                    directionName = null,
                    directionCode = null,
                )
            )
        )
        val candidate = journey(
            provider = "odsay",
            routeId = "bus-402",
            departureAt = searchedAt,
            arrivalAt = searchedAt.plus(40, ChronoUnit.MINUTES),
            directionName = null,
            directionCode = null,
        )

        assertEquals(candidate, matcher.findSelected(legacySelected, listOf(candidate)))
    }

    @Test
    fun `같은 지하철 노선 ID와 정차 구간이어도 일반과 급행이 다르면 거부한다`() {
        val selectedSubway = subwaySelected(TransitServiceClass.EXPRESS)
        val localCandidate = subwayJourney(TransitServiceClass.LOCAL)

        assertNull(matcher.findSelected(selectedSubway, listOf(localCandidate)))
    }

    @Test
    fun `명시된 지하철 종별까지 같으면 선택 경로로 일치시킨다`() {
        val selectedSubway = subwaySelected(TransitServiceClass.EXPRESS)
        val expressCandidate = subwayJourney(TransitServiceClass.EXPRESS)

        assertEquals(expressCandidate, matcher.findSelected(selectedSubway, listOf(expressCandidate)))
    }

    @Test
    fun `serviceClass가 없는 legacy 지하철 선택 경로는 추론 매칭하지 않는다`() {
        val legacySelected = subwaySelected(TransitServiceClass.UNKNOWN)
        val expressCandidate = subwayJourney(TransitServiceClass.EXPRESS)

        assertNull(matcher.findSelected(legacySelected, listOf(expressCandidate)))
    }

    @Test
    fun `후보 지하철 종별이 UNKNOWN이면 명시된 선택 경로와 추론 매칭하지 않는다`() {
        val selectedSubway = subwaySelected(TransitServiceClass.LOCAL)
        val unknownCandidate = subwayJourney(TransitServiceClass.UNKNOWN)

        assertNull(matcher.findSelected(selectedSubway, listOf(unknownCandidate)))
    }

    private fun subwaySelected(serviceClass: TransitServiceClass) = SelectedTransitRoute(
        provider = "odsay",
        rides = listOf(
            TransitRideSignature(
                mode = TransitLegMode.SUBWAY,
                providerRouteId = "subway-9",
                lineName = "9호선",
                serviceClass = serviceClass,
                fromIds = setOf("ODsay:station-a"),
                fromName = "여의도역",
                toIds = setOf("ODsay:station-b"),
                toName = "김포공항역",
                directionName = "김포공항 방면",
                directionCode = "DOWN",
            )
        ),
        legacyBoardingPlan = null,
    )

    private fun subwayJourney(serviceClass: TransitServiceClass) = TransitJourney(
        provider = "odsay",
        requestedDepartureAt = searchedAt,
        departureAt = searchedAt.plus(3, ChronoUnit.MINUTES),
        arrivalAt = searchedAt.plus(33, ChronoUnit.MINUTES),
        totalMinutes = 30,
        legs = listOf(
            TransitJourneyLeg(
                sequence = 0,
                mode = TransitLegMode.SUBWAY,
                durationMinutes = 30,
                waitingMinutes = 3,
                from = TransitStop(providerStopId = "station-a", name = "여의도역"),
                to = TransitStop(providerStopId = "station-b", name = "김포공항역"),
                line = TransitLine(
                    providerRouteId = "subway-9",
                    name = "9호선",
                    serviceClass = serviceClass,
                ),
                directionName = "김포공항행",
                directionCode = "DOWN",
            )
        ),
        fetchedAt = searchedAt,
    )

    private fun journey(
        provider: String,
        routeId: String,
        departureAt: Instant,
        arrivalAt: Instant,
        directionName: String? = "강남역행",
        directionCode: String? = "DOWN",
    ) = TransitJourney(
        provider = provider,
        requestedDepartureAt = searchedAt,
        departureAt = departureAt,
        arrivalAt = arrivalAt,
        totalMinutes = Math.toIntExact(
            ChronoUnit.MINUTES.between(departureAt, arrivalAt)
        ),
        legs = listOf(
            TransitJourneyLeg(
                sequence = 0,
                mode = TransitLegMode.BUS,
                durationMinutes = Math.toIntExact(
                    ChronoUnit.MINUTES.between(departureAt, arrivalAt)
                ),
                waitingMinutes = 5,
                from = TransitStop(
                    providerStopId = "1001",
                    arsId = "02005",
                    name = "서울역버스환승센터(5번승강장)",
                ),
                to = TransitStop(
                    providerStopId = "2001",
                    arsId = "22009",
                    name = "강남역",
                ),
                line = TransitLine(
                    providerRouteId = routeId,
                    name = "간선버스 402번",
                ),
                directionName = directionName,
                directionCode = directionCode,
            )
        ),
        fetchedAt = searchedAt,
    )
}
