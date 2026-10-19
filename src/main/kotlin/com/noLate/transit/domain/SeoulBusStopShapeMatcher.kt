package com.noLate.transit.domain

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/** Static geometry evidence only: never a passenger fix, vehicle association, or navigation permit. */
internal object SeoulBusStopShapeMatcher {
    enum class Status {
        MATCHED, INVALID_CATALOG, MISSING_STOP, UNNUMBERED_STOP, DIRECTION_MISMATCH,
        INVALID_SHAPE, STOP_OFF_SHAPE, STOP_SHAPE_AMBIGUOUS, STOP_ORDER_MISMATCH,
    }
    data class StopProjection(
        val stopSequence: Int,
        val segmentStartOrder: Int,
        val segmentFraction: Double,
        val alongShapeMeters: Double,
        val crossTrackMeters: Double,
    )
    enum class Ambiguity { OCCURRENCE_LIMIT, OCCURRENCE_SPAN, SELECTED_MULTIPLE }
    // Fixed categories and verified sequence only; no coordinates, station IDs or provider body.
    data class Result(val status: Status, val stops: List<StopProjection> = emptyList(),
        val ambiguity: Ambiguity? = null, val diagnosticStopSequence: Int? = null,
        val candidateOccurrenceCount: Int? = null)

    private const val EARTH_METERS = 6_371_000.0
    private const val MAX_CROSS_TRACK = 40.0
    private const val MAX_OCCURRENCE_SPAN = 100.0
    private val id = Regex("[1-9][0-9]{8}")

    /** Both catalogs must be bound by the caller to the same official route request, not display names.
     * Loops/parallel passages remain unavailable if more than one shape occurrence is plausible.
     * Stop sequence, shape order and road section are distinct namespaces.
     */
    fun match(routeId: String, stops: List<SeoulBusRouteStop>, shapeRouteId: String,
        shape: List<SeoulBusRoutePathPoint>, firstStopSequence: Int, lastStopSequence: Int): Result {
        fun reject(status: Status) = Result(status)
        if (!id.matches(routeId) || shapeRouteId != routeId || stops.size !in 2..999 ||
            firstStopSequence !in 1..999 || lastStopSequence !in 1..999 ||
            lastStopSequence <= firstStopSequence || lastStopSequence - firstStopSequence >= 64 ||
            stops.any { it.routeId != routeId || it.sequence !in 1..999 || !id.matches(it.stationId) } ||
            stops.zipWithNext().any { (a, b) -> b.sequence != a.sequence + 1 }) return reject(Status.INVALID_CATALOG)
        val selected = stops.filter { it.sequence in firstStopSequence..lastStopSequence }
        if (selected.size != lastStopSequence - firstStopSequence + 1) return reject(Status.MISSING_STOP)
        if (selected.any { it.publicStopNumberInvalid || it.arsId == null ||
                !it.arsId.matches(Regex("[0-9]{5}")) || it.arsId == "00000" }) return reject(Status.UNNUMBERED_STOP)
        val direction = selected.first().direction
        if (direction.isBlank() || direction.length > 128 || direction.any(Char::isISOControl) ||
            selected.any { it.direction != direction }) return reject(Status.DIRECTION_MISMATCH)
        if (shape.size !in 2..20_000 || shape.any { it.order !in 0..100_000 || !valid(it.latitude, it.longitude) } ||
            stops.any { !valid(it.latitude, it.longitude) } ||
            shape.zipWithNext().any { (a, b) -> b.order != a.order + 1 }) return reject(Status.INVALID_SHAPE)

        var length = 0.0
        val segments = shape.zipWithNext().mapNotNull { (a, b) ->
            val latitudeScale = EARTH_METERS * Math.PI / 180
            val longitudeScale = latitudeScale * cos(Math.toRadians((a.latitude + b.latitude) / 2))
            val dx = (b.longitude - a.longitude) * longitudeScale
            val dy = (b.latitude - a.latitude) * latitudeScale
            val meters = hypot(dx, dy)
            if (!meters.isFinite() || meters > 1500) return reject(Status.INVALID_SHAPE)
            if (meters <= 0.01) null else Segment(a, dx, dy, longitudeScale, latitudeScale, length, meters).also { length += meters }
        }
        if (segments.isEmpty() || length !in 20.0..100_000.0) return reject(Status.INVALID_SHAPE)
        val occurrences = mutableListOf<List<Occurrence>>()
        // An unnumbered unselected catalog row can constrain static geometry order, but is
        // never returned as a boarding mapping. Use the complete catalog, not just endpoints.
        for (stop in stops) {
            val candidates = segments.mapNotNull { segment ->
                val x = (stop.longitude - segment.start.longitude) * segment.longitudeScale
                val y = (stop.latitude - segment.start.latitude) * segment.latitudeScale
                val unboundedFraction = (x * segment.dx + y * segment.dy) / (segment.meters * segment.meters)
                val fraction = unboundedFraction.coerceIn(0.0, 1.0)
                val distance = hypot(x - fraction * segment.dx, y - fraction * segment.dy)
                if (distance > MAX_CROSS_TRACK) null else StopProjection(stop.sequence, segment.start.order,
                    fraction, segment.offset + fraction * segment.meters, distance).let { projection ->
                    // Exact preimage of the stop's tolerance disk on this line segment.
                    // Nearest projections can be >20m apart on ONE continuous passage;
                    // grouping those points by a gap invented a second occurrence.
                    val perpendicular = hypot(x - unboundedFraction * segment.dx, y - unboundedFraction * segment.dy)
                    val radiusAlong = sqrt((MAX_CROSS_TRACK * MAX_CROSS_TRACK - perpendicular * perpendicular).coerceAtLeast(0.0))
                    val centerAlong = unboundedFraction * segment.meters
                    Passage(projection, segment.offset + (centerAlong - radiusAlong).coerceIn(0.0, segment.meters),
                        segment.offset + (centerAlong + radiusAlong).coerceIn(0.0, segment.meters))
                }
            }.sortedBy { it.start }
            if (candidates.isEmpty()) return reject(Status.STOP_OFF_SHAPE)
            // Merge ONLY overlapping/touching tolerance intervals. Leaving the disk and
            // returning is a distinct passage even when the outside gap is under 20m.
            val groups = mutableListOf<MutableList<Passage>>()
            var groupEnd = Double.NEGATIVE_INFINITY
            for (candidate in candidates) {
                val group = groups.lastOrNull()
                if (group == null || candidate.start > groupEnd + 0.001) {
                    groups += mutableListOf(candidate)
                    groupEnd = candidate.end
                } else { group += candidate; groupEnd = maxOf(groupEnd, candidate.end) }
            }
            if (groups.size > 32) {
                return Result(Status.STOP_SHAPE_AMBIGUOUS, ambiguity = Ambiguity.OCCURRENCE_LIMIT,
                    diagnosticStopSequence = stop.sequence, candidateOccurrenceCount = groups.size)
            }
            if (stop.sequence in firstStopSequence..lastStopSequence &&
                groups.any { group -> group.maxOf { it.end } - group.first().start > MAX_OCCURRENCE_SPAN }) {
                return Result(Status.STOP_SHAPE_AMBIGUOUS, ambiguity = Ambiguity.OCCURRENCE_SPAN,
                    diagnosticStopSequence = stop.sequence, candidateOccurrenceCount = groups.size)
            }
            occurrences += groups.map { group ->
                val projection = group.minBy { it.projection.crossTrackMeters }.projection
                // Unselected rows constrain order with their ENTIRE uncertainty interval.
                // A bend inside the tolerance disk must not be silently skipped, narrowed
                // to its nearest point, or returned as a precise boarding projection.
                // Selected occurrences retain the existing bounded nearest projection.
                if (stop.sequence in firstStopSequence..lastStopSequence)
                    Occurrence(projection, projection.alongShapeMeters, projection.alongShapeMeters)
                else Occurrence(projection, group.first().start, group.maxOf { it.end })
            }
        }
        // Earliest/latest feasible positions from BOTH ends keep complete monotone
        // assignments, not merely pairwise-compatible intervals (which can form an
        // impossible chain). A selected point must be reachable at its exact projection.
        // Ambiguity outside the selected window is irrelevant if every feasible assignment agrees
        // on each selected occurrence. Never force endpoints, skip stops or choose nearest.
        val forward = occurrences.map { DoubleArray(it.size) { Double.POSITIVE_INFINITY } }
        val backward = occurrences.map { DoubleArray(it.size) { Double.NEGATIVE_INFINITY } }
        occurrences.first().forEachIndexed { i, occurrence -> forward.first()[i] = occurrence.start }
        for (row in 1 until occurrences.size) {
            val previousEarliest = forward[row - 1].min()
            for ((index, current) in occurrences[row].withIndex()) {
                val earliest = maxOf(current.start, previousEarliest + 5)
                if (earliest <= current.end) forward[row][index] = earliest
            }
        }
        if (forward.last().none { it.isFinite() }) return reject(Status.STOP_ORDER_MISMATCH)
        occurrences.last().forEachIndexed { i, occurrence -> backward.last()[i] = occurrence.end }
        for (row in occurrences.lastIndex - 1 downTo 0) {
            val nextLatest = backward[row + 1].max()
            for ((index, current) in occurrences[row].withIndex()) {
                val latest = minOf(current.end, nextLatest - 5)
                if (latest >= current.start) backward[row][index] = latest
            }
        }
        val matched = mutableListOf<StopProjection>()
        for (row in occurrences.indices.filter { stops[it].sequence in firstStopSequence..lastStopSequence }) {
            val feasible = occurrences[row].indices.filter { forward[row][it] <= backward[row][it] }
            if (feasible.size != 1) return Result(Status.STOP_SHAPE_AMBIGUOUS,
                ambiguity = Ambiguity.SELECTED_MULTIPLE, diagnosticStopSequence = stops[row].sequence,
                candidateOccurrenceCount = feasible.size)
            matched += occurrences[row][feasible.single()].projection
        }
        return Result(Status.MATCHED, matched)
    }

    private fun valid(latitude: Double, longitude: Double) = latitude.isFinite() && longitude.isFinite() &&
        latitude in 33.0..39.5 && longitude in 124.0..132.0
    private data class Segment(val start: SeoulBusRoutePathPoint, val dx: Double, val dy: Double,
        val longitudeScale: Double, val latitudeScale: Double, val offset: Double, val meters: Double)
    private data class Passage(val projection: StopProjection, val start: Double, val end: Double)
    private data class Occurrence(val projection: StopProjection, val start: Double, val end: Double)
}
