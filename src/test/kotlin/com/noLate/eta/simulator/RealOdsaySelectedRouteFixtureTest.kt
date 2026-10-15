package com.noLate.eta.simulator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.domain.*
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class RealOdsaySelectedRouteFixtureTest {
    private val mapper = jacksonObjectMapper()
    private fun journey(provider: String = "odsay") = TransitJourney(
        provider, Instant.parse("2026-10-11T12:00:00Z"), Instant.parse("2026-10-11T12:00:00Z"),
        Instant.parse("2026-10-11T13:05:00Z"), 65, listOf(
            TransitJourneyLeg(0, TransitLegMode.WALK, 1),
            TransitJourneyLeg(1, TransitLegMode.SUBWAY, 48, from = TransitStop(providerStopId = "411", name = "노원"),
                to = TransitStop(providerStopId = "433", name = "사당"),
                line = TransitLine(providerRouteId = "1000:4", name = "수도권 4호선", serviceClass = TransitServiceClass.LOCAL)),
            TransitJourneyLeg(2, TransitLegMode.WALK, 5),
            TransitJourneyLeg(3, TransitLegMode.SUBWAY, 10, from = TransitStop(providerStopId = "226", name = "사당"),
                to = TransitStop(providerStopId = "230", name = "신림"),
                line = TransitLine(providerRouteId = "1000:2", name = "수도권 2호선", serviceClass = TransitServiceClass.LOCAL)),
            TransitJourneyLeg(4, TransitLegMode.WALK, 1)), Instant.parse("2026-10-11T11:59:30Z"))

    @Test
    fun `새 ODsay 여정 식별자만 저장하며 Tmap 경로를 재명명하지 않는다`() {
        val source = journey()
        assertEquals(source, RealOdsaySelectedRouteFixture.select(listOf(journey("tmap"), source)))
        val json = RealOdsaySelectedRouteFixture.encode(mapper, source)
        val decoded = requireNotNull(SelectedTransitRouteDecoder(mapper).decode(json))
        assertEquals("odsay", decoded.provider)
        assertEquals(listOf("1000:4", "1000:2"), decoded.rides.map { it.providerRouteId })
        assertEquals(setOf("PROVIDER:odsay:411"), decoded.rides[0].fromIds)
        val tree = mapper.readTree(json)
        assertEquals("odsay", tree.path("routeInfo").path("provider").asText())
        assertEquals("estimated", tree.path("routeInfo").path("timeBasis").asText())
        assertTrue(tree.path("transitLegs").all { !it.has("durationSeconds") && !it.has("pathCoords") })
    }

    @Test
    fun `다른 공급자나 다른 경로는 시드로 허용하지 않는다`() {
        assertThrows(IllegalArgumentException::class.java) { RealOdsaySelectedRouteFixture.select(listOf(journey("tmap"))) }
        assertThrows(IllegalArgumentException::class.java) { RealOdsaySelectedRouteFixture.encode(mapper, journey("tmap")) }
        val other = journey().let { it.copy(legs = it.legs.map { leg ->
            if (leg.sequence == 3) leg.copy(to = TransitStop(name = "강남")) else leg }) }
        assertThrows(IllegalArgumentException::class.java) { RealOdsaySelectedRouteFixture.select(listOf(other)) }
    }
}
