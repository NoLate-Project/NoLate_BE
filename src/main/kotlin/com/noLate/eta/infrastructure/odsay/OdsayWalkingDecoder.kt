package com.noLate.eta.infrastructure.odsay

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.eta.domain.*

/**
 * maasRP: duration은 분, routes.DURATION은 초, distance/LENGTH는 m이다.
 * 계약: https://lab.odsay.com/guide/releaseReference (maasRP 도보 RP 세부 구간).
 * 상세 좌표 제공 여부와 구간 시간의 완전성은 별개이며, 누락 시 분 단위 계산을 유지한다.
 */
internal object OdsayWalkingDecoder {
    data class Decoded(val geometry: TransitWalkingGeometry?, val durationSeconds: Long?)

    fun decode(node: JsonNode, durationMinutes: Int): Decoded {
        val distance = integer(node.path("distance"))
        val raw = node.path("routes")
        var seconds = 0L
        var length = 0L
        var completeTiming = true
        val steps = mutableListOf<TransitWalkingStep>()
        var coordinateCount = 0
        val status = when {
            raw.isMissingNode || raw.isNull || (raw.isArray && raw.isEmpty) -> TransitWalkingPathStatus.UNAVAILABLE
            !raw.isArray -> TransitWalkingPathStatus.INVALID
            raw.size() > TransitWalkingPathEvidence.MAX_STEPS -> TransitWalkingPathStatus.LIMIT_EXCEEDED
            else -> run {
                for ((index, link) in raw.withIndex()) {
                    if (!link.isObject) return@run TransitWalkingPathStatus.INVALID
                    val points = link.path("xyInfos")
                    if (!points.isArray || points.size() < 2) return@run TransitWalkingPathStatus.INVALID
                    if (points.size() > TransitWalkingPathEvidence.MAX_COORDINATES_PER_STEP) {
                        return@run TransitWalkingPathStatus.LIMIT_EXCEEDED
                    }
                    coordinateCount += points.size()
                    if (coordinateCount > TransitWalkingPathEvidence.MAX_TOTAL_COORDINATES) {
                        return@run TransitWalkingPathStatus.LIMIT_EXCEEDED
                    }
                    val coordinates = points.map { point ->
                        coordinate(point) ?: return@run TransitWalkingPathStatus.INVALID
                    }
                    if (steps.isNotEmpty() && steps.last().coordinates.last() != coordinates.first()) {
                        completeTiming = false
                    }
                    val meters = integer(link.path("LENGTH"))
                    val duration = integer(link.path("DURATION"))
                    if (meters == null || duration == null || integer(link.path("rseq")) != index + 1L) {
                        completeTiming = false
                    }
                    // crossXYInfos는 별도 직선 연결이다. 해당 시간의 포함 계약이 없어
                    // 상세 링크 합만으로 전체 구간을 짧게 만들지 않는다.
                    val cross = link.path("crossXYInfos")
                    if (!cross.isMissingNode && !cross.isNull && !(cross.isArray && cross.isEmpty)) {
                        completeTiming = false
                    }
                    seconds += duration ?: 0
                    length += meters ?: 0
                    steps += TransitWalkingStep(coordinates, meters?.toDouble())
                }
                TransitWalkingPathStatus.PROVIDED
            }
        }
        val evidence = TransitWalkingPathEvidence("odsay", status,
            if (status == TransitWalkingPathStatus.PROVIDED) steps else emptyList())
        // xyInfos는 링크 자체의 좌표다. 별도 연결/승강장 포함을 증명하지 않으며,
        // 이를 명목 구간의 from/to 좌표로 승격하거나 역 내부 포함으로 표시하지 않는다.
        val geometry = if (distance == null && status == TransitWalkingPathStatus.UNAVAILABLE) null
            else TransitWalkingGeometry(distanceMeters = distance, pathEvidence = evidence,
                providerQueryEndpoints = if (status == TransitWalkingPathStatus.PROVIDED) queryEndpoints(raw, steps, distance) else null)
        val exact = seconds.takeIf {
            status == TransitWalkingPathStatus.PROVIDED && completeTiming &&
                distance != null && length == distance &&
                (it / 60 + if (it % 60 == 0L) 0 else 1) == durationMinutes.toLong()
        }
        return Decoded(geometry, exact)
    }

    /** 연결 좌표를 역/승강장 좌표로 승격하지 않는다. 동일 도보 V2 조회의 입력으로만 사용한다. */
    private fun queryEndpoints(raw: JsonNode, steps: List<TransitWalkingStep>, distance: Long?): TransitWalkingQueryEndpoints? {
        if (distance == null || steps.sumOf { it.distanceMeters ?: return null }.toLong() != distance ||
            raw.withIndex().any { integer(it.value.path("rseq")) != it.index + 1L } ||
            steps.zipWithNext().any { it.first.coordinates.last() != it.second.coordinates.first() }) return null
        if ((1 until raw.size() - 1).any { i -> raw[i].path("crossXYInfos").let {
            !it.isMissingNode && !it.isNull && !(it.isArray && it.isEmpty)
        } }) return null
        fun endpoint(link: JsonNode, start: Boolean, point: TransitWalkingCoordinate): TransitWalkingCoordinate? {
            val cross = link.path("crossXYInfos")
            if (cross.isMissingNode || cross.isNull || (cross.isArray && cross.isEmpty)) return point
            if (!cross.isArray || cross.size() != 2) return null
            val connected = coordinate(cross[if (start) 1 else 0]) ?: return null
            if (connected != point) return null
            return coordinate(cross[if (start) 0 else 1])
        }
        // 한 링크의 두 연결을 하나의 cross 배열로 증명할 수 없으면 조회하지 않는다.
        if (raw.size() < 2) return null
        return TransitWalkingQueryEndpoints(
            endpoint(raw.first(), true, steps.first().coordinates.first()) ?: return null,
            endpoint(raw.last(), false, steps.last().coordinates.last()) ?: return null)
    }

    private fun integer(node: JsonNode): Long? = when {
        node.isIntegralNumber && node.canConvertToInt() -> node.longValue()
        node.isTextual -> node.textValue().toIntOrNull()?.toLong()
        else -> null
    }?.takeIf { it >= 0 }

    private fun coordinate(node: JsonNode): TransitWalkingCoordinate? {
        fun number(field: String): Double? {
            val value = node.path(field)
            return when {
                value.isNumber -> value.doubleValue()
                value.isTextual -> value.textValue().toDoubleOrNull()
                else -> null
            }?.takeIf(Double::isFinite)
        }
        val lat = number("y")?.takeIf { it in -90.0..90.0 } ?: return null
        val lng = number("x")?.takeIf { it in -180.0..180.0 } ?: return null
        return TransitWalkingCoordinate(lat, lng)
    }
}
