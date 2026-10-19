package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.*
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.transit.infrastructure.SeoulMetroShortestPathClient
import com.noLate.transit.infrastructure.SeoulMetroStationCoordinateClient
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Opt-in simulator fixture: saved passenger route is artificial, but station topology and
 * train observations come from the official live clients. Never asserts that the rider boarded. */
internal class OfficialSubwayNavigationFixture(
    pathKey: String, trainKey: String, private val clock: Clock,
) : AutoCloseable {
    private val path = SeoulMetroShortestPathClient(pathKey)
    private val coordinates = SeoulMetroStationCoordinateClient()
    private val trains = SeoulTransitArrivalClient("", trainKey, "",
        "http://swopenapi.seoul.go.kr/api/subway", "http://ws.bus.go.kr/api/rest", true)
    val requests = AtomicInteger()
    val lastProviderSummary = AtomicReference<Map<String, Any>>(emptyMap())

    fun service(f: WalkingSnapshotDatabaseFixture, automatic: AutomaticJourneyService): JourneySubwayObservationService {
        val catalog = LiveOfficialSubwayServicePatternCatalog({ path.fetch(it) }, { coordinates.fetch() }, clock)
        // The HTTP harness freezes its fixture clock at request entry. A live provider stamps
        // receipt after I/O, so the matcher must use the advancing wall clock here.
        val check = SavedJourneySubwayObservationCheck(f.fixture.mapper, null, catalog,
            { query ->
                requests.incrementAndGet()
                trains.getSubwayTrainObservations(query).also { batch ->
                    lastProviderSummary.set(mapOf("status" to batch.status.name,
                        "usable" to batch.observationCount,
                        "rejections" to batch.rejectionCounts.mapKeys { it.key.name },
                        "receivedAt" to clock.instant().toString(),
                        "routeStationRows" to batch.observations.filter {
                            it.stationName in setOf("강남", "역삼", "선릉")
                        }.map {
                            mapOf("train" to it.trainNumber, "station" to it.stationName,
                                "stationId" to it.stationId, "destination" to it.destinationStationName,
                                "destinationId" to it.destinationStationId, "direction" to it.direction,
                                "service" to it.service, "state" to it.state,
                                "sourceAgeSeconds" to Duration.between(it.sourceUpdatedAt, it.receivedAt).seconds)
                        }))
                }
            }, Clock.systemUTC(), true)
        return JourneySubwayObservationService({ member, schedule, login, session, command ->
            automatic.requireSubwayObservationFence(member, schedule, login, session, command)
        }, check)
    }

    override fun close() { trains.close(); coordinates.close(); path.close() }

    companion object {
        fun apply(f: AutomaticJourneyFixture) {
            SubwayNavigationFixture.apply(f)
            val route = f.plan.route!!.deepCopy<ObjectNode>()
            route.put("id", "official-live-subway-simulator-fixture")
                .put("label", "강남 → 선릉 시험 경로")
                .put("summary", "공식 열차 조회 · 시뮬레이터 위치 시험")
            val legs = route.path("transitLegs")
            val stations = listOf(
                Triple("강남", 37.497958, 127.027539),
                Triple("역삼", 37.500658, 127.036430),
                Triple("선릉", 37.504257, 127.048174),
            )
            fun point(lat: Double, lng: Double): ObjectNode = f.mapper.createObjectNode()
                .put("lat", lat).put("lng", lng)
            fun path(leg: ObjectNode, values: List<ObjectNode>) {
                leg.set<JsonNode>("startCoord", values.first())
                leg.set<JsonNode>("endCoord", values.last())
                val coords = leg.putArray("pathCoords")
                values.forEach { coords.add(it.deepCopy()) }
            }
            path(legs[0] as ObjectNode, listOf(point(37.4970, 127.0268),
                point(stations.first().second, stations.first().third)))
            val train = legs[1] as ObjectNode
            train.put("label", "2호선").put("lineName", "2호선")
                .put("startName", "강남").put("endName", "선릉")
                .put("directionCode", "DOWN")
            path(train, stations.map { point(it.second, it.third) })
            val evidence = train.path("navigationEvidence") as ObjectNode
            evidence.put("directionCode", "DOWN")
            (evidence.path("subwayIdentifiers") as ObjectNode).put("wayCode", "2")
            stations.forEachIndexed { index, (name, latitude, longitude) ->
                val stop = evidence.path("stopOccurrences")[index] as ObjectNode
                // ODsay routing IDs are not the official Seoul station codes (0222/0221/0220).
                // Keep the saved raw namespace internally consistent with startID/endID.
                stop.put("name", name).put("routingStationID", (222+index).toString())
                stop.set<JsonNode>("coord", point(latitude, longitude))
            }
            path(legs[2] as ObjectNode, listOf(point(stations.last().second, stations.last().third),
                point(37.5046, 127.0488)))
            f.plan = f.plan.copy(route = route,
                origin = SchedulePlaceDto(name = "강남역 인근", lat = 37.4970, lng = 127.0268),
                destination = SchedulePlaceDto(name = "선릉역 인근", lat = 37.5046, lng = 127.0488))
        }
    }
}
