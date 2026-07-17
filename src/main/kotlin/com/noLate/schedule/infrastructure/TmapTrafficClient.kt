package com.noLate.schedule.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.route.infrastructure.tmapTransitSecondsToMinutes
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.domain.ScheduleTravelMode
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import com.noLate.global.config.externalHttpRequestFactory
import kotlin.math.ceil

@Component
@ConditionalOnProperty(prefix = "schedule.traffic.tmap", name = ["enabled"], havingValue = "true")
class TmapTrafficClient(
    @Value("\${schedule.traffic.tmap.app-key}") private val appKey: String,
    @Value("\${schedule.traffic.tmap.base-url}") baseUrl: String,
) : TrafficClient {
    private val restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("appKey", appKey)
        .requestFactory(externalHttpRequestFactory())
        .build()

    override fun getTravelMinutes(request: TrafficRequest): Int {
        return runCatching { getLiveTravelMinutes(request) }
            .getOrElse { request.selectedRouteTravelMinutes ?: request.fallbackTravelMinutes }
            .coerceAtLeast(1)
    }

    private fun getLiveTravelMinutes(request: TrafficRequest): Int {
        if (request.travelMode == ScheduleTravelMode.TRANSIT) {
            return getTransitTravelMinutes(request)
        }

        val path = if (request.travelMode == ScheduleTravelMode.WALK) {
            "/tmap/routes/pedestrian"
        } else {
            "/tmap/routes"
        }
        val form = linkedMapOf(
            "startX" to request.originLng.toString(),
            "startY" to request.originLat.toString(),
            "endX" to request.destinationLng.toString(),
            "endY" to request.destinationLat.toString(),
            "reqCoordType" to "WGS84GEO",
            "resCoordType" to "WGS84GEO",
            "startName" to "출발지",
            "endName" to "도착지",
            "trafficInfo" to "Y",
        ).entries.joinToString("&") { (key, value) ->
            "$key=${java.net.URLEncoder.encode(value, Charsets.UTF_8)}"
        }

        val response = restClient.post()
            .uri("$path?version=1&format=json")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(JsonNode::class.java)
            ?: error("Tmap 응답이 비어 있습니다.")

        val totalTimeSeconds = response.path("features")
            .firstOrNull()
            ?.path("properties")
            ?.path("totalTime")
            ?.takeIf { it.isNumber }
            ?.asLong()
            ?: error("Tmap 응답에 totalTime이 없습니다.")

        return ceil(totalTimeSeconds / 60.0).toInt().coerceAtLeast(1)
    }

    private fun getTransitTravelMinutes(request: TrafficRequest): Int {
        val response = restClient.post()
            .uri("/transit/routes")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "startX" to request.originLng.toString(),
                    "startY" to request.originLat.toString(),
                    "endX" to request.destinationLng.toString(),
                    "endY" to request.destinationLat.toString(),
                    "count" to 1,
                    "lang" to 0,
                    "format" to "json",
                )
            )
            .retrieve()
            .body(JsonNode::class.java)
            ?: error("Tmap 대중교통 응답이 비어 있습니다.")

        val totalTimeSeconds = response.path("metaData")
            .path("plan")
            .path("itineraries")
            .firstOrNull()
            ?.path("totalTime")
            ?.takeIf { it.isNumber }
            ?.asDouble()
            ?: error("Tmap 대중교통 응답에 totalTime이 없습니다.")

        return tmapTransitSecondsToMinutes(totalTimeSeconds)
    }
}
