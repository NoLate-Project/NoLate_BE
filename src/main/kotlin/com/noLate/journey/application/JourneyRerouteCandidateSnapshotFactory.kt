package com.noLate.journey.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.notification.domain.OpaquePushIdentifier
import kotlin.math.*

/** Builds a server-owned route snapshot from an ODsay candidate and two verified TMAP walks.
 * This is only an input to static official transit admission; it is not a live session. */
class JourneyRerouteCandidateSnapshotFactory(private val mapper: ObjectMapper) {
    fun bus(candidate: JourneyRerouteSearchService.CandidateContext,
        walking: JourneyRerouteWalkingProofService.Proof): JourneyRouteSnapshot {
        val raw = candidate.path.path("rps")
        if (!raw.isArray || raw.size() != 3 || raw[0].path("trafficType").asInt(-1) != 3 ||
            raw[1].path("trafficType").asInt(-1) != 2 || raw[2].path("trafficType").asInt(-1) != 3 ||
            walking.walkingPaths.keys != setOf(0, 2)) reject()
        val ride = raw[1]
        val first = walking.walkingPaths[0] ?: reject()
        val last = walking.walkingPaths[2] ?: reject()
        val boarding = coord(ride.path("startY"), ride.path("startX"))
        val alighting = coord(ride.path("endY"), ride.path("endX"))
        if (first.size < 2 || last.size < 2 ||
            meters(first.first(), candidate.origin) > 30 || meters(first.last(), boarding) > 75 ||
            meters(last.first(), alighting) > 75 || meters(last.last(), candidate.destination) > 30)
            reject()
        val bus = mapper.createObjectNode().apply {
            put("kind", "BUS")
            for (key in listOf("startName", "endName")) ride.path(key).takeIf { it.isTextual &&
                it.asText().length in 1..100 && it.asText().none(Char::isISOControl) }
                ?.let { put(key, it.asText()) }
            val line = ride.path("lane").path(0)
            listOf(line.path("busNo"), line.path("name")).firstOrNull { it.isTextual &&
                it.asText().length in 1..100 && it.asText().none(Char::isISOControl) }
                ?.let { put("lineName", it.asText()) }
            ride.path("duration").takeIf { it.isNumber && it.asDouble() in 0.01..1_440.0 }
                ?.let { put("durationMinutes", it.asDouble()) }
            put("pathCoordsIsExact", true)
            put("pathGeometrySource", "TRANSIT_PASS_SHAPE_LINESTRING")
            set<ObjectNode>("startCoord", point(boarding))
            set<ObjectNode>("endCoord", point(alighting))
            set<ArrayNode>("pathCoords", graph(ride.path("graph"), boarding, alighting))
            copyIdentifier(ride.path("lane").path(0), "busLocalBlID", "localRouteId")
            copyIdentifier(ride.path("lane").path(0), "busCityCode", "routeCityCode")
            copyIdentifier(ride.path("lane").path(0), "busProviderCode", "routeProviderCode")
            for (key in listOf("startLocalStationID", "startArsID", "startStationCityCode", "startStationProviderCode"))
                copyIdentifier(ride, key, key)
            val direction = ride.path("way")
            if (direction.isTextual && direction.asText().length in 1..128 && direction.asText().none(Char::isISOControl))
                put("directionName", direction.asText())
            val busFields = this
            val evidence = mapper.createObjectNode().apply {
                put("version", 1)
                put("provider", "odsay")
                put("geometryInputComplete", true)
                if (busFields.has("directionName")) put("directionName", busFields.path("directionName").asText())
                set<ObjectNode>("busIdentifiers", mapper.createObjectNode().also { ids ->
                    for (key in listOf("localRouteId", "routeCityCode", "routeProviderCode",
                        "startLocalStationID", "startArsID", "startStationCityCode", "startStationProviderCode"))
                        ids.set<JsonNode>(key, busFields.path(key).deepCopy())
                })
                val stations = ride.path("passStopList").path("stations")
                if (!stations.isArray || stations.size() !in 2..64) reject()
                val occurrences = mapper.createArrayNode()
                val passStops = mapper.createArrayNode()
                stations.forEachIndexed { index, station ->
                    val sequence = station.path("index")
                    if (!sequence.isIntegralNumber || !sequence.canConvertToInt() || sequence.asInt() < 0) reject()
                    val stop = mapper.createObjectNode().apply {
                        put("sourceIndex", index)
                        put("providerSequence", sequence.asInt())
                        set<ObjectNode>("coord", point(coord(station.path("y"), station.path("x"))))
                        for (key in listOf("localStationID", "arsID", "stationCityCode", "stationProviderCode"))
                            copyIdentifier(station, key, key)
                        put("code", path("localStationID").asText())
                        station.path("stationName").takeIf { it.isTextual && it.asText().length in 1..100 &&
                            it.asText().none(Char::isISOControl) }?.let { put("name", it.asText()) }
                    }
                    occurrences.add(stop)
                    passStops.add(mapper.createObjectNode().apply {
                        put("sequence", index + 1)
                        set<ObjectNode>("coord", point(coord(station.path("y"), station.path("x"))))
                        stop.path("name").takeIf { it.isTextual }?.let { put("name", it.asText()) }
                    })
                }
                set<ArrayNode>("stopOccurrences", occurrences)
                busFields.set<ArrayNode>("passStops", passStops)
                val declared = ride.path("stationCount")
                // maasRP commonly omits stationCount while returning the complete,
                // ordered passStopList. Preserve a supplied count only if it agrees.
                if (!declared.isMissingNode && !declared.isNull &&
                    (!declared.isIntegralNumber || declared.asInt(-1) != stations.size() - 1)) reject()
                put("declaredStationCount", stations.size() - 1)
            }
            set<ObjectNode>("navigationEvidence", evidence)
            put("stationCount", evidence.path("stopOccurrences").size() - 1)
        }
        val legs = mapper.createArrayNode().apply {
            add(walk(first)); add(bus); add(walk(last))
        }
        val route = mapper.createObjectNode().apply {
            // The app's saved-route map and time rail only accept a route option
            // with an identity. Preserve the provider's total travel estimate too.
            put("id", "odsay-reroute-${OpaquePushIdentifier.fingerprint(mapper.writeValueAsString(legs)).take(16)}")
            put("mode", "TRANSIT")
            put("provider", "odsay")
            put("source", "api")
            put("routeReliability", "provider_estimate")
            put("routePlausibility", "normal")
            put("transitServiceState", "unknown")
            candidate.path.path("totalTime").takeIf { it.isNumber && it.asDouble() in 0.01..1_440.0 }
                ?.let { put("minutes", it.asDouble()) }
            set<ArrayNode>("transitLegs", legs)
        }
        val json = mapper.writeValueAsString(route)
        if (json.toByteArray(Charsets.UTF_8).size > 1_048_576) reject()
        return JourneyRouteSnapshot(json, mapper.writeValueAsString(legs),
            OpaquePushIdentifier.fingerprint(json), JourneyRouteSnapshot.describe(legs))
    }

    private fun ObjectNode.copyIdentifier(source: JsonNode, rawKey: String, targetKey: String) {
        val value = source.path(rawKey)
        val id = when {
            value.isTextual -> value.asText()
            value.isIntegralNumber && value.canConvertToLong() -> value.asLong().toString()
            else -> reject()
        }
        if (id.length !in 1..32 || if (rawKey.equals("arsID", true) || rawKey.endsWith("ArsID"))
            !id.matches(Regex("[0-9]{5}|[0-9]{2}-[0-9]{3}")) else id.any { !it.isDigit() }) reject()
        put(targetKey, id)
    }
    private fun walk(points: List<ServerWalkingCoordinate>) = mapper.createObjectNode().apply {
        put("kind", "WALK")
        set<ObjectNode>("startCoord", point(points.first()))
        set<ObjectNode>("endCoord", point(points.last()))
        set<ArrayNode>("pathCoords", mapper.createArrayNode().apply { points.forEach { add(point(it)) } })
        put("pathCoordsIsExact", true)
        put("pathGeometrySource", "WALK_STEPS_LINESTRING")
        set<ObjectNode>("navigationEvidence", mapper.createObjectNode().apply {
            put("version", 1)
            put("provider", "tmap")
            put("geometryInputComplete", true)
            set<ArrayNode>("stopOccurrences", mapper.createArrayNode())
        })
    }
    private fun point(value: ServerWalkingCoordinate) = mapper.createObjectNode().apply {
        put("lat", value.lat); put("lng", value.lng)
    }
    private fun graph(value: JsonNode, start: ServerWalkingCoordinate, end: ServerWalkingCoordinate): ArrayNode {
        if (!value.isTextual || value.asText().length !in 7..100_000) reject()
        val parts = value.asText().split('|')
        if (parts.size !in 2..2000) reject()
        val points = mapper.createArrayNode()
        for (part in parts) {
            val pair = part.trim().split(Regex("\\s+"))
            if (pair.size != 2) reject()
            val lng = pair[0].toDoubleOrNull() ?: reject()
            val lat = pair[1].toDoubleOrNull() ?: reject()
            points.add(point(coord(mapper.valueToTree<JsonNode>(lat), mapper.valueToTree<JsonNode>(lng))))
        }
        val first = coord(points[0].path("lat"), points[0].path("lng"))
        val last = coord(points[points.size() - 1].path("lat"), points[points.size() - 1].path("lng"))
        if (meters(first, start) > 30 || meters(last, end) > 30) reject()
        return points
    }
    private fun coord(lat: JsonNode, lng: JsonNode): ServerWalkingCoordinate {
        if (!lat.isNumber || !lng.isNumber || !lat.asDouble().isFinite() || !lng.asDouble().isFinite() ||
            lat.asDouble() !in 33.0..39.5 || lng.asDouble() !in 124.0..132.0) reject()
        return ServerWalkingCoordinate(lat.asDouble(), lng.asDouble())
    }
    private fun meters(a: ServerWalkingCoordinate, b: ServerWalkingCoordinate): Double {
        val dlat = Math.toRadians(b.lat - a.lat); val dlng = Math.toRadians(b.lng - a.lng)
        val h = sin(dlat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dlng / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    private fun reject(): Nothing = throw WalkingContractRejected("INVALID_REROUTE_TRANSIT")
}
