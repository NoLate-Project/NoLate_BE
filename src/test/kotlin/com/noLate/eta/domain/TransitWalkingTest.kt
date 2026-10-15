package com.noLate.eta.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class TransitWalkingTest {
    private val now = Instant.parse("2026-10-06T00:00:00Z")
    private fun journey(modes: List<TransitLegMode>) = TransitJourney("any-provider", now, now,
        now.plusSeconds(modes.size * 31L), modes.size, modes.mapIndexed { index, mode ->
            TransitJourneyLeg(index, mode, 1, durationSeconds = 31)
        }, now)

    @Test
    fun `인접 도보와 0초 도보도 승차 구간의 앞 사이 뒤로 구분하며 시간은 그대로 유지한다`() {
        val original = journey(listOf(TransitLegMode.WALK, TransitLegMode.WALK, TransitLegMode.SUBWAY,
            TransitLegMode.WALK, TransitLegMode.WALK, TransitLegMode.BUS, TransitLegMode.WALK))
        val route = original.copy(legs = original.legs.mapIndexed { index, leg ->
            if (index == 3) leg.copy(durationSeconds = 0, durationMinutes = 0) else leg
        })
        assertEquals(listOf(TransitWalkingScope.ACCESS, TransitWalkingScope.ACCESS, null,
            TransitWalkingScope.TRANSFER, TransitWalkingScope.TRANSFER, null, TransitWalkingScope.EGRESS),
            route.legs.indices.map(route::walkingScopeAt))
        assertEquals(186L, route.legs.sumOf { it.movementDurationSeconds })
        assertEquals(route.legs, route.copy(provider = "odsay").legs)
        assertEquals(route.legs.indices.map(route::walkingScopeAt),
            route.legs.indices.map(route.copy(provider = "odsay")::walkingScopeAt))
    }

    @Test
    fun `승차가 없는 도보를 접근이나 도착으로 추정하지 않는다`() {
        val route = journey(listOf(TransitLegMode.WALK, TransitLegMode.ETC))
        assertEquals(TransitWalkingScope.UNKNOWN, route.walkingScopeAt(0))
        assertNull(route.walkingScopeAt(1))
    }

    @Test
    fun `잘못된 좌표와 거리 및 승차 구간의 도보 메타데이터를 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { TransitWalkingCoordinate(Double.NaN, 127.0) }
        assertThrows(IllegalArgumentException::class.java) { TransitWalkingCoordinate(91.0, 127.0) }
        assertThrows(IllegalArgumentException::class.java) { TransitWalkingGeometry(distanceMeters = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            TransitJourneyLeg(0, TransitLegMode.SUBWAY, 1, walkingGeometry = TransitWalkingGeometry())
        }
    }

    @Test
    fun `상세 제공을 승강장 포함 확정으로 저장하거나 부분 증거를 정상으로 만들지 않는다`() {
        assertThrows(IllegalArgumentException::class.java) {
            TransitWalkingPathEvidence("tmap", TransitWalkingPathStatus.PROVIDED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransitWalkingPathEvidence("tmap", TransitWalkingPathStatus.UNAVAILABLE,
                stationInteriorCoverage = TransitStationInteriorCoverage.INCLUDED_TO_PLATFORM)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransitWalkingStep(listOf(TransitWalkingCoordinate(37.5,127.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransitWalkingStep(listOf(TransitWalkingCoordinate(37.5,127.0), TransitWalkingCoordinate(37.6,127.1)),
                distanceMeters = Double.NaN)
        }
    }
}
