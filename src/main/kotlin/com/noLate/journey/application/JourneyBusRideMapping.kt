package com.noLate.journey.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.transit.domain.*
import java.time.Instant
import kotlin.math.*

/** Static provider crosswalk, not a boarding decision. All geometry stays out of logs. */
internal object JourneyBusRideMapping {
    data class Coordinate(val latitude: Double, val longitude: Double)
    class Verified(val routeId: String, val direction: String, val coordinates: List<Coordinate>,
        val stops: List<SeoulBusRouteStop>, val projections: List<SeoulBusStopShapeMatcher.StopProjection>,
        val shape: List<SeoulBusRoutePathPoint>, val catalog: List<SeoulBusRouteStop>,
        val departureWindowMeters: Double = 0.0, val observationEndSequence: Int = stops.last().sequence) {
        override fun toString() = "JourneyBusRideMapping.Verified(REDACTED)"
    }
    data class Result(val status: String, val verified: Verified? = null)
    private val mapper = jacksonObjectMapper()

    fun resolve(snapshot: JourneyRouteSnapshot, catalog: List<SeoulBusRouteStop>, shape: List<SeoulBusRoutePathPoint>, busLegIndex: Int = 1): Result {
        val saved = JourneyBusRouteBindingResolver().resolveRaw(snapshot, busLegIndex)
        val rawBinding = saved.evidence ?: return Result(saved.status)
        val routeId = rawBinding.routeId
        if (!routeId.matches(Regex("[1-9][0-9]{8}"))) return Result("ROUTE_NAMESPACE_UNAVAILABLE")
        val leg = mapper.readTree(snapshot.legsJson).path(busLegIndex)
        val evidence = leg.path("navigationEvidence")
        val rawStops = evidence.path("stopOccurrences")
        if (rawStops.size() !in 2..64) return Result("STOP_WINDOW_UNAVAILABLE")
        // Match the complete ordered local BIS/ARS tuple sequence, NOT ODsay's ordinal.
        val windows = catalog.windowed(rawStops.size()).filter { window ->
            window.withIndex().all { (i, row) ->
                val raw = rawStops[i]
                row.routeId == routeId && !row.publicStopNumberInvalid && row.arsId != null &&
                    row.stationId == raw.path("localStationID").asText() &&
                    row.arsId == raw.path("arsID").asText().replace("-", "") &&
                    raw.path("coord").path("lat").isNumber && raw.path("coord").path("lng").isNumber &&
                    distance(Coordinate(row.latitude, row.longitude), Coordinate(raw.path("coord").path("lat").asDouble(),
                        raw.path("coord").path("lng").asDouble())) <= 30
            }
        }
        if (windows.size != 1) return Result("STOP_OCCURRENCE_CROSSWALK_UNAVAILABLE")
        val stops = windows.single()
        val officialDirection = stops.first().direction
        if (officialDirection.isBlank() || officialDirection.length > 128 || officialDirection.any(Char::isISOControl) ||
            stops.any { it.direction != officialDirection } ||
            rawBinding.direction?.let { it != officialDirection } == true) return Result("DIRECTION_CROSSWALK_MISMATCH")
        val matched = SeoulBusStopShapeMatcher.match(routeId, catalog, routeId,
            shape, stops.first().sequence, stops.last().sequence)
        if (matched.status != SeoulBusStopShapeMatcher.Status.MATCHED) return Result(matched.status.name)
        val rawCoordinates = leg.path("pathCoords")
        if (leg.path("pathCoordsIsExact").asBoolean(false) != true ||
            leg.path("pathGeometrySource").asText() != "TRANSIT_PASS_SHAPE_LINESTRING" ||
            !evidence.path("geometryInputComplete").asBoolean(false) || !rawCoordinates.isArray ||
            rawCoordinates.size() !in 2..2000) return Result("SAVED_BUS_GEOMETRY_UNVERIFIED")
        val coordinates = rawCoordinates.map { point ->
            if (!point.path("lat").isNumber || !point.path("lng").isNumber) return Result("SAVED_BUS_GEOMETRY_UNVERIFIED")
            Coordinate(point.path("lat").asDouble(), point.path("lng").asDouble()).also {
                if (!valid(it)) return Result("SAVED_BUS_GEOMETRY_UNVERIFIED")
            }
        }
        val first = matched.stops.first().alongShapeMeters; val last = matched.stops.last().alongShapeMeters
        var previous = first - 30
        var savedLength = 0.0
        for ((index, point) in coordinates.withIndex()) {
            val projection = project(point, shape, first - 30, last + 30) ?: return Result("SAVED_BUS_SHAPE_MISMATCH")
            if (projection.second > 25 || projection.first < previous - 5) return Result("SAVED_BUS_SHAPE_MISMATCH")
            if (index == 0 && abs(projection.first - first) > 30 || index == coordinates.lastIndex && abs(projection.first - last) > 30) {
                return Result("SAVED_BUS_SHAPE_MISMATCH")
            }
            previous = projection.first
            if (index > 0) {
                val a = coordinates[index - 1]; val meters = distance(a, point)
                if (meters > 500) return Result("SAVED_BUS_SHAPE_MISMATCH")
                savedLength += meters
                // Verify interior samples too; endpoints alone cannot certify a shortcut.
                val count = ceil(meters / 20).toInt().coerceAtLeast(1)
                for (step in 1 until count) {
                    val f = step.toDouble() / count
                    val middle = Coordinate(a.latitude + (point.latitude - a.latitude) * f, a.longitude + (point.longitude - a.longitude) * f)
                    if (project(middle, shape, first - 30, last + 30)?.second?.let { it <= 25 } != true) return Result("SAVED_BUS_SHAPE_MISMATCH")
                }
            }
        }
        if (last - first < 100 || savedLength / (last - first) !in 0.8..1.2) return Result("SAVED_BUS_SHAPE_MISMATCH")
        // Optional, bounded continuation to ONE immediately following official stop. Failure
        // here removes departure evidence capability, not the already verified selected ride.
        val next = catalog.singleOrNull { it.sequence == stops.last().sequence + 1 && it.direction == stops.first().direction }
        val continuation = next?.let { SeoulBusStopShapeMatcher.match(routeId, catalog,
            routeId, shape, stops.last().sequence, it.sequence) }
        val continuationMeters = continuation?.takeIf { it.status == SeoulBusStopShapeMatcher.Status.MATCHED }
            ?.stops?.let { rows ->
                if (abs(rows.first().alongShapeMeters - last) > 0.01) null
                else (rows.last().alongShapeMeters - last).takeIf { it in 100.0..350.0 }
            } ?: 0.0
        return Result("VERIFIED", Verified(routeId, officialDirection, coordinates, stops,
            matched.stops, shape, catalog, continuationMeters,
            if (continuationMeters > 0) next!!.sequence else stops.last().sequence))
    }

    /** Preserve source clocks; section ordinals never stand in for stop ordinals.
     * Official lastStnId is labelled only "final station ID", not certified as a passed-stop
     * occurrence. Do NOT infer the current stop/direction from it. Require a unique geographic
     * passage on the COMPLETE official shape; the native reducer then requires forward motion.
     */
    fun vehicles(mapping: Verified, batch: SeoulBusVehicleObservationBatch, now: Instant): List<JourneyBusRideVehicle> {
        if (batch.status != SeoulBusVehicleObservationStatus.AVAILABLE || batch.observations.size > 100) return emptyList()
        val duplicateIds = batch.observations.groupingBy { it.vehicleId }.eachCount().filterValues { it > 1 }.keys
        return batch.observations.mapNotNull { vehicle ->
            val coordinate = vehicle.wgs84Coordinate ?: return@mapNotNull null
            if (vehicle.vehicleId in duplicateIds || !vehicle.vehicleId.matches(Regex("[1-9][0-9]{8}")) || vehicle.routeId != mapping.routeId ||
                vehicle.sourceUpdatedAt > vehicle.receivedAt || vehicle.receivedAt > now || vehicle.sourceUpdatedAt > now ||
                vehicle.validUntil > vehicle.sourceUpdatedAt.plusSeconds(15) || !vehicle.validUntil.isAfter(now)) return@mapNotNull null
            val projected = project(Coordinate(coordinate.latitude, coordinate.longitude), mapping.shape, 0.0, 100_000.0)
                ?: return@mapNotNull null
            if (projected.second > 25 || projected.first < mapping.projections.first().alongShapeMeters - 25 ||
                projected.first > mapping.projections.last().alongShapeMeters + maxOf(25.0, mapping.departureWindowMeters)) return@mapNotNull null
            val past = projected.first - mapping.projections.last().alongShapeMeters
            val after = past > 25
            if (after && mapping.departureWindowMeters <= 0) return@mapNotNull null
            JourneyBusRideVehicle(vehicle.vehicleId, vehicle.routeId, mapping.direction,
                Coordinate(coordinate.latitude, coordinate.longitude), seconds(vehicle.sourceUpdatedAt), seconds(vehicle.receivedAt), seconds(vehicle.validUntil),
                if (after) "AFTER_ALIGHTING" else "SELECTED_SEGMENT", if (after) past else null)
        }
    }
    private fun seconds(value: Instant) = value.toEpochMilli() / 1000.0
    private fun valid(p: Coordinate) = p.latitude.isFinite() && p.longitude.isFinite() && p.latitude in 33.0..39.5 && p.longitude in 124.0..132.0
    private fun distance(a: Coordinate, b: Coordinate): Double {
        val lat = Math.toRadians(b.latitude - a.latitude); val lng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lng / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    private fun project(point: Coordinate, shape: List<SeoulBusRoutePathPoint>, minimum: Double, maximum: Double): Pair<Double, Double>? {
        if (!valid(point)) return null
        var along = 0.0
        val hits = mutableListOf<Pair<Double, Double>>()
        for ((a, b) in shape.zipWithNext()) {
            val sy = 6_371_000 * Math.PI / 180; val sx = sy * cos(Math.toRadians((a.latitude + b.latitude) / 2))
            val dx = (b.longitude - a.longitude) * sx; val dy = (b.latitude - a.latitude) * sy; val length = hypot(dx, dy)
            if (length <= 0.01) continue
            val x = (point.longitude - a.longitude) * sx; val y = (point.latitude - a.latitude) * sy
            val f = ((x * dx + y * dy) / (length * length)).coerceIn(0.0, 1.0)
            val offset = along + f * length; val cross = hypot(x - f * dx, y - f * dy)
            if (offset in minimum..maximum && cross <= 25) hits += offset to cross
            along += length
        }
        // Refuse distinct parallel/loop projections instead of selecting the closest passage.
        if (hits.isEmpty() || hits.maxOf { it.first } - hits.minOf { it.first } > 60) return null
        return hits.minBy { it.second }
    }
}

/** Ephemeral private response to the native engine; never log/cache or persist vehicle identity. */
internal class JourneyBusRideVehicle(val id: String, val routeId: String, val direction: String,
    val coordinate: JourneyBusRideMapping.Coordinate, val sourceAt: Double, val receivedAt: Double, val validUntil: Double,
    val positionScope: String = "SELECTED_SEGMENT", val metersPastAlighting: Double? = null) {
    val source: String get() = "verifiedWGS84"
    override fun toString() = "JourneyBusRideVehicle(REDACTED)"
}
