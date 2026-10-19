package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** Synthetic ODsay namespace tuples, intentionally distinct from synthetic Seoul station IDs. */
internal fun syntheticSavedSubwayRoute(mapper: ObjectMapper, transfer: Boolean = false): JsonNode {
    val coords = listOf(mapOf("lat" to 37.50, "lng" to 127.00), mapOf("lat" to 37.51, "lng" to 127.01),
        mapOf("lat" to 37.52, "lng" to 127.02))
    val leg = mapOf("kind" to "SUBWAY", "stationCount" to 2, "routeCityCode" to "1000", "providerRouteId" to "1000:2",
        "startID" to "222", "endID" to "224", "startCoord" to coords.first(), "endCoord" to coords.last(), "directionCode" to "UP",
        "navigationEvidence" to mapOf("version" to 1, "provider" to "odsay", "declaredStationCount" to 2, "directionCode" to "UP",
            "subwayIdentifiers" to mapOf("startID" to "222", "endID" to "224", "wayCode" to "1",
                "lanes" to listOf(mapOf("sourceIndex" to 0, "subwayCode" to "2", "subwayCityCode" to "1000"))),
            "stopOccurrences" to coords.mapIndexed { index, coord -> mapOf("sourceIndex" to index,
                "providerSequence" to index * 10, "routingStationID" to (222 + index).toString(), "coord" to coord) }))
    val walk = mapOf("kind" to "WALK")
    return mapper.valueToTree(mapOf("provider" to "odsay", "source" to "api", "transitLegs" to
        if (transfer) listOf(walk, leg, walk, leg, walk) else listOf(walk, leg, walk)))
}
