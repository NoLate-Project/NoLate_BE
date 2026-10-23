package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.JourneyRouteSnapshot
import com.noLate.journey.application.SavedSubwayOfficialPathComparison
import com.noLate.transit.infrastructure.SeoulMetroShortestPathParser
import kotlin.test.*
import org.junit.jupiter.api.Test

class SavedSubwayOfficialPathComparisonTest {
    private val f = AutomaticJourneyFixture()
    private val official = SeoulMetroShortestPathParser.parse("""{
      "header":{"resultCode":"00"},"body":{"trsitNmtm":0,"paths":[
        {"dptreStn":{"stnCd":"0222","lineNm":"2호선","stnNm":"강남"},
         "arvlStn":{"stnCd":"0221","lineNm":"2호선","stnNm":"역삼"},
         "tmnlStnCd":"0211","upbdnbSe":"외선","trsitYn":"N","etrnYn":"N"},
        {"dptreStn":{"stnCd":"0221","lineNm":"2호선","stnNm":"역삼"},
         "arvlStn":{"stnCd":"0220","lineNm":"2호선","stnNm":"선릉"},
         "tmnlStnCd":"0211","upbdnbSe":"외선","trsitYn":"N","etrnYn":"N"}
      ]}
    }""".trimIndent().toByteArray())

    private fun snapshot(change: (ObjectNode) -> Unit = {}): JourneyRouteSnapshot {
        val route = syntheticSavedSubwayRoute(f.mapper) as ObjectNode
        val leg = route.path("transitLegs")[1] as ObjectNode
        leg.put("lineName", "2호선").put("startName", "강남").put("endName", "선릉")
        val names = listOf("강남", "역삼", "선릉")
        leg.path("navigationEvidence").path("stopOccurrences").forEachIndexed { index, row ->
            (row as ObjectNode).put("name", names[index])
        }
        change(route)
        return JourneyRouteSnapshot.from(f.plan.copy(route = route), f.mapper)
    }

    @Test fun `full line and ordered names allow only a path candidate not a train mapping`() {
        val candidate = assertNotNull(SavedSubwayOfficialPathComparison.compare(snapshot(), 1, official))
        assertEquals(listOf("0222", "0221", "0220"), candidate.officialStationCodes)
        assertFalse(candidate.realtimeStationIdsVerified)
        assertFalse(candidate.passengerBoardingConfirmed)
        assertFalse(candidate.toString().contains("0222"))
    }

    @Test fun `ODsay metropolitan display prefix preserves the independently matched raw line`() {
        val prefixed = snapshot { (it.path("transitLegs")[1] as ObjectNode).put("lineName", "수도권 2호선") }
        assertEquals("2호선", SavedSubwayOfficialPathComparison.compare(prefixed, 1, official)?.line)
        assertNull(SavedSubwayOfficialPathComparison.compare(snapshot {
            (it.path("transitLegs")[1] as ObjectNode).put("lineName", "수도권 4호선")
        }, 1, official))
        assertNull(SavedSubwayOfficialPathComparison.canonicalLine("2", "수도권 2호선 급행"))
    }

    @Test fun `single name match cannot override a conflicting full saved route`() {
        val changes = listOf<(ObjectNode) -> Unit>(
            { ((it.path("transitLegs")[1] as ObjectNode).path("navigationEvidence")
                .path("stopOccurrences")[1] as ObjectNode).put("name", "잠실") },
            { (it.path("transitLegs")[1] as ObjectNode).put("lineName", "4호선") },
            { (it.path("transitLegs")[1] as ObjectNode).put("endName", "사당") },
            { ((it.path("transitLegs")[1] as ObjectNode).path("navigationEvidence")
                .path("stopOccurrences")[1] as ObjectNode).put("name", "강남") },
        )
        changes.forEach { assertNull(SavedSubwayOfficialPathComparison.compare(snapshot(it), 1, official)) }
    }
}
