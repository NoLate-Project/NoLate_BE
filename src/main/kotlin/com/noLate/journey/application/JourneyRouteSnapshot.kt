package com.noLate.journey.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.journey.domain.JourneyLeg
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import com.noLate.schedule.domain.ScheduleTravelPlanStatus

data class JourneyRouteSnapshot(val json: String, val legsJson: String, val fingerprint: String, val legs: List<JourneyLeg>) {
    companion object {
        fun from(plan: ScheduleTravelPlanDto, mapper: ObjectMapper): JourneyRouteSnapshot {
            if (plan.status != ScheduleTravelPlanStatus.READY || plan.travelMode != ScheduleTravelMode.TRANSIT) {
                throw BusinessException(ErrorCode.INVALID_STATE, "현재 사용할 수 있는 본인의 대중교통 경로가 필요합니다.")
            }
            val route = plan.route ?: throw BusinessException(ErrorCode.INVALID_STATE, "저장된 대중교통 경로가 없습니다.")
            val rawLegs = sequenceOf(
                route.path("transitLegs"), route.path("routeInfo").path("transitLegs"),
                route.path("transitJourney").path("legs"), route.path("selectedItinerary").path("legs"),
                route.path("itinerary").path("legs"), route.path("legs"),
            ).firstOrNull { it.isArray && !it.isEmpty }
                ?: throw BusinessException(ErrorCode.INVALID_STATE, "상세 구간이 있는 경로를 다시 선택해 주세요.")
            if (rawLegs.size() > 64) throw BusinessException(ErrorCode.INVALID_INPUT, "경로 구간이 너무 많습니다.")
            val normalized = mapper.createArrayNode()
            rawLegs.forEach { raw ->
                if (!raw.isObject) throw BusinessException(ErrorCode.INVALID_STATE, "경로 구간 형식이 올바르지 않습니다.")
                val leg = raw.deepCopy<ObjectNode>()
                val kind = raw.path("kind").asText().ifBlank { raw.path("mode").asText() }.uppercase()
                if (kind !in setOf("WALK", "BUS", "SUBWAY")) {
                    throw BusinessException(ErrorCode.INVALID_STATE, "이 경로는 아직 길안내를 지원하지 않습니다.")
                }
                leg.put("kind", kind)
                normalized.add(leg)
            }
            val legs = describe(normalized)
            if (legs.none { it.kind != "WALK" }) throw BusinessException(ErrorCode.INVALID_STATE, "대중교통 구간이 필요합니다.")
            val json = mapper.writeValueAsString(route)
            if (json.toByteArray(Charsets.UTF_8).size > 1_048_576) throw BusinessException(ErrorCode.INVALID_INPUT, "저장된 경로가 너무 큽니다.")
            val identity = mapper.writeValueAsString(listOf(route, plan.origin, plan.destination, plan.travelMode))
            return JourneyRouteSnapshot(json, mapper.writeValueAsString(normalized), OpaquePushIdentifier.fingerprint(identity), legs)
        }

        fun describe(legs: JsonNode): List<JourneyLeg> = legs.map { leg ->
            val kind = leg.path("kind").asText()
            val stops = if (kind == "WALK") null else {
                leg.path("stationCount").takeIf { it.isIntegralNumber && it.asInt() >= 0 }?.asInt()
                    ?: leg.path("passStops").takeIf { it.isArray && it.size() > 1 }?.let { it.size() - 1 }
            }
            JourneyLeg(kind, stops, leg.text("startName"), leg.text("endName"), leg.text("lineName"))
        }

        private fun JsonNode.text(field: String): String? = path(field).takeIf { it.isTextual }?.asText()?.take(100)?.takeIf(String::isNotBlank)
    }
}
