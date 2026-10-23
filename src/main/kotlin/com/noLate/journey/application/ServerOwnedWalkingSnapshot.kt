package com.noLate.journey.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import java.time.Clock
import java.time.Instant
import kotlin.math.*

/** A server adapter, never a client-supplied geometry/provenance DTO. The adapter must bound
 * HTTP time/body size, prohibit redirects, and return the untouched WGS84GEO sort=index body.
 * No production adapter is enabled by defining this port. */
fun interface ServerPedestrianProvider {
    fun fetch(request: ServerWalkingRequest): String
}
data class ServerWalkingCoordinate(val lat: Double, val lng: Double)
data class ServerWalkingRequest(val origin: ServerWalkingCoordinate, val destination: ServerWalkingCoordinate)
class WalkingContractRejected(val reason: String) : RuntimeException(reason)

/** Internal server object, NOT a signed receipt or a deserializable client admission token.
 * ODsay remains the transit owner; TMAP walks stay separate. Nothing rewrites saved geometry,
 * flips geometryInputComplete, grants GPS/vehicle evidence, or starts a native session. */
class ServerOwnedWalkingSnapshot internal constructor(
    internal val memberId: Long, internal val scheduleId: Long, internal val loginGeneration: Long,
    internal val revision: Long, internal val fingerprint: String, internal val issuedAt: Instant,
    internal val expiresAt: Instant, internal val walkingPaths: Map<Int, List<ServerWalkingCoordinate>>,
) {
    fun validatedWalks(plan: ScheduleTravelPlanDto, actor: Long, login: Long, routeRevision: Long,
        now: Instant, mapper: ObjectMapper): Map<Int, List<ServerWalkingCoordinate>> {
        if (actor != memberId || plan.memberId != memberId || plan.scheduleId != scheduleId || login != loginGeneration)
            throw WalkingContractRejected("OWNER_CHANGED")
        if (routeRevision != revision) throw WalkingContractRejected("REVISION_CHANGED")
        if (now.isBefore(issuedAt) || !now.isBefore(expiresAt)) throw WalkingContractRejected("EXPIRED")
        val latest = runCatching { JourneyRouteSnapshot.from(plan, mapper).fingerprint }.getOrNull()
        if (latest != fingerprint) throw WalkingContractRejected("SAVED_ROUTE_CHANGED")
        // No caller can mutate stored collections and then revalidate an altered path.
        return walkingPaths.mapValues { (_, points) -> points.toList() }
    }
}

/** First mixed-provider slice: bind BOTH walks of one owned WALK-BUS-WALK saved ODsay route.
 * Caller must first authenticate the current login and load the owned plan transactionally.
 * Revalidate immediately before consumption against the freshly loaded plan/revision/login.
 * Transit binding/shape/vehicle + native mixed-provider admission are independent remaining gates. */
class ServerOwnedWalkingSnapshotFactory(
    private val mapper: ObjectMapper, private val provider: ServerPedestrianProvider,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun issue(plan: ScheduleTravelPlanDto, actor: Long, login: Long, revision: Long): ServerOwnedWalkingSnapshot {
        if (actor <= 0 || actor != plan.memberId || plan.scheduleId <= 0 || login !in 0..9_007_199_254_740_991L) reject("OWNER_CHANGED")
        if (revision !in 1..9_007_199_254_740_991L) reject("REVISION_CHANGED")
        val snapshot = runCatching { JourneyRouteSnapshot.from(plan, mapper) }.getOrElse { reject("UNSUPPORTED_SAVED_ROUTE") }
        // Work only from an immutable serialization captured before the provider calls.
        val raw = mapper.readTree(snapshot.json)
        val containers = listOf(raw.path("transitLegs"), raw.path("routeInfo").path("transitLegs"))
            .filter { it.isArray && !it.isEmpty }
        if (containers.size != 1) reject("UNSUPPORTED_SAVED_ROUTE")
        val route = if (raw.path("transitLegs").isArray) raw else raw.path("routeInfo")
        if (raw.path("mode").asText() != "TRANSIT" || route.path("provider").asText() != "odsay" ||
            route.path("source").asText() != "api" || route.path("routePlausibility").asText() != "normal" ||
            route.path("transitServiceState").asText() !in setOf("operating", "unknown") ||
            route.path("routeReliability").asText() !in setOf("live_provider", "provider_estimate")) reject("UNSUPPORTED_SAVED_ROUTE")
        val legs = containers.single()
        if (legs.size() !in 3..31 || legs.size() % 2 != 1 || legs.withIndex().any { (i,leg) ->
            if(i % 2 == 0) leg.path("kind").asText() != "WALK" else leg.path("kind").asText() !in setOf("BUS","SUBWAY") })
            reject("UNSUPPORTED_SAVED_ROUTE")
        val starts = legs.map { coord(it.path("startCoord")) }
        val ends = legs.map { coord(it.path("endCoord")) }
        val origin = plan.origin?.let { coord(mapper.valueToTree(it)) } ?: reject("ENDPOINT_MISMATCH")
        val destination = plan.destination?.let { coord(mapper.valueToTree(it)) } ?: reject("ENDPOINT_MISMATCH")
        // ODsay stores an interpolated walk endpoint a few centimetres away from
        // the same ride/origin tuple. This one-metre rounding allowance never
        // inserts a connector or substitutes a nearby station for the saved leg.
        fun samePoint(a: ServerWalkingCoordinate, b: ServerWalkingCoordinate) = meters(a, b) <= 1.0
        if (!samePoint(starts[0], origin) || !samePoint(ends.last(), destination) ||
            (0 until legs.size()-1).any { !samePoint(ends[it], starts[it+1]) })
            reject("ENDPOINT_MISMATCH")
        val issued = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val paths = linkedMapOf<Int, List<ServerWalkingCoordinate>>()
        for (index in 0 until legs.size() step 2) {
            val request = ServerWalkingRequest(starts[index], ends[index])
            if (request.origin == request.destination) reject("ENDPOINT_MISMATCH")
            val body = try { provider.fetch(request) }
                catch (failure: WalkingContractRejected) { throw failure }
                catch (_: Exception) { reject("PROVIDER_UNAVAILABLE") }
            paths[index] = parse(body, request)
            if (paths.values.sumOf { it.size } > 12_000) reject("INPUT_LIMIT")
            val now = clock.instant()
            if (now.isBefore(issued) || !now.isBefore(issued.plusSeconds(30))) reject("FETCH_DEADLINE")
        }
        // Also detects mutation of the saved JsonNode by a concurrent adapter/caller.
        val latest = runCatching { JourneyRouteSnapshot.from(plan, mapper).fingerprint }.getOrNull()
        if (latest != snapshot.fingerprint) reject("SAVED_ROUTE_CHANGED")
        return ServerOwnedWalkingSnapshot(actor, plan.scheduleId, login, revision, snapshot.fingerprint,
            issued, issued.plusSeconds(300), paths.mapValues { it.value.toList() })
    }

    private fun parse(body: String, request: ServerWalkingRequest): List<ServerWalkingCoordinate> {
        if (body.toByteArray(Charsets.UTF_8).size !in 1..1_048_576) reject("INPUT_LIMIT")
        val root: JsonNode = try {
            mapper.factory.createParser(body).use { parser ->
                parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                val node = mapper.readTree<JsonNode>(parser)
                if (parser.nextToken() != null) reject("INVALID_RESPONSE")
                node ?: reject("INVALID_RESPONSE")
            }
        } catch (failure: WalkingContractRejected) { throw failure }
        catch (_: Exception) { reject("INVALID_RESPONSE") }
        val features = root.path("features")
        if (root.path("type").asText() != "FeatureCollection" || root.has("error") || !features.isArray || features.isEmpty)
            reject("INVALID_RESPONSE")
        if (features.size() > 4096) reject("INPUT_LIMIT")
        val points = mutableListOf<ServerWalkingCoordinate>()
        var previous = -1L; var count = 0; var length = 0.0
        for (feature in features) {
            val geometry = feature.path("geometry"); val order = feature.path("properties").path("index")
            if (feature.path("type").asText() != "Feature" || !order.isIntegralNumber ||
                !order.canConvertToLong() || order.asLong() !in 0..9_007_199_254_740_991L || order.asLong() <= previous)
                reject("FEATURE_ORDER")
            previous = order.asLong()
            val values = geometry.path("coordinates")
            if (geometry.path("type").asText() == "Point") { pair(values); continue }
            if (geometry.path("type").asText() != "LineString" || !values.isArray || values.size() < 2) reject("INVALID_RESPONSE")
            count += values.size()
            if (count > 2000) reject("INPUT_LIMIT")
            for ((i, value) in values.withIndex()) {
                val point = pair(value); val tail = points.lastOrNull()
                if (i == 0 && tail != null && point != tail) reject("DISCONNECTED_LINES")
                if (point == tail) continue
                if (tail != null) {
                    val distance = meters(tail, point)
                    if (distance > 150) reject("SEGMENT_TOO_LONG")
                    length += distance
                }
                points.add(point)
            }
        }
        if (points.size < 2 || length <= 0) reject("EMPTY_PATH")
        if (meters(points.first(), request.origin) > 30 || meters(points.last(), request.destination) > 30)
            reject("ENDPOINT_MISMATCH")
        return points.toList()
    }
    private fun pair(value: JsonNode): ServerWalkingCoordinate {
        if (!value.isArray || value.size() != 2) reject("INVALID_COORDINATE")
        return coordinate(value[1], value[0])
    }
    private fun coord(value: JsonNode) = coordinate(value.path("lat"), value.path("lng"))
    private fun coordinate(lat: JsonNode, lng: JsonNode): ServerWalkingCoordinate {
        if (!lat.isNumber || !lng.isNumber || !lat.asDouble().isFinite() || !lng.asDouble().isFinite() ||
            abs(lat.asDouble()) > 90 || abs(lng.asDouble()) > 180) reject("INVALID_COORDINATE")
        return ServerWalkingCoordinate(lat.asDouble(), lng.asDouble())
    }
    private fun meters(a: ServerWalkingCoordinate, b: ServerWalkingCoordinate): Double {
        val rad = PI / 180
        val h = sin((b.lat - a.lat) * rad / 2).pow(2) + cos(a.lat * rad) * cos(b.lat * rad) * sin((b.lng - a.lng) * rad / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    private fun reject(reason: String): Nothing = throw WalkingContractRejected(reason)
}
