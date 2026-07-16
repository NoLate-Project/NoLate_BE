package com.noLate.route.quickshare

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.schedule.domain.ScheduleTravelMode
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

@Component
@ConditionalOnProperty(prefix = "routing.tmap", name = ["enabled"], havingValue = "true")
class TmapQuickShareRouteProvider(
    @Value("\${routing.tmap.app-key}") appKey: String,
    @Value("\${routing.tmap.base-url}") baseUrl: String,
) : QuickShareRouteProvider {
    private val restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("appKey", appKey)
        .build()

    override fun searchPlaces(query: String): List<QuickSharePlace> {
        val normalized = query.trim()
        require(normalized.length in 1..120) { "장소 검색어는 1~120자여야 합니다." }
        val encoded = URLEncoder.encode(normalized, Charsets.UTF_8)
        val response = restClient.get()
            .uri("/tmap/pois?version=1&format=json&count=5&reqCoordType=WGS84GEO&resCoordType=WGS84GEO&searchKeyword=$encoded")
            .retrieve()
            .body(JsonNode::class.java)
            ?: return emptyList()

        return response.path("searchPoiInfo").path("pois").path("poi")
            .takeIf { it.isArray }
            ?.mapNotNull { poi ->
                val lat = poi.path("noorLat").asText().toDoubleOrNull() ?: return@mapNotNull null
                val lng = poi.path("noorLon").asText().toDoubleOrNull() ?: return@mapNotNull null
                val name = poi.path("name").asText().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val roadAddress = listOf(
                    poi.path("upperAddrName").asText(),
                    poi.path("middleAddrName").asText(),
                    poi.path("lowerAddrName").asText(),
                    poi.path("roadName").asText(),
                    poi.path("firstNo").asText(),
                ).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
                QuickSharePlace(
                    name = name,
                    address = roadAddress,
                    lat = lat,
                    lng = lng,
                    providerPlaceId = poi.path("id").asText().takeIf { it.isNotBlank() },
                )
            }
            .orEmpty()
            .distinctBy { "${it.name}:${it.lat}:${it.lng}" }
    }

    override fun getRouteOptions(request: QuickShareRouteRequest): List<QuickShareRouteOption> =
        when (request.mode) {
            ScheduleTravelMode.CAR -> listOf("0", "1", "2").mapIndexedNotNull { index, option ->
                runCatching { roadRoute(request, option, index) }.getOrNull()
            }
            ScheduleTravelMode.WALK -> listOf("0", "4").mapIndexedNotNull { index, option ->
                runCatching { roadRoute(request, option, index) }.getOrNull()
            }
            ScheduleTravelMode.TRANSIT -> transitRoutes(request)
            else -> emptyList()
        }.distinctBy { "${it.mode}:${it.minutes}:${it.distanceMeters}" }

    private fun roadRoute(
        request: QuickShareRouteRequest,
        searchOption: String,
        index: Int,
    ): QuickShareRouteOption {
        val pedestrian = request.mode == ScheduleTravelMode.WALK
        val path = if (pedestrian) "/tmap/routes/pedestrian" else "/tmap/routes"
        val form = linkedMapOf(
            "startX" to request.origin.lng.toString(),
            "startY" to request.origin.lat.toString(),
            "endX" to request.destination.lng.toString(),
            "endY" to request.destination.lat.toString(),
            "startName" to (request.origin.name ?: "출발지"),
            "endName" to (request.destination.name ?: "도착지"),
            "reqCoordType" to "WGS84GEO",
            "resCoordType" to "WGS84GEO",
            "searchOption" to searchOption,
            "trafficInfo" to "Y",
        ).entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }
        val response = restClient.post()
            .uri("$path?version=1&format=json")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(JsonNode::class.java)
            ?: error("TMAP 경로 응답이 비어 있습니다.")
        val properties = response.path("features").firstOrNull()?.path("properties")
            ?: error("TMAP 경로 요약이 없습니다.")
        val seconds = properties.path("totalTime").takeIf { it.isNumber }?.asDouble()
            ?: error("TMAP 이동 시간이 없습니다.")
        val minutes = ceil(seconds / 60.0).toInt().coerceAtLeast(1)
        val distance = properties.path("totalDistance").takeIf { it.isNumber }?.asInt()
        val label = when {
            pedestrian && searchOption == "4" -> "편안한 도보 경로"
            pedestrian -> "추천 도보 경로"
            searchOption == "1" -> "무료도로 우선"
            searchOption == "2" -> "최소시간 경로"
            else -> "추천 자동차 경로"
        }
        return QuickShareRouteOption(
            id = "${request.mode.name.lowercase()}-$index-$minutes",
            mode = request.mode,
            minutes = minutes,
            distanceMeters = distance,
            fareWon = properties.path("totalFare").takeIf { it.isNumber }?.asInt(),
            summary = label,
            providerRouteOption = searchOption,
        )
    }

    private fun transitRoutes(request: QuickShareRouteRequest): List<QuickShareRouteOption> {
        val departure = request.departureAt
            ?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.of("Asia/Seoul")) }.getOrNull() }
            ?.format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
        val body = linkedMapOf<String, Any>(
            "startX" to request.origin.lng.toString(),
            "startY" to request.origin.lat.toString(),
            "endX" to request.destination.lng.toString(),
            "endY" to request.destination.lat.toString(),
            "count" to 5,
            "lang" to 0,
            "format" to "json",
        ).apply { departure?.let { put("searchDttm", it) } }
        val response = restClient.post()
            .uri("/transit/routes")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(JsonNode::class.java)
            ?: return emptyList()
        val itineraries = response.path("metaData").path("plan").path("itineraries")
        if (!itineraries.isArray) return emptyList()
        return itineraries.mapIndexedNotNull { index, itinerary ->
            val rawTime = itinerary.path("totalTime").takeIf { it.isNumber }?.asDouble()
                ?: return@mapIndexedNotNull null
            val minutes = if (rawTime > 1000) ceil(rawTime / 60.0).toInt() else ceil(rawTime).toInt()
            val transfers = itinerary.path("transferCount").takeIf { it.isNumber }?.asInt()
            QuickShareRouteOption(
                id = "transit-$index-$minutes-${transfers ?: 0}",
                mode = ScheduleTravelMode.TRANSIT,
                minutes = minutes.coerceAtLeast(1),
                distanceMeters = itinerary.path("totalDistance").takeIf { it.isNumber }?.asInt(),
                transferCount = transfers,
                walkMeters = itinerary.path("totalWalkDistance").takeIf { it.isNumber }?.asInt(),
                fareWon = itinerary.path("fare").path("regular").path("totalFare")
                    .takeIf { it.isNumber }?.asInt(),
                summary = transfers?.let { "대중교통 · 환승 ${it}회" } ?: "추천 대중교통 경로",
            )
        }.sortedBy { it.minutes }
    }
}

