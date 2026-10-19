package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.*
import com.noLate.transit.domain.*
import com.noLate.transit.infrastructure.SeoulMetroShortestPath
import com.noLate.transit.infrastructure.SeoulMetroShortestPathClient
import com.noLate.transit.infrastructure.SeoulMetroStationCoordinateCatalog
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import kotlin.test.*

class LiveOfficialSubwayServicePatternCatalogTest {
    private val clock = AutomaticJourneyMutableClock()
    private val f = AutomaticJourneyFixture(clock)
    private val stations = listOf("0222" to "강남", "0221" to "역삼", "0220" to "선릉", "0219" to "삼성")
        .map { (code, name) -> SeoulMetroShortestPath.Station(code, "2호선", name) }
    private fun path(calls: List<SeoulMetroShortestPath.Station>, direction: String = "외선") =
        SeoulMetroShortestPath(calls.zipWithNext { from, to -> SeoulMetroShortestPath.Section(
            from, to, "0219", direction, "N", "N") }, 0)
    private fun snapshot(change: (ObjectNode) -> Unit = {}): JourneyRouteSnapshot {
        val route = syntheticSavedSubwayRoute(f.mapper) as ObjectNode
        val leg = route.path("transitLegs")[1] as ObjectNode
        leg.put("lineName", "2호선").put("startName", "강남").put("endName", "선릉")
            .put("directionCode", "DOWN")
        val evidence = leg.path("navigationEvidence") as ObjectNode
        evidence.put("directionCode", "DOWN")
        (evidence.path("subwayIdentifiers") as ObjectNode).put("wayCode", "2")
        leg.path("navigationEvidence").path("stopOccurrences").forEachIndexed { index, row ->
            (row as ObjectNode).put("name", stations[index].name)
        }
        change(route)
        return JourneyRouteSnapshot.from(f.plan.copy(route = route), f.mapper)
    }
    private fun coordinates(): SeoulMetroStationCoordinateCatalog {
        val rows = listOf("31,2,222,강남,37.50,127.00,1982-12-30,2025-08-14",
            "30,2,221,역삼,37.51,127.01,1982-12-30,2025-08-14",
            "29,2,220,선릉,37.52,127.02,1982-12-30,2025-08-14",
            "28,2,219,삼성,37.53,127.03,1982-12-30,2025-08-14")
        return SeoulMetroStationCoordinateCatalog.parse((
            "연번,호선,고유역번호(외부역코드),역명,위도,경도,작성일자,작성기준일\n" +
                rows.joinToString("\n")).toByteArray(Charset.forName("MS949")))
    }

    @Test fun `owned saved leg reaches native input only after official paths and named live train agree`() {
        var pathCalls = 0; var trainCalls = 0; var captures = 0
        val catalog = LiveOfficialSubwayServicePatternCatalog({ query ->
            pathCalls++
            when (query.stationValueType) {
                SeoulMetroShortestPathClient.StationValueType.NAME -> {
                    assertEquals("강남", query.departure); assertEquals("선릉", query.arrival)
                    path(stations.take(3))
                }
                SeoulMetroShortestPathClient.StationValueType.CODE -> {
                    assertEquals("0222", query.departure); assertEquals("0219", query.arrival)
                    path(stations)
                }
            }
        }, ::coordinates, clock)
        val checker = SavedJourneySubwayObservationCheck(f.mapper, null, catalog, { query ->
            trainCalls++
            assertEquals(SeoulSubwayObservationLine.LINE_2, query.line)
            SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.AVAILABLE, listOf(
                SeoulSubwayTrainObservation("12345", query.line, "1002000221", "1002000219",
                    1, 1, 0, false, clock.now.minusSeconds(2), clock.now, clock.now.plusSeconds(28),
                    "역삼", "삼성종착")))
        }, clock, true)
        val saved = snapshot()
        repeat(2) {
            val result = checker.checkWithFence(7, 9001, 2, 1, 1) { captures++; saved }
            assertEquals("TRAIN_REFERENCES_ONLY", result.status)
            assertEquals(1, result.matchingTrainCount)
            assertEquals(listOf("0222", "0221", "0220"),
                (result.nativeInput?.get("stations") as List<*>).map { (it as Map<*, *>)["stationId"] })
            assertFalse(result.navigationReady)
            assertFalse(result.passengerBoardingConfirmed)
        }
        assertEquals(2, pathCalls)
        assertEquals(2, trainCalls); assertEquals(4, captures)
    }

    @Test fun `a changed official direction or saved stop cannot reach train provider`() {
        var trainCalls = 0
        val catalog = LiveOfficialSubwayServicePatternCatalog({ query ->
            if (query.stationValueType == SeoulMetroShortestPathClient.StationValueType.NAME)
                path(stations.take(3)) else path(stations, "내선")
        }, ::coordinates, clock)
        val checker = SavedJourneySubwayObservationCheck(f.mapper, null, catalog,
            { trainCalls++; error("train must not be queried") }, clock, true)
        val result = checker.checkWithFence(7, 9001, 2, 1, 1) { snapshot() }
        assertEquals("STATION_CROSSWALK_REQUIRED", result.status)
        assertEquals(0, trainCalls)
    }

    @Test fun `saved station coordinate disagreement blocks train lookup`() {
        var trainCalls = 0
        val catalog = LiveOfficialSubwayServicePatternCatalog({ query ->
            if (query.stationValueType == SeoulMetroShortestPathClient.StationValueType.NAME)
                path(stations.take(3)) else path(stations)
        }, ::coordinates, clock)
        val checker = SavedJourneySubwayObservationCheck(f.mapper, null, catalog,
            { trainCalls++; error("train must not be queried") }, clock, true)
        val saved = snapshot { root ->
            val leg = root.path("transitLegs")[1] as ObjectNode
            val station = leg.path("navigationEvidence").path("stopOccurrences")[1] as ObjectNode
            (station.path("coord") as ObjectNode).put("lat", 37.60)
        }
        val result = checker.checkWithFence(7, 9001, 2, 1, 1) { saved }
        assertEquals("STATION_CROSSWALK_REQUIRED", result.status)
        assertEquals(0, trainCalls)
    }
}
