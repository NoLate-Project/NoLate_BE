package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.JourneyRouteSnapshot
import com.noLate.journey.application.JourneySubwaySavedLegEvidence as Resolver
import kotlin.test.*

class JourneySubwaySavedLegEvidenceTest {
    private val f = AutomaticJourneyFixture()
    private fun result(change: (ObjectNode) -> Unit = {}): Resolver.Result {
        val route = syntheticSavedSubwayRoute(f.mapper) as ObjectNode
        change(route)
        return Resolver.resolve(JourneyRouteSnapshot.from(f.plan.copy(route = route), f.mapper), 1)
    }
    private fun ObjectNode.leg() = path("transitLegs")[1] as ObjectNode
    private fun ObjectNode.raw() = leg().path("navigationEvidence") as ObjectNode
    private fun ObjectNode.ids() = raw().path("subwayIdentifiers") as ObjectNode

    @Test fun `raw subway tuples retain ODsay namespace and never claim Seoul crosswalk or boarding`() {
        val r = result(); val data = assertNotNull(r.evidence)
        assertEquals("RAW_SAVED_SUBWAY_METADATA_ONLY", r.status)
        assertEquals("ODSAY", data.namespace); assertEquals("1000", data.routingCity); assertEquals("2", data.routingLine)
        assertEquals("1", data.routingWayCode); assertEquals("222", data.startStationId); assertEquals("224", data.endStationId)
        assertEquals(listOf(0, 10, 20), data.stations.map { it.providerSequence })
        assertEquals(listOf("222", "223", "224"), data.stations.map { it.routingStationId })
        assertFalse(data.seoulRealtimeCrosswalkVerified); assertFalse(data.passengerBoardingConfirmed)
        assertFalse(f.mapper.writeValueAsString(r).contains("222")); assertFalse(data.toString().contains("222"))
    }

    @Test fun `display local station ID and legacy aliases cannot replace raw station IDs`() {
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.raw().remove("subwayIdentifiers") },
            { it.ids().remove("startID"); it.ids().put("startId", "222") },
            { val row = it.raw().path("stopOccurrences")[0] as ObjectNode
                row.remove("routingStationID"); row.put("code", "222"); row.put("localStationID", "222") },
        )) assertNull(result(change).evidence)
    }

    @Test fun `all lane occurrences must be present and unambiguous not first lane wins`() {
        for (change in listOf<(ObjectNode) -> Unit>(
            { (it.ids().path("lanes") as ArrayNode).add(it.ids().path("lanes")[0].deepCopy<ObjectNode>()) },
            { (it.ids().path("lanes") as ArrayNode).removeAll() },
            { (it.ids().path("lanes")[0] as ObjectNode).put("sourceIndex", 1) },
        )) assertEquals("AMBIGUOUS_SUBWAY_LANE", result(change).status)
    }

    @Test fun `city line and displayed routing namespace must agree without Seoul conversion`() {
        for (change in listOf<(ObjectNode) -> Unit>(
            { (it.ids().path("lanes")[0] as ObjectNode).put("subwayCityCode", "7000") },
            { (it.ids().path("lanes")[0] as ObjectNode).put("subwayCode", "1002") },
            { it.leg().put("providerRouteId", "1002") },
            { it.leg().put("routeCityCode", "1002") },
        )) assertEquals("SUBWAY_ROUTING_NAMESPACE_REQUIRED", result(change).status)
    }

    @Test fun `raw way code uses original ODsay 1 and 2 not Seoul 0 and 1`() {
        assertEquals("RAW_SUBWAY_DIRECTION_REQUIRED", result { it.ids().put("wayCode", "0") }.status)
        assertEquals("RAW_SUBWAY_DIRECTION_REQUIRED", result { it.ids().put("wayCode", "2") }.status)
        assertEquals("RAW_SUBWAY_DIRECTION_REQUIRED", result { it.leg().put("directionCode", "DOWN") }.status)
        assertNotNull(result { it.ids().put("wayCode", "2"); it.raw().put("directionCode", "DOWN"); it.leg().put("directionCode", "DOWN") }.evidence)
    }

    @Test fun `raw rows retain order and require complete explicit count original index and increasing sequence`() {
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.raw().remove("declaredStationCount") },
            { it.raw().put("declaredStationCount", 1) },
            { it.leg().put("stationCount", 1) },
            { (it.raw().path("stopOccurrences")[1] as ObjectNode).put("sourceIndex", 0) },
            { (it.raw().path("stopOccurrences")[1] as ObjectNode).put("providerSequence", 0) },
            { (it.raw().path("stopOccurrences")[1] as ObjectNode).put("providerSequence", 1.5) },
            { (it.raw().path("stopOccurrences") as ArrayNode).set(1, f.mapper.nullNode()) },
        )) assertEquals("RAW_SUBWAY_STATIONS_REQUIRED", result(change).status)
    }

    @Test fun `loop station occurrences are preserved not deduplicated to make a unique mapping`() {
        val data = assertNotNull(result {
            (it.raw().path("stopOccurrences")[1] as ObjectNode).put("routingStationID", "222")
        }.evidence)
        assertEquals(listOf("222", "222", "224"), data.stations.map { it.routingStationId })
        assertFalse(data.seoulRealtimeCrosswalkVerified)
    }

    @Test fun `missing malformed unsafe or invalid coordinate cannot create raw station evidence`() {
        for (change in listOf<(ObjectNode) -> Unit>(
            { (it.raw().path("stopOccurrences")[1] as ObjectNode).put("routingStationID", 223) },
            { (it.raw().path("stopOccurrences")[1] as ObjectNode).put("routingStationID", "0") },
            { (it.raw().path("stopOccurrences")[1] as ObjectNode).put("routingStationID", "x".repeat(21)) },
            { (it.raw().path("stopOccurrences")[1].path("coord") as ObjectNode).put("lat", 90) },
        )) assertEquals("RAW_SUBWAY_STATIONS_REQUIRED", result(change).status)
    }

    @Test fun `first last raw station and endpoint tuple must agree with saved leg`() {
        for (change in listOf<(ObjectNode) -> Unit>(
            { (it.raw().path("stopOccurrences")[0] as ObjectNode).put("routingStationID", "223") },
            { (it.raw().path("stopOccurrences")[2] as ObjectNode).put("routingStationID", "223") },
            { (it.leg().path("startCoord") as ObjectNode).put("lat", 37.501) },
        )) assertEquals("SUBWAY_ENDPOINT_OCCURRENCE_MISMATCH", result(change).status)
    }

    @Test fun `wrong provider evidence version bus identity or duplicate route containers reject`() {
        assertEquals("UNSUPPORTED_PROVIDER", result { it.put("provider", "tmap") }.status)
        assertEquals("RAW_SUBWAY_IDENTIFIERS_REQUIRED", result { it.raw().put("version", 2) }.status)
        assertEquals("RAW_SUBWAY_IDENTIFIERS_REQUIRED", result { it.raw().set<ObjectNode>("busIdentifiers", f.mapper.createObjectNode()) }.status)
        assertEquals("AMBIGUOUS_ROUTE_SNAPSHOT", result { it.set<ArrayNode>("legs", it.path("transitLegs").deepCopy()) }.status)
    }

    @Test fun `malformed inconsistent or excessive snapshot cannot throw raw JSON or create evidence`() {
        val valid = JourneyRouteSnapshot.from(f.plan.copy(route = syntheticSavedSubwayRoute(f.mapper)), f.mapper)
        for (json in listOf("", "null", "[]", "{PRIVATE", " ".repeat(1_048_577)))
            assertNull(Resolver.resolve(valid.copy(json = json), 1).evidence)
        assertNull(Resolver.resolve(valid.copy(legsJson = "[]"), 1).evidence)
        for (index in listOf(-1, 0, 64)) assertNull(Resolver.resolve(valid, index).evidence)
    }
}
