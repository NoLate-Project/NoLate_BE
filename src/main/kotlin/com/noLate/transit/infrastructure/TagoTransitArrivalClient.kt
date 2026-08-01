package com.noLate.transit.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.domain.estimatedTransitArrivalStatus
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.global.observability.TransitEtaProviderMetricId
import com.noLate.global.observability.observeTransitEtaProviderCall
import com.noLate.eta.resilience.EtaCalculationDeadline
import com.noLate.eta.resilience.EtaDeadlineAwareClientHttpRequestFactory
import com.noLate.eta.resilience.EtaProviderGuard
import com.noLate.eta.resilience.StaticEtaProviderResiliencePolicyResolver
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

@Component
class TagoTransitArrivalClient(
    @Value("\${transit.tago.api-key:}") private val commonApiKey: String,
    @Value("\${transit.tago.bus-api-key:}") private val busApiKey: String,
    @Value("\${transit.tago.base-url:https://apis.data.go.kr/1613000}") baseUrl: String,
    @Value("\${transit.tago.city-codes:}") cityCodeCandidatesValue: String,
    @Value("\${transit.tago.allow-insecure-http:false}") allowInsecureHttp: Boolean = false,
    private val calculationDeadline: EtaCalculationDeadline = EtaCalculationDeadline(),
    private val operationalMetrics: NoLateOperationalMetrics? = null,
    private val providerGuard: EtaProviderGuard = EtaProviderGuard(
        policyResolver = StaticEtaProviderResiliencePolicyResolver(),
        calculationDeadline = calculationDeadline,
    ),
    private val wireMetrics: TransitProviderWireMetrics? = null,
    private val wireRateLimiter: TransitProviderWireRateLimiter = TransitProviderWireRateLimiter(),
) {
    init {
        validateTransitProviderEndpoint(
            provider = TransitWireProvider.TAGO_BUS,
            baseUrl = baseUrl,
            credentialConfigured = tagoKey().isNotBlank(),
            allowInsecureHttp = allowInsecureHttp,
        )
    }

    private val client = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            EtaDeadlineAwareClientHttpRequestFactory(
                calculationDeadline = calculationDeadline,
                configuredConnectTimeout = Duration.ofSeconds(2),
                configuredReadTimeout = Duration.ofSeconds(4),
            )
        )
        .build()

    private val configuredCityCodes = cityCodeCandidatesValue
        .split(",", " ", "\n", "\t")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    private val stationCache = ConcurrentHashMap<String, List<TagoStation>>()

    fun getBusArrivals(
        arsId: String?,
        nodeId: String?,
        cityCode: String?,
        stationName: String?,
        routeName: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        val apiKey = tagoKey()
        if (apiKey.isBlank()) return emptyList()

        return operationalMetrics.observeTransitEtaProviderCall(
            provider = TransitEtaProviderMetricId.TAGO_BUS,
            isEmpty = { arrivals -> arrivals.isEmpty() },
        ) {
            providerGuard.execute(TAGO_BUS_PROVIDER_ID) {
                getBusArrivalsUnobserved(
                    apiKey = apiKey,
                    arsId = arsId,
                    nodeId = nodeId,
                    cityCode = cityCode,
                    stationName = stationName,
                    routeName = routeName,
                    limit = limit,
                )
            }
        }
    }

    private fun getBusArrivalsUnobserved(
        apiKey: String,
        arsId: String?,
        nodeId: String?,
        cityCode: String?,
        stationName: String?,
        routeName: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        val normalizedNodeId = nodeId?.trim()?.takeIf { it.isNotBlank() }
        val normalizedCityCode = cityCode?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
        val routeFilter = routeName?.let(::normalizeRouteName)?.takeIf { it.isNotBlank() }
        if (normalizedCityCode != null && normalizedNodeId != null) {
            val direct = requestArrivals(
                apiKey = apiKey,
                cityCode = normalizedCityCode,
                nodeId = normalizedNodeId,
                arsId = arsId,
                routeFilter = routeFilter,
                limit = limit,
            )
            if (direct.isNotEmpty()) return direct
        }

        return resolveStationCandidates(
            apiKey = apiKey,
            arsId = arsId,
            cityCode = normalizedCityCode,
            stationName = stationName,
        )
            .asSequence()
            .flatMap { station ->
                requestArrivals(
                    apiKey = apiKey,
                    cityCode = station.cityCode,
                    nodeId = station.nodeId,
                    arsId = station.nodeNo,
                    routeFilter = routeFilter,
                    limit = limit,
                ).asSequence()
            }
            .take(limit)
            .toList()
    }

    private fun resolveStationCandidates(
        apiKey: String,
        arsId: String?,
        cityCode: String?,
        stationName: String?,
    ): List<TagoStation> {
        val nodeNo = arsId?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
        val normalizedStationName = stationName?.trim()?.takeIf { it.isNotBlank() }
        if (nodeNo == null && normalizedStationName == null) return emptyList()

        val cityCodes = cityCode?.let(::listOf) ?: cityCodeCandidates()
        val cacheKey = listOf(nodeNo ?: "", normalizedStationName ?: "", cityCodes.joinToString(",")).joinToString("|")
        if (stationCache.size >= MAX_STATION_CACHE_ENTRIES && !stationCache.containsKey(cacheKey)) {
            stationCache.clear()
        }
        stationCache[cacheKey]?.let { return it }
        val resolved = run {
            val byNodeNo = if (nodeNo == null) emptyList() else cityCodes
                .asSequence()
                .flatMap { code ->
                    requestStations(
                        apiKey = apiKey,
                        cityCode = code,
                        nodeNo = nodeNo,
                        nodeName = null,
                    ).asSequence()
                }
                .take(MAX_STATION_CANDIDATES)
                .toList()

            if (byNodeNo.isNotEmpty()) {
                byNodeNo
            } else if (normalizedStationName != null) {
                cityCodes
                    .asSequence()
                    .flatMap { code ->
                        requestStations(
                            apiKey = apiKey,
                            cityCode = code,
                            nodeNo = null,
                            nodeName = normalizedStationName,
                        ).asSequence()
                    }
                    .sortedByDescending { station -> station.matchScore(normalizedStationName) }
                    .take(MAX_STATION_CANDIDATES)
                    .toList()
            } else {
                emptyList()
            }
        }
        // Do not create a process-lifetime negative cache entry. A later call after a transient
        // provider/quota failure or station-data propagation must be able to resolve the stop.
        if (resolved.isNotEmpty()) stationCache.putIfAbsent(cacheKey, resolved)
        return resolved
    }

    private fun requestStations(
        apiKey: String,
        cityCode: String,
        nodeNo: String?,
        nodeName: String?,
    ): List<TagoStation> {
        return observeWire(
            operation = TransitWireOperation.STATION_LOOKUP,
            isEmpty = List<TagoStation>::isEmpty,
        ) {
            val response = client.get()
                .uri { uriBuilder ->
                    val builder = uriBuilder
                        .path("/BusSttnInfoInqireService/getSttnNoList")
                        // 공공데이터포털의 decoding key는 '+', '/', '='을 포함할 수 있다.
                        // literal query parameter로 넣으면 '+'가 그대로 전송되어 provider가
                        // form-style space로 해석하므로, URI variable로 확장해 reserved 문자를
                        // percent-encode한다.
                        .queryParam("serviceKey", "{serviceKey}")
                        .queryParam("_type", "json")
                        .queryParam("cityCode", cityCode)
                        .queryParam("numOfRows", 10)
                    if (!nodeNo.isNullOrBlank()) builder.queryParam("nodeNo", nodeNo)
                    if (!nodeName.isNullOrBlank()) builder.queryParam("nodeNm", nodeName)
                    builder.build(apiKey)
                }
                .retrieve()
                .body(JsonNode::class.java)
                ?: return@observeWire emptyList()

            if (!tagoResponseHasData(response)) return@observeWire emptyList()
            response.items()
                .mapNotNull { item ->
                    val resolvedNodeId = item.text("nodeid") ?: item.text("nodeId")
                    if (resolvedNodeId.isNullOrBlank()) return@mapNotNull null
                    TagoStation(
                        cityCode = cityCode,
                        nodeId = resolvedNodeId,
                        nodeName = item.text("nodenm") ?: item.text("nodeNm"),
                        nodeNo = item.text("nodeno") ?: item.text("nodeNo"),
                    )
                }
        }
    }

    private fun requestArrivals(
        apiKey: String,
        cityCode: String,
        nodeId: String,
        arsId: String?,
        routeFilter: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        return observeWire(
            operation = TransitWireOperation.ARRIVAL,
            isEmpty = List<TransitArrivalDto>::isEmpty,
        ) {
            val response = client.get()
                .uri { uriBuilder ->
                    uriBuilder
                        .path("/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList")
                        .queryParam("serviceKey", "{serviceKey}")
                        .queryParam("_type", "json")
                        .queryParam("cityCode", cityCode)
                        .queryParam("nodeId", nodeId)
                        .queryParam("numOfRows", 30)
                        .build(apiKey)
                }
                .retrieve()
                .body(JsonNode::class.java)
                ?: return@observeWire emptyList()

            if (!tagoResponseHasData(response)) return@observeWire emptyList()
            response.items()
                .filter { item ->
                    routeFilter == null || normalizeRouteName(item.routeName()) == routeFilter
                }
                .mapNotNull { item -> item.toTagoBusArrival() }
                .map { arrival ->
                    arrival.copy(
                        cityCode = cityCode,
                        nodeId = nodeId,
                        arsId = arsId?.filter(Char::isDigit)?.takeIf(String::isNotBlank),
                    )
                }
                .take(limit)
        }
    }

    private fun JsonNode.toTagoBusArrival(): TransitArrivalDto? {
        val routeName = routeName()
        val stationName = text("nodenm") ?: text("nodeNm")
        val waitSeconds = positiveInt("arrtime") ?: positiveInt("arrTime")
        val waitMinutes = waitSeconds?.toWaitMinutes()
        val previousStops = positiveInt("arrprevstationcnt") ?: positiveInt("arrPrevStationCnt")
        val vehicleType = text("vehicletp") ?: text("vehicleTp")
        val message = buildList {
            if (waitMinutes != null) add(if (waitMinutes <= 0) "곧 도착" else "${waitMinutes}분 후")
            if (previousStops != null) add("${previousStops}정류장 전")
        }.joinToString(" · ").takeIf { it.isNotBlank() }

        if (routeName.isNullOrBlank() && stationName.isNullOrBlank() && waitSeconds == null) return null

        val observedAt = Instant.now()
        val arrivalStatus = estimatedTransitArrivalStatus(waitSeconds, previousStops, message)
        return TransitArrivalDto(
            provider = "tago",
            kind = "BUS",
            lineName = routeName,
            routeName = routeName,
            stationName = stationName,
            direction = text("routetp") ?: text("routeTp"),
            arrivalMessage = message,
            waitSeconds = waitSeconds,
            waitMinutes = waitMinutes,
            expectedAt = waitSeconds?.let { observedAt.plusSeconds(it.toLong()).toString() },
            realtime = true,
            arrivalStatus = arrivalStatus,
            observedAt = observedAt.toString(),
            freshnessEvidence = TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY,
            remainingStops = previousStops,
            vehicleType = vehicleType,
            lowFloor = vehicleType?.contains("저상") == true,
        )
    }

    private fun JsonNode.items(): List<JsonNode> {
        val item = path("response").path("body").path("items").path("item")
        if (item.isArray) return item.filter { it.isObject }
        if (item.isObject) return listOf(item)
        return emptyList()
    }

    /**
     * 공공데이터포털 resultCode=03은 정상적인 NODATA_ERROR다. 이를 빈 결과로 반환해야
     * 다음 city/station 후보를 계속 조회하고 provider circuit도 실패로 오염되지 않는다.
     */
    private fun tagoResponseHasData(response: JsonNode): Boolean {
        val resultCode = response.path("response").path("header").path("resultCode")
            .asText(null)
            ?.trim()
        if (resultCode in TAGO_NO_DATA_CODES) return false
        if (resultCode !in TAGO_SUCCESS_CODES) {
            throw TransitProviderApplicationException(TransitWireProvider.TAGO_BUS, resultCode)
        }
        return true
    }

    private fun <T> observeWire(
        operation: TransitWireOperation,
        isEmpty: (T) -> Boolean,
        call: () -> T,
    ): T {
        val guardedCall = {
            wireRateLimiter.requirePermit(TransitWireProvider.TAGO_BUS)
            call()
        }
        return wireMetrics?.observe(
            TransitWireProvider.TAGO_BUS,
            operation,
            isEmpty,
            guardedCall,
        ) ?: guardedCall()
    }

    private fun JsonNode.routeName(): String? =
        text("routeno") ?: text("routeNo") ?: text("rtNm") ?: text("routeNm")

    private fun JsonNode.text(fieldName: String): String? =
        path(fieldName).asText(null)?.trim()?.takeIf { it.isNotBlank() }

    private fun JsonNode.positiveInt(fieldName: String): Int? =
        text(fieldName)?.toIntOrNull()?.takeIf { it >= 0 }

    private fun normalizeRouteName(value: String?): String =
        value
            ?.replace("\\s+".toRegex(), "")
            ?.replace("수도권", "")
            ?.replace("지하철", "")
            ?.replace("버스", "")
            ?.replace("노선", "")
            ?.replace("번", "")
            ?.trim()
            ?: ""

    private fun cityCodeCandidates(): List<String> =
        configuredCityCodes.ifEmpty { DEFAULT_CITY_CODES }

    private fun tagoKey(): String = busApiKey.ifBlank { commonApiKey }

    private fun Int.toWaitMinutes(): Int = ceil(this / 60.0).toInt().coerceAtLeast(0)

    private data class TagoStation(
        val cityCode: String,
        val nodeId: String,
        val nodeName: String?,
        val nodeNo: String?,
    ) {
        fun matchScore(query: String): Int {
            val normalizedQuery = query.replace("\\s+".toRegex(), "")
            val normalizedName = nodeName?.replace("\\s+".toRegex(), "") ?: return 0
            return when {
                normalizedName == normalizedQuery -> 3
                normalizedName.startsWith(normalizedQuery) -> 2
                normalizedName.contains(normalizedQuery) -> 1
                else -> 0
            }
        }
    }

    private companion object {
        const val TAGO_BUS_PROVIDER_ID = "tago_bus"
        const val MAX_STATION_CACHE_ENTRIES = 500
        const val MAX_STATION_CANDIDATES = 6
        val TAGO_SUCCESS_CODES = setOf("0", "00")
        val TAGO_NO_DATA_CODES = setOf("3", "03")

        val DEFAULT_CITY_CODES = listOf(
            "12", "21", "22", "23", "24", "25", "26", "39",
            "31010", "31020", "31030", "31040", "31050", "31060", "31070", "31080",
            "31090", "31100", "31110", "31120", "31130", "31140", "31150", "31160",
            "31170", "31180", "31190", "31200", "31210", "31220", "31230", "31240",
            "31250", "31260", "31270", "31320", "31350", "31370", "31380",
        )
    }
}
