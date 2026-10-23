package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.JourneyRouteSnapshot
import com.noLate.journey.application.JourneySubwayTrainStationMatcher
import com.noLate.journey.application.OfficialSubwaySavedLegPattern
import com.noLate.transit.domain.SeoulSubwayTrainObservation
import com.noLate.transit.domain.SeoulSubwayTrainObservationBatch
import com.noLate.transit.domain.SeoulSubwayTrainStatus
import com.noLate.transit.infrastructure.SeoulMetroShortestPath
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.*

class OfficialSubwaySavedLegPatternTest {
    private val f = AutomaticJourneyFixture()
    private val stations = listOf("0222" to "강남", "0221" to "역삼", "0220" to "선릉", "0219" to "삼성")
        .map { (code, name) -> SeoulMetroShortestPath.Station(code, "2호선", name) }

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

    private fun path(calls: List<SeoulMetroShortestPath.Station>, direction: String = "외선") =
        SeoulMetroShortestPath(calls.zipWithNext { from, to -> SeoulMetroShortestPath.Section(
            from, to, "0219", direction, "N", "N") }, 0)

    @Test fun `full saved sequence and terminal-bound official service produce a bounded train pattern`() {
        val pattern = assertNotNull(OfficialSubwaySavedLegPattern.resolve(snapshot(), 1,
            path(stations.take(3)), path(stations)))
        assertEquals(listOf("0222", "0221", "0220", "0219"), pattern.stationCalls)
        assertEquals(listOf("강남", "역삼", "선릉", "삼성"), pattern.officialStationNames)
        assertEquals(0, pattern.boardingIndex)
        assertEquals(2, pattern.alightingIndex)
        assertEquals("0219", pattern.terminalStationId)
        assertEquals(1, pattern.direction)
        assertEquals(0, pattern.service)
    }

    @Test fun `route mismatch and changed train service fail closed without a live arrivals feed`() {
        val segment = path(stations.take(3))
        val service = path(stations)
        assertNull(OfficialSubwaySavedLegPattern.resolve(snapshot { route ->
            ((route.path("transitLegs")[1] as ObjectNode).path("navigationEvidence")
                .path("stopOccurrences")[1] as ObjectNode).put("name", "잠실")
        }, 1, segment, service))
        assertNotNull(OfficialSubwaySavedLegPattern.resolve(snapshot { route ->
            val leg = route.path("transitLegs")[1] as ObjectNode
            leg.put("directionCode", "UP")
            val evidence = leg.path("navigationEvidence") as ObjectNode
            evidence.put("directionCode", "UP")
            (evidence.path("subwayIdentifiers") as ObjectNode).put("wayCode", "1")
        }, 1, segment, service))
        assertNull(OfficialSubwaySavedLegPattern.resolve(snapshot { route ->
            val leg = route.path("transitLegs")[1] as ObjectNode
            leg.put("directionCode", "UP")
        }, 1, segment, service))
        assertNull(OfficialSubwaySavedLegPattern.resolve(snapshot(), 1, segment,
            path(stations, "내선")))
        assertNull(OfficialSubwaySavedLegPattern.resolve(snapshot(), 1, segment,
            SeoulMetroShortestPath(service.sections.mapIndexed { index, row ->
                if (index == 2) row.copy(terminalCode = "0222") else row
            }, 0)))
    }

    @Test fun `a fresh train must corroborate its official station and terminal names as well as codes`() {
        val pattern = assertNotNull(OfficialSubwaySavedLegPattern.resolve(snapshot(), 1,
            path(stations.take(3)), path(stations)))
        val now = Instant.parse("2026-10-14T03:00:10Z")
        fun train(stationId: String = "1002000221", stationName: String? = "역삼",
            terminalId: String = "1002000219", terminalName: String? = "삼성종착") =
            SeoulSubwayTrainObservation("12345", pattern.line, stationId, terminalId, 1, 1, 0, false,
                now.minusSeconds(2), now, now.plusSeconds(28), stationName, terminalName)
        fun matched(observation: SeoulSubwayTrainObservation) = JourneySubwayTrainStationMatcher.match(pattern,
            SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.AVAILABLE, listOf(observation)), now)
        assertEquals("TRAIN_REFERENCES_ONLY", matched(train()).status)
        for (bad in listOf(train(stationId = "1002000999"), train(stationId = "1003000221"),
            train(stationName = "선릉"), train(stationName = null),
            train(terminalId = "1002000222"), train(terminalName = "강남종착"),
            train(terminalName = null))) {
            val result = matched(bad)
            assertEquals("SERVICE_PATTERN_OR_FRESHNESS_MISMATCH", result.status)
            assertEquals(0, result.trainCount)
        }
    }
}
