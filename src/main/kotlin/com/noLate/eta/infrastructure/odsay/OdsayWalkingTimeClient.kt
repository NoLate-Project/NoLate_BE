package com.noLate.eta.infrastructure.odsay

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.eta.application.port.TransitWalkingTimeProvider
import com.noLate.eta.application.port.VerifiedWalkingTime
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

/** 고정 분류만 기록한다. 공급자 메시지·요청 URL·인증키는 기록하지 않는다. */
internal enum class WalkingRefinementFailure {
    HTTP_AUTH, HTTP_QUOTA, HTTP_SERVER, HTTP_CLIENT, PROVIDER_APPLICATION_ERROR,
    EMPTY_RESPONSE, INVALID_RESPONSE, PATH_COUNT_MISMATCH, NO_PATH,
    ENDPOINT_MISMATCH, DISTANCE_MISMATCH, DURATION_MISMATCH,
    LINK_COUNT_MISMATCH, LINK_DISTANCE_MISMATCH, LINK_COORDINATES_MISMATCH,
    TIMEOUT, TRANSPORT, DEADLINE, CIRCUIT_OPEN, BULKHEAD, INTERRUPTED, UNEXPECTED,
}

private class WalkingRefinementRejected(val reason: WalkingRefinementFailure) : RuntimeException(reason.name)

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
        "cacheHits" to cacheHits, "unavailableCalls" to unavailableCalls, "cacheEntries" to cache.size,
        "failureCounts" to failureCounts.mapKeys { it.key.name },
        "lastFailure" to mapOf("reason" to lastFailure?.name, "observedAt" to lastFailureAt,
            "httpStatus" to lastHttpStatus, "providerCode" to lastProviderCode))

    @Synchronized
    override fun refine(geometry: TransitWalkingGeometry, originalMinutes: Int): VerifiedWalkingTime? {
        if (!enabled || originalMinutes !in 1..30 || geometry.providerQueryEndpoints == null ||
            geometry.pathEvidence?.provider != providerId || geometry.pathEvidence.status != TransitWalkingPathStatus.PROVIDED) return null
        val now = clock.instant()
        cache[geometry]?.let { cached ->
            if (cached.fetchedAt <= now && cached.fetchedAt.plus(Duration.ofHours(24)) > now &&
                (cached.seconds + 59) / 60 == originalMinutes.toLong()) {
                cacheHits++
                return cached
            }
            cache.remove(geometry)
        }
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
        return try {
            providerGuard.execute("odsay-walking") {
                val endpoints = requireNotNull(geometry.providerQueryEndpoints)
                val loc = "${endpoints.start.lng},${endpoints.start.lat},${endpoints.end.lng},${endpoints.end.lat}"
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
                val seconds = validateSeconds(response, geometry, originalMinutes)
                VerifiedWalkingTime(seconds, clock.instant()).also { cache[geometry] = it }
            }
        } catch (failure: RuntimeException) {
            unavailableCalls++
            val reason = classifyFailure(failure)
            failureCounts[reason] = (failureCounts[reason] ?: 0) + 1
            lastFailure = reason
            lastFailureAt = clock.instant()
            lastHttpStatus = (failure as? RestClientResponseException)?.statusCode?.value()
            lastProviderCode = responseCode
            logger.warn("Optional walking refinement rejected: reason={}, httpStatus={}, providerCode={}",
                reason.name, lastHttpStatus, lastProviderCode)
            // 인증/한도/불완전 응답에 재시도하지 않는다. 오류 원문·키·URL도 밖으로 전달하지 않는다.
            blockedUntil = clock.instant().plusSeconds(300)
            null
        }
    }

    internal fun verifiedSeconds(response: JsonNode, geometry: TransitWalkingGeometry, minutes: Int): Long? {
        return try { validateSeconds(response, geometry, minutes) } catch (_: WalkingRefinementRejected) { null }
    }

    private fun validateSeconds(response: JsonNode, geometry: TransitWalkingGeometry, minutes: Int): Long {
        fun reject(reason: WalkingRefinementFailure): Nothing = throw WalkingRefinementRejected(reason)
        if (!response.isObject) reject(WalkingRefinementFailure.INVALID_RESPONSE)
        if (response.hasNonNull("error")) reject(WalkingRefinementFailure.PROVIDER_APPLICATION_ERROR)
        val paths = response.path("result").path("path")
        if (!paths.isArray) reject(WalkingRefinementFailure.INVALID_RESPONSE)
        if (paths.size() != 1) reject(WalkingRefinementFailure.PATH_COUNT_MISMATCH)
        val path = paths.single()
        if (!path.path("hasPathResult").isBoolean) reject(WalkingRefinementFailure.INVALID_RESPONSE)
        if (!path.path("hasPathResult").booleanValue()) reject(WalkingRefinementFailure.NO_PATH)
        val recommend = path.path("recommend")
        val summary = recommend.path("summary")
        if (!summary.isObject) reject(WalkingRefinementFailure.INVALID_RESPONSE)
        fun integer(n: JsonNode): Long? = n.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()?.takeIf { it >= 0 }
        fun point(n: JsonNode): TransitWalkingCoordinate? = runCatching {
            if (!n.path("x").isNumber || !n.path("y").isNumber) return null
            TransitWalkingCoordinate(n.path("y").doubleValue(), n.path("x").doubleValue())
        }.getOrNull()
        fun same(a: TransitWalkingCoordinate?, b: TransitWalkingCoordinate) = a != null &&
            abs(a.lat - b.lat) <= 1e-7 && abs(a.lng - b.lng) <= 1e-7
        val ends = geometry.providerQueryEndpoints ?: reject(WalkingRefinementFailure.INVALID_RESPONSE)
        if (!same(point(summary.path("start")), ends.start) || !same(point(summary.path("end")), ends.end))
            reject(WalkingRefinementFailure.ENDPOINT_MISMATCH)
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
