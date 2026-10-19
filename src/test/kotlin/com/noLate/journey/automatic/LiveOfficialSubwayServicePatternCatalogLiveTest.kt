package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.JourneyRouteSnapshot
import com.noLate.journey.application.LiveOfficialSubwayServicePatternCatalog
import com.noLate.journey.application.SavedJourneySubwayObservationCheck
import com.noLate.journey.application.SavedSubwayOfficialPathComparison
import com.noLate.transit.domain.SeoulSubwayTrainStatus
import com.noLate.transit.infrastructure.SeoulMetroShortestPathClient
import com.noLate.transit.infrastructure.SeoulMetroStationCoordinateClient
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opt-in real-provider check against a saved-route fixture; no passenger GPS or UI assertion. */
class LiveOfficialSubwayServicePatternCatalogLiveTest {
    @Test fun `simulator saved route reaches the official path and current train feed`() {
        val pathKey = System.getenv("DATA_GO_KR_SUBWAY_PATH_API_KEY")
        val trainKey = System.getenv("SEOUL_SUBWAY_API_KEY")
        assumeTrue(System.getenv("RUN_SEOUL_OFFICIAL_SUBWAY_LIVE") == "1" &&
            !pathKey.isNullOrBlank() && !trainKey.isNullOrBlank())
        val f = AutomaticJourneyFixture(Clock.systemUTC())
        f.useRideGuidancePlan()
        OfficialSubwayNavigationFixture.apply(f)
        val saved = JourneyRouteSnapshot.from(f.plan, f.mapper)
        SeoulMetroShortestPathClient(requireNotNull(pathKey)).use { path ->
            SeoulMetroStationCoordinateClient().use { coordinates ->
                val catalog = LiveOfficialSubwayServicePatternCatalog({ path.fetch(it) }, { coordinates.fetch() })
                val pattern = catalog.resolve(saved, 1)
                println("LIVE_SIMULATOR_PATH pattern=${pattern != null}")
                assertNotNull(pattern)
                println("LIVE_SIMULATOR_PATTERN direction=${pattern.direction} service=${pattern.service} terminal=${pattern.terminalStationId} terminalName=${pattern.officialStationNames?.last()} firstCalls=${pattern.stationCalls.take(3)} names=${pattern.officialStationNames?.take(3)}")
                SeoulTransitArrivalClient("", requireNotNull(trainKey), "",
                    "http://swopenapi.seoul.go.kr/api/subway", "http://ws.bus.go.kr/api/rest", true).use { trains ->
                    val result = SavedJourneySubwayObservationCheck(f.mapper, null, catalog,
                        { query -> trains.getSubwayTrainObservations(query).also { batch ->
                            println("LIVE_SIMULATOR_BATCH status=${batch.status} usable=${batch.observationCount} rejected=${batch.rejectionCounts}")
                            batch.observations.filter { it.stationName in setOf("강남", "역삼", "선릉") }
                                .forEach { row -> println("LIVE_SIMULATOR_ROW station=${row.stationName} code=${row.stationId} destination=${row.destinationStationName}/${row.destinationStationId} direction=${row.direction} service=${row.service} state=${row.state} ageSeconds=${java.time.Duration.between(row.sourceUpdatedAt, row.receivedAt).seconds}") }
                        } }, enabled = true)
                        .checkWithFence(7, 9001, 2, 1, 1) { saved }
                    println("LIVE_SIMULATOR_PATH observation=${result.status} nativeInput=${result.nativeInput != null}")
                    assertTrue(result.status in setOf("TRAIN_REFERENCES_ONLY", "SERVICE_PATTERN_OR_FRESHNESS_MISMATCH",
                        "TRAIN_OBSERVATIONS_UNAVAILABLE"))
                }
            }
        }
    }

    @Test fun `official path resolves saved calls without requiring every live arrival`() {
        val pathKey = System.getenv("DATA_GO_KR_SUBWAY_PATH_API_KEY")
        val arrivalsKey = System.getenv("SEOUL_SUBWAY_API_KEY")
        assumeTrue(System.getenv("RUN_SEOUL_OFFICIAL_SUBWAY_LIVE") == "1" &&
            !pathKey.isNullOrBlank() && arrivalsKey?.matches(Regex("[A-Za-z0-9]{8,128}")) == true,
            "live provider check not enabled")
        val f = AutomaticJourneyFixture()
        val route = syntheticSavedSubwayRoute(f.mapper) as ObjectNode
        val leg = route.path("transitLegs")[1] as ObjectNode
        leg.put("lineName", "2호선").put("startName", "강남").put("endName", "선릉")
            .put("directionCode", "DOWN")
        val evidence = leg.path("navigationEvidence") as ObjectNode
        evidence.put("directionCode", "DOWN")
        (evidence.path("subwayIdentifiers") as ObjectNode).put("wayCode", "2")
        val stations = listOf(Triple("강남", 37.497958, 127.027539),
            Triple("역삼", 37.500658, 127.036430), Triple("선릉", 37.504257, 127.048174))
        stations.forEachIndexed { index, (name, latitude, longitude) ->
            val row = leg.path("navigationEvidence").path("stopOccurrences")[index] as ObjectNode
            row.put("name", name)
            (row.path("coord") as ObjectNode).put("lat", latitude).put("lng", longitude)
        }
        (leg.path("startCoord") as ObjectNode).put("lat", stations.first().second).put("lng", stations.first().third)
        (leg.path("endCoord") as ObjectNode).put("lat", stations.last().second).put("lng", stations.last().third)
        val saved = JourneyRouteSnapshot.from(f.plan.copy(route = route), f.mapper)
        SeoulMetroShortestPathClient(requireNotNull(pathKey)).use { path ->
            SeoulMetroStationCoordinateClient().use { coordinates ->
                // The path API returns a successful but empty path after subway service hours.
                // Use a daytime service query to test the official station contract;
                // the live train feed below still uses the current observation time.
                val seoul = ZoneId.of("Asia/Seoul")
                val serviceClock = Clock.fixed(LocalDate.now(seoul).atTime(12, 0).atZone(seoul).toInstant(), seoul)
                val serviceTime = java.time.LocalDateTime.ofInstant(serviceClock.instant(), seoul)
                val segment = path.fetch(SeoulMetroShortestPathClient.Query("강남", "선릉", serviceTime))
                assertNotNull(SavedSubwayOfficialPathComparison.compare(saved, 1, segment))
                val calls = listOf(segment.sections.first().departure) + segment.sections.map { it.arrival }
                val reference = coordinates.fetch()
                assertTrue(calls.zip(stations).all { (official, station) ->
                    reference.corroborates(official, station.second, station.third)
                })
                val terminal = assertNotNull(segment.sections.map { it.terminalCode }.distinct().singleOrNull())
                val full = path.fetch(SeoulMetroShortestPathClient.Query(
                    calls.first().code, terminal, serviceTime, SeoulMetroShortestPathClient.StationValueType.CODE))
                val catalog = LiveOfficialSubwayServicePatternCatalog(
                    { if (it.stationValueType == SeoulMetroShortestPathClient.StationValueType.NAME) segment else full },
                    { reference }, serviceClock)
                val pattern = assertNotNull(catalog.resolve(saved, 1))
                assertEquals(listOf("0222", "0221", "0220"), pattern.stationCalls.take(3))
                assertEquals("0211", pattern.terminalStationId)
                assertEquals(2, pattern.alightingIndex)
                assertEquals(1, pattern.direction)
                val officialNames = requireNotNull(pattern.officialStationNames)
                SeoulTransitArrivalClient("", arrivalsKey, "",
                    "http://swopenapi.seoul.go.kr/api/subway", "http://ws.bus.go.kr/api/rest", true).use { trains ->
                    var providerStatus: SeoulSubwayTrainStatus? = null
                    var diagnostic = ""
                    val checker = SavedJourneySubwayObservationCheck(f.mapper, null,
                        catalog,
                        { query -> trains.getSubwayTrainObservations(query).also { batch ->
                            providerStatus = batch.status
                            val sameDirection = batch.observations.filter { it.direction == pattern.direction && it.service == pattern.service }
                            val matchingTerminal = sameDirection.filter { it.destinationStationId.endsWith(pattern.terminalStationId) &&
                                it.destinationStationName in setOf(officialNames.last(), "${officialNames.last()}종착") }
                            val atCall = matchingTerminal.filter { train -> pattern.stationCalls.indices.any { index ->
                                train.stationId.endsWith(pattern.stationCalls[index]) &&
                                    train.stationName == officialNames[index]
                            } }
                            diagnostic = "rows=${batch.observationCount} direction=${sameDirection.size} terminal=${matchingTerminal.size} atCall=${atCall.size}"
                        } },
                        enabled = true)
                    val result = checker.checkWithFence(7, 9001, 2, 1, 1) { saved }
                    println("LIVE_OFFICIAL_SUBWAY_CHECK status=${result.status} provider=$providerStatus $diagnostic nativeInput=${result.nativeInput != null}")
                    assertTrue(providerStatus in setOf(SeoulSubwayTrainStatus.AVAILABLE,
                        SeoulSubwayTrainStatus.NO_USABLE_OBSERVATIONS, SeoulSubwayTrainStatus.NO_TRAINS))
                    assertTrue(result.status in setOf("TRAIN_REFERENCES_ONLY", "SERVICE_PATTERN_OR_FRESHNESS_MISMATCH",
                        "TRAIN_OBSERVATIONS_UNAVAILABLE"), result.status)
                    if (result.status == "TRAIN_REFERENCES_ONLY") assertNotNull(result.nativeInput)
                    else assertNull(result.nativeInput)
                    assertEquals(false, result.navigationReady)
                    assertEquals(false, result.passengerBoardingConfirmed)
                }
            }
        }
    }
}
