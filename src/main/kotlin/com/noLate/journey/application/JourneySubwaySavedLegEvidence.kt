package com.noLate.journey.application

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** ODsay raw metadata only. No Seoul station ID, service pattern, or passenger association is
 * derived here. A later independent catalog must crosswalk these exact ordered tuples. */
internal object JourneySubwaySavedLegEvidence {
    class Station(val sourceIndex: Int, val providerSequence: Int, val routingStationId: String,
        val latitude: Double, val longitude: Double) {
        override fun toString() = "RawSubwayStation(REDACTED)"
    }
    class Evidence(val legIndex: Int, val routingCity: String, val routingLine: String, val routingWayCode: String,
        val startStationId: String, val endStationId: String, stations: List<Station>) {
        val stations = stations.toList()
        val namespace = "ODSAY"
        val seoulRealtimeCrosswalkVerified = false
        val passengerBoardingConfirmed = false
        override fun toString() = "RawSubwayLegEvidence(REDACTED)"
    }
    class Result(val status: String, @get:JsonIgnore val evidence: Evidence? = null) {
        val seoulRealtimeCrosswalkVerified = false
        override fun toString() = "RawSubwayLegResult(status=$status)"
    }
    private val mapper = jacksonObjectMapper()

    fun resolve(snapshot: JourneyRouteSnapshot, legIndex: Int): Result {
        fun blocked(reason: String) = Result(reason)
        if (legIndex !in 0..63 || snapshot.json.toByteArray().size > 1_048_576 ||
            snapshot.legsJson.toByteArray().size > 1_048_576) return blocked("UNSUPPORTED_SUBWAY_LEG")
        val root = try { mapper.readTree(snapshot.json) } catch (_: Exception) { return blocked("UNSUPPORTED_SAVED_ROUTE") }
        if (root == null || !root.isObject) return blocked("UNSUPPORTED_SAVED_ROUTE")
        val candidates = listOf(root.path("transitLegs"), root.path("routeInfo").path("transitLegs"),
            root.path("transitJourney").path("legs"), root.path("selectedItinerary").path("legs"),
            root.path("itinerary").path("legs"), root.path("legs")).filter { it.isArray && !it.isEmpty }
        if (candidates.size != 1) return blocked("AMBIGUOUS_ROUTE_SNAPSHOT")
        val container = if (root.path("transitLegs").isArray) root else root.path("routeInfo")
        if (!container.isObject || container.path("transitLegs") != candidates.single() ||
            listOf("provider", "source").any { root.hasNonNull(it) && root.path(it) != container.path(it) })
            return blocked("AMBIGUOUS_ROUTE_SNAPSHOT")
        if (container.text("provider") != "odsay" || container.text("source") != "api") return blocked("UNSUPPORTED_PROVIDER")
        val leg = candidates.single().path(legIndex)
        if (leg.text("kind") != "SUBWAY" || snapshot.legs.getOrNull(legIndex)?.kind != "SUBWAY")
            return blocked("UNSUPPORTED_SUBWAY_LEG")
        val normalized = try { mapper.readTree(snapshot.legsJson).path(legIndex) }
            catch (_: Exception) { return blocked("UNSUPPORTED_SAVED_ROUTE") }
        if (normalized != leg) return blocked("AMBIGUOUS_ROUTE_SNAPSHOT")
        val raw = leg.path("navigationEvidence")
        if (raw.path("version").integer() != 1 || raw.text("provider") != "odsay" || raw.hasNonNull("busIdentifiers"))
            return blocked("RAW_SUBWAY_IDENTIFIERS_REQUIRED")
        val ids = raw.path("subwayIdentifiers")
        if (!ids.isObject) return blocked("RAW_SUBWAY_IDENTIFIERS_REQUIRED")
        val lanes = ids.path("lanes")
        if (!lanes.isArray || lanes.size() != 1 || lanes[0].path("sourceIndex").integer() != 0)
            return blocked("AMBIGUOUS_SUBWAY_LANE")
        val lane = lanes[0]
        val city = lane.text("subwayCityCode")
        val line = lane.text("subwayCode")?.takeIf(::validId)
        if (city != "1000" || line == null || leg.text("routeCityCode") != city ||
            leg.text("providerRouteId") != "$city:$line") return blocked("SUBWAY_ROUTING_NAMESPACE_REQUIRED")
        val start = ids.text("startID")?.takeIf(::validId)
        val end = ids.text("endID")?.takeIf(::validId)
        if (start == null || end == null || leg.text("startID") != start || leg.text("endID") != end)
            return blocked("RAW_SUBWAY_IDENTIFIERS_REQUIRED")
        val way = ids.text("wayCode")?.takeIf { it == "1" || it == "2" }
            ?: return blocked("RAW_SUBWAY_DIRECTION_REQUIRED")
        val direction = if (way == "1") "UP" else "DOWN"
        if (raw.text("directionCode") != direction || leg.text("directionCode") != direction)
            return blocked("RAW_SUBWAY_DIRECTION_REQUIRED")
        val rows = raw.path("stopOccurrences")
        val count = raw.path("declaredStationCount").integer()
        if (!rows.isArray || rows.size() !in 2..500 || count != rows.size() - 1 || leg.path("stationCount").integer() != count)
            return blocked("RAW_SUBWAY_STATIONS_REQUIRED")
        var previous = -1
        val stations = rows.mapIndexed { index, row ->
            val sequence = row.path("providerSequence").integer()
            val id = row.text("routingStationID")?.takeIf(::validId)
            val coord = coordinate(row.path("coord"))
            if (row.path("sourceIndex").integer() != index || sequence == null || sequence <= previous || id == null || coord == null)
                return blocked("RAW_SUBWAY_STATIONS_REQUIRED")
            previous = sequence
            Station(index, sequence, id, coord.first, coord.second)
        }
        val first = stations.first(); val last = stations.last()
        if (first.routingStationId != start || last.routingStationId != end ||
            coordinate(leg.path("startCoord")) != (first.latitude to first.longitude) ||
            coordinate(leg.path("endCoord")) != (last.latitude to last.longitude))
            return blocked("SUBWAY_ENDPOINT_OCCURRENCE_MISMATCH")
        return Result("RAW_SAVED_SUBWAY_METADATA_ONLY", Evidence(legIndex, city, line, way, start, end, stations))
    }

    private fun validId(value: String) = value.matches(Regex("[0-9]{1,20}")) && value.any { it != '0' }
    private fun JsonNode.text(key: String) = path(key).takeIf { it.isTextual }?.asText()
    private fun JsonNode.integer(): Int? = takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()?.takeIf { it >= 0 }
    private fun coordinate(node: JsonNode): Pair<Double, Double>? {
        if (!node.path("lat").isNumber || !node.path("lng").isNumber) return null
        val lat = node.path("lat").asDouble(); val lng = node.path("lng").asDouble()
        return (lat to lng).takeIf { lat.isFinite() && lng.isFinite() && lat in 33.0..39.5 && lng in 124.0..132.0 }
    }
}
