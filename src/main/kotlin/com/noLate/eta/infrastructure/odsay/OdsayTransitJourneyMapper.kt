package com.noLate.eta.infrastructure.odsay

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
import com.noLate.transit.domain.TransitCityCodeNamespace
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * ODsay maasRP 원본을 ETA 계산이 공급자 스키마를 몰라도 되는 canonical 여정으로 바꾼다.
 */
@Component
class OdsayTransitJourneyMapper {
    fun map(
        response: JsonNode,
        request: TransitJourneySearchRequest,
        fetchedAt: Instant,
    ): List<TransitJourney> {
        if (hasProviderError(response)) {
            throw IllegalStateException("ODsay가 경로 조회 오류를 반환했습니다.")
        }
        return response.path("result").path("paths")
            .arrayLike()
            .asSequence()
            .filter { it.path("pathType").asInt() == PUBLIC_TRANSIT_PATH_TYPE }
            .mapNotNull { path -> mapPath(path, request, fetchedAt) }
            .sortedWith(compareBy<TransitJourney> { it.arrivalAt }.thenBy { it.totalMinutes })
            .toList()
    }

    private fun mapPath(
        path: JsonNode,
        request: TransitJourneySearchRequest,
        fetchedAt: Instant,
    ): TransitJourney? {
        val totalMinutes = positiveMinutes(path.path("totalTime"), request.maxTravelMinutes)
            ?: return null
        val departureAt = parseOdsayDateTime(path.text("startDateTime")) ?: return null
        val arrivalAt = parseOdsayDateTime(path.text("endDateTime")) ?: return null
        if (arrivalAt.isBefore(departureAt)) return null
        // The provider timestamp is minute-granular, but accepting a route before the requested
        // instant can turn already elapsed waiting time into an artificial early-arrival delta.
        // Callers normalize SearchTime to a minute before this boundary; the mapper stays closed.
        if (departureAt.isBefore(request.departureAt)) return null

        val rawLegs = path.path("rps").arrayLike()
        val mappedLegs = rawLegs
            .mapIndexed { index, node -> mapLeg(index, node, request.maxTravelMinutes) }
        if (rawLegs.isEmpty() || mappedLegs.any { it == null }) return null
        val legs = mappedLegs.filterNotNull()
        if (legs.none(TransitJourneyLeg::isRide)) return null
        if (legs.any { it.waitingMinutes != null && requireNotNull(it.waitingMinutes) > it.durationMinutes }) {
            return null
        }
        if (legs.any(::hasInvalidTimeline)) return null
        if (!hasCoherentJourneyTimeline(departureAt, arrivalAt, legs)) return null
        val summedMinutes = legs.sumOf(TransitJourneyLeg::durationMinutes)
        if (abs(summedMinutes - totalMinutes) > ALLOWED_TOTAL_ROUNDING_MINUTES) return null

        val timelineMinutes = ceil(Duration.between(departureAt, arrivalAt).toSeconds() / 60.0).toInt()
        if (abs(timelineMinutes - totalMinutes) > ALLOWED_TIMELINE_ROUNDING_MINUTES) return null

        return TransitJourney(
            provider = PROVIDER_ID,
            requestedDepartureAt = request.departureAt,
            departureAt = departureAt,
            arrivalAt = arrivalAt,
            totalMinutes = totalMinutes,
            legs = legs,
            fetchedAt = fetchedAt,
        )
    }

    private fun hasInvalidTimeline(leg: TransitJourneyLeg): Boolean {
        val departureAt = leg.scheduledDepartureAt
        val arrivalAt = leg.scheduledArrivalAt
        // A half-populated interval cannot safely participate in transfer feasibility.
        if ((departureAt == null) != (arrivalAt == null)) return true
        if (departureAt == null || arrivalAt == null) return false
        if (arrivalAt.isBefore(departureAt)) return true
        val timelineMinutes =
            ceil(Duration.between(departureAt, arrivalAt).toSeconds() / 60.0).toInt()
        return abs(timelineMinutes - leg.durationMinutes) > ALLOWED_LEG_TIMELINE_ROUNDING_MINUTES
    }

    /**
     * Rejects individually plausible legs that form a reversed, overlapping, or disconnected
     * itinerary. ODsay timestamps only carry minute precision, so one minute of boundary drift is
     * tolerated; larger gaps must be represented by an explicit rps leg instead of disappearing.
     * Legs without either timestamp remain usable only as UNKNOWN/degraded transfer timing.
     */
    private fun hasCoherentJourneyTimeline(
        departureAt: Instant,
        arrivalAt: Instant,
        legs: List<TransitJourneyLeg>,
    ): Boolean {
        val first = legs.first()
        first.scheduledDepartureAt?.let { firstDeparture ->
            if (!withinBoundaryPrecision(departureAt, firstDeparture)) return false
        }
        val last = legs.last()
        last.scheduledArrivalAt?.let { lastArrival ->
            if (!withinBoundaryPrecision(lastArrival, arrivalAt)) return false
        }

        var lastKnownAt = departureAt
        legs.forEachIndexed { index, leg ->
            val legDeparture = leg.scheduledDepartureAt
            val legArrival = leg.scheduledArrivalAt
            if (legDeparture != null && legArrival != null) {
                if (legDeparture.isBefore(lastKnownAt.minusSeconds(TIMELINE_BOUNDARY_PRECISION_SECONDS))) {
                    return false
                }
                if (legArrival.isBefore(lastKnownAt.minusSeconds(TIMELINE_BOUNDARY_PRECISION_SECONDS))) {
                    return false
                }
                lastKnownAt = legArrival
            }

            if (index == legs.lastIndex) return@forEachIndexed
            val nextDeparture = legs[index + 1].scheduledDepartureAt
            if (
                legArrival != null &&
                nextDeparture != null &&
                !withinBoundaryPrecision(legArrival, nextDeparture)
            ) {
                return false
            }
        }
        return !lastKnownAt.isAfter(arrivalAt.plusSeconds(TIMELINE_BOUNDARY_PRECISION_SECONDS))
    }

    private fun withinBoundaryPrecision(left: Instant, right: Instant): Boolean =
        abs(Duration.between(left, right).seconds) <= TIMELINE_BOUNDARY_PRECISION_SECONDS

    private fun mapLeg(
        index: Int,
        node: JsonNode,
        maxTravelMinutes: Int,
    ): TransitJourneyLeg? {
        val mode = when (node.path("trafficType").asInt(-1)) {
            1 -> TransitLegMode.SUBWAY
            2 -> TransitLegMode.BUS
            3 -> TransitLegMode.WALK
            4 -> TransitLegMode.TRAIN
            5 -> TransitLegMode.EXPRESS_BUS
            6 -> TransitLegMode.INTERCITY_BUS
            else -> TransitLegMode.ETC
        }
        val durationMinutes = nonNegativeMinutes(node.path("duration"), maxTravelMinutes)
            ?: return null
        // 공급자 도보 상세를 버리지 않는다. 불완전한 상세는 기존 분 단위 계산을 유지한다.
        val walking = if (mode == TransitLegMode.WALK) OdsayWalkingDecoder.decode(node, durationMinutes) else null
        val waitingMinutes = node.path("waitingTime")
            .takeIf { !it.isMissingNode && !it.isNull }
            ?.let { nonNegativeMinutes(it, maxTravelMinutes) }
            ?: if (node.hasNonNull("waitingTime")) return null else null
        val lanes = node.path("lane").arrayLike()
        val lane = lanes.firstOrNull()
        val subwayStops = orderedSubwayStops(node)
        val canonicalLine = if (mode == TransitLegMode.SUBWAY && lanes.size == 1 && subwayStops != null) {
            seoulSubwayLine(lane)
        } else null

        // maasRP 시각은 실제 운행 시간표가 아니라 SearchTime과
        // 구간별 이동시간으로 만들어진 경로 표시용 예상시각이다.
        val providerDepartureAt =
            parseOdsayDateTime(node.text("startDateTime"))
        val providerArrivalAt =
            parseOdsayDateTime(node.text("endDateTime"))

        return TransitJourneyLeg(
            sequence = index,
            mode = mode,
            durationMinutes = durationMinutes,
            durationSeconds = walking?.durationSeconds,
            walkingGeometry = walking?.geometry,
            waitingMinutes = waitingMinutes,
            scheduledDepartureAt = providerDepartureAt,
            scheduledArrivalAt = providerArrivalAt,
            from = stop(node, "start"),
            to = stop(node, "end"),
            line = when (mode) {
                TransitLegMode.BUS -> TransitLine(
                    providerRouteId = lane?.text("busID") ?: lane?.text("busId"),
                    localRouteId = lane?.text("busLocalBlID"),
                    cityCode = lane?.text("busCityCode"),
                    providerCode = lane?.text("busProviderCode"),
                    name = lane?.text("busNo") ?: lane?.text("name"),
                )
                TransitLegMode.SUBWAY -> TransitLine(
                    providerRouteId = listOfNotNull(
                        lane?.text("subwayCityCode"),
                        lane?.text("subwayCode"),
                    )
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(":"),
                    cityCode = lane?.text("subwayCityCode"),
                    name = lane?.text("name"),
                    serviceClass = subwayServiceClass(lane?.text("name")),
                    // ODsay ID를 서울 역코드로 변환하지 않는다. 공식 도시/노선 코드와
                    // 노선명이 일치하고 대안 노선이 모호하지 않을 때만 시간표 연결을 허용한다.
                    seoulSubwayLine = canonicalLine,
                )
                TransitLegMode.TRAIN, TransitLegMode.EXPRESS_BUS, TransitLegMode.INTERCITY_BUS, TransitLegMode.COACH -> TransitLine(
                    name = lane?.text("name") ?: node.text("trafficSubType"),
                )
                else -> null
            },
            directionName = node.text("way"),
            // 2호선의 ODsay 상하행 코드를 서울 시간표 INOUT_TAG와 동일시하지 않는다.
            // 검증한 다음 역의 동일 열차 시각 순서로 시간표 adapter가 방향을 증명한다.
            // 다음 역이 없거나 양방향 모두 가능하면 기존 provider가 빈 결과로 닫는다.
            directionCode = if (canonicalLine == SeoulSubwayLine.LINE_2) null else when (node.path("wayCode").integer()) {
                1 -> "UP"
                2 -> "DOWN"
                else -> null
            },
            timingBasis = if (
                providerDepartureAt != null &&
                providerArrivalAt != null
            ) {
                TransitLegTimingBasis.ROUTE_ESTIMATE
            } else {
                TransitLegTimingBasis.UNKNOWN
            },
            nextStopName = if (mode == TransitLegMode.SUBWAY) subwayStops?.getOrNull(1)?.text("stationName") else null,
        )
    }

    /** https://lab.odsay.com/guide/releaseReference : maasRP 및 지하철 노선/도시 코드 표. */
    private fun seoulSubwayLine(lane: JsonNode?): SeoulSubwayLine? {
        if (lane == null || lane.path("subwayCityCode").integer() != 1000) return null
        val line = when (lane.path("subwayCode").integer()) {
            1 -> SeoulSubwayLine.LINE_1
            2 -> SeoulSubwayLine.LINE_2
            3 -> SeoulSubwayLine.LINE_3
            4 -> SeoulSubwayLine.LINE_4
            5 -> SeoulSubwayLine.LINE_5
            6 -> SeoulSubwayLine.LINE_6
            7 -> SeoulSubwayLine.LINE_7
            8 -> SeoulSubwayLine.LINE_8
            9 -> SeoulSubwayLine.LINE_9
            else -> return null
        }
        val name = lane.text("name")?.replace(WHITESPACE_PATTERN, "") ?: return null
        // 지선·다른 지역·미확인 접미사를 이름으로 넓혀 해석하지 않는다.
        val number = SEOUL_LINE_NAME.matchEntire(name)?.groupValues?.get(1) ?: return null
        return line.takeIf { it.displayName == "${number}호선" }
    }

    /** 경유역이 있으면 순번 및 양 끝 ID/역명을 대조한다. 없는 다음 역은 만들어내지 않는다. */
    private fun orderedSubwayStops(node: JsonNode): List<JsonNode>? {
        val passStops = node.path("passStopList")
        if (passStops.isMissingNode || passStops.isNull) return emptyList()
        if (!passStops.isObject) return null
        val raw = passStops.path("stations").arrayLike()
        if (raw.size !in 2..512) return null
        val indexed = raw.map { stop ->
            val index = stop.path("index").integer()?.takeIf { it >= 0 } ?: return null
            if (stop.text("stationID") == null || stop.text("stationName") == null) return null
            index to stop
        }.sortedBy { it.first }
        val indices = indexed.map { it.first }
        if (indices != (indices.first() until indices.first() + indices.size).toList()) return null
        val stops = indexed.map { it.second }
        fun matches(stop: JsonNode, prefix: String): Boolean =
            stop.text("stationID") == node.text("${prefix}ID") &&
                stop.text("stationName") == node.text("${prefix}Name")
        return stops.takeIf { matches(it.first(), "start") && matches(it.last(), "end") }
    }

    private fun JsonNode.integer(): Int? = when {
        isIntegralNumber && canConvertToInt() -> intValue()
        isTextual -> textValue().trim().takeIf(INTEGER_PATTERN::matches)?.toIntOrNull()
        else -> null
    }

    private fun stop(node: JsonNode, prefix: String): TransitStop? {
        val stop = TransitStop(
            providerStopId = node.text("${prefix}ID") ?: node.text("${prefix}Id"),
            localStopId = node.text("${prefix}LocalStationID"),
            cityCode = node.text("${prefix}StationCityCode"),
            cityCodeNamespace = TransitCityCodeNamespace.ODSAY_CID,
            providerCode = node.text("${prefix}StationProviderCode"),
            arsId = node.text("${prefix}ArsID") ?: node.text("${prefix}ArsId"),
            name = node.text("${prefix}Name"),
        )
        return stop.takeIf {
            it.providerStopId != null ||
                it.localStopId != null ||
                it.arsId != null ||
                it.name != null
        }
    }

    private fun positiveMinutes(node: JsonNode, maximum: Int): Int? =
        numericMinutes(node)
            ?.takeIf { it in 1..maximum }

    private fun nonNegativeMinutes(node: JsonNode, maximum: Int): Int? =
        numericMinutes(node)
            ?.takeIf { it in 0..maximum }

    private fun numericMinutes(node: JsonNode): Int? {
        if (!node.isNumber && !node.isTextual) return null
        val raw = node.asText().toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
        if (raw < 0.0) return null
        return ceil(raw).toInt()
    }

    private fun parseOdsayDateTime(raw: String?): Instant? {
        if (raw == null || !ODSAY_DATE_PATTERN.matches(raw)) return null
        return runCatching {
            LocalDateTime.parse(raw, ODSAY_DATE_FORMATTER)
                .atZone(SERVICE_ZONE)
                .toInstant()
        }.getOrNull()
    }

    private fun hasProviderError(response: JsonNode): Boolean {
        val rootError = response.path("error")
        val resultError = response.path("result").path("error")
        return (!rootError.isMissingNode && !rootError.isNull) ||
            (!resultError.isMissingNode && !resultError.isNull)
    }

    /**
     * ODsay는 현재 급행 경로를 별도 결과로 제공할 때 노선명에 반드시 `(급행)`을
     * 붙인다고 명시한다. 이 공급자 계약 안에서 비어 있지 않은 비급행 노선명은 LOCAL로
     * 분류하되, 상충 표기나 노선명 누락은 UNKNOWN으로 닫는다.
     */
    private fun subwayServiceClass(lineName: String?): TransitServiceClass {
        val normalized = lineName
            ?.replace(WHITESPACE_PATTERN, "")
            ?.uppercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)
            ?: return TransitServiceClass.UNKNOWN
        val express = EXPRESS_SERVICE_MARKERS.any(normalized::contains)
        val local = LOCAL_SERVICE_MARKERS.any(normalized::contains)
        return when {
            express && local -> TransitServiceClass.UNKNOWN
            express -> TransitServiceClass.EXPRESS
            else -> TransitServiceClass.LOCAL
        }
    }

    private fun JsonNode.arrayLike(): List<JsonNode> = when {
        isArray -> toList()
        isObject -> listOf(this)
        else -> emptyList()
    }

    private fun JsonNode.text(field: String): String? =
        path(field)
            .takeIf { it.isTextual || it.isIntegralNumber }
            ?.asText()
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private companion object {
        const val PROVIDER_ID = "odsay"
        const val PUBLIC_TRANSIT_PATH_TYPE = 2
        const val ALLOWED_TOTAL_ROUNDING_MINUTES = 1
        const val ALLOWED_TIMELINE_ROUNDING_MINUTES = 2
        const val ALLOWED_LEG_TIMELINE_ROUNDING_MINUTES = 2
        const val TIMELINE_BOUNDARY_PRECISION_SECONDS = 60L
        val SERVICE_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        val ODSAY_DATE_PATTERN = Regex("""\d{12}""")
        val WHITESPACE_PATTERN = Regex("""\s+""")
        val INTEGER_PATTERN = Regex("""\d+""")
        val SEOUL_LINE_NAME = Regex("""^(?:수도권|서울)([1-9])호선(?:\(급행\)|급행)?$""")
        val EXPRESS_SERVICE_MARKERS = listOf("급행", "특급", "ITX", "EXPRESS")
        val LOCAL_SERVICE_MARKERS = listOf("일반열차", "(일반)", "완행", "LOCAL")
        val ODSAY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("uuuuMMddHHmm")
            .withResolverStyle(ResolverStyle.STRICT)
    }
}
