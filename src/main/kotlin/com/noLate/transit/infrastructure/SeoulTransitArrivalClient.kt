package com.noLate.transit.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.domain.TransitArrivalStatus
import com.noLate.transit.domain.estimatedTransitArrivalStatus
import com.noLate.transit.domain.seoulSubwayArrivalStatus
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
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.concurrent.ConcurrentHashMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil

@Component
class SeoulTransitArrivalClient(
    @Value("\${transit.seoul.api-key:}") private val commonApiKey: String,
    @Value("\${transit.seoul.subway-api-key:}") private val subwayApiKey: String,
    @Value("\${transit.seoul.bus-api-key:}") private val busApiKey: String,
    @Value("\${transit.seoul.subway-base-url:http://swopenAPI.seoul.go.kr/api/subway}") subwayBaseUrl: String,
    @Value("\${transit.seoul.bus-base-url:http://ws.bus.go.kr/api/rest}") busBaseUrl: String,
    @Value("\${transit.seoul.allow-insecure-http:false}") allowInsecureHttp: Boolean = false,
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
            provider = TransitWireProvider.SEOUL_SUBWAY,
            baseUrl = subwayBaseUrl,
            credentialConfigured = subwayKey().isNotBlank(),
            allowInsecureHttp = allowInsecureHttp,
        )
        validateTransitProviderEndpoint(
            provider = TransitWireProvider.SEOUL_BUS,
            baseUrl = busBaseUrl,
            credentialConfigured = busKey().isNotBlank(),
            allowInsecureHttp = allowInsecureHttp,
        )
    }

    private val requestFactory = EtaDeadlineAwareClientHttpRequestFactory(
        calculationDeadline = calculationDeadline,
        configuredConnectTimeout = Duration.ofSeconds(2),
        configuredReadTimeout = Duration.ofSeconds(4),
    )
    private val subwayClient = RestClient.builder()
        .baseUrl(subwayBaseUrl)
        .requestFactory(requestFactory)
        .build()

    private val busClient = RestClient.builder()
        .baseUrl(busBaseUrl)
        .requestFactory(requestFactory)
        .build()
    private val stationArsCache = ConcurrentHashMap<String, List<String>>()

    fun getSubwayArrivals(
        stationName: String,
        lineName: String?,
        directionName: String?,
        directionCode: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        val apiKey = subwayKey()
        if (apiKey.isBlank()) return emptyList()

        return operationalMetrics.observeTransitEtaProviderCall(
            provider = TransitEtaProviderMetricId.SEOUL_SUBWAY,
            isEmpty = { arrivals -> arrivals.isEmpty() },
        ) {
            providerGuard.execute(SEOUL_SUBWAY_PROVIDER_ID) {
                getSubwayArrivalsUnobserved(
                    apiKey = apiKey,
                    stationName = stationName,
                    lineName = lineName,
                    directionName = directionName,
                    directionCode = directionCode,
                    limit = limit,
                )
            }
        }
    }

    private fun getSubwayArrivalsUnobserved(
        apiKey: String,
        stationName: String,
        lineName: String?,
        directionName: String?,
        directionCode: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        val arrivals = stationNameCandidates(stationName)
            .asSequence()
            .map { requestSubwayArrivals(apiKey, it, lineName, directionName, directionCode, limit) }
            .firstOrNull { it.isNotEmpty() }
            ?: emptyList()

        return arrivals.take(limit)
    }

    fun getBusArrivals(
        arsId: String?,
        stationName: String?,
        routeName: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        val apiKey = busKey()
        if (apiKey.isBlank()) return emptyList()

        return operationalMetrics.observeTransitEtaProviderCall(
            provider = TransitEtaProviderMetricId.SEOUL_BUS,
            isEmpty = { arrivals -> arrivals.isEmpty() },
        ) {
            providerGuard.execute(SEOUL_BUS_PROVIDER_ID) {
                getBusArrivalsUnobserved(
                    apiKey = apiKey,
                    arsId = arsId,
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
        stationName: String?,
        routeName: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        val arsCandidates = buildList {
            val directArsId = arsId?.filter { it.isDigit() }?.takeIf(::isSeoulBusArsId)
            if (directArsId != null) add(directArsId)
            seoulBusStationSearchTerms(stationName).forEach { searchTerm ->
                addAll(resolveSeoulBusArsIds(apiKey, searchTerm))
            }
        }.distinct().take(MAX_ARS_CANDIDATES)

        return arsCandidates
            .asSequence()
            .map { candidate -> requestBusArrivals(apiKey, candidate, routeName, limit) }
            .firstOrNull { it.isNotEmpty() }
            ?: emptyList()
    }

    private fun requestBusArrivals(
        apiKey: String,
        arsId: String,
        routeName: String?,
        limit: Int,
    ): List<TransitArrivalDto> {

        return observeWire(
            provider = TransitWireProvider.SEOUL_BUS,
            operation = TransitWireOperation.ARRIVAL,
            isEmpty = List<TransitArrivalDto>::isEmpty,
        ) {
            val response = busClient.get()
                .uri { uriBuilder ->
                    uriBuilder
                        .path("/stationinfo/getStationByUid")
                        .queryParam("serviceKey", apiKey)
                        .queryParam("arsId", arsId)
                        .build()
                }
                .retrieve()
                .body(String::class.java)
                ?: return@observeWire emptyList()

            parseBusArrivals(response, routeName, limit)
                .map { arrival ->
                    arrival.copy(
                        arsId = arsId,
                    )
                }
        }
    }

    internal fun parseBusArrivals(
        response: String,
        routeName: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        val routeFilter = routeName?.let(::normalizeRouteName)?.takeIf { it.isNotBlank() }
        return parseBusItems(response)
            .filter { item ->
                routeFilter == null || normalizeRouteName(item.text("rtNm")) == routeFilter
            }
            .flatMap { item -> item.toBusArrivals(limit) }
            .take(limit)
    }

    private fun resolveSeoulBusArsIds(apiKey: String, stationName: String): List<String> {
        stationArsCache[stationName]?.let { return it }
        if (stationArsCache.size >= MAX_STATION_CACHE_ENTRIES && !stationArsCache.containsKey(stationName)) {
            stationArsCache.clear()
        }
        val resolved = observeWire(
            provider = TransitWireProvider.SEOUL_BUS,
            operation = TransitWireOperation.STATION_LOOKUP,
            isEmpty = List<String>::isEmpty,
        ) {
            val response = busClient.get()
                .uri { uriBuilder ->
                    uriBuilder
                        .path("/stationinfo/getStationByName")
                        .queryParam("serviceKey", apiKey)
                        .queryParam("stSrch", stationName)
                        .build()
                }
                .retrieve()
                .body(String::class.java)
                ?: return@observeWire emptyList()

            parseBusItems(response)
                .mapNotNull { item -> item.text("arsId")?.filter { it.isDigit() } }
                .filter(::isSeoulBusArsId)
                .distinct()
                .take(MAX_ARS_CANDIDATES)
        }
        // Empty station searches are not cached. Besides normal eventual consistency, providers
        // sometimes encode quota/auth errors as an empty HTTP-200 payload; a later healthy lookup
        // must be allowed to recover in the same process.
        if (resolved.isNotEmpty()) stationArsCache.putIfAbsent(stationName, resolved)
        return resolved
    }

    private fun requestSubwayArrivals(
        apiKey: String,
        stationName: String,
        lineName: String?,
        directionName: String?,
        directionCode: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        return observeWire(
            provider = TransitWireProvider.SEOUL_SUBWAY,
            operation = TransitWireOperation.ARRIVAL,
            isEmpty = List<TransitArrivalDto>::isEmpty,
        ) {
            val response = subwayClient.get()
                .uri("/{apiKey}/json/realtimeStationArrival/0/{endIndex}/{stationName}", apiKey, 40, stationName)
                .retrieve()
                .body(JsonNode::class.java)
                ?: return@observeWire emptyList()

            parseSubwayArrivals(
                response = response,
                lineName = lineName,
                directionName = directionName,
                directionCode = directionCode,
                limit = limit,
            )
        }
    }

    internal fun parseSubwayArrivals(
        response: JsonNode,
        lineName: String?,
        directionName: String?,
        directionCode: String?,
        limit: Int,
        observedAt: Instant = Instant.now(),
    ): List<TransitArrivalDto> {
        if (!seoulSubwayResponseHasData(response)) return emptyList()
        val lineFilter = lineName?.let(::normalizeRouteName)?.takeIf { it.isNotBlank() }
        val arrivals = response.path("realtimeArrivalList")
            .filter { it.isObject }
            .filter { node -> lineFilter == null || subwayLineMatches(node.path("subwayId").asText(), lineFilter) }
            .mapNotNull { node -> node.toSubwayArrival(observedAt) }
        return filterSubwayDirection(arrivals, directionName, directionCode).take(limit)
    }

    /** ODsay 경로의 방면 코드로 반대 방향 열차를 먼저 제거하고, 매칭 실패 시 전체 결과를 보존한다. */
    internal fun filterSubwayDirection(
        arrivals: List<TransitArrivalDto>,
        directionName: String?,
        directionCode: String?,
    ): List<TransitArrivalDto> {
        val directionTokens = when (directionCode?.uppercase()) {
            "UP" -> setOf("상행", "내선")
            "DOWN" -> setOf("하행", "외선")
            else -> emptySet()
        }
        if (directionTokens.isNotEmpty()) {
            val matchedByCode = arrivals.filter { arrival ->
                directionTokens.any { token -> arrival.direction?.contains(token) == true }
            }
            if (matchedByCode.isNotEmpty()) return matchedByCode
        }

        val normalizedDirectionName = normalizeDirectionName(directionName)
        if (normalizedDirectionName.isNotBlank()) {
            val matchedByName = arrivals.filter { arrival ->
                listOf(arrival.destinationName, arrival.direction)
                    .map(::normalizeDirectionName)
                    .filter { it.isNotBlank() }
                    .any { value -> value.contains(normalizedDirectionName) || normalizedDirectionName.contains(value) }
            }
            if (matchedByName.isNotEmpty()) return matchedByName
        }
        return arrivals
    }

    private fun normalizeDirectionName(value: String?): String =
        value
            ?.replace("\\s+".toRegex(), "")
            ?.removeSuffix("방면")
            ?.removeSuffix("행")
            ?.removeSuffix("역")
            ?.trim()
            ?: ""

    private fun JsonNode.toSubwayArrival(observedAt: Instant): TransitArrivalDto? {
        val waitSeconds = parsePositiveInt(path("barvlDt").asText(null)) ?: parseWaitSeconds(path("arvlMsg2").asText(null))
        val waitMinutes = waitSeconds?.toWaitMinutes()
        val message = text("arvlMsg2") ?: text("arvlMsg3")
        val sourceUpdatedAt = parseSeoulTimestamp(text("recptnDt"))
        val expectedBase = sourceUpdatedAt
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: observedAt
        val trainType = text("btrainSttus")
        val arrivalStatus = seoulSubwayArrivalStatus(text("arvlCd"), message)
        return TransitArrivalDto(
            provider = "seoul-openapi",
            kind = "SUBWAY",
            lineName = subwayIdToLineName(path("subwayId").asText(null)),
            stationName = text("statnNm"),
            direction = text("updnLine"),
            destinationName = text("bstatnNm"),
            arrivalMessage = message,
            waitSeconds = waitSeconds,
            waitMinutes = waitMinutes,
            expectedAt = waitSeconds?.let { expectedBase.plusSeconds(it.toLong()).toString() },
            lastTrain = text("lstcarAt") == "1" ||
                listOf(trainType, message).filterNotNull().any { it.contains("막차") || it.contains("막") },
            realtime = true,
            arrivalStatus = arrivalStatus,
            observedAt = observedAt.toString(),
            sourceUpdatedAt = sourceUpdatedAt,
            freshnessEvidence = if (sourceUpdatedAt != null) {
                TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP
            } else {
                TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY
            },
            vehicleType = trainType,
            express = trainType?.let { type ->
                type.contains("급행") || type.contains("특급") || type.contains("ITX", ignoreCase = true)
            },
        )
    }

    private fun Element.toBusArrivals(limit: Int): List<TransitArrivalDto> {
        val routeName = text("rtNm")
        val stationName = text("stNm")
        val direction = text("adirection")
        val observedAt = Instant.now()
        // getStationByUid의 mkTm은 서울시 공식 명세상 공급자 "제공시각"이다.
        // HTTP 수신시각과 구분해 원천 freshness 및 도착예정 절대시각의 기준으로 사용한다.
        val sourceUpdatedAt = parseSeoulTimestamp(text("mkTm"))

        return listOf(
            busArrivalFromSlot(
                routeName = routeName,
                stationName = stationName,
                direction = direction,
                destinationName = text("stationNm1"),
                message = text("arrmsg1"),
                waitSeconds = parsePositiveInt(text("traTime1")),
                arrivalCode = text("isArrive1"),
                lastBusCode = text("isLast1"),
                busTypeCode = text("busType1"),
                observedAt = observedAt,
                sourceUpdatedAt = sourceUpdatedAt,
            ),
            busArrivalFromSlot(
                routeName = routeName,
                stationName = stationName,
                direction = direction,
                destinationName = text("stationNm2"),
                message = text("arrmsg2"),
                waitSeconds = parsePositiveInt(text("traTime2")),
                arrivalCode = text("isArrive2"),
                lastBusCode = text("isLast2"),
                busTypeCode = text("busType2"),
                observedAt = observedAt,
                sourceUpdatedAt = sourceUpdatedAt,
            ),
        )
            .filterNotNull()
            .take(limit)
    }

    private fun busArrivalFromSlot(
        routeName: String?,
        stationName: String?,
        direction: String?,
        destinationName: String?,
        message: String?,
        waitSeconds: Int?,
        arrivalCode: String?,
        lastBusCode: String?,
        busTypeCode: String?,
        observedAt: Instant,
        sourceUpdatedAt: String?,
    ): TransitArrivalDto? {
        if (message.isNullOrBlank() && waitSeconds == null) return null
        val resolvedWaitSeconds = waitSeconds ?: parseWaitSeconds(message)
        val expectedBase = sourceUpdatedAt
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: observedAt
        val arrivalStatus = if (arrivalCode == "1") {
            TransitArrivalStatus.ARRIVED
        } else {
            estimatedTransitArrivalStatus(resolvedWaitSeconds, null, message)
        }
        val vehicleType = seoulBusVehicleType(busTypeCode)
        return TransitArrivalDto(
            provider = "seoul-bus",
            kind = "BUS",
            lineName = routeName,
            routeName = routeName,
            stationName = stationName,
            direction = direction,
            destinationName = destinationName,
            arrivalMessage = message,
            waitSeconds = resolvedWaitSeconds,
            waitMinutes = resolvedWaitSeconds?.toWaitMinutes(),
            expectedAt = resolvedWaitSeconds?.let { expectedBase.plusSeconds(it.toLong()).toString() },
            lastTrain = lastBusCode == "1",
            realtime = true,
            arrivalStatus = arrivalStatus,
            observedAt = observedAt.toString(),
            sourceUpdatedAt = sourceUpdatedAt,
            freshnessEvidence = if (sourceUpdatedAt != null) {
                TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP
            } else {
                TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY
            },
            vehicleType = vehicleType,
            lowFloor = busTypeCode == SEOUL_LOW_FLOOR_BUS_CODE,
        )
    }

    private fun parseBusItems(xml: String): List<Element> {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isExpandEntityReferences = false
        }
        val document = documentBuilderFactory
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        requireSeoulBusSuccess(document)
        val nodes = document.getElementsByTagName("itemList")
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.text(tagName: String): String? {
        val node = getElementsByTagName(tagName).item(0) ?: return null
        return node.textContent?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun Document.text(tagName: String): String? {
        val node = getElementsByTagName(tagName).item(0) ?: return null
        return node.textContent?.trim()?.takeIf(String::isNotBlank)
    }

    private fun requireSeoulBusSuccess(document: Document) {
        val resultCode = document.text("headerCd") ?: document.text("returnCode")
        if (resultCode == null) {
            if (document.text("headerMsg") != null) {
                throw TransitProviderApplicationException(TransitWireProvider.SEOUL_BUS, null)
            }
            return
        }
        if (resultCode !in SEOUL_BUS_SUCCESS_CODES) {
            throw TransitProviderApplicationException(TransitWireProvider.SEOUL_BUS, resultCode)
        }
    }

    /**
     * Seoul Open API의 INFO-200은 정상적인 빈 조회다. 예외로 올리면 첫 역명 후보에서
     * 순회가 중단되고 circuit failure까지 누적되므로, data 유무를 별도로 반환한다.
     */
    private fun seoulSubwayResponseHasData(response: JsonNode): Boolean {
        val resultCode = (
            response.path("RESULT").path("CODE").asText(null)
                ?: response.path("errorMessage").path("code").asText(null)
        )?.trim()
        if (resultCode == null) {
            val resultMessagePresent = response.path("RESULT").path("MESSAGE").asText(null) != null ||
                response.path("errorMessage").path("message").asText(null) != null
            if (resultMessagePresent || !response.path("realtimeArrivalList").isArray) {
                throw TransitProviderApplicationException(TransitWireProvider.SEOUL_SUBWAY, null)
            }
            return true
        }
        if (resultCode in SEOUL_SUBWAY_NO_DATA_CODES) return false
        if (resultCode !in SEOUL_SUBWAY_SUCCESS_CODES) {
            throw TransitProviderApplicationException(TransitWireProvider.SEOUL_SUBWAY, resultCode)
        }
        if (!response.path("realtimeArrivalList").isArray) {
            throw TransitProviderApplicationException(TransitWireProvider.SEOUL_SUBWAY, resultCode)
        }
        return true
    }

    private fun <T> observeWire(
        provider: TransitWireProvider,
        operation: TransitWireOperation,
        isEmpty: (T) -> Boolean,
        call: () -> T,
    ): T {
        val guardedCall = {
            wireRateLimiter.requirePermit(provider)
            call()
        }
        return wireMetrics?.observe(provider, operation, isEmpty, guardedCall) ?: guardedCall()
    }

    private fun JsonNode.text(fieldName: String): String? =
        path(fieldName).asText(null)?.trim()?.takeIf { it.isNotBlank() }

    private fun stationNameCandidates(stationName: String): List<String> {
        val normalized = stationName.trim()
        val stripped = normalized.removeSuffix("역").trim()
        return listOf(normalized, stripped)
            .filter { it.isNotBlank() }
            .distinct()
    }

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

    private fun subwayLineMatches(subwayId: String?, lineFilter: String): Boolean {
        val lineName = normalizeRouteName(subwayIdToLineName(subwayId))
        if (lineName.isBlank()) return false
        return lineName == lineFilter || lineName.contains(lineFilter) || lineFilter.contains(lineName)
    }

    private fun subwayIdToLineName(subwayId: String?): String? = when (subwayId) {
        "1001" -> "1호선"
        "1002" -> "2호선"
        "1003" -> "3호선"
        "1004" -> "4호선"
        "1005" -> "5호선"
        "1006" -> "6호선"
        "1007" -> "7호선"
        "1008" -> "8호선"
        "1009" -> "9호선"
        "1063" -> "경의중앙선"
        "1065" -> "공항철도"
        "1067" -> "경춘선"
        "1075" -> "수인분당선"
        "1077" -> "신분당선"
        "1092" -> "우이신설선"
        "1093" -> "서해선"
        "1032" -> "GTX-A"
        else -> null
    }

    private fun parsePositiveInt(value: String?): Int? =
        value?.trim()?.takeIf { it.isNotBlank() }?.toIntOrNull()?.takeIf { it >= 0 }

    private fun parseWaitSeconds(message: String?): Int? {
        val normalized = message?.trim() ?: return null
        val minute = "(\\d+)\\s*분".toRegex().find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (minute != null) return minute * 60
        val second = "(\\d+)\\s*초".toRegex().find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (second != null) return second
        if (normalized.contains("곧") || normalized.contains("진입") || normalized.contains("도착")) return 0
        return null
    }

    private fun parseSeoulTimestamp(value: String?): String? {
        val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            LocalDateTime
                .parse(normalized, SEOUL_TIMESTAMP_FORMATTER)
                .atZone(SEOUL_ZONE_ID)
                .toInstant()
                .toString()
        }.getOrNull()
    }

    private fun subwayKey(): String = subwayApiKey.ifBlank { commonApiKey }

    private fun busKey(): String = busApiKey.ifBlank { commonApiKey }

    private fun Int.toWaitMinutes(): Int = ceil(this / 60.0).toInt().coerceAtLeast(0)

    private companion object {
        const val MAX_STATION_CACHE_ENTRIES = 500
        const val MAX_ARS_CANDIDATES = 8
        const val SEOUL_LOW_FLOOR_BUS_CODE = "1"
        const val SEOUL_BUS_PROVIDER_ID = "seoul_bus"
        const val SEOUL_SUBWAY_PROVIDER_ID = "seoul_subway"
        val SEOUL_BUS_SUCCESS_CODES = setOf("0", "00", "INFO-000")
        val SEOUL_SUBWAY_SUCCESS_CODES = setOf("0", "00", "INFO-000")
        val SEOUL_SUBWAY_NO_DATA_CODES = setOf("INFO-200")
        val SEOUL_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter()
        val SEOUL_ZONE_ID: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

internal fun seoulBusVehicleType(code: String?): String? = when (code?.trim()) {
    "0" -> "일반"
    "1" -> "저상"
    "2" -> "굴절"
    else -> null
}
