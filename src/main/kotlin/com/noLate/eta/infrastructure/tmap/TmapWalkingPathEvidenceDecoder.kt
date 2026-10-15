package com.noLate.eta.infrastructure.tmap

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.eta.domain.*

/** Tmap steps.linestring(WGS84 경도,위도)을 제한된 진단 데이터로만 보존한다. */
internal object TmapWalkingPathEvidenceDecoder {
    fun decode(node: JsonNode): TransitWalkingPathEvidence {
        fun result(status: TransitWalkingPathStatus) = TransitWalkingPathEvidence("tmap", status)
        val raw = node.path("steps")
        if (raw.isMissingNode || raw.isNull || (raw.isArray && raw.isEmpty)) {
            return result(TransitWalkingPathStatus.UNAVAILABLE)
        }
        if (!raw.isArray) return result(TransitWalkingPathStatus.INVALID)
        if (raw.size() > TransitWalkingPathEvidence.MAX_STEPS) return result(TransitWalkingPathStatus.LIMIT_EXCEEDED)
        val steps = mutableListOf<TransitWalkingStep>()
        var totalCoordinates = 0
        for (step in raw) {
            if (!step.isObject) return result(TransitWalkingPathStatus.INVALID)
            val line = step.path("linestring")
            if (!line.isTextual || line.textValue().isBlank()) return result(TransitWalkingPathStatus.INVALID)
            // 잘라낸 경로를 완전한 원본처럼 저장하지 않는다. 큰 문자열은 split 전에 거절한다.
            if (line.textValue().length > 8192) return result(TransitWalkingPathStatus.LIMIT_EXCEEDED)
            val tokens = line.textValue().trim().split(Regex("\\s+"),
                limit = TransitWalkingPathEvidence.MAX_COORDINATES_PER_STEP + 1)
            if (tokens.size > TransitWalkingPathEvidence.MAX_COORDINATES_PER_STEP) {
                return result(TransitWalkingPathStatus.LIMIT_EXCEEDED)
            }
            if (tokens.size < 2) return result(TransitWalkingPathStatus.INVALID)
            totalCoordinates += tokens.size
            if (totalCoordinates > TransitWalkingPathEvidence.MAX_TOTAL_COORDINATES) {
                return result(TransitWalkingPathStatus.LIMIT_EXCEEDED)
            }
            val coordinates = tokens.map { token ->
                val pair = token.split(',', limit = 3)
                if (pair.size != 2) return result(TransitWalkingPathStatus.INVALID)
                val lng = pair[0].toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }
                    ?: return result(TransitWalkingPathStatus.INVALID)
                val lat = pair[1].toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 }
                    ?: return result(TransitWalkingPathStatus.INVALID)
                TransitWalkingCoordinate(lat, lng)
            }
            val distanceNode = step.path("distance")
            val distance = if (distanceNode.isMissingNode || distanceNode.isNull) null else {
                val value = when {
                    distanceNode.isNumber -> distanceNode.doubleValue()
                    distanceNode.isTextual -> distanceNode.textValue().toDoubleOrNull()
                    else -> null
                }
                value?.takeIf { it.isFinite() && it >= 0 } ?: return result(TransitWalkingPathStatus.INVALID)
            }
            val text = step.path("description")
            val description = if (text.isMissingNode || text.isNull) null else {
                if (!text.isTextual) return result(TransitWalkingPathStatus.INVALID)
                if (text.textValue().length > TransitWalkingPathEvidence.MAX_DESCRIPTION_LENGTH) {
                    return result(TransitWalkingPathStatus.LIMIT_EXCEEDED)
                }
                if (text.textValue().any(Char::isISOControl)) return result(TransitWalkingPathStatus.INVALID)
                text.textValue().trim().takeIf(String::isNotBlank)
            }
            steps += TransitWalkingStep(coordinates, distance, description)
        }
        // 출구를 언급하는 설명도 승강장 포함의 증거로 해석하지 않는다.
        return TransitWalkingPathEvidence("tmap", TransitWalkingPathStatus.PROVIDED, steps)
    }
}
