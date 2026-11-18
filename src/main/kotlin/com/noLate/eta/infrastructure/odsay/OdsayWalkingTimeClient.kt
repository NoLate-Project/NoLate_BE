package com.noLate.eta.infrastructure.odsay

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.eta.application.port.TransitWalkingTimeProvider
import com.noLate.eta.application.port.VerifiedWalkingTime
import com.noLate.eta.application.port.VerifiedAccessWalk
import com.noLate.eta.application.port.BoundaryWalkRefinement
import com.noLate.eta.application.port.WalkingPathRefinement
import com.noLate.eta.domain.*
import com.noLate.eta.resilience.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.ResourceAccessException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/** 고정 분류만 기록한다. 공급자 메시지·요청 URL·인증키는 기록하지 않는다. */
internal enum class WalkingRefinementFailure {
    HTTP_AUTH, HTTP_QUOTA, HTTP_SERVER, HTTP_CLIENT, PROVIDER_APPLICATION_ERROR,
    EMPTY_RESPONSE, INVALID_RESPONSE, PATH_COUNT_MISMATCH, NO_PATH,
    ENDPOINT_MISMATCH, BOUNDARY_ENDPOINT_GAP, DISTANCE_MISMATCH, DURATION_MISMATCH,
    LINK_COUNT_MISMATCH, LINK_DISTANCE_MISMATCH, LINK_COORDINATES_MISMATCH,
    TIMEOUT, TRANSPORT, DEADLINE, CIRCUIT_OPEN, BULKHEAD, INTERRUPTED, UNEXPECTED,
}

private class WalkingRefinementRejected(
    val reason: WalkingRefinementFailure,
    val providerCode: String? = null,
) : RuntimeException(reason.name)

/** optional refinement: whole-path proof only, bounded cache/calls, no retry or stale-success reuse. */
@Component
@ConditionalOnProperty(prefix = "eta.transit.odsay", name = ["enabled"], havingValue = "true")
class OdsayWalkingTimeClient(
    @Value("\${eta.transit.odsay.api-key}") private val apiKey: String,
    @Value("\${eta.transit.odsay.base-url:https://api.odsay.com/v1/api}") baseUrl: String,
    @Value("\${eta.transit.odsay.allow-insecure-http:false}") allowInsecureHttp: Boolean = false,
    @Value("\${eta.transit.odsay.allow-custom-endpoint:false}") allowCustomEndpoint: Boolean = false,
    @Value("\${eta.transit.odsay.walking-refinement-enabled:true}") private val enabled: Boolean = true,
    private val clock: Clock = Clock.systemUTC(),
    private val calculationDeadline: EtaCalculationDeadline = EtaCalculationDeadline(),
    requestFactory: ClientHttpRequestFactory = EtaDeadlineAwareClientHttpRequestFactory(
        calculationDeadline, Duration.ofSeconds(2), Duration.ofSeconds(2)),
    private val providerGuard: EtaProviderGuard = EtaProviderGuard(StaticEtaProviderResiliencePolicyResolver(), calculationDeadline),
) : TransitWalkingTimeProvider {
    override val providerId = "odsay"
    init {
        require(apiKey.isNotBlank())
        validateEtaProviderEndpoint("odsay-walking", baseUrl, true, allowInsecureHttp,
            setOf("api.odsay.com"), allowCustomEndpoint)
    }
    private val client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build()
    private val cache = object : LinkedHashMap<TransitWalkingGeometry, VerifiedWalkingTime>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TransitWalkingGeometry, VerifiedWalkingTime>?) = size > 256
    }
    private val accessCache = object : LinkedHashMap<TransitWalkingQueryEndpoints, VerifiedAccessWalk>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TransitWalkingQueryEndpoints, VerifiedAccessWalk>?) = size > 256
    }
    private var blockedUntil = Instant.MIN
    private var httpCalls = 0L
    private var cacheHits = 0L
    private var unavailableCalls = 0L
    private val failureCounts = java.util.EnumMap<WalkingRefinementFailure, Long>(WalkingRefinementFailure::class.java)
    private var lastFailure: WalkingRefinementFailure? = null
    private var lastFailureAt: Instant? = null
    private var lastHttpStatus: Int? = null
    private var lastProviderCode: String? = null
    private val logger = LoggerFactory.getLogger(javaClass)
    private val attemptedScopes = java.util.WeakHashMap<EtaSoftDeadline, Boolean>()
    private var unscopedNextCallAt = Instant.MIN
    @Synchronized
    fun diagnostics(): Map<String, Any> = mapOf("provider" to "odsay-walking", "httpCalls" to httpCalls,
        "cacheHits" to cacheHits, "unavailableCalls" to unavailableCalls, "cacheEntries" to (cache.size + accessCache.size),
        "failureCounts" to failureCounts.mapKeys { it.key.name },
        "lastFailure" to mapOf("reason" to lastFailure?.name, "observedAt" to lastFailureAt,
            "httpStatus" to lastHttpStatus, "providerCode" to lastProviderCode))

    @Synchronized
    override fun refine(geometry: TransitWalkingGeometry, originalMinutes: Int): VerifiedWalkingTime? {
        if (!enabled || !eligible(geometry, originalMinutes)) return null
        return cached(geometry, originalMinutes) ?: requestPaths(listOf(requireNotNull(geometry.providerQueryEndpoints))) { paths ->
            VerifiedWalkingTime(validatePath(paths.single(), geometry, originalMinutes), clock.instant()).also { cache[geometry] = it }
        }
    }

    @Synchronized
    override fun refineBoundaries(
        access: TransitWalkingQueryEndpoints?, exit: TransitWalkingQueryEndpoints?,
        unchanged: List<WalkingPathRefinement>,
    ): BoundaryWalkRefinement {
        if (!enabled) return BoundaryWalkRefinement(null, null, emptyMap())
        val boundaryPins = listOfNotNull(access, exit).distinct()
        fun lookup(pin: TransitWalkingQueryEndpoints?): VerifiedAccessWalk? = pin?.let { accessCache[it]?.takeIf { v -> fresh(v.fetchedAt) } }
        val missingBoundaries = boundaryPins.filter { pin ->
            val cached = lookup(pin)
            if (cached == null) accessCache.remove(pin) else cacheHits++
            cached == null
        }
        val strictResult = mutableMapOf<WalkingPathRefinement, VerifiedWalkingTime>()
        val missingStrict = unchanged.distinct().filter { eligible(it.geometry, it.originalMinutes) }.mapNotNull { path ->
            val cached = cached(path.geometry, path.originalMinutes)
            if (cached == null) path to requireNotNull(path.geometry.providerQueryEndpoints)
            else { strictResult[path] = cached; null }
        }.toMap()
        // Multiple coordinate sets share one physical request and the same evaluation budget.
        val endpoints = (missingBoundaries + missingStrict.values).distinct().take(3)
        if (endpoints.isNotEmpty()) requestPaths<Unit>(endpoints) { paths ->
            val byPin = endpoints.zip(paths).toMap()
            var rejection: WalkingRefinementRejected? = null
            for (pin in missingBoundaries) {
                try {
                    validateAccessPath(byPin.getValue(pin), pin)?.let { accessCache[pin] = it }
                } catch (failure: WalkingRefinementRejected) {
                    if (rejection == null) rejection = failure
                }
            }
            for ((path, pin) in missingStrict) {
                val response = byPin[pin] ?: continue
                try {
                    val seconds = validatePath(response, path.geometry, path.originalMinutes)
                    val verified = VerifiedWalkingTime(seconds, clock.instant())
                    cache[path.geometry] = verified
                    strictResult[path] = verified
                } catch (failure: WalkingRefinementRejected) {
                    if (rejection == null) rejection = failure
                }
            }
            if (rejection != null) throw rejection
        }
        return BoundaryWalkRefinement(lookup(access), lookup(exit), strictResult)
    }

    private fun eligible(geometry: TransitWalkingGeometry, minutes: Int): Boolean =
        minutes in 1..30 && geometry.providerQueryEndpoints != null &&
            geometry.pathEvidence?.provider == providerId && geometry.pathEvidence.status == TransitWalkingPathStatus.PROVIDED

    private fun fresh(fetchedAt: Instant): Boolean {
        val now = clock.instant()
        return fetchedAt <= now && fetchedAt.plus(Duration.ofHours(24)) > now
    }

    private fun cached(geometry: TransitWalkingGeometry, minutes: Int): VerifiedWalkingTime? {
        cache[geometry]?.let { cached ->
            if (fresh(cached.fetchedAt) && (cached.seconds + 59) / 60 == minutes.toLong()) {
                cacheHits++
                return cached
            }
            cache.remove(geometry)
        }
        return null
    }

    private fun <T> requestPaths(endpoints: List<TransitWalkingQueryEndpoints>, decode: (List<JsonNode>) -> T): T? {
        val now = clock.instant()
        if (now < blockedUntil) return null
        // 한 ETA 평가에서 새 도보 HTTP는 최대 1회. 다른 후보는 캐시 또는 기존 분 값을 쓴다.
        val scope = calculationDeadline.current()
        if (scope != null) {
            if (attemptedScopes.put(scope, true) != null) return null
        } else {
            if (now < unscopedNextCallAt) return null
            unscopedNextCallAt = now.plusSeconds(1)
        }
        var responseCode: String? = null
        val paths = try {
            providerGuard.execute("odsay-walking") {
                val loc = endpoints.joinToString("@") { "${it.start.lng},${it.start.lat},${it.end.lng},${it.end.lat}" }
                httpCalls++
                val response = client.get().uri { builder -> builder.path("/searchWalkPathV2")
                    .queryParam("apiKey", "{key}").queryParam("opt", "reco").queryParam("loc", loc)
                    .queryParam("lang", 0).queryParam("output", "json").build(apiKey) }
                    .retrieve().body(JsonNode::class.java)
                if (response == null) throw WalkingRefinementRejected(WalkingRefinementFailure.EMPTY_RESPONSE)
                // 숫자 오류 코드만 허용한다. 원문 메시지를 보고 인증/한도를 추측하지 않는다.
                val error = response.path("error").let { if (it.isArray) it.firstOrNull() ?: it else it }
                responseCode = safeProviderCode(error.path("code"))
                if (responseCode == null) responseCode = safeProviderCode(response.path("result").path("path").path(0).path("errorCode"))
                paths(response, endpoints.size)
            }
        } catch (failure: RuntimeException) {
            recordFailure(failure, responseCode)
            // 인증/한도/불완전 응답에 재시도하지 않는다. 오류 원문·키·URL도 밖으로 전달하지 않는다.
            blockedUntil = clock.instant().plusSeconds(300)
            return null
        }
        return try {
            decode(paths)
        } catch (failure: RuntimeException) {
            // A different geometry in an otherwise valid response only rejects this refinement.
            // Keep the per-evaluation call budget without opening a provider-wide cooldown.
            recordFailure(failure, (failure as? WalkingRefinementRejected)?.providerCode)
            null
        }
    }

    private fun recordFailure(failure: RuntimeException, providerCode: String?) {
        unavailableCalls++
        val reason = classifyFailure(failure)
        failureCounts[reason] = (failureCounts[reason] ?: 0) + 1
        lastFailure = reason
        lastFailureAt = clock.instant()
        lastHttpStatus = (failure as? RestClientResponseException)?.statusCode?.value()
        lastProviderCode = providerCode
        logger.warn("Optional walking refinement rejected: reason={}, httpStatus={}, providerCode={}",
            reason.name, lastHttpStatus, lastProviderCode)
    }

    internal fun verifiedSeconds(response: JsonNode, geometry: TransitWalkingGeometry, minutes: Int): Long? {
        return try { validatePath(paths(response, 1).single(), geometry, minutes) }
        catch (_: WalkingRefinementRejected) { null }
    }

    private fun reject(reason: WalkingRefinementFailure): Nothing = throw WalkingRefinementRejected(reason)

    private fun paths(response: JsonNode, count: Int): List<JsonNode> {
        if (!response.isObject) reject(WalkingRefinementFailure.INVALID_RESPONSE)
        if (response.hasNonNull("error")) reject(WalkingRefinementFailure.PROVIDER_APPLICATION_ERROR)
        val paths = response.path("result").path("path")
        if (!paths.isArray) reject(WalkingRefinementFailure.INVALID_RESPONSE)
        if (paths.size() != count) reject(WalkingRefinementFailure.PATH_COUNT_MISMATCH)
        return paths.toList()
    }

    private fun recommendation(path: JsonNode, endpoints: TransitWalkingQueryEndpoints): JsonNode {
        if (!path.path("hasPathResult").isBoolean) reject(WalkingRefinementFailure.INVALID_RESPONSE)
        if (!path.path("hasPathResult").booleanValue()) {
            throw WalkingRefinementRejected(WalkingRefinementFailure.NO_PATH, safeProviderCode(path.path("errorCode")))
        }
        val recommend = path.path("recommend")
        val summary = recommend.path("summary")
        if (!summary.isObject) reject(WalkingRefinementFailure.INVALID_RESPONSE)
        if (!same(point(summary.path("start")), endpoints.start) || !same(point(summary.path("end")), endpoints.end))
            reject(WalkingRefinementFailure.ENDPOINT_MISMATCH)
        return recommend
    }

    private fun integer(n: JsonNode): Long? =
        n.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()?.takeIf { it >= 0 }

    private fun point(n: JsonNode): TransitWalkingCoordinate? = runCatching {
            if (!n.path("x").isNumber || !n.path("y").isNumber) return null
            TransitWalkingCoordinate(n.path("y").doubleValue(), n.path("x").doubleValue())
    }.getOrNull()

    private fun same(a: TransitWalkingCoordinate?, b: TransitWalkingCoordinate) = a != null &&
        abs(a.lat - b.lat) <= 1e-7 && abs(a.lng - b.lng) <= 1e-7

    private fun validatePath(path: JsonNode, geometry: TransitWalkingGeometry, minutes: Int): Long {
        val ends = geometry.providerQueryEndpoints ?: reject(WalkingRefinementFailure.INVALID_RESPONSE)
        val recommend = recommendation(path, ends)
        val summary = recommend.path("summary")
        if (integer(summary.path("distance")) != geometry.distanceMeters) reject(WalkingRefinementFailure.DISTANCE_MISMATCH)
        val seconds = integer(summary.path("duration"))?.takeIf { it in 1..1800 && (it + 59) / 60 == minutes.toLong() }
            ?: reject(WalkingRefinementFailure.DURATION_MISMATCH)
        val original = geometry.pathEvidence?.steps ?: reject(WalkingRefinementFailure.INVALID_RESPONSE)
        val routes = recommend.path("routes")
        if (!routes.isArray || routes.size() != original.size || routes.size() !in 1..32) reject(WalkingRefinementFailure.LINK_COUNT_MISMATCH)
        if (original.sumOf { it.distanceMeters ?: reject(WalkingRefinementFailure.LINK_DISTANCE_MISMATCH) } != geometry.distanceMeters?.toDouble())
            reject(WalkingRefinementFailure.LINK_DISTANCE_MISMATCH)
        for ((index, route) in routes.withIndex()) {
            val step = original[index]
            if (integer(route.path("distance"))?.toDouble() != step.distanceMeters) reject(WalkingRefinementFailure.LINK_DISTANCE_MISMATCH)
            val points = route.path("coordinate")
            if (!points.isArray || points.size() != step.coordinates.size ||
                points.withIndex().any { !same(point(it.value), step.coordinates[it.index]) }) reject(WalkingRefinementFailure.LINK_COORDINATES_MISMATCH)
        }
        return seconds
    }

    private fun validateAccessPath(path: JsonNode, endpoints: TransitWalkingQueryEndpoints): VerifiedAccessWalk? {
        val route = recommendation(path, endpoints)
        val summary = route.path("summary")
        val distance = integer(summary.path("distance"))?.takeIf { it in 1..3000 } ?: return null
        val duration = integer(summary.path("duration"))?.takeIf { it in 1..1800 } ?: return null
        val links = route.path("routes")
        if (!links.isArray || links.size() !in 1..32) return null
        var totalMeters = 0L
        var totalSeconds = 0L
        var pathStart: TransitWalkingCoordinate? = null
        var previous: TransitWalkingCoordinate? = null
        for (link in links) {
            val coords = link.path("coordinate")
            if (!coords.isArray || coords.size() !in 2..128) return null
            val points = coords.map { point(it) ?: return null }
            if (previous == null) {
                pathStart = points.first()
                if (!nearPin(pathStart, endpoints.start)) reject(WalkingRefinementFailure.BOUNDARY_ENDPOINT_GAP)
            } else if (!same(points.first(), previous)) return null
            previous = points.last()
            totalMeters += integer(link.path("distance"))?.takeIf { it <= distance } ?: return null
            totalSeconds += integer(link.path("duration"))?.takeIf { it <= duration } ?: return null
        }
        if (totalMeters != distance || totalSeconds != duration) return null
        val pathEnd = requireNotNull(previous)
        // Both ODsay responses must point to the same sidewalk endpoint before using the
        // provider's whole-meter precision at the egress threshold. No connector time is inferred.
        val originalEnd = endpoints.originalEgressEndpoint
        val corroboratedEgress = originalEnd != null && same(pathEnd, originalEnd) &&
            distanceToPin(pathEnd, endpoints.end) < 5.5
        if (!nearPin(pathEnd, endpoints.end) && !corroboratedEgress) reject(WalkingRefinementFailure.BOUNDARY_ENDPOINT_GAP)
        return VerifiedAccessWalk(duration, distance, clock.instant(), requireNotNull(pathStart), pathEnd)
    }

    // V2 snaps its first and last link to its road network. A nearby snap is
    // only an entrance estimate. Do not accept routes that omit a connector.
    private fun nearPin(linkPoint: TransitWalkingCoordinate, pin: TransitWalkingCoordinate): Boolean =
        distanceToPin(linkPoint, pin) <= 5

    private fun distanceToPin(linkPoint: TransitWalkingCoordinate, pin: TransitWalkingCoordinate): Double =
        hypot(linkPoint.lat - pin.lat, (linkPoint.lng - pin.lng) * cos(Math.toRadians(pin.lat))) * 111_195

    private fun safeProviderCode(node: JsonNode): String? = node.takeIf { it.isTextual || it.isIntegralNumber }
        ?.asText()?.takeIf { it.matches(Regex("-?[0-9]{1,6}")) }

    internal fun classifyFailure(failure: RuntimeException): WalkingRefinementFailure = when (failure) {
        is WalkingRefinementRejected -> failure.reason
        is RestClientResponseException -> when (failure.statusCode.value()) {
            401, 403 -> WalkingRefinementFailure.HTTP_AUTH
            429 -> WalkingRefinementFailure.HTTP_QUOTA
            in 500..599 -> WalkingRefinementFailure.HTTP_SERVER
            else -> WalkingRefinementFailure.HTTP_CLIENT
        }
        is EtaSoftDeadlineExceededException -> WalkingRefinementFailure.DEADLINE
        is EtaProviderCircuitOpenException -> WalkingRefinementFailure.CIRCUIT_OPEN
        is EtaProviderBulkheadRejectedException -> WalkingRefinementFailure.BULKHEAD
        is EtaProviderCallInterruptedException -> WalkingRefinementFailure.INTERRUPTED
        is ResourceAccessException -> if (generateSequence<Throwable>(failure) { it.cause }.take(12).any {
                it is java.net.SocketTimeoutException || it is java.net.http.HttpTimeoutException
            }) WalkingRefinementFailure.TIMEOUT else WalkingRefinementFailure.TRANSPORT
        is org.springframework.web.client.RestClientException -> WalkingRefinementFailure.INVALID_RESPONSE
        else -> WalkingRefinementFailure.UNEXPECTED
    }
}
