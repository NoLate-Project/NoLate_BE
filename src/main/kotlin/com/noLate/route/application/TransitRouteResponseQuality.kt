package com.noLate.route.application

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.route.domain.TransitRouteRequest
import kotlin.math.ceil
import kotlin.math.max

data class TransitRouteResponseQuality(
    val usableCandidateCount: Int,
    val fastestMinutes: Int?,
    val geometryCompleteCandidateCount: Int,
    val fastestGeometryCompleteMinutes: Int?,
    val rideLegCount: Int,
    val rideShapeLegCount: Int,
) {
    val isUsable: Boolean
        get() = usableCandidateCount > 0 && fastestMinutes != null
}

/** 공급자 응답을 화면에 넘기기 전에 최소한의 후보 품질을 검증한다. */
object TransitRouteResponseQualityEvaluator {
    fun evaluate(response: JsonNode, request: TransitRouteRequest): TransitRouteResponseQuality {
        val itineraries = response.path("metaData").path("plan").path("itineraries")
        if (!itineraries.isArray) return TransitRouteResponseQuality(0, null, 0, null, 0, 0)

        val minimumMinutes = minimumPlausibleMinutes(request)
        val usableCandidates = itineraries.mapNotNull { itinerary ->
            val duration = normalizeDurationMinutes(itinerary.path("totalTime")) ?: return@mapNotNull null
            val legs = itinerary.path("legs")
            if (!legs.isArray || legs.none(::isTransitLeg)) return@mapNotNull null
            if (duration < minimumMinutes) return@mapNotNull null
            CandidateGeometry(
                durationMinutes = duration,
                geometryComplete = isGeometryComplete(legs),
                rideLegCount = legs.count(::isTransitLeg),
                rideShapeLegCount = legs.count { isTransitLeg(it) && hasPathGeometry(it) },
            )
        }
        val completeCandidates = usableCandidates.filter { it.geometryComplete }

        return TransitRouteResponseQuality(
            usableCandidateCount = usableCandidates.size,
            fastestMinutes = usableCandidates.minOfOrNull { it.durationMinutes }?.let(::ceil)?.toInt(),
            geometryCompleteCandidateCount = completeCandidates.size,
            fastestGeometryCompleteMinutes = completeCandidates
                .minOfOrNull { it.durationMinutes }
                ?.let(::ceil)
                ?.toInt(),
            rideLegCount = usableCandidates.sumOf { it.rideLegCount },
            rideShapeLegCount = usableCandidates.sumOf { it.rideShapeLegCount },
        )
    }

    private data class CandidateGeometry(
        val durationMinutes: Double,
        val geometryComplete: Boolean,
        val rideLegCount: Int,
        val rideShapeLegCount: Int,
    )

    private fun isGeometryComplete(legs: JsonNode): Boolean = legs.all { leg ->
        when {
            isTransitLeg(leg) -> hasPathGeometry(leg)
            isMeaningfulWalkLeg(leg) -> hasPathGeometry(leg)
            else -> true
        }
    }

    private fun isMeaningfulWalkLeg(leg: JsonNode): Boolean {
        val mode = leg.path("mode").asText(leg.path("type").asText()).uppercase()
        if (mode != "WALK" && mode != "3") return false
        val distance = leg.path("distance")
        return distance.isNumber && distance.asDouble() > MIN_WALK_GEOMETRY_DISTANCE_METERS
    }

    private fun hasPathGeometry(leg: JsonNode): Boolean {
        val directCandidates = listOf(
            leg.path("passShape").path("linestring"),
            leg.path("passShape").path("coordinates"),
            leg.path("shape"),
            leg.path("path"),
            leg.path("geometry"),
        )
        if (directCandidates.any(::hasCoordinatePayload)) return true

        val steps = leg.path("steps")
        return steps.isArray && steps.any { step ->
            hasCoordinatePayload(step.path("linestring")) || hasCoordinatePayload(step.path("path"))
        }
    }

    private fun hasCoordinatePayload(node: JsonNode): Boolean = when {
        node.isTextual -> node.asText().trim().length >= MIN_LINESTRING_LENGTH
        node.isArray -> node.size() >= 2
        else -> false
    }

    private fun normalizeDurationMinutes(value: JsonNode): Double? {
        if (!value.isNumber && !value.isTextual) return null
        val raw = value.asText().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
        // TMAP transit의 totalTime은 값의 크기와 관계없이 항상 초 단위다.
        return raw / 60.0
    }

    private fun isTransitLeg(leg: JsonNode): Boolean {
        val mode = leg.path("mode").asText(leg.path("type").asText()).uppercase()
        return mode == "BUS" || mode == "SUBWAY" || mode == "1" || mode == "2"
    }

    private fun minimumPlausibleMinutes(request: TransitRouteRequest): Double {
        val directDistanceKm = request.directDistanceMeters() / 1_000.0
        return max(1.0, directDistanceKm / MAX_PLAUSIBLE_AVERAGE_SPEED_KPH * 60.0)
    }

    private fun TransitRouteRequest.directDistanceMeters(): Double {
        val earthRadiusMeters = 6_371_000.0
        val startLat = Math.toRadians(startY)
        val endLat = Math.toRadians(endY)
        val deltaLat = Math.toRadians(endY - startY)
        val deltaLng = Math.toRadians(endX - startX)
        val haversine = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
            Math.cos(startLat) * Math.cos(endLat) *
            Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2)
        return earthRadiusMeters * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine))
    }

    private const val MAX_PLAUSIBLE_AVERAGE_SPEED_KPH = 120.0
    private const val MIN_WALK_GEOMETRY_DISTANCE_METERS = 40.0
    private const val MIN_LINESTRING_LENGTH = 7
}
