package com.noLate.transit.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.estimatedTransitArrivalStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

@Component
class TagoTransitArrivalClient(
    @Value("\${transit.tago.api-key:}") private val commonApiKey: String,
    @Value("\${transit.tago.bus-api-key:}") private val busApiKey: String,
    @Value("\${transit.tago.base-url:https://apis.data.go.kr/1613000}") baseUrl: String,
    @Value("\${transit.tago.city-codes:}") cityCodeCandidatesValue: String,
) {
    private val client = RestClient.builder()
        .baseUrl(baseUrl)
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

        val normalizedNodeId = nodeId?.trim()?.takeIf { it.isNotBlank() }
        val normalizedCityCode = cityCode?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
        val routeFilter = routeName?.let(::normalizeRouteName)?.takeIf { it.isNotBlank() }
        if (normalizedCityCode != null && normalizedNodeId != null) {
            val direct = requestArrivals(
                apiKey = apiKey,
                cityCode = normalizedCityCode,
                nodeId = normalizedNodeId,
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
        return stationCache.getOrPut(cacheKey) {
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
    }

    private fun requestStations(
        apiKey: String,
        cityCode: String,
        nodeNo: String?,
        nodeName: String?,
    ): List<TagoStation> = runCatching {
        val response = client.get()
            .uri { uriBuilder ->
                val builder = uriBuilder
                    .path("/BusSttnInfoInqireService/getSttnNoList")
                    .queryParam("serviceKey", apiKey)
                    .queryParam("_type", "json")
                    .queryParam("cityCode", cityCode)
                    .queryParam("numOfRows", 10)
                if (!nodeNo.isNullOrBlank()) builder.queryParam("nodeNo", nodeNo)
                if (!nodeName.isNullOrBlank()) builder.queryParam("nodeNm", nodeName)
                builder.build()
            }
            .retrieve()
            .body(JsonNode::class.java)
            ?: return emptyList()

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
    }.getOrDefault(emptyList())

    private fun requestArrivals(
        apiKey: String,
        cityCode: String,
        nodeId: String,
        routeFilter: String?,
        limit: Int,
    ): List<TransitArrivalDto> = runCatching {
        val response = client.get()
            .uri { uriBuilder ->
                uriBuilder
                    .path("/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList")
                    .queryParam("serviceKey", apiKey)
                    .queryParam("_type", "json")
                    .queryParam("cityCode", cityCode)
                    .queryParam("nodeId", nodeId)
                    .queryParam("numOfRows", 30)
                    .build()
            }
            .retrieve()
            .body(JsonNode::class.java)
            ?: return emptyList()

        response.items()
            .filter { item ->
                routeFilter == null || normalizeRouteName(item.routeName()) == routeFilter
            }
            .mapNotNull { item -> item.toTagoBusArrival() }
            .take(limit)
    }.getOrDefault(emptyList())

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
        const val MAX_STATION_CANDIDATES = 6

        val DEFAULT_CITY_CODES = listOf(
            "12", "21", "22", "23", "24", "25", "26", "39",
            "31010", "31020", "31030", "31040", "31050", "31060", "31070", "31080",
            "31090", "31100", "31110", "31120", "31130", "31140", "31150", "31160",
            "31170", "31180", "31190", "31200", "31210", "31220", "31230", "31240",
            "31250", "31260", "31270", "31320", "31350", "31370", "31380",
        )
    }
}
