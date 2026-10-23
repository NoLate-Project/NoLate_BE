package com.noLate.journey.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import java.security.MessageDigest

/** Full native v1 allow-list parity against the owned saved route, not client-selected geometry. */
class SavedJourneyInputValidator(private val mapper: ObjectMapper) {
    fun validate(plan: ScheduleTravelPlanDto, json: String, digest: String, revision: Long = 1,
        walking: WalkingSnapshotContract? = null) {
        if (json.toByteArray().size !in 1..1_048_576 ||
            MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) } != digest) reject()
        val snapshot = JourneyRouteSnapshot.from(plan, mapper)
        val raw = mapper.readTree(snapshot.json)
        if (raw.has("mode") && raw.path("mode").asText() != "TRANSIT") reject()
        val arrays = listOf(raw.path("transitLegs"), raw.path("routeInfo").path("transitLegs"))
            .filter { it.isArray && !it.isEmpty }
        if (arrays.size != 1 || arrays[0].size() > 32) reject()
        val route = if (raw.path("transitLegs").isArray) raw else raw.path("routeInfo")
        val actual = runCatching {
            mapper.factory.createParser(json).use { parser ->
                parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                val node = mapper.readTree<JsonNode>(parser)
                if (parser.nextToken() != null) reject()
                node
            }
        }.getOrElse { reject() }
        val origin = mapper.valueToTree<JsonNode>(plan.origin); val destination = mapper.valueToTree<JsonNode>(plan.destination)
        var points = 0; var stops = 0
        val legs = arrays[0].mapIndexed { index, leg ->
            val evidence = leg.path("navigationEvidence")
            val path = leg.path("pathCoords").takeIf { it.isArray }?.toList() ?: emptyList()
            val occurrences = evidence.path("stopOccurrences").takeIf { it.isArray }?.toList() ?: emptyList()
            points += path.size; stops += occurrences.size
            if (path.size > 2000 || occurrences.size > 500 || points > 12000 || stops > 2000) reject()
            linkedMapOf<String, Any?>("index" to index, "kind" to choice(leg, "kind", setOf("WALK", "BUS", "SUBWAY", "ETC")),
                "geometrySource" to (text(leg, "pathGeometrySource") ?: "UNKNOWN"), "exact" to (leg.path("pathCoordsIsExact") == mapper.booleanNode(true)),
                "geometryInputComplete" to (evidence.path("geometryInputComplete") == mapper.booleanNode(true)),
                "coordinates" to path.map(::coordinate), "start" to coordinate(leg.path("startCoord")), "end" to coordinate(leg.path("endCoord")),
                "providerRouteId" to text(leg, "providerRouteId"), "localRouteId" to text(leg, "localRouteId"),
                "directionName" to text(evidence, "directionName"), "directionCode" to text(evidence, "directionCode"),
                "serviceAvailable" to leg.path("serviceAvailable").takeIf { it.isBoolean }?.booleanValue(),
                "declaredStationCount" to integer(evidence, "declaredStationCount"), "stopEvidenceProvider" to text(evidence, "provider"),
                "stopEvidenceVersion" to integer(evidence, "version"), "stops" to occurrences.map { stop ->
                    linkedMapOf<String,Any?>("sourceIndex" to (integer(stop, "sourceIndex") ?: -1), "providerSequence" to integer(stop, "providerSequence"),
                        "name" to text(stop, "name"), "code" to text(stop, "code"), "coordinate" to coordinate(stop.path("coord"))).also { row ->
                        if(leg.path("kind").asText()=="SUBWAY")text(stop,"routingStationID")?.let{row["routingStationId"]=it}
                    }
                }).also { expectedLeg ->
                    // Legacy v1 without display remains valid, but cannot acquire new metadata.
                    // Any supplied display must exactly match the owned saved provider route.
                    if (actual.path("legs").path(index).has("display")) {
                        val transit = leg.path("kind").asText() in setOf("BUS", "SUBWAY")
                        expectedLeg["display"] = linkedMapOf(
                            "lineName" to if (transit) displayName(leg.path("lineName")) else null,
                            "lineColor" to if (transit) displayColor(leg.path("lineColor")) else null,
                            "durationMinutes" to displayNumber(leg.path("durationMinutes"), 1440.0),
                            "distanceMeters" to displayNumber(leg.path("distanceMeters"), 10_000_000.0))
                    }
                }
        }
        val expected = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(linkedMapOf("schemaVersion" to 1, "routeRevision" to revision, "context" to "SAVED_ROUTE",
            "provider" to choice(route, "provider", setOf("tmap", "odsay")), "source" to choice(route, "source", setOf("api", "fallback")),
            "reliability" to choice(route, "routeReliability", setOf("live_provider", "provider_estimate", "fallback_estimate")),
            "plausibility" to choice(route, "routePlausibility", setOf("normal", "geometry_suspected")),
            "serviceState" to choice(route, "transitServiceState", setOf("operating", "not_operating")),
            "origin" to coordinate(origin), "destination" to coordinate(destination), "legs" to legs))
        // The saved ODsay route stays byte-for-byte equivalent in meaning. Walking is a
        // separate server-owned contract, never a client replacement of its geometry/evidence.
        if (walking != null) expected.set<JsonNode>("walkingSnapshot", mapper.valueToTree(walking))
        if (!same(actual, expected) || expected.path("origin").isNull || expected.path("destination").isNull) reject()
    }

    /** DEVICE admission is narrower than a structurally valid saved route. Only a single
     * transit leg may be prepared; a static official crosswalk never confirms boarding. */
    fun requireDeviceReady(plan: ScheduleTravelPlanDto, json: String, walking: WalkingSnapshotContract?,
        officialBus: JourneyBusDeviceAdmission? = null,
        officialSubway: JourneySubwayDeviceAdmission? = null) {
        val input = runCatching { mapper.readTree(json) }.getOrNull() ?: rejectDevice()
        val legs = input.path("legs")
        if (officialBus != null && officialSubway != null) rejectDevice()
        val kind = if (officialSubway != null) "SUBWAY" else "BUS"
        if (walking == null || input.path("provider").asText() != "odsay" || input.path("source").asText() != "api" ||
            input.path("plausibility").asText() != "normal" ||
            input.path("serviceState").asText() !in (if (officialBus == null && officialSubway == null) setOf("operating") else setOf("operating", "UNKNOWN")) ||
            input.path("reliability").asText() !in setOf("live_provider", "provider_estimate") ||
            !legs.isArray || legs.size() != 3 || listOf("WALK", kind, "WALK").indices.any { index ->
                legs[index].path("index").asInt(-1) != index || legs[index].path("kind").asText() != listOf("WALK", kind, "WALK")[index]
            }) rejectDevice()
        val transit = legs[1]
        if (transit.path("geometrySource").asText() != "TRANSIT_PASS_SHAPE_LINESTRING" ||
            !transit.path("exact").asBoolean(false) || !transit.path("geometryInputComplete").asBoolean(false) ||
            transit.path("coordinates").size() !in 2..2000) rejectDevice()
        if (transit.path("serviceAvailable").isBoolean && !transit.path("serviceAvailable").asBoolean()) rejectDevice()
        if (officialSubway != null) {
            val raw = JourneySubwaySavedLegEvidence.resolve(JourneyRouteSnapshot.from(plan, mapper), 1).evidence ?: rejectDevice()
            if (officialSubway.schemaVersion != 1 || officialSubway.identityEvidence != "OFFICIAL_STATION_PATH_CROSSWALK" ||
                officialSubway.subwayLegIndex != 1 || officialSubway.providerRouteId != "${raw.routingCity}:${raw.routingLine}" ||
                officialSubway.providerRouteId != transit.path("providerRouteId").asText() ||
                officialSubway.startRoutingStationId != raw.startStationId ||
                officialSubway.endRoutingStationId != raw.endStationId ||
                officialSubway.stationCount !in 1..63 || officialSubway.stationCount != raw.stations.size - 1 ||
                transit.path("stops").size() != raw.stations.size ||
                raw.stations.indices.any { transit.path("stops").path(it).path("routingStationId").asText() != raw.stations[it].routingStationId } ||
                transit.path("declaredStationCount").takeIf { it.isNumber }?.asInt()?.let { it != officialSubway.stationCount } == true)
                rejectDevice()
        } else if (officialBus == null) {
            val binding = JourneyBusRouteBindingResolver().resolve(JourneyRouteSnapshot.from(plan, mapper))
            if (binding.query?.providerRouteId?.matches(Regex("[1-9][0-9]{8}")) != true) rejectDevice()
        } else {
            val raw = JourneyBusRouteBindingResolver().resolveRaw(JourneyRouteSnapshot.from(plan, mapper))
            if (raw.evidence == null || officialBus.schemaVersion != 1 ||
                officialBus.identityEvidence != "OFFICIAL_STOP_SHAPE_CROSSWALK" || officialBus.busLegIndex != 1 ||
                officialBus.routeId != raw.evidence.routeId || officialBus.routeId != transit.path("localRouteId").asText() ||
                officialBus.stationCount != legs[1].path("stops").size() - 1 ||
                officialBus.firstStopSequence < 0 || officialBus.lastStopSequence.toLong() - officialBus.firstStopSequence.toLong() != officialBus.stationCount.toLong() ||
                officialBus.direction.isBlank() || officialBus.direction.length > 128 || officialBus.direction.any(Char::isISOControl) ||
                raw.evidence.direction?.let { it != officialBus.direction } == true ||
                transit.path("directionName").takeIf { it.isTextual }?.asText()?.let { it != officialBus.direction } == true ||
                transit.path("declaredStationCount").takeIf { it.isNumber }?.asInt()?.let { it != officialBus.stationCount } == true) rejectDevice()
        }
    }

    private fun rejectDevice(): Nothing = throw BusinessException(
        ErrorCode.INVALID_STATE, "이 저장 경로는 아직 실기기 자동 길안내를 지원하지 않습니다.")
    // JS encodes 1.0 as 1; Jackson IntNode/LongNode/DoubleNode equality is too strict.
    // Only numeric representation is relaxed, never object keys, order, strings or nulls.
    private fun same(a: JsonNode?, b: JsonNode): Boolean {
        if (a == null) return false
        if (a.isNumber && b.isNumber) return a.decimalValue().compareTo(b.decimalValue()) == 0
        if (a.isObject && b.isObject) return a.size() == b.size() && b.fields().asSequence().all { same(a.get(it.key), it.value) }
        if (a.isArray && b.isArray) return a.size() == b.size() && (0 until b.size()).all { same(a[it], b[it]) }
        return a == b
    }
    private fun ObjectMapper.booleanNode(value: Boolean) = nodeFactory.booleanNode(value)
    private fun displayNumber(node: JsonNode, maximum: Double): Double? = node.takeIf {
        it.isNumber && it.asDouble().isFinite() && it.asDouble() > 0 && it.asDouble() <= maximum
    }?.asDouble()
    private fun displayName(node: JsonNode): String? = node.takeIf { it.isTextual }?.asText()?.takeIf {
        it.trim().isNotEmpty() && it.trim().length <= 64 && it.trim().toByteArray().size <= 256 &&
            it.none { c -> c.code <= 31 || c.code in 127..159 }
    }?.trim()
    private fun displayColor(node: JsonNode): String? = node.takeIf { it.isTextual }?.asText()?.takeIf {
        it.matches(Regex("#?[0-9a-fA-F]{6}"))
    }?.removePrefix("#")?.uppercase()?.let { "#$it" }
    private fun choice(node: JsonNode, key: String, allowed: Set<String>) = node.path(key).takeIf { it.isTextual }?.asText()?.takeIf { it in allowed } ?: "UNKNOWN"
    private fun integer(node: JsonNode, key: String): Long? = node.path(key).takeIf {
        it.isNumber && it.decimalValue().stripTrailingZeros().scale() <= 0 &&
            it.decimalValue() >= java.math.BigDecimal.ZERO && it.decimalValue() <= java.math.BigDecimal("9007199254740991")
    }?.asLong()
    private fun text(node: JsonNode, key: String): String? {
        val value = node.path(key).takeIf { it.isTextual }?.asText() ?: return null
        if (value.length > 256 || value.toByteArray().size > 256) reject()
        return value.trim().takeIf { it.isNotEmpty() && value.none { c -> c.code <= 31 || c.code == 127 } }
    }
    private fun coordinate(node: JsonNode): Map<String, Double>? {
        val lat = node.path("lat"); val lng = node.path("lng")
        if (!lat.isNumber || !lng.isNumber || !lat.asDouble().isFinite() || !lng.asDouble().isFinite()) return null
        return linkedMapOf("latitude" to lat.asDouble(), "longitude" to lng.asDouble())
    }
    private fun reject(): Nothing = throw BusinessException(ErrorCode.INVALID_STATE, "저장된 경로와 native 안내 입력이 일치하지 않습니다.")
}
