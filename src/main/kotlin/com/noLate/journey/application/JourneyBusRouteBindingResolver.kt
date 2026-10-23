package com.noLate.journey.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.journey.domain.*
import kotlin.math.*

/** Saved metadata crosswalk, not an attestation that the provider actually returned these IDs. */
class JourneyBusRouteBindingResolver {
    private val mapper = jacksonObjectMapper()
    fun resolve(snapshot: JourneyRouteSnapshot, busLegIndex: Int = 1): JourneyBusRouteBinding {
        val raw = resolveRaw(snapshot, busLegIndex)
        val evidence = raw.evidence ?: return blocked(raw.status)
        val direction = evidence.direction ?: return blocked("DIRECTION_REQUIRED")
        if (!evidence.hasDeclaredStationCount) return blocked("STOP_OCCURRENCE_REQUIRED")
        return JourneyBusRouteBinding("SUPPORTED", JourneyBusArrivalQuery("SEOUL_BUS", evidence.ars, evidence.routeId, direction, busLegIndex), evidence.boarding)
    }

    /** Raw ODsay identifiers can be checked against an official catalog even when maasRP omits way/count.
     * This is NOT an arrival query or a navigation admission decision. */
    internal fun resolveRaw(snapshot: JourneyRouteSnapshot, busLegIndex: Int = 1): JourneyBusRawBinding {
        if (busLegIndex !in 1..29 || busLegIndex % 2 != 1 || snapshot.legs.getOrNull(busLegIndex - 1)?.kind != "WALK") return rawBlocked("UNSUPPORTED_LEG")
        val root = mapper.readTree(snapshot.json)
        val candidates = listOf(root.path("transitLegs"), root.path("routeInfo").path("transitLegs"),
            root.path("transitJourney").path("legs"), root.path("selectedItinerary").path("legs"),
            root.path("itinerary").path("legs"), root.path("legs")).filter { it.isArray && !it.isEmpty }
        if (candidates.size != 1) return rawBlocked("AMBIGUOUS_ROUTE_SNAPSHOT")
        val container = if (root.path("transitLegs").isArray) root else root.path("routeInfo")
        if (listOf("provider", "source").any { root.hasNonNull(it) && root.path(it) != container.path(it) }) return rawBlocked("AMBIGUOUS_ROUTE_SNAPSHOT")
        if (container.text("provider") != "odsay" || container.text("source") != "api") return rawBlocked("UNSUPPORTED_PROVIDER")
        val leg = mapper.readTree(snapshot.legsJson).path(busLegIndex)
        if (candidates.single().path(busLegIndex) != leg) return rawBlocked("AMBIGUOUS_ROUTE_SNAPSHOT")
        if (snapshot.legs.firstOrNull()?.kind != "WALK") return rawBlocked("UNSUPPORTED_LEG")
        if (leg.text("kind") != "BUS") return rawBlocked("UNSUPPORTED_LEG")
        val provider = leg.text("routeProviderCode")
        if (leg.text("routeCityCode") != "1000" || provider !in setOf("4", "10") ||
            leg.text("startStationCityCode") != "1000" || leg.text("startStationProviderCode") != provider) return rawBlocked("UNSUPPORTED_NAMESPACE")
        val routeId = leg.text("localRouteId")?.takeIf { it.matches(Regex("[1-9][0-9]{0,19}")) }
            ?: return rawBlocked("LOCAL_ROUTE_ID_REQUIRED")
        val ars = ars(leg.text("startArsID")) ?: return rawBlocked("BOARDING_ARS_REQUIRED")
        val localStop = leg.text("startLocalStationID")?.takeIf { it.matches(Regex("[1-9][0-9]{0,19}")) }
            ?: return rawBlocked("LOCAL_STATION_ID_REQUIRED")
        val evidence = leg.path("navigationEvidence")
        if (!evidence.path("version").isIntegralNumber || evidence.path("version").asLong(-1) != 1L || evidence.text("provider") != "odsay") return rawBlocked("RAW_STOP_EVIDENCE_REQUIRED")
        val rawIds = evidence.path("busIdentifiers")
        if (listOf("localRouteId", "routeCityCode", "routeProviderCode", "startLocalStationID", "startArsID", "startStationCityCode", "startStationProviderCode")
                .any { rawIds.text(it) == null || rawIds.path(it) != leg.path(it) }) return rawBlocked("RAW_BUS_IDENTIFIERS_REQUIRED")
        val directionNode = evidence.path("directionName")
        val direction = if (directionNode.isMissingNode || directionNode.isNull) null else
            evidence.text("directionName")?.takeIf { it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl) }
                ?: return rawBlocked("DIRECTION_REQUIRED")
        // Display-derived direction and ODsay busID never substitute for raw local BIS IDs.
        val stops = evidence.path("stopOccurrences")
        val count = evidence.path("declaredStationCount")
        if (!stops.isArray || stops.size() !in 2..500 || (!count.isMissingNode && !count.isNull &&
                (!count.isIntegralNumber || count.asLong() != stops.size().toLong() - 1))) return rawBlocked("STOP_OCCURRENCE_REQUIRED")
        var previous = -1L
        for ((index, stop) in stops.withIndex()) {
            val sequence = stop.path("providerSequence")
            if (!stop.path("sourceIndex").isIntegralNumber || stop.path("sourceIndex").asLong(-1) != index.toLong() || !sequence.isIntegralNumber ||
                sequence.asLong() !in 0..Int.MAX_VALUE.toLong() || sequence.asLong() <= previous || coordinate(stop.path("coord")) == null ||
                stop.text("localStationID")?.matches(Regex("[1-9][0-9]{0,19}")) != true || ars(stop.text("arsID")) == null ||
                stop.text("stationCityCode") != "1000" || stop.text("stationProviderCode") != provider) return rawBlocked("STOP_OCCURRENCE_REQUIRED")
            previous = sequence.asLong()
        }
        val first = stops[0]
        if (first.text("localStationID") != localStop || ars(first.text("arsID")) != ars ||
            first.text("stationCityCode") != "1000" || first.text("stationProviderCode") != provider) return rawBlocked("BOARDING_OCCURRENCE_MISMATCH")
        // Loops with repeated boarding identifiers need an additional provider occurrence lookup; no guess.
        if (stops.count { it.text("localStationID") == localStop || ars(it.text("arsID")) == ars } != 1) return rawBlocked("AMBIGUOUS_BOARDING_OCCURRENCE")
        val coord = coordinate(first.path("coord"))!!
        val start = coordinate(leg.path("startCoord")) ?: return rawBlocked("BOARDING_COORDINATE_REQUIRED")
        if (distance(coord, start) > 30) return rawBlocked("BOARDING_OCCURRENCE_MISMATCH")
        return JourneyBusRawBinding("RAW_VALID", JourneyBusRawEvidence(routeId, ars, direction, count.isIntegralNumber,
            JourneyBusStopOccurrence(0, first.path("providerSequence").asInt(), localStop, coord.first, coord.second)))
    }
    private fun blocked(reason: String) = JourneyBusRouteBinding(reason)
    private fun rawBlocked(reason: String) = JourneyBusRawBinding(reason)
    private fun JsonNode.text(key: String) = path(key).takeIf { it.isTextual }?.asText()
    private fun ars(value: String?): String? = value?.takeIf { it.matches(Regex("[0-9]{5}|[0-9]{2}-[0-9]{3}")) }
        ?.replace("-", "")?.takeIf { it != "00000" }
    private fun coordinate(node: JsonNode): Pair<Double, Double>? {
        if (!node.path("lat").isNumber || !node.path("lng").isNumber) return null
        val lat = node.path("lat").asDouble(); val lng = node.path("lng").asDouble()
        return (lat to lng).takeIf { lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0 }
    }
    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dlat = Math.toRadians(b.first - a.first); val dlng = Math.toRadians(b.second - a.second)
        val h = sin(dlat / 2).pow(2) + cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dlng / 2).pow(2)
        return 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

internal data class JourneyBusRawBinding(val status: String, val evidence: JourneyBusRawEvidence? = null)
internal data class JourneyBusRawEvidence(val routeId: String, val ars: String, val direction: String?,
    val hasDeclaredStationCount: Boolean, val boarding: JourneyBusStopOccurrence) {
    override fun toString() = "JourneyBusRawEvidence(REDACTED)"
}
