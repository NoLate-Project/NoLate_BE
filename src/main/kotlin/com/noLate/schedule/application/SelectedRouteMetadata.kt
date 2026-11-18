package com.noLate.schedule.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.schedule.domain.ScheduleTravelMode
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 여러 FE 버전이 저장한 선택 경로 JSON에서 provider 재조회에 필요한 최소 메타데이터를
 * 복원한다. 원본 JSON은 [TrafficRequest.selectedRouteJson]으로 그대로 유지한다.
 */
data class SelectedRouteMetadata(
    val travelMinutes: Int? = null,
    val anchoredDepartureAt: Instant? = null,
    val routeOption: String? = null,
    val transitItineraryJson: String? = null,
    val routeSegments: List<RouteDisplaySegment> = emptyList(),
    val odsayTimetable: SavedOdsayTimetable? = null,
    val odsayEstimatedDurationSeconds: Long? = null,
    val odsayBusPreview: SavedOdsayBusPreview? = null,
) {
    internal fun odsayEstimatedDepartureAt(target: Instant, minutes: Int): Instant? = odsayEstimatedDurationSeconds
        ?.takeIf { roundedMinutes(it) == minutes.toLong() }
        ?.let(target::minusSeconds)

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
                val timetable = extractOdsayTimetable(root).takeIf { travelMode == ScheduleTravelMode.TRANSIT }
                SelectedRouteMetadata(
                    travelMinutes = timetable?.travelMinutes?.takeIf {
                        EtaTravelTimePolicy.isValid(it, maxTravelMinutes)
                    } ?: extractTravelMinutes(root, maxTravelMinutes),
                    anchoredDepartureAt = extractAnchoredDeparture(root),
                    routeOption = extractRouteOption(root),
                    transitItineraryJson = extractTransitItinerary(root, travelMode)?.toString(),
                    routeSegments = extractRouteSegments(root, travelMode),
                    odsayTimetable = timetable,
                    odsayEstimatedDurationSeconds = extractOdsayEstimateSeconds(root)
                        .takeIf { travelMode == ScheduleTravelMode.TRANSIT },
                    odsayBusPreview = extractOdsayBusPreview(root)?.takeIf {
                        travelMode == ScheduleTravelMode.TRANSIT &&
                            EtaTravelTimePolicy.isValid(it.travelMinutes, maxTravelMinutes)
                    },
                )
            }.getOrElse {
                SelectedRouteMetadata(routeSegments = fallbackRouteSegments(travelMode))
            }
        }

        internal fun extractAnchoredDeparture(root: JsonNode): Instant? = runCatching {
            val journey = root.path("intercityJourney")
            val info = root.path("routeInfo")
            if (root.path("schemaVersion").asInt() != 3 || journey.path("version").asInt() != 1 ||
                info.path("timeBasis").asText() != "anchored_journey" || info.path("intercityJourney") != journey ||
                info.path("departureTime").asText() != journey.path("departureAt").asText() ||
                journey.path("service").path("kind").asText() !in setOf("TRAIN", "EXPRESS_BUS", "INTERCITY_BUS") ||
                journey.path("service").path("timingBasis").asText() !in setOf("TIMETABLE", "TICKET")) return@runCatching null
            val departure = OffsetDateTime.parse(journey.path("departureAt").asText()).toInstant()
            val arrival = OffsetDateTime.parse(journey.path("arrivalAt").asText()).toInstant()
            departure.takeIf { arrival.isAfter(it) }
        }.getOrNull()

        /** An old saved metro/bus departure must not delay preparation past the current appointment's estimate. */
        internal fun guardSavedDeparture(
            target: Instant,
            saved: Instant?,
            travelMinutes: Int?,
            travelMode: ScheduleTravelMode?,
            route: JsonNode?,
        ): Instant? {
            if (travelMode != ScheduleTravelMode.TRANSIT) return saved
            if (saved != null && saved < target && route?.let(::extractAnchoredDeparture) == saved) return saved
            route?.let(::extractOdsayTimetable)?.departureIfCompatible(target, saved, travelMinutes)?.let { return it }
            route?.let(::extractOdsayBusPreview)?.takeIf { it.matches(target, saved, travelMinutes) }
                ?.let { return it.departureAt }
            route?.let { originalOdsayBusEstimateForChangedTarget(it, target) }?.let {
                return target.minusSeconds(it)
            }
            route?.let(::extractOdsayEstimateSeconds)?.takeIf { roundedMinutes(it) == travelMinutes?.toLong() }?.let {
                val estimate = target.minusSeconds(it)
                return saved?.let { minOf(it, estimate) } ?: estimate
            }
            if (saved == null) return null
            val estimate = travelMinutes?.takeIf { it > 0 }?.let { target.minusSeconds(it.toLong() * 60) }
            return if (estimate != null) minOf(saved, estimate) else saved.takeIf { it < target }
        }

        /** Publish the same appointment timeline in both the schedule and its selected ODsay route. */
        internal fun alignOdsayRouteTiming(
            target: Instant,
            departure: Instant?,
            travelMinutes: Int?,
            travelMode: ScheduleTravelMode?,
            route: JsonNode?,
        ): JsonNode? {
            if (travelMode != ScheduleTravelMode.TRANSIT || route !is ObjectNode || route.has("intercityJourney"))
                return route
            val info = route.get("routeInfo") ?: route
            if (info !is ObjectNode || info.path("provider").asText() != "odsay") return route
            if (extractOdsayTimetable(route)?.matches(target, departure, travelMinutes) == true ||
                extractOdsayBusPreview(route)?.matches(target, departure, travelMinutes) == true) return route

            val preciseSeconds = extractOdsayEstimateSeconds(route)?.takeIf { roundedMinutes(it) == travelMinutes?.toLong() }
            val minutes = travelMinutes?.takeIf { EtaTravelTimePolicy.isValid(it, EtaTravelTimePolicy.DEFAULT_MAX_TRAVEL_MINUTES) }
            val seconds = preciseSeconds ?: minutes?.toLong()?.times(60)
            val arrival = seconds?.let { departure?.plusSeconds(it) }?.takeIf { it <= target }
            fun hasClock(node: JsonNode, key: String, time: Instant?) =
                runCatching { Instant.parse(node.path(key).asText()) == time }.getOrDefault(false)
            if (info.path("timeBasis").asText() == "estimated" &&
                info.path("providerTimeBasis").asText() == "route_estimate" &&
                !route.hasNonNull("odsayTimetablePreview") && !route.hasNonNull("odsayBusPreview") &&
                !info.hasNonNull("busPreviewObservation") &&
                arrival != null && departure != null &&
                hasClock(info, "departureTime", departure) && hasClock(info, "arrivalTime", arrival) &&
                (!route.hasNonNull("transitDepartureAt") || hasClock(route, "transitDepartureAt", departure)) &&
                (!route.hasNonNull("providerDepartureAt") || hasClock(route, "providerDepartureAt", departure)) &&
                (!route.hasNonNull("providerArrivalAt") || hasClock(route, "providerArrivalAt", arrival))) return route

            val normalized = route.deepCopy()
            val normalizedInfo = normalized.get("routeInfo") as? ObjectNode ?: normalized
            normalized.remove(listOf("providerDepartureAt", "providerArrivalAt", "transitDepartureAt",
                "transitDepartureTimeSource", "odsayTimetablePreview", "odsayBusPreview"))
            normalized.put("providerTimeBasis", "route_estimate")
            normalizedInfo.put("providerTimeBasis", "route_estimate")
            normalizedInfo.put("timeBasis", "estimated")
            normalizedInfo.remove("busPreviewObservation")
            normalizedInfo.put("departureTime", departure?.takeIf { arrival != null }?.toString().orEmpty())
            normalizedInfo.put("arrivalTime", arrival?.toString().orEmpty())
            if (minutes != null) normalizedInfo.put("totalDurationMinutes", minutes)
            if (preciseSeconds == null) normalizedInfo.remove("totalDurationSeconds")
            // Vehicle clocks describe the old service. Keep stop IDs and route geometry for a new lookup.
            normalizedInfo.path("steps").filterIsInstance<ObjectNode>().forEach { it.remove("scheduledRide") }
            normalized.path("transitLegs").filterIsInstance<ObjectNode>().forEach {
                it.remove(listOf("startDateTime", "endDateTime"))
            }
            return normalized
        }

        private fun originalOdsayBusEstimateForChangedTarget(route: JsonNode, target: Instant): Long? = runCatching {
            if (!isOdsayBusEstimate(route)) return@runCatching null
            val originalTarget = Instant.parse(route.path("routeInfo").path("busPreviewObservation").path("targetArrivalAt").asText())
            if (originalTarget == target) return@runCatching null
            val minutes = route.path("minutes").takeIf { it.isIntegralNumber }?.asLong()
                ?.takeIf { it in 1..360 } ?: return@runCatching null
            route.path("totalDurationSeconds").takeIf { it.isIntegralNumber }?.asLong()
                ?.takeIf { it > 0 && roundedMinutes(it) == minutes } ?: minutes * 60
        }.getOrNull()

        private fun isOdsayBusEstimate(route: JsonNode): Boolean {
            val info = route.path("routeInfo")
            if (route.path("provider").asText() != "odsay" || info.path("provider").asText() != "odsay" ||
                info.path("timeBasis").asText() != "estimated" ||
                info.path("providerTimeBasis").asText() != "route_estimate") return false
            val legs = route.path("transitLegs")
            return legs.isArray && legs.count { it.path("kind").asText() == "BUS" } in 1..2 &&
                legs.all { it.path("kind").asText() in setOf("BUS", "WALK") }
        }

        private fun extractOdsayBusPreview(route: JsonNode): SavedOdsayBusPreview? = runCatching {
            if (!isOdsayBusEstimate(route)) return@runCatching null
            extractOdsayEstimateSeconds(route) ?: return@runCatching null
            val info = route.path("routeInfo")
            val observation = info.path("busPreviewObservation")
            val target = Instant.parse(observation.path("targetArrivalAt").asText())
            val departure = Instant.parse(info.path("departureTime").asText())
            val arrival = Instant.parse(info.path("arrivalTime").asText())
            val observedAt = Instant.parse(observation.path("sourceUpdatedAt").asText())
            if (observedAt > departure || arrival > target) return@runCatching null
            SavedOdsayBusPreview(departure, target, info.path("totalDurationMinutes").asInt())
        }.getOrNull()

        /** Preserves an internally consistent route estimate without granting timetable authority. */
        private fun extractOdsayEstimateSeconds(root: JsonNode): Long? = runCatching {
            val info = root.path("routeInfo").takeUnless { it.isMissingNode } ?: root
            if ((root.has("provider") && root.path("provider").asText() != "odsay") ||
                info.path("provider").asText() != "odsay" || info.path("timeBasis").asText() != "estimated" ||
                info.path("providerTimeBasis").asText() != "route_estimate") return@runCatching null
            val seconds = info.path("totalDurationSeconds").takeIf { it.isIntegralNumber && it.canConvertToLong() }
                ?.asLong()?.takeIf { it > 0 } ?: return@runCatching null
            val minutes = info.path("totalDurationMinutes").takeIf { it.isIntegralNumber && it.canConvertToInt() }
                ?.asLong() ?: return@runCatching null
            val departure = Instant.parse(info.path("departureTime").asText())
            val arrival = Instant.parse(info.path("arrivalTime").asText())
            seconds.takeIf { roundedMinutes(it) == minutes && Duration.between(departure, arrival) == Duration.ofSeconds(it) }
        }.getOrNull()

        private fun roundedMinutes(seconds: Long): Long = (seconds - 1) / 60 + 1

        /**
         * The preview duration includes the actual waits and is rounded up only for display.
         * Keep the second-precise departure only when the stored duration, dates and ride clocks agree.
         */
        private fun extractOdsayTimetable(root: JsonNode): SavedOdsayTimetable? = runCatching {
            val info = root.path("routeInfo")
            if (root.path("provider").asText() != "odsay" || info.path("provider").asText() != "odsay" ||
                info.path("timeBasis").asText() != "provider_schedule" ||
                info.path("providerTimeBasis").asText() != "timetable") return@runCatching null
            val departure = Instant.parse(info.path("departureTime").asText())
            val arrival = Instant.parse(info.path("arrivalTime").asText())
            if (departure.nano != 0 || arrival.nano != 0 || arrival <= departure) return@runCatching null
            val duration = Duration.between(departure, arrival).seconds
            val seconds = info.path("totalDurationSeconds")
            val minutes = info.path("totalDurationMinutes")
            if (!seconds.isIntegralNumber || seconds.asLong(-1) != duration ||
                !minutes.isIntegralNumber || !minutes.canConvertToInt() ||
                (duration - 1) / 60 + 1 != minutes.asLong()) return@runCatching null
            val steps = info.path("steps")
            if (!steps.isArray || steps.any { it.path("type").asText() !in setOf("ORIGIN", "DESTINATION", "WALK", "SUBWAY") })
                return@runCatching null
            val rides = steps.filter { it.path("type").asText() == "SUBWAY" }
            if (rides.size !in 1..3) return@runCatching null
            val serviceDate = departure.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString()
            var previous = departure
            var walkBeforeBoarding = 0L
            for (step in steps) {
                if (step.path("type").asText() == "WALK" && step.hasNonNull("durationSeconds")) {
                    val secondsNode = step.path("durationSeconds")
                    if (!secondsNode.isIntegralNumber || !secondsNode.canConvertToLong() ||
                        secondsNode.asLong() !in 0..(duration - walkBeforeBoarding)) return@runCatching null
                    walkBeforeBoarding += secondsNode.asLong()
                    continue
                }
                if (step.path("type").asText() != "SUBWAY") continue
                val ride = step.path("scheduledRide")
                if (ride.path("provider").asText() !in setOf("seoul-timetable", "seoul-dated-path") ||
                    ride.path("serviceDate").asText() != serviceDate) return@runCatching null
                val boarding = Instant.parse(ride.path("boardingAt").asText())
                val alighting = Instant.parse(ride.path("arrivalAt").asText())
                if (boarding < previous || alighting <= boarding || alighting > arrival) return@runCatching null
                if (Duration.between(previous, boarding).seconds < walkBeforeBoarding) return@runCatching null
                previous = alighting
                walkBeforeBoarding = 0
            }
            val egress = steps.toList().drop(steps.indexOfLast { it.path("type").asText() == "SUBWAY" } + 1)
                .filter { it.path("type").asText() == "WALK" }
            // Older saved routes can omit walking seconds. If every last-mile segment has them,
            // the destination time must agree with the last train and the complete walking interval.
            if (egress.isNotEmpty() && egress.all { it.hasNonNull("durationSeconds") }) {
                var remaining = Duration.between(previous, arrival).seconds
                for (walk in egress) {
                    val walkingSeconds = walk.path("durationSeconds")
                    if (!walkingSeconds.isIntegralNumber || !walkingSeconds.canConvertToLong() ||
                        walkingSeconds.asLong() !in 0..remaining) return@runCatching null
                    remaining -= walkingSeconds.asLong()
                }
                if (remaining != 0L) return@runCatching null
            }
            SavedOdsayTimetable(departure, arrival, minutes.asInt())
        }.getOrNull()

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

data class SavedOdsayTimetable(val departureAt: Instant, val arrivalAt: Instant, val travelMinutes: Int) {
    internal fun matches(target: Instant, saved: Instant?, minutes: Int?): Boolean =
        saved == departureAt && minutes == travelMinutes && arrivalAt <= target

    /** Older rows can omit the top-level departure while retaining the verified timetable. */
    internal fun departureIfCompatible(target: Instant, saved: Instant?, minutes: Int?): Instant? =
        departureAt.takeIf { matches(target, saved ?: departureAt, minutes) }
}

data class SavedOdsayBusPreview(val departureAt: Instant, val targetArrivalAt: Instant, val travelMinutes: Int) {
    internal fun matches(target: Instant, saved: Instant?, minutes: Int?): Boolean =
        target == targetArrivalAt && (saved == null || saved == departureAt) &&
            (minutes == null || minutes == travelMinutes)
}

data class RouteDisplaySegment(
    val kind: String,
    val label: String,
    val colorHex: String,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val legIndex: Int? = null,
)
