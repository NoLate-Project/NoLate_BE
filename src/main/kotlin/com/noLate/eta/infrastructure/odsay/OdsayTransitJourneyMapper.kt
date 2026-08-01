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
            else -> TransitLegMode.ETC
        }
        val durationMinutes = nonNegativeMinutes(node.path("duration"), maxTravelMinutes)
            ?: return null
        val waitingMinutes = node.path("waitingTime")
            .takeIf { !it.isMissingNode && !it.isNull }
            ?.let { nonNegativeMinutes(it, maxTravelMinutes) }
            ?: if (node.hasNonNull("waitingTime")) return null else null
        val lane = node.path("lane").arrayLike().firstOrNull()

        return TransitJourneyLeg(
            sequence = index,
            mode = mode,
            durationMinutes = durationMinutes,
            waitingMinutes = waitingMinutes,
            scheduledDepartureAt = parseOdsayDateTime(node.text("startDateTime")),
            scheduledArrivalAt = parseOdsayDateTime(node.text("endDateTime")),
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
                )
                else -> null
            },
            directionName = node.text("way"),
            directionCode = when (node.path("wayCode").takeIf(JsonNode::isNumber)?.asInt()) {
                1 -> "UP"
                2 -> "DOWN"
                else -> null
            },
            timingBasis = if (
                parseOdsayDateTime(node.text("startDateTime")) != null &&
                parseOdsayDateTime(node.text("endDateTime")) != null
            ) {
                TransitLegTimingBasis.TIMETABLE
            } else {
                TransitLegTimingBasis.UNKNOWN
            },
        )
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
        val EXPRESS_SERVICE_MARKERS = listOf("급행", "특급", "ITX", "EXPRESS")
        val LOCAL_SERVICE_MARKERS = listOf("일반열차", "(일반)", "완행", "LOCAL")
        val ODSAY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("uuuuMMddHHmm")
            .withResolverStyle(ResolverStyle.STRICT)
    }
}
