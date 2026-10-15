package com.noLate.eta.infrastructure.tmap

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLegTimingBasis
import com.noLate.eta.domain.TransitLine
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.eta.domain.TransitStop
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitWalkingCoordinate
import com.noLate.eta.domain.TransitWalkingGeometry
import com.noLate.eta.domain.TransitWalkingEndpointBasis
import com.noLate.eta.domain.TransitWalkingPathStatus
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Locale

/**
 * Maps /transit/routes estimates, never vehicle timetables.
 * Contract: https://transit.tmapmobility.com/docs/routes
 */
@Component
class TmapTransitJourneyMapper {
    fun map(
        response: JsonNode,
        request: TransitJourneySearchRequest,
        fetchedAt: Instant,
    ): List<TransitJourney> {
        if (
            response.hasNonNull("error") ||
            response.path("metaData").hasNonNull("error") ||
            response.path("result").hasNonNull("status")
        ) {
            throw IllegalStateException("TMAP이 경로 조회 오류를 반환했습니다.")
        }
        if (request.maxTravelMinutes <= 0) return emptyList()
        val itineraries = response.path("metaData").path("plan").path("itineraries")
        if (!itineraries.isArray) return emptyList()
        return itineraries.mapNotNull { mapItinerary(it, request, fetchedAt) }
            .sortedWith(compareBy<TransitJourney> { it.arrivalAt }.thenBy { it.totalMinutes })
    }

    private fun mapItinerary(
        itinerary: JsonNode,
        request: TransitJourneySearchRequest,
        fetchedAt: Instant,
    ): TransitJourney? {
        val maximumSeconds = request.maxTravelMinutes.toLong() * 60
        val totalSeconds = itinerary.path("totalTime").integer()
            ?.takeIf { it in 1..maximumSeconds } ?: return null
        val rawLegs = itinerary.path("legs")
        if (!rawLegs.isArray || rawLegs.isEmpty) return null

        var elapsedSeconds = 0L
        val legs = rawLegs.mapIndexed { index, node ->
            val sectionSeconds = node.path("sectionTime").integer()
                ?.takeIf { it in 0..totalSeconds } ?: return null
            if (sectionSeconds > totalSeconds - elapsedSeconds) return null
            val departureAt = runCatching { request.departureAt.plusSeconds(elapsedSeconds) }
                .getOrNull() ?: return null
            elapsedSeconds += sectionSeconds
            val arrivalAt = runCatching { request.departureAt.plusSeconds(elapsedSeconds) }
                .getOrNull() ?: return null
            mapLeg(index, node, sectionSeconds, departureAt, arrivalAt) ?: return null
        }
        // Both fields are integer seconds. Unexplained time cannot be assigned to a wait/ride.
        if (elapsedSeconds != totalSeconds || legs.none(TransitJourneyLeg::isRide)) return null

        return TransitJourney(
            provider = PROVIDER_ID,
            requestedDepartureAt = request.departureAt,
            departureAt = request.departureAt,
            arrivalAt = requireNotNull(legs.last().scheduledArrivalAt),
            totalMinutes = minutes(totalSeconds),
            legs = legs,
            fetchedAt = fetchedAt,
        )
    }

    private fun mapLeg(
        index: Int,
        node: JsonNode,
        sectionSeconds: Long,
        departureAt: Instant,
        arrivalAt: Instant,
    ): TransitJourneyLeg? {
        val mode = when (node.text("mode")?.uppercase(Locale.ROOT)) {
            "WALK" -> TransitLegMode.WALK
            "BUS" -> TransitLegMode.BUS
            "SUBWAY" -> TransitLegMode.SUBWAY
            "TRAIN" -> TransitLegMode.TRAIN
            "EXPRESSBUS" -> when (node.path("type").integer()) {
                16L, 22L -> TransitLegMode.INTERCITY_BUS
                23L -> TransitLegMode.EXPRESS_BUS
                else -> TransitLegMode.COACH
            }
            // Air/ferry remain unsupported; never reinterpret them as walking.
            else -> return null
        }
        val isRide = mode != TransitLegMode.WALK
        if (isRide && (sectionSeconds == 0L || node.path("service").integer() != 1L)) return null

        val stations = orderedStations(node) ?: return null
        val from = stop(stations.firstOrNull(), node.path("start"))
        val to = stop(stations.lastOrNull(), node.path("end"))
        val line = if (isRide) {
            // Lane contains alternative routes; its first item is not the primary route.
            val routeId = node.identifier("routeId")
            val routeName = node.text("route")
            if (routeId == null && routeName == null) return null
            if (from == null || to == null) return null
            TransitLine(
                providerRouteId = routeId,
                name = routeName,
                serviceClass = if (mode == TransitLegMode.SUBWAY) {
                    subwayServiceClass(node.path("type"))
                } else {
                    TransitServiceClass.UNKNOWN
                },
                seoulSubwayLine = if (mode == TransitLegMode.SUBWAY) {
                    seoulSubwayLine(node.path("type"), routeName)
                } else null,
            )
        } else {
            null
        }

        return TransitJourneyLeg(
            sequence = index,
            mode = mode,
            durationMinutes = minutes(sectionSeconds),
            // 표시용 분은 올림하되 승차 접근·환승 도보 계산에는 원본 초를 전달한다.
            durationSeconds = sectionSeconds,
            waitingMinutes = null,
            scheduledDepartureAt = departureAt,
            scheduledArrivalAt = arrivalAt,
            from = from,
            to = to,
            line = line,
            directionName = DIRECTION_FIELDS.firstNotNullOfOrNull { field -> node.text(field) },
            directionCode = node.text("directionCode")?.uppercase(Locale.ROOT)
                ?.takeIf { it == "UP" || it == "DOWN" },
            timingBasis = TransitLegTimingBasis.ROUTE_ESTIMATE,
            nextStopName = stations.getOrNull(1)?.text("stationName"),
            // WALK 원본 경계만 보존한다. 승차 정류장 좌표나 요청 좌표로 대체하지 않는다.
            walkingGeometry = if (mode == TransitLegMode.WALK) walkingGeometry(node) else null,
        )
    }

    private fun orderedStations(node: JsonNode): List<JsonNode>? {
        val passStops = node.path("passStopList")
        if (passStops.isMissingNode || passStops.isNull) return emptyList()
        if (!passStops.isObject) return null
        // The documented field and official sample use these two spellings respectively.
        val stations = passStops.path("stationList").takeUnless(JsonNode::isMissingNode)
            ?: passStops.path("stations")
        if (!stations.isArray || stations.size() < 2 || stations.any { !it.isObject }) return null
        val values = stations.toList()
        if (values.none { it.has("index") }) return values
        val indices = values.map { station ->
            station.path("index").integer()?.takeIf { it >= 0 } ?: return null
        }
        if (indices.toSet().size != indices.size) return null
        return values.zip(indices).sortedBy { it.second }.map { it.first }
    }

    private fun stop(station: JsonNode?, endpoint: JsonNode): TransitStop? {
        val providerStopId = station?.identifier("stationID")
        val name = station?.text("stationName") ?: endpoint.text("name")
        if (providerStopId == null && name == null) return null
        // TMAP IDs are provider-scoped; no undocumented city/ARS/local namespace conversion.
        return TransitStop(providerStopId = providerStopId, name = name)
    }

    private fun walkingGeometry(node: JsonNode): TransitWalkingGeometry? {
        val from = walkingCoordinate(node.path("start"))
        val to = walkingCoordinate(node.path("end"))
        val distance = node.path("distance").integer()?.takeIf { it >= 0 }
        val evidence = TmapWalkingPathEvidenceDecoder.decode(node)
        // 상세 경로 오류가 정상 소요초나 전체 경로를 버리게 하지 않는다. 명목 끝점도 보존한다.
        return if (from == null && to == null && distance == null &&
            evidence.status == TransitWalkingPathStatus.UNAVAILABLE) null
        else TransitWalkingGeometry(from, to, distance,
            TransitWalkingEndpointBasis.PROVIDER_LEG_ENDPOINTS, evidence)
    }

    private fun walkingCoordinate(node: JsonNode): TransitWalkingCoordinate? {
        fun number(field: String): Double? = node.path(field).let {
            when {
                it.isNumber -> it.doubleValue()
                it.isTextual -> it.textValue().trim().toDoubleOrNull()
                else -> null
            }
        }?.takeIf(Double::isFinite)
        val lat = number("lat")?.takeIf { it in -90.0..90.0 } ?: return null
        val lng = number("lon")?.takeIf { it in -180.0..180.0 } ?: return null
        return TransitWalkingCoordinate(lat, lng)
    }

    private fun subwayServiceClass(type: JsonNode): TransitServiceClass = when (type.integer()) {
        in EXPRESS_SUBWAY_TYPES -> TransitServiceClass.EXPRESS
        in LOCAL_SUBWAY_TYPES -> TransitServiceClass.LOCAL
        else -> TransitServiceClass.UNKNOWN
    }

    private fun seoulSubwayLine(type: JsonNode, routeName: String?): SeoulSubwayLine? {
        // 공식 Tmap mode별 type ↔ 서울 subwayId의 명시적 매핑만 사용한다.
        // type 6은 공개 표의 호선 설명이 모호하므로 이 단계에서는 연결하지 않는다.
        // 수도권 외 노선·지선·확인하지 않은 코드는 이름만 보고 서울로 추정하지 않는다.
        val line = when (type.integer()) {
            1L, 117L, 118L -> SeoulSubwayLine.LINE_1
            2L -> SeoulSubwayLine.LINE_2
            3L -> SeoulSubwayLine.LINE_3
            4L, 119L -> SeoulSubwayLine.LINE_4
            5L -> SeoulSubwayLine.LINE_5
            7L -> SeoulSubwayLine.LINE_7
            8L -> SeoulSubwayLine.LINE_8
            9L, 120L -> SeoulSubwayLine.LINE_9
            else -> return null
        }
        val normalizedName = routeName?.replace(Regex("""\s+"""), "") ?: return null
        val number = Regex("""^(?:수도권|서울)?([1-9])호선(?:\(급행\)|급행)?$""")
            .matchEntire(normalizedName)?.groupValues?.get(1) ?: return null
        return line.takeIf { it.displayName == "${number}호선" }
    }

    private fun JsonNode.integer(): Long? = when {
        isIntegralNumber && canConvertToLong() -> longValue()
        isTextual -> textValue().trim().takeIf(INTEGER_PATTERN::matches)?.toLongOrNull()
        else -> null
    }

    private fun JsonNode.identifier(field: String): String? = path(field)
        .takeIf { it.isTextual || it.isIntegralNumber }
        ?.asText()?.trim()?.takeIf(String::isNotBlank)

    private fun JsonNode.text(field: String): String? = path(field)
        .takeIf(JsonNode::isTextual)
        ?.textValue()?.trim()?.takeIf(String::isNotBlank)

    private fun minutes(seconds: Long): Int = ((seconds + 59) / 60).toInt()

    private companion object {
        const val PROVIDER_ID = "tmap"
        val INTEGER_PATTERN = Regex("""\d+""")
        val DIRECTION_FIELDS = listOf("directionName", "direction", "routeDirection", "headsign", "destinationName")
        // Explicit /transit/routes mode-specific codes, not a guess from an unmarked line name.
        val EXPRESS_SUBWAY_TYPES = (117L..124L).toSet()
        val LOCAL_SUBWAY_TYPES = (1L..11L).toSet() + setOf(
            21L, 22L, 31L, 41L, 42L, 43L, 44L, 51L, 71L, 72L, 73L, 74L, 78L, 79L,
            100L, 101L, 104L, 107L, 108L, 109L, 110L, 112L, 113L, 114L, 115L, 116L, 125L,
        )
    }
}
