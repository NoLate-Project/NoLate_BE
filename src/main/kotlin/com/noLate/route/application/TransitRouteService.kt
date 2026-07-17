package com.noLate.route.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.route.domain.TransitRouteRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Service
class TransitRouteService(
    private val providers: List<TransitRouteProvider>,
    private val clock: Clock,
) {
    private data class CacheEntry(val expiresAtMillis: Long, val response: JsonNode)
    private data class ProviderEvaluation(
        val providerId: String,
        val providerIndex: Int,
        val response: JsonNode?,
        val quality: TransitRouteResponseQuality?,
        val failure: Throwable?,
    )

    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun getRoutes(rawRequest: TransitRouteRequest): JsonNode {
        val request = rawRequest.validated()
        val cacheKey = request.cacheKey()
        val now = clock.millis()
        cache.entries.removeIf { it.value.expiresAtMillis <= now }
        cache[cacheKey]?.takeIf { it.expiresAtMillis > now }?.let {
            return it.response.deepCopy()
        }
        if (providers.isEmpty()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "대중교통 경로 공급자가 설정되지 않았습니다.")
        }

        val evaluations = providers.mapIndexed { index, provider ->
            runCatching { provider.getRoutes(request) }
                .fold(
                    onSuccess = { response ->
                        val quality = TransitRouteResponseQualityEvaluator.evaluate(response, request)
                        log.info(
                            "transit route provider evaluated provider={} candidates={} completeGeometry={} fastestMinutes={}",
                            provider.providerId(),
                            quality.usableCandidateCount,
                            quality.geometryCompleteCandidateCount,
                            quality.fastestMinutes,
                        )
                        ProviderEvaluation(provider.providerId(), index, response, quality, null)
                    },
                    onFailure = { error ->
                        log.warn("transit route provider failed provider={}", provider.providerId(), error)
                        ProviderEvaluation(provider.providerId(), index, null, null, error)
                    },
                )
        }

        val selected = evaluations
            .filter { it.response != null && it.quality?.isUsable == true }
            .minWithOrNull(
                compareByDescending<ProviderEvaluation> {
                    (it.quality?.geometryCompleteCandidateCount ?: 0) > 0
                }
                    .thenBy {
                        it.quality?.fastestGeometryCompleteMinutes ?: it.quality?.fastestMinutes ?: Int.MAX_VALUE
                    }
                    .thenByDescending { it.quality?.geometryCompleteCandidateCount ?: 0 }
                    .thenByDescending { it.quality?.usableCandidateCount ?: 0 }
                    .thenBy { it.providerIndex }
            )

        if (selected != null) {
            val response = attachRoutingDiagnostics(selected, evaluations)
            if (cache.size >= MAX_CACHE_ENTRIES && !cache.containsKey(cacheKey)) {
                cache.clear()
            }
            cache[cacheKey] = CacheEntry(now + CACHE_TTL_MILLIS, response.deepCopy())
            log.debug(
                "transit route provider selected provider={} fallbackUsed={}",
                selected.providerId,
                selected.providerIndex > 0,
            )
            return response
        }

        throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "대중교통 경로 공급자에서 유효한 경로 후보를 받지 못했습니다.",
            evaluations.mapNotNull { it.failure }.lastOrNull(),
        )
    }

    private fun attachRoutingDiagnostics(
        selected: ProviderEvaluation,
        evaluations: List<ProviderEvaluation>,
    ): JsonNode {
        val response: JsonNode = selected.response!!.deepCopy()
        if (response !is ObjectNode) return response

        val diagnostics = JsonNodeFactory.instance.objectNode().apply {
            put("provider", selected.providerId)
            put("fallbackUsed", selected.providerIndex > 0)
            put("candidateCount", selected.quality?.usableCandidateCount ?: 0)
            put("geometryCompleteCandidateCount", selected.quality?.geometryCompleteCandidateCount ?: 0)
            put("rideLegCount", selected.quality?.rideLegCount ?: 0)
            put("rideShapeLegCount", selected.quality?.rideShapeLegCount ?: 0)
            selected.quality?.fastestMinutes?.let { put("fastestMinutes", it) }
            selected.quality?.fastestGeometryCompleteMinutes?.let { put("fastestGeometryCompleteMinutes", it) }
            set<JsonNode>("evaluatedProviders", JsonNodeFactory.instance.arrayNode().apply {
                evaluations.forEach { evaluation ->
                    add(JsonNodeFactory.instance.objectNode().apply {
                        put("provider", evaluation.providerId)
                        put(
                            "status",
                            when {
                                evaluation.failure != null -> "failed"
                                evaluation.quality?.isUsable == true -> "usable"
                                else -> "rejected"
                            },
                        )
                        evaluation.quality?.let { quality ->
                            put("candidateCount", quality.usableCandidateCount)
                            put("geometryCompleteCandidateCount", quality.geometryCompleteCandidateCount)
                            put("rideLegCount", quality.rideLegCount)
                            put("rideShapeLegCount", quality.rideShapeLegCount)
                            quality.fastestMinutes?.let { put("fastestMinutes", it) }
                        }
                    })
                }
            })
        }
        response.set<JsonNode>(ROUTING_DIAGNOSTICS_FIELD, diagnostics)
        return response
    }

    private fun TransitRouteRequest.cacheKey(): String = listOf(
        coordinate(startX),
        coordinate(startY),
        coordinate(endX),
        coordinate(endY),
        count,
        lang,
        searchDttm.orEmpty(),
    ).joinToString(":")

    private fun coordinate(value: Double): String = String.format(Locale.US, "%.6f", value)

    private companion object {
        const val CACHE_TTL_MILLIS = 45_000L
        const val MAX_CACHE_ENTRIES = 1_000
        const val ROUTING_DIAGNOSTICS_FIELD = "_noLateRouting"
    }
}
