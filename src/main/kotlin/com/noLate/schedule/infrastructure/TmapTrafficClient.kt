package com.noLate.schedule.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.schedule.application.EtaTravelTimePolicy
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

@Component
@ConditionalOnProperty(prefix = "schedule.traffic.tmap", name = ["enabled"], havingValue = "true")
class TmapTrafficClient(
    @Value("\${schedule.traffic.tmap.app-key}") private val appKey: String,
    @Value("\${schedule.traffic.tmap.base-url}") baseUrl: String,
    private val clock: Clock = Clock.systemUTC(),
    requestFactory: ClientHttpRequestFactory = externalHttpRequestFactory(),
    @Value("\${schedule.traffic.max-travel-minutes:1440}")
    private val maxTravelMinutes: Int = EtaTravelTimePolicy.DEFAULT_MAX_TRAVEL_MINUTES,
) : TrafficClient {
    init {
        EtaTravelTimePolicy.requireValidMaximum(maxTravelMinutes)
    }

    private val restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("appKey", appKey)
        .requestFactory(requestFactory)
        .build()

    override fun getTravelMinutes(request: TrafficRequest): TrafficResult {
        require(request.maxTravelMinutes == maxTravelMinutes) {
            "TrafficRequest와 TMAP client의 이동 시간 상한이 일치해야 합니다."
        }
        request.liveRefreshBlockedReason?.let { return request.fallbackResult(it) }

        if (request.travelMode == ScheduleTravelMode.BIKE) {
            return request.fallbackResult(
                TrafficFailureReasons.unsupportedMode(request.travelMode)
            )
        }
        if (request.travelMode == ScheduleTravelMode.TRANSIT) {
            return request.fallbackResult(TrafficFailureReasons.TRANSIT_ITINERARY_REFRESH_UNSUPPORTED)
        }
        if (
            request.travelMode in setOf(
                ScheduleTravelMode.CAR,
                ScheduleTravelMode.ETC,
                ScheduleTravelMode.WALK,
            ) &&
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
        val path = when (request.travelMode) {
            ScheduleTravelMode.CAR,
            ScheduleTravelMode.ETC -> "/tmap/routes"
            ScheduleTravelMode.WALK -> "/tmap/routes/pedestrian"
            ScheduleTravelMode.TRANSIT -> error("대중교통 동일 itinerary 실시간 갱신은 지원하지 않습니다.")
            ScheduleTravelMode.BIKE -> error("자전거 실시간 ETA는 지원하지 않습니다.")
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
            ?.asDouble()
            ?: error("Tmap 응답에 totalTime이 없습니다.")

        return validatedTmapTravelMinutes(
            totalTimeSeconds = totalTimeSeconds,
            maxTravelMinutes = maxTravelMinutes,
            travelMode = request.travelMode,
        )
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

internal fun validatedTmapTravelMinutes(
    totalTimeSeconds: Double,
    maxTravelMinutes: Int,
    travelMode: ScheduleTravelMode,
): Int {
    require(
        travelMode in setOf(
            ScheduleTravelMode.CAR,
            ScheduleTravelMode.ETC,
            ScheduleTravelMode.WALK,
            ScheduleTravelMode.TRANSIT,
        )
    ) {
        "지원하지 않는 TMAP 이동 수단입니다."
    }
    val minutes = EtaTravelTimePolicy.normalizeMinutes(totalTimeSeconds / 60.0, maxTravelMinutes)
        ?: error("TMAP 이동 시간은 유한한 양수이며 제품 상한 이하여야 합니다.")
    return minutes
}
