package com.noLate.schedule.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.route.infrastructure.tmapTransitSecondsToMinutes
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.application.fallbackResult
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.ResourceAccessException
import com.noLate.global.config.externalHttpRequestFactory
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import kotlin.math.ceil

@Component
@ConditionalOnProperty(prefix = "schedule.traffic.tmap", name = ["enabled"], havingValue = "true")
class TmapTrafficClient(
    @Value("\${schedule.traffic.tmap.app-key}") private val appKey: String,
    @Value("\${schedule.traffic.tmap.base-url}") baseUrl: String,
    private val clock: Clock = Clock.systemUTC(),
    requestFactory: ClientHttpRequestFactory = externalHttpRequestFactory(),
) : TrafficClient {
    private val restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("appKey", appKey)
        .requestFactory(requestFactory)
        .build()

    override fun getTravelMinutes(request: TrafficRequest): TrafficResult {
        request.liveRefreshBlockedReason?.let { return request.fallbackResult(it) }

        if (request.travelMode in setOf(ScheduleTravelMode.BIKE, ScheduleTravelMode.ETC)) {
            return request.fallbackResult(
                TrafficFailureReasons.unsupportedMode(request.travelMode)
            )
        }
        if (request.travelMode == ScheduleTravelMode.TRANSIT && !request.selectedRouteJson.isNullOrBlank()) {
            return request.fallbackResult(TrafficFailureReasons.SELECTED_TRANSIT_ROUTE_NOT_REFRESHABLE)
        }
        if (
            request.travelMode in setOf(ScheduleTravelMode.CAR, ScheduleTravelMode.WALK) &&
            !request.selectedRouteJson.isNullOrBlank() &&
            request.selectedRouteOption == null
        ) {
            return request.fallbackResult(TrafficFailureReasons.SELECTED_ROUTE_OPTION_MISSING)
        }

        return runCatching {
            TrafficResult(
                travelMinutes = getLiveTravelMinutes(request),
                source = TrafficSource.LIVE_PROVIDER,
                fetchedAt = Instant.now(clock),
                stale = false,
            )
        }.getOrElse { exception ->
            request.fallbackResult(providerFailureReason(exception))
        }
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
        ).apply {
            request.selectedRouteOption?.let { put("searchOption", it) }
        }.entries.joinToString("&") { (key, value) ->
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

    private fun providerFailureReason(exception: Throwable): String {
        val causes = generateSequence(exception) { it.cause }.toList()
        return when {
            causes.any { it is SocketTimeoutException } -> TrafficFailureReasons.PROVIDER_TIMEOUT
            causes.any { it is RestClientResponseException } -> TrafficFailureReasons.PROVIDER_HTTP_ERROR
            causes.any { it is ResourceAccessException } -> TrafficFailureReasons.PROVIDER_UNAVAILABLE
            causes.any { it is IllegalStateException || it is IllegalArgumentException } ->
                TrafficFailureReasons.PROVIDER_INVALID_RESPONSE
            else -> TrafficFailureReasons.PROVIDER_UNAVAILABLE
        }
    }
}
