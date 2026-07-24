package com.noLate.schedule.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.schedule.domain.ScheduleTravelMode
import kotlin.math.ceil

/**
 * 여러 FE 버전이 저장한 선택 경로 JSON에서 provider 재조회에 필요한 최소 메타데이터를
 * 복원한다. 원본 JSON은 [TrafficRequest.selectedRouteJson]으로 그대로 유지한다.
 */
data class SelectedRouteMetadata(
    val travelMinutes: Int? = null,
    val routeOption: String? = null,
    val transitItineraryJson: String? = null,
) {
    companion object {
        fun parse(
            objectMapper: ObjectMapper,
            routeJson: String?,
            travelMode: ScheduleTravelMode?,
        ): SelectedRouteMetadata {
            if (routeJson.isNullOrBlank()) return SelectedRouteMetadata()

            return runCatching {
                val root = objectMapper.readTree(routeJson)
                SelectedRouteMetadata(
                    travelMinutes = extractTravelMinutes(root),
                    routeOption = extractRouteOption(root),
                    transitItineraryJson = extractTransitItinerary(root, travelMode)?.toString(),
                )
            }.getOrDefault(SelectedRouteMetadata())
        }

        private fun extractTravelMinutes(root: JsonNode): Int? {
            val routeInfo = root.path("routeInfo")
            return sequenceOf(
                root.path("totalDurationMinutes"),
                root.path("minutes"),
                root.path("travelMinutes"),
                root.path("durationMinutes"),
                routeInfo.path("totalDurationMinutes"),
                routeInfo.path("minutes"),
                routeInfo.path("travelMinutes"),
                routeInfo.path("durationMinutes"),
            )
                .firstOrNull { it.isNumber }
                ?.asDouble()
                ?.takeIf { it.isFinite() && it > 0 }
                ?.let { ceil(it).toInt().coerceAtLeast(1) }
        }

        private fun extractRouteOption(root: JsonNode): String? {
            val routeInfo = root.path("routeInfo")
            return sequenceOf(
                root.path("searchOption"),
                root.path("providerRouteOption"),
                routeInfo.path("searchOption"),
                routeInfo.path("providerRouteOption"),
            )
                .mapNotNull { node ->
                    node.takeIf { it.isTextual || it.isIntegralNumber }
                        ?.asText()
                        ?.trim()
                        ?.takeIf { it.matches(Regex("\\d{1,2}")) }
                }
                .firstOrNull()
        }

        private fun extractTransitItinerary(
            root: JsonNode,
            travelMode: ScheduleTravelMode?,
        ): JsonNode? {
            if (travelMode != ScheduleTravelMode.TRANSIT) return null

            val routeInfo = root.path("routeInfo")
            return sequenceOf(
                root.path("selectedItinerary"),
                root.path("itinerary"),
                routeInfo.path("selectedItinerary"),
                routeInfo.path("itinerary"),
            )
                .firstOrNull { it.isObject }
                ?: root.takeIf {
                    it.isObject && (it.path("legs").isArray || it.path("transferCount").isNumber)
                }
        }
    }
}
