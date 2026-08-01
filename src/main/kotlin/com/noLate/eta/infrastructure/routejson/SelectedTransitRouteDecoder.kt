package com.noLate.eta.infrastructure.routejson

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.eta.domain.LegacyTransitBoardingPlan
import com.noLate.eta.domain.SelectedTransitRoute
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitRideSignature
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.transit.domain.TransitCityCodeNamespace
import org.springframework.stereotype.Component
import java.util.Locale

/**
 * 여러 앱 버전이 저장한 route JSON을 ETA 패키지의 안정된 입력으로 변환한다.
 *
 * root transitLegs가 routeInfo.steps보다 provider 식별자를 더 많이 보존하므로 항상 먼저 읽는다.
 * 공급자 JSON의 차이는 이 경계 밖으로 노출하지 않는다.
 */
@Component
class SelectedTransitRouteDecoder(
    private val objectMapper: ObjectMapper,
) {
    fun decode(routeJson: String?): SelectedTransitRoute? {
        val root = routeJson
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
            ?: return null
        val steps = routeSteps(root) ?: return null
        val parsedSteps = steps.mapNotNull(::parseStep)
        val rides = parsedSteps
            .filter { it.mode == TransitLegMode.BUS || it.mode == TransitLegMode.SUBWAY }
            .map { step ->
                TransitRideSignature(
                    mode = step.mode,
                    providerRouteId = step.providerRouteId,
                    lineName = step.lineName,
                    serviceClass = step.serviceClass,
                    fromIds = step.fromIds,
                    fromName = step.fromName,
                    toIds = step.toIds,
                    toName = step.toName,
                    directionName = step.directionName,
                    directionCode = step.directionCode,
                )
            }
        if (rides.isEmpty()) return null

        val provider = sequenceOf(
            root.path("provider"),
            root.path("routeInfo").path("provider"),
        )
            .firstText()
            ?.lowercase()

        return SelectedTransitRoute(
            provider = provider,
            rides = rides,
            legacyBoardingPlan = legacyBoardingPlan(parsedSteps)?.copy(
                cityCodeNamespace = if (provider == "odsay") {
                    TransitCityCodeNamespace.ODSAY_CID
                } else {
                    TransitCityCodeNamespace.UNKNOWN
                }
            ),
        )
    }

    private fun legacyBoardingPlan(steps: List<ParsedStep>): LegacyTransitBoardingPlan? {
        val firstRideIndex = steps.indexOfFirst {
            it.mode == TransitLegMode.BUS || it.mode == TransitLegMode.SUBWAY
        }
        if (firstRideIndex < 0) return null
        val movementSteps = steps.filter(ParsedStep::countsTowardTravelTime)
        if (movementSteps.isEmpty() || movementSteps.any { it.durationMinutes == null }) return null
        val movementMinutes = movementSteps.sumOf { requireNotNull(it.durationMinutes) }
        if (movementMinutes <= 0.0) return null

        val accessSteps = steps
            .take(firstRideIndex)
            .filter(ParsedStep::countsTowardTravelTime)
        if (accessSteps.any { it.durationMinutes == null }) return null
        val accessMinutes = accessSteps.sumOf { requireNotNull(it.durationMinutes) }
        val ride = steps[firstRideIndex]
        val stationName = ride.fromName?.takeIf(String::isNotBlank) ?: return null
        val lineName = ride.lineName?.takeIf(String::isNotBlank) ?: return null
        if (
            ride.mode == TransitLegMode.SUBWAY &&
            ride.directionName.isNullOrBlank() &&
            ride.directionCode.isNullOrBlank()
        ) {
            return null
        }
        val travelMinutesWithoutFirstWait = movementMinutes - (ride.waitingMinutes ?: 0.0)
        if (travelMinutesWithoutFirstWait <= 0.0) return null

        val firstCode = ride.fromIds.firstOrNull()
        val arsId = firstCode
            ?.let(ARS_CODE_PATTERN::matchEntire)
            ?.groupValues
            ?.get(1)
            ?: firstCode?.takeIf(LEGACY_ARS_PATTERN::matches)
        val cityNodeMatch = firstCode
            ?.takeUnless { ARS_CODE_PATTERN.matches(it) }
            ?.let(CITY_NODE_PATTERN::matchEntire)

        return LegacyTransitBoardingPlan(
            kind = ride.mode,
            accessMinutes = accessMinutes,
            travelMinutesWithoutFirstWait = travelMinutesWithoutFirstWait,
            stationName = stationName,
            lineName = lineName,
            directionName = ride.directionName,
            directionCode = ride.directionCode,
            arsId = arsId,
            cityCode = cityNodeMatch?.groupValues?.get(1),
            cityCodeNamespace = TransitCityCodeNamespace.UNKNOWN,
            nodeId = cityNodeMatch?.groupValues?.get(2)
                ?: firstCode
                    ?.takeIf { arsId == null }
                    ?.takeIf { ':' !in it && '|' !in it && '-' !in it }
                    ?.takeIf { it.any(Char::isLetter) && it.any(Char::isDigit) },
        )
    }

    private fun routeSteps(root: JsonNode): List<JsonNode>? =
        sequenceOf(
            root.path("transitJourney").path("legs"),
            root.path("transitLegs"),
            root.path("selectedItinerary").path("legs"),
            root.path("itinerary").path("legs"),
            root.path("routeInfo").path("steps"),
            root.path("steps"),
        )
            .firstOrNull { it.isArray && !it.isEmpty }
            ?.toList()

    private fun parseStep(node: JsonNode): ParsedStep? {
        val mode = stepMode(node) ?: return null
        val passStops = sequenceOf(
            node.path("passStops"),
            node.path("passStopList").path("stations"),
        ).firstOrNull(JsonNode::isArray)
        val firstStop = passStops?.firstOrNull()
        val lastStop = passStops?.lastOrNull()
        val lane = node.path("lane").let {
            when {
                it.isArray -> it.firstOrNull()
                it.isObject -> it
                else -> null
            }
        }

        return ParsedStep(
            mode = mode,
            countsTowardTravelTime = mode !in setOf(TransitLegMode.ETC),
            durationMinutes = numeric(node, "durationMinutes", "duration"),
            waitingMinutes = numeric(node, "waitingMinutes", "waitingTime"),
            providerRouteId = firstText(
                node,
                "providerRouteId",
                "routeId",
                "busID",
                "busId",
                "subwayCode",
            ) ?: firstText(lane, "busID", "busId", "subwayCode", "routeId"),
            lineName = firstText(node, "lineName", "badgeText", "route")
                ?: firstText(lane, "name", "busNo", "route"),
            serviceClass = subwayServiceClass(node, lane, mode),
            fromIds = stopIds(
                explicitCode = firstText(node, "startStopCode", "fromStopCode"),
                stop = firstStop,
                owner = node,
                prefix = "start",
            ),
            fromName = firstText(node, "startName", "fromName", "title")
                ?: firstText(firstStop, "name", "stationName"),
            toIds = stopIds(
                explicitCode = firstText(node, "endStopCode", "toStopCode"),
                stop = lastStop,
                owner = node,
                prefix = "end",
            ),
            toName = firstText(node, "endName", "toName")
                ?: firstText(lastStop, "name", "stationName"),
            directionName = firstText(node, "directionName", "way"),
            directionCode = firstText(node, "directionCode")
                ?.uppercase()
                ?.takeIf { it == "UP" || it == "DOWN" }
                ?: when (node.path("wayCode").takeIf(JsonNode::isNumber)?.asInt()) {
                    1 -> "UP"
                    2 -> "DOWN"
                    else -> null
                },
        )
    }

    private fun stopIds(
        explicitCode: String?,
        stop: JsonNode?,
        owner: JsonNode,
        prefix: String,
    ): Set<String> = buildSet {
        explicitCode?.normalizedStopCode()?.let(::add)
        firstText(stop, "code")?.normalizedStopCode()?.let(::add)

        val arsId = firstText(owner, "${prefix}ArsID", "${prefix}ArsId")
            ?: firstText(stop, "arsID", "arsId")
        arsId
            ?.filter(Char::isDigit)
            ?.takeIf(LEGACY_ARS_PATTERN::matches)
            ?.let { add("ARS:$it") }

        val providerId = firstText(owner, "${prefix}ID", "${prefix}Id")
            ?: firstText(stop, "stationID", "stationId")
        providerId?.let { add("ODsay:$it") }

        val localId = firstText(owner, "${prefix}LocalStationID", "${prefix}LocalStationId")
            ?: firstText(stop, "localStationID", "localStationId")
        val cityCode = firstText(owner, "${prefix}StationCityCode", "${prefix}CityCode")
            ?: firstText(stop, "stationCityCode", "cityCode")
        if (localId != null) {
            add(cityCode?.let { "CITY:$it:$localId" } ?: "LOCAL:$localId")
        }
    }

    private fun String.normalizedStopCode(): String? {
        val raw = trim().takeIf(String::isNotBlank) ?: return null
        val ars = ARS_CODE_PATTERN.matchEntire(raw)?.groupValues?.get(1)
            ?: raw.filter(Char::isDigit).takeIf(LEGACY_ARS_PATTERN::matches)
        if (ars != null) return "ARS:$ars"
        val cityNode = CITY_NODE_PATTERN.matchEntire(raw)
        if (cityNode != null) return "CITY:${cityNode.groupValues[1]}:${cityNode.groupValues[2]}"
        return raw
    }

    private fun stepMode(node: JsonNode): TransitLegMode? {
        val textual = firstText(node, "type", "kind", "mode")?.uppercase()
        if (textual != null) {
            return when (textual) {
                "ORIGIN", "DESTINATION" -> TransitLegMode.ETC
                "WALK", "TRANSFER" -> TransitLegMode.WALK
                "BUS" -> TransitLegMode.BUS
                "SUBWAY" -> TransitLegMode.SUBWAY
                else -> TransitLegMode.ETC
            }
        }
        return when (node.path("trafficType").takeIf(JsonNode::isNumber)?.asInt()) {
            1 -> TransitLegMode.SUBWAY
            2 -> TransitLegMode.BUS
            3 -> TransitLegMode.WALK
            else -> null
        }
    }

    private fun numeric(node: JsonNode, vararg names: String): Double? =
        names.asSequence()
            .map(node::path)
            .firstOrNull(JsonNode::isNumber)
            ?.asDouble()
            ?.takeIf { it.isFinite() && it >= 0.0 }

    private fun subwayServiceClass(
        node: JsonNode,
        lane: JsonNode?,
        mode: TransitLegMode,
    ): TransitServiceClass {
        if (mode != TransitLegMode.SUBWAY) return TransitServiceClass.UNKNOWN
        val raw = firstText(node, "serviceClass", "subwayServiceClass")
            ?: firstText(lane, "serviceClass", "subwayServiceClass")
            ?: return TransitServiceClass.UNKNOWN
        return runCatching {
            TransitServiceClass.valueOf(raw.uppercase(Locale.ROOT))
        }.getOrDefault(TransitServiceClass.UNKNOWN)
    }

    private fun firstText(node: JsonNode?, vararg names: String): String? {
        if (node == null) return null
        return names.asSequence()
            .map(node::path)
            .firstText()
    }

    private fun Sequence<JsonNode>.firstText(): String? =
        firstOrNull { it.isTextual || it.isIntegralNumber }
            ?.asText()
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private data class ParsedStep(
        val mode: TransitLegMode,
        val countsTowardTravelTime: Boolean,
        val durationMinutes: Double?,
        val waitingMinutes: Double?,
        val providerRouteId: String?,
        val lineName: String?,
        val serviceClass: TransitServiceClass,
        val fromIds: Set<String>,
        val fromName: String?,
        val toIds: Set<String>,
        val toName: String?,
        val directionName: String?,
        val directionCode: String?,
    )

    private companion object {
        val ARS_CODE_PATTERN = Regex("""ARS[:|-](\d{5})""", RegexOption.IGNORE_CASE)
        val LEGACY_ARS_PATTERN = Regex("""\d{5}""")
        val CITY_NODE_PATTERN = Regex("""(?:CITY:)?(\d{2,5})[:|-](.+)""", RegexOption.IGNORE_CASE)
    }
}
