package com.noLate.schedule.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.schedule.domain.ScheduleTravelMode

/**
 * 여러 FE 버전이 저장한 선택 경로 JSON에서 provider 재조회에 필요한 최소 메타데이터를
 * 복원한다. 원본 JSON은 [TrafficRequest.selectedRouteJson]으로 그대로 유지한다.
 */
data class SelectedRouteMetadata(
    val travelMinutes: Int? = null,
    val routeOption: String? = null,
    val transitItineraryJson: String? = null,
    val routeSegments: List<RouteDisplaySegment> = emptyList(),
) {
    companion object {
        fun parse(
            objectMapper: ObjectMapper,
            routeJson: String?,
            travelMode: ScheduleTravelMode?,
            maxTravelMinutes: Int = EtaTravelTimePolicy.DEFAULT_MAX_TRAVEL_MINUTES,
        ): SelectedRouteMetadata {
            EtaTravelTimePolicy.requireValidMaximum(maxTravelMinutes)
            if (routeJson.isNullOrBlank()) {
                return SelectedRouteMetadata(routeSegments = fallbackRouteSegments(travelMode))
            }

            return runCatching {
                val root = objectMapper.readTree(routeJson)
                SelectedRouteMetadata(
                    travelMinutes = extractTravelMinutes(root, maxTravelMinutes),
                    routeOption = extractRouteOption(root),
                    transitItineraryJson = extractTransitItinerary(root, travelMode)?.toString(),
                    routeSegments = extractRouteSegments(root, travelMode),
                )
            }.getOrElse {
                SelectedRouteMetadata(routeSegments = fallbackRouteSegments(travelMode))
            }
        }

        private fun extractTravelMinutes(root: JsonNode, maxTravelMinutes: Int): Int? {
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
                ?.let { EtaTravelTimePolicy.normalizeMinutes(it, maxTravelMinutes) }
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

        private fun extractRouteSegments(
            root: JsonNode,
            travelMode: ScheduleTravelMode?,
        ): List<RouteDisplaySegment> {
            val steps = sequenceOf(
                root.path("transitJourney").path("legs"),
                root.path("transitLegs"),
                root.path("selectedItinerary").path("legs"),
                root.path("itinerary").path("legs"),
                root.path("routeInfo").path("steps"),
                root.path("steps"),
            ).firstOrNull { it.isArray && !it.isEmpty }
            val parsed = steps?.mapNotNull(::displaySegment).orEmpty()
            val ordered = parsed.fold(mutableListOf<RouteDisplaySegment>()) { result, segment ->
                if (result.lastOrNull() != segment) result += segment
                result
            }
            val bounded = compressMovementSegments(ordered)
            if (bounded.isNotEmpty()) return bounded
            return fallbackRouteSegments(travelMode)
        }

        private fun fallbackRouteSegments(
            travelMode: ScheduleTravelMode?,
        ): List<RouteDisplaySegment> = when (travelMode) {
                ScheduleTravelMode.CAR -> listOf(RouteDisplaySegment("drive", "자동차", "#2979FF"))
                ScheduleTravelMode.WALK -> listOf(RouteDisplaySegment("walk", "도보", "#9CA3AF"))
                ScheduleTravelMode.BIKE -> listOf(RouteDisplaySegment("bike", "자전거", "#22C55E"))
                ScheduleTravelMode.TRANSIT -> listOf(RouteDisplaySegment("transfer", "대중교통", "#9CA3AF"))
                else -> emptyList()
            }

        /**
         * The Live Activity route bar has five movement slots; its serializer appends destination
         * as slot six. Preserve both ends of a long itinerary and represent the one contiguous
         * omitted range explicitly instead of silently dropping late transfers.
         */
        private fun compressMovementSegments(
            ordered: List<RouteDisplaySegment>,
        ): List<RouteDisplaySegment> {
            if (ordered.size <= MAX_MOVEMENT_SEGMENTS) return ordered
            val omittedCount = ordered.size - PRESERVED_EDGE_SEGMENTS * 2
            return ordered.take(PRESERVED_EDGE_SEGMENTS) +
                RouteDisplaySegment(
                    kind = "transfer",
                    label = "${omittedCount}구간 생략",
                    colorHex = "#9CA3AF",
                ) +
                ordered.takeLast(PRESERVED_EDGE_SEGMENTS)
        }

        private fun displaySegment(node: JsonNode): RouteDisplaySegment? {
            val raw = sequenceOf("type", "kind", "mode")
                .map(node::path)
                .firstOrNull { it.isTextual }
                ?.asText()
                ?.uppercase()
            val kind = when (raw) {
                "WALK" -> "walk"
                "BUS" -> "bus"
                "SUBWAY" -> "subway"
                "CAR", "DRIVE" -> "drive"
                "BIKE", "BICYCLE" -> "bike"
                "TRANSFER" -> "transfer"
                "ORIGIN", "DESTINATION" -> return null
                else -> when (node.path("trafficType").takeIf(JsonNode::isNumber)?.asInt()) {
                    1 -> "subway"
                    2 -> "bus"
                    3 -> "walk"
                    else -> return null
                }
            }
            val lane = node.path("lane").let {
                when {
                    it.isArray -> it.firstOrNull()
                    it.isObject -> it
                    else -> null
                }
            }
            val label = sequenceOf(
                node.path("lineName"),
                node.path("badgeText"),
                node.path("route"),
                lane?.path("name"),
                lane?.path("busNo"),
            ).filterNotNull()
                .firstOrNull { it.isTextual || it.isIntegralNumber }
                ?.asText()
                ?.trim()
                ?.take(18)
                ?.takeIf(String::isNotBlank)
                ?: when (kind) {
                    "walk" -> "도보"
                    "bus" -> "버스"
                    "subway" -> "지하철"
                    "drive" -> "자동차"
                    "bike" -> "자전거"
                    else -> "환승"
                }
            val color = when (kind) {
                "bus", "drive" -> "#2979FF"
                "subway" -> "#00B140"
                "bike" -> "#22C55E"
                else -> "#9CA3AF"
            }
            return RouteDisplaySegment(kind, label, color)
        }

        private const val MAX_MOVEMENT_SEGMENTS = 5
        private const val PRESERVED_EDGE_SEGMENTS = 2
    }
}

data class RouteDisplaySegment(
    val kind: String,
    val label: String,
    val colorHex: String,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val legIndex: Int? = null,
)
