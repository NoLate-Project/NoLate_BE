package com.noLate.transit.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalStatus
import com.noLate.transit.domain.estimatedTransitArrivalStatus
import com.noLate.transit.domain.seoulSubwayArrivalStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import com.noLate.global.config.externalHttpRequestFactory
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
) {
    private val subwayClient = RestClient.builder()
        .baseUrl(subwayBaseUrl)
        .requestFactory(externalHttpRequestFactory())
        .build()

    private val busClient = RestClient.builder()
        .baseUrl(busBaseUrl)
        .requestFactory(externalHttpRequestFactory())
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
            ?: return emptyList()

        return parseBusArrivals(response, routeName, limit)
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
        if (stationArsCache.size >= MAX_STATION_CACHE_ENTRIES && !stationArsCache.containsKey(stationName)) {
            stationArsCache.clear()
        }
        return stationArsCache.getOrPut(stationName) {
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
                ?: return@getOrPut emptyList()

            parseBusItems(response)
                .mapNotNull { item -> item.text("arsId")?.filter { it.isDigit() } }
                .filter(::isSeoulBusArsId)
                .distinct()
                .take(MAX_ARS_CANDIDATES)
        }
    }

    private fun requestSubwayArrivals(
        apiKey: String,
        stationName: String,
        lineName: String?,
        directionName: String?,
        directionCode: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        val response = subwayClient.get()
            .uri("/{apiKey}/json/realtimeStationArrival/0/{endIndex}/{stationName}", apiKey, 40, stationName)
            .retrieve()
            .body(JsonNode::class.java)
            ?: return emptyList()

        val lineFilter = lineName?.let(::normalizeRouteName)?.takeIf { it.isNotBlank() }
        val arrivals = response.path("realtimeArrivalList")
            .filter { it.isObject }
            .filter { node -> lineFilter == null || subwayLineMatches(node.path("subwayId").asText(), lineFilter) }
            .mapNotNull { node -> node.toSubwayArrival() }
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

    private fun JsonNode.toSubwayArrival(): TransitArrivalDto? {
        val waitSeconds = parsePositiveInt(path("barvlDt").asText(null)) ?: parseWaitSeconds(path("arvlMsg2").asText(null))
        val waitMinutes = waitSeconds?.toWaitMinutes()
        val message = text("arvlMsg2") ?: text("arvlMsg3")
        val observedAt = Instant.now()
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
            expectedAt = waitSeconds?.let { observedAt.plusSeconds(it.toLong()).toString() },
            lastTrain = text("lstcarAt") == "1" ||
                listOf(trainType, message).filterNotNull().any { it.contains("막차") || it.contains("막") },
            realtime = true,
            arrivalStatus = arrivalStatus,
            observedAt = observedAt.toString(),
            sourceUpdatedAt = parseSeoulTimestamp(text("recptnDt")),
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
    ): TransitArrivalDto? {
        if (message.isNullOrBlank() && waitSeconds == null) return null
        val resolvedWaitSeconds = waitSeconds ?: parseWaitSeconds(message)
        val observedAt = Instant.now()
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
            expectedAt = resolvedWaitSeconds?.let { observedAt.plusSeconds(it.toLong()).toString() },
            lastTrain = lastBusCode == "1",
            realtime = true,
            arrivalStatus = arrivalStatus,
            observedAt = observedAt.toString(),
            vehicleType = vehicleType,
            lowFloor = busTypeCode == SEOUL_LOW_FLOOR_BUS_CODE,
        )
    }

    private fun parseBusItems(xml: String): List<Element> {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isExpandEntityReferences = false
        }
        val document = documentBuilderFactory
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val nodes = document.getElementsByTagName("itemList")
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.text(tagName: String): String? {
        val node = getElementsByTagName(tagName).item(0) ?: return null
        return node.textContent?.trim()?.takeIf { it.isNotBlank() }
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
        val SEOUL_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val SEOUL_ZONE_ID: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

internal fun seoulBusVehicleType(code: String?): String? = when (code?.trim()) {
    "0" -> "일반"
    "1" -> "저상"
    "2" -> "굴절"
    else -> null
}
