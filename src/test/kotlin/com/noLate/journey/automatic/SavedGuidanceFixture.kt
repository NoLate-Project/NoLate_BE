package com.noLate.journey.automatic

import com.noLate.schedule.domain.SchedulePlaceDto

/** Full synthetic saved TMAP-shaped route. No ODsay companion geometry/IDs. */
fun AutomaticJourneyFixture.useSavedGuidancePlan(mapped: Boolean = false) {
    fun point(lng: Double) = mapOf("lat" to 37.5665, "lng" to lng)
    fun leg(kind: String, points: List<Double>, duration: Int): Map<String, Any?> {
        val bus = kind == "BUS"
        return linkedMapOf("kind" to kind, "label" to if (bus) "합성 버스" else "합성 도보", "durationMinutes" to duration,
            "startName" to if (bus) "합성 승차" else "합성 도보", "endName" to if (bus) "합성 하차" else "합성 도보 끝",
            "lineName" to if (bus) "합성 버스" else null, "stationCount" to if (bus) 2 else null,
            "lineColor" to if (bus) "#E8752B" else null,
            "startCoord" to point(points.first()), "endCoord" to point(points.last()),
            "pathCoords" to points.map(::point), "pathCoordsIsExact" to true,
            "pathGeometrySource" to if (bus) "TRANSIT_PASS_SHAPE_LINESTRING" else "WALK_STEPS_LINESTRING",
            "serviceAvailable" to if (bus) true else null,
            "navigationEvidence" to linkedMapOf("version" to 1, "provider" to "tmap", "geometryInputComplete" to true,
                "declaredStationCount" to if (bus) 2 else null, "directionName" to if (bus) "합성 동쪽 방면" else null,
                "directionCode" to if (bus) "DOWN" else null, "stopOccurrences" to if (bus) points.mapIndexed { i, lng ->
                    mapOf("sourceIndex" to i, "providerSequence" to i, "name" to "합성 정류장 ${i + 1}",
                        "code" to "synthetic-$i", "coord" to point(lng))
                } else emptyList<Map<String, Any?>>()))
    }
    val route = linkedMapOf("id" to "saved-guidance-synthetic-tmap", "label" to "합성 경로", "summary" to "합성 버스 · 첫 도보 132m",
        "durationMinutes" to 9, "distanceMeters" to 1145, "mode" to "TRANSIT", "provider" to "tmap", "source" to "api",
        "routeReliability" to "live_provider", "routePlausibility" to "normal", "transitServiceState" to "operating",
        "transitLegs" to listOf(leg("WALK", listOf(126.978, 126.97875, 126.9795), 2),
            leg("BUS", listOf(126.9795, 126.9845, 126.9895), 5),
            leg("WALK", listOf(126.9895, 126.99025, 126.991), 2)))
    plan = plan.copy(route = mapper.valueToTree(route),
        origin = SchedulePlaceDto(name = "합성 출발", lat = 37.5665, lng = 126.978),
        destination = SchedulePlaceDto(name = "합성 도착", lat = 37.5665, lng = 126.991))
    if (mapped) {
        // Entire artificial fixture, never a production ODsay parser output or real ID crosswalk.
        // Production ODsay WALK connectors remain geometryInputComplete=false.
        val complete = plan
        useMappedBusPlan()
        val metadata = plan.route!!.path("transitLegs")[1].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        val whole = complete.route!!.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        whole.put("provider", "odsay").put("id", "saved-guidance-synthetic-mapped").put("summary", "전체 합성 저장 경로 · 가짜 시험 ID")
        val legs = whole.path("transitLegs")
        for (leg in legs) (leg.path("navigationEvidence") as com.fasterxml.jackson.databind.node.ObjectNode).put("provider", "odsay")
        val bus = legs[1] as com.fasterxml.jackson.databind.node.ObjectNode
        metadata.fields().forEachRemaining { (key, value) -> if (key != "navigationEvidence") bus.set<com.fasterxml.jackson.databind.JsonNode>(key, value) }
        val evidence = metadata.path("navigationEvidence") as com.fasterxml.jackson.databind.node.ObjectNode
        evidence.put("geometryInputComplete", true).put("directionCode", "DOWN")
        for ((index, stop) in evidence.path("stopOccurrences").withIndex()) {
            (stop as com.fasterxml.jackson.databind.node.ObjectNode).put("name", "합성 정류장 ${index + 1}").put("code", stop.path("localStationID").asText())
        }
        bus.set<com.fasterxml.jackson.databind.JsonNode>("navigationEvidence", evidence)
        plan = complete.copy(route = whole)
    }
}

/** Display stress only. Later legs have synthetic continuity, never stop/vehicle evidence. */
fun AutomaticJourneyFixture.useSavedGuidanceDisplayPlan(variant: String) {
    require(variant in setOf("long-name", "32-legs"))
    useSavedGuidancePlan(mapped = true)
    val route = plan.route!!.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
    val legs = route.path("transitLegs") as com.fasterxml.jackson.databind.node.ArrayNode
    val name = "합성 시험 노선 · 매우 긴 노선명을 끝까지 읽을 수 있어야 하는 접근성 검증 전용 버스"
    (legs[1] as com.fasterxml.jackson.databind.node.ObjectNode).put("lineName", name).put("lineColor", "#FFF5A0")
    if (variant == "32-legs") {
        repeat(29) { offset ->
            // Synthetic endpoints preserve the full-route continuity gate; absent stops/IDs/
            // direction still block ride readiness. No production geometry gate is relaxed.
            val start = mapOf("lat" to 37.5665, "lng" to 126.991 + offset * 0.001)
            val end = mapOf("lat" to 37.5665, "lng" to 126.991 + (offset + 1) * 0.001)
            val added = legs.addObject().put("kind", if (offset % 2 == 0) "BUS" else "SUBWAY")
                .put("label", "합성 표시 구간 ${offset + 4}").put("lineName", "$name ${offset + 4}")
                .put("lineColor", "#FFF5A0").put("durationMinutes", 1)
                .put("pathGeometrySource", "TRANSIT_PASS_SHAPE_LINESTRING").put("pathCoordsIsExact", true)
            added.set<com.fasterxml.jackson.databind.JsonNode>("startCoord", mapper.valueToTree(start))
            added.set<com.fasterxml.jackson.databind.JsonNode>("endCoord", mapper.valueToTree(end))
            added.set<com.fasterxml.jackson.databind.JsonNode>("pathCoords", mapper.valueToTree(listOf(start, end)))
        }
        route.put("durationMinutes", 38)
    }
    route.put("id", "saved-guidance-display-$variant").put("summary", "합성 표시 스트레스 · 후속 구간 판정 불가")
    plan = plan.copy(route = route, destination = if (variant == "32-legs")
        SchedulePlaceDto(name = "합성 표시 끝", lat = 37.5665, lng = 127.020) else plan.destination)
}
