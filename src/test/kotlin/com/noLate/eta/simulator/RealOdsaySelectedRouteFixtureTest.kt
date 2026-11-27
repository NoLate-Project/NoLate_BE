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

    @Test
    fun `짧은 첫 승차 검증은 별도 동작 사당 신림 원본 ODsay 경로만 선택한다`() {
        val short = journey().let { source -> source.copy(legs = source.legs.map { leg ->
            if (leg.sequence == 1) leg.copy(from = TransitStop(providerStopId = "431", name = "동작")) else leg
        }) }
        assertThrows(IllegalArgumentException::class.java) { RealOdsaySelectedRouteFixture.select(listOf(short)) }
        assertThrows(IllegalArgumentException::class.java) {
            RealOdsaySelectedRouteFixture.select(listOf(journey()), shortFirstRide = true)
        }
        assertEquals(short, RealOdsaySelectedRouteFixture.select(listOf(journey(), short), shortFirstRide = true))
        val decoded = requireNotNull(SelectedTransitRouteDecoder(mapper).decode(RealOdsaySelectedRouteFixture.encode(mapper, short)))
        assertEquals(setOf("PROVIDER:odsay:431"), decoded.rides.first().fromIds)
    }

    private fun mixed(busSequence: Int): TransitJourney {
        val source = journey()
        return source.copy(legs = source.legs.map { leg ->
            when {
                leg.sequence == busSequence -> leg.copy(mode = TransitLegMode.BUS,
                    from = TransitStop(providerStopId = "1234", localStopId = "100000111", arsId = "01011", name = "첫 정류장"),
                    to = TransitStop(providerStopId = "5678", localStopId = "100000222", arsId = "01012", name = "다음 정류장"),
                    line = TransitLine(providerRouteId = "3211", localRouteId = "100100585", name = "N37"))
                leg.mode == TransitLegMode.SUBWAY -> leg.copy(line = leg.line?.copy(seoulSubwayLine = SeoulSubwayLine.LINE_2))
                else -> leg
            }
        })
    }

    @Test
    fun `혼합 검증은 요청한 방향과 원본 서울 버스 식별자가 있는 여정만 선택한다`() {
        val source = journey()
        val busFirst = mixed(1)
        val busLast = mixed(3)
        val noBusBinding = busFirst.copy(legs = busFirst.legs.map { leg ->
            if (leg.mode == TransitLegMode.BUS) leg.copy(line = leg.line?.copy(localRouteId = null)) else leg
        })
        val candidates = listOf(source, noBusBinding, busLast, busFirst)
        assertEquals(busFirst, RealOdsaySelectedRouteFixture.select(candidates, mixedBusFirst = true))
        assertEquals(busLast, RealOdsaySelectedRouteFixture.select(candidates, mixedBusFirst = false))
        assertThrows(IllegalArgumentException::class.java) {
            RealOdsaySelectedRouteFixture.select(listOf(source, noBusBinding, busLast), mixedBusFirst = true)
        }
        val decoded = requireNotNull(SelectedTransitRouteDecoder(mapper)
            .decode(RealOdsaySelectedRouteFixture.encode(mapper, busFirst)))
        assertEquals("3211", decoded.rides.first().providerRouteId)
        assertEquals(setOf("PROVIDER:odsay:1234", "ARS:01011"), decoded.rides.first().fromIds)
    }

    @Test
    fun `공개 지도 비교는 첫 후보가 아닌 같은 노선의 승하차 지점을 고른다`() {
        val route = mixed(3)
        val other = route.copy(legs = route.legs.map { leg ->
            if (leg.mode == TransitLegMode.SUBWAY) leg.copy(to = leg.to?.copy(name = "다른 역")) else leg
        })
        val target = RealOdsaySelectedRouteFixture.PublicMixedRoute(SeoulSubwayLine.LINE_2, "노원", "사당",
            "N37", "첫 정류장", "다음 정류장", "01011", "01012")
        assertEquals(other, RealOdsaySelectedRouteFixture.select(listOf(other, route), mixedBusFirst = false))
        assertEquals(route, RealOdsaySelectedRouteFixture.select(listOf(other, route), mixedBusFirst = false, publicMixedRoute = target))
        assertThrows(IllegalArgumentException::class.java) {
            RealOdsaySelectedRouteFixture.select(listOf(other), mixedBusFirst = false, publicMixedRoute = target)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RealOdsaySelectedRouteFixture.select(listOf(route), mixedBusFirst = false, publicMixedRoute = target.copy(busFromArs = "01015"))
        }
    }

    @Test
    fun `같은 이름으로 두 운행 경로가 맞으면 첫 편으로 추정하지 않는다`() {
        val route = mixed(3)
        val variant = route.copy(legs = route.legs.map { leg ->
            if (leg.mode == TransitLegMode.BUS) leg.copy(line = leg.line?.copy(localRouteId = "100100586"),
                from = leg.from?.copy(arsId = "01-015")) else leg
        })
        val target = RealOdsaySelectedRouteFixture.PublicMixedRoute(SeoulSubwayLine.LINE_2, "노원", "사당",
            "N37", "첫 정류장", "다음 정류장")
        assertThrows(IllegalArgumentException::class.java) {
            RealOdsaySelectedRouteFixture.select(listOf(route, variant), mixedBusFirst = false, publicMixedRoute = target)
        }
        assertEquals(route, RealOdsaySelectedRouteFixture.select(listOf(route, variant), mixedBusFirst = false,
            publicMixedRoute = target.copy(busFromArs = "01011")))
        assertEquals(variant, RealOdsaySelectedRouteFixture.select(listOf(route, variant), mixedBusFirst = false,
            publicMixedRoute = target.copy(busFromArs = "01015")))
        assertEquals(route, RealOdsaySelectedRouteFixture.select(listOf(route, route.copy(totalMinutes = 67)),
            mixedBusFirst = false, publicMixedRoute = target))
    }
}
