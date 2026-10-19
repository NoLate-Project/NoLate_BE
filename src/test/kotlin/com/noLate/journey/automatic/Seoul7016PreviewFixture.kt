package com.noLate.journey.automatic

import com.noLate.schedule.domain.SchedulePlaceDto

/** Public TOPIS observation, 2026-10-07. Preview only, NOT an ODsay response or complete navigation route. */
fun AutomaticJourneyFixture.useSeoul7016Preview() {
    val stops = listOf(
        listOf(44, "101000007", "02007", "서울역버스환승센터.강우규의거터", 37.555411, 126.972926),
        listOf(45, "101000033", "02128", "소공동주민센터", 37.562197167, 126.97644367),
        listOf(46, "101000039", "02137", "서울신문사", 37.5683632936, 126.9773647051),
        listOf(47, "100000023", "01118", "광화문역2번출구.KT광화문지사", 37.5723209262, 126.9774162769),
        listOf(48, "100000021", "01116", "경복궁역3번출구", 37.5776713863, 126.9722828893),
    )
    fun coord(stop: List<Any>) = mapOf("lat" to stop[4], "lng" to stop[5])
    val boarding = coord(stops.first()); val alighting = coord(stops.last())
    val route = mapOf("id" to "seoul-7016-public-preview", "mode" to "TRANSIT", "provider" to "seoul-topis",
        "source" to "public-web-preview", "summary" to "7016 상명대 방면 · 실제 정류장 관측 · 안내 미지원",
        "transitLegs" to listOf(
            mapOf("kind" to "WALK", "label" to "도보 연결 경로 미확보", "startCoord" to boarding, "endCoord" to boarding),
            mapOf("kind" to "BUS", "lineName" to "7016", "lineColor" to "#36A84B", "startName" to stops.first()[3],
                "endName" to stops.last()[3], "startCoord" to boarding, "endCoord" to alighting,
                "localRouteId" to "100100447", "stationCount" to 4,
                "navigationEvidence" to mapOf("version" to 1, "provider" to "seoul-topis", "geometryInputComplete" to false,
                    "directionName" to "상명대", "declaredStationCount" to 4,
                    "stopOccurrences" to stops.mapIndexed { i, stop -> mapOf("sourceIndex" to i, "providerSequence" to stop[0],
                        "localStationID" to stop[1], "arsID" to stop[2], "name" to stop[3], "coord" to coord(stop)) }))
        ))
    // No invented walking polyline, stop-to-stop bus line, ODsay ID, ETA, or passenger/vehicle association.
    plan = plan.copy(route = mapper.valueToTree(route),
        origin = SchedulePlaceDto(name = "서울역 시험 시작점", lat = 37.555411, lng = 126.972926),
        destination = SchedulePlaceDto(name = "경복궁역3번출구", lat = 37.5776713863, lng = 126.9722828893))
}
