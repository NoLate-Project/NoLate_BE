package com.noLate.schedule.application

import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import java.time.Instant

/**
 * ETA 계산값과 그 값이 어디에서 왔는지를 함께 전달한다.
 *
 * [fetchedAt]은 외부 provider가 실제 응답한 경우에만 존재한다. 애플리케이션이 계산을 시도한
 * 시각(lastCheckedAt/evaluatedAt)과 provider 데이터 취득 시각을 같다고 가정하지 않는다.
 */
data class TrafficResult(
    val travelMinutes: Int,
    val source: TrafficSource,
    val fetchedAt: Instant? = null,
    val stale: Boolean,
    val failureReason: String? = null,
) {
    init {
        require(travelMinutes > 0) { "travelMinutes는 0보다 커야 합니다." }
        require(source != TrafficSource.LIVE_PROVIDER || fetchedAt != null) {
            "LIVE_PROVIDER 결과에는 fetchedAt이 필요합니다."
        }
        require(source == TrafficSource.LIVE_PROVIDER || fetchedAt == null) {
            "fallback 결과에는 provider fetchedAt을 기록할 수 없습니다."
        }
    }
}

data class TrafficRequest(
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val travelMode: ScheduleTravelMode,
    val fallbackTravelMinutes: Int,
    val selectedRouteJson: String? = null,
    val selectedRouteTravelMinutes: Int? = null,
    /** TMAP 도로 경로의 searchOption 또는 quick-share providerRouteOption. */
    val selectedRouteOption: String? = null,
    /** 저장된 대중교통 여정 전체/부분 JSON. 동일 여정 갱신 가능성 판단에 사용한다. */
    val selectedTransitItineraryJson: String? = null,
    /** 일정/목적지 변경처럼 live 재계산 자체를 막아야 하는 이유. */
    val liveRefreshBlockedReason: String? = null,
    val maxTravelMinutes: Int = EtaTravelTimePolicy.DEFAULT_MAX_TRAVEL_MINUTES,
) {
    init {
        EtaTravelTimePolicy.requireValidMaximum(maxTravelMinutes)
        require(EtaTravelTimePolicy.isValid(fallbackTravelMinutes, maxTravelMinutes)) {
            "fallbackTravelMinutes는 1~$maxTravelMinutes 사이여야 합니다."
        }
        require(
            selectedRouteTravelMinutes == null ||
                EtaTravelTimePolicy.isValid(selectedRouteTravelMinutes, maxTravelMinutes)
        ) {
            "selectedRouteTravelMinutes는 null이거나 1~$maxTravelMinutes 사이여야 합니다."
        }
    }
}

interface TrafficClient {
    fun getTravelMinutes(request: TrafficRequest): TrafficResult
}

object TrafficFailureReasons {
    const val PROVIDER_DISABLED =
        "PROVIDER_DISABLED: 실시간 ETA 공급자가 비활성화되어 저장된 ETA를 사용합니다."
    const val PROVIDER_TIMEOUT =
        "PROVIDER_TIMEOUT: 실시간 ETA 공급자 응답 시간이 초과되었습니다."
    const val PROVIDER_HTTP_ERROR =
        "PROVIDER_HTTP_ERROR: 실시간 ETA 공급자 요청을 처리할 수 없습니다."
    const val PROVIDER_INVALID_RESPONSE =
        "PROVIDER_INVALID_RESPONSE: 실시간 ETA 공급자 응답을 해석할 수 없습니다."
    const val PROVIDER_UNAVAILABLE =
        "PROVIDER_UNAVAILABLE: 실시간 ETA 공급자에 연결할 수 없습니다."
    const val SELECTED_ROUTE_OPTION_MISSING =
        "SELECTED_ROUTE_OPTION_MISSING: 선택 경로 옵션이 없어 같은 경로를 실시간으로 다시 조회할 수 없습니다."
    const val TRANSIT_ITINERARY_REFRESH_UNSUPPORTED =
        "TRANSIT_ITINERARY_REFRESH_UNSUPPORTED: 대중교통 여정은 동일 itinerary로 다시 조회할 수 없습니다."
    const val UNSUPPORTED_TRAVEL_MODE =
        "UNSUPPORTED_TRAVEL_MODE: 선택한 이동 수단은 실시간 ETA 조회를 지원하지 않습니다."
    const val ROUTE_STALE =
        "ROUTE_STALE: 저장된 경로가 변경 전 일정 시각 또는 목적지를 기준으로 합니다."
    const val ETA_FALLBACK =
        "ETA_FALLBACK: 실시간 ETA를 사용할 수 없어 저장된 ETA를 사용합니다."

    fun unsupportedMode(mode: ScheduleTravelMode): String =
        "UNSUPPORTED_TRAVEL_MODE: ${mode.name} 이동 수단은 실시간 ETA 조회를 지원하지 않습니다."
}

/**
 * DB와 공개 상태 API에는 provider 예외 원문 대신 안정된 reason code만 남긴다.
 */
fun sanitizeTrafficFailureReason(reason: String?): String? {
    if (reason == null) return null
    val code = reason
        .substringBefore(':')
        .trim()
        .takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{1,63}")) }
        ?: return TrafficFailureReasons.ETA_FALLBACK
    return when (code) {
        "PROVIDER_DISABLED" -> TrafficFailureReasons.PROVIDER_DISABLED
        "PROVIDER_TIMEOUT" -> TrafficFailureReasons.PROVIDER_TIMEOUT
        "PROVIDER_HTTP_ERROR" -> TrafficFailureReasons.PROVIDER_HTTP_ERROR
        "PROVIDER_INVALID_RESPONSE" -> TrafficFailureReasons.PROVIDER_INVALID_RESPONSE
        "PROVIDER_UNAVAILABLE" -> TrafficFailureReasons.PROVIDER_UNAVAILABLE
        "SELECTED_ROUTE_OPTION_MISSING" -> TrafficFailureReasons.SELECTED_ROUTE_OPTION_MISSING
        "TRANSIT_ITINERARY_REFRESH_UNSUPPORTED" ->
            TrafficFailureReasons.TRANSIT_ITINERARY_REFRESH_UNSUPPORTED
        "UNSUPPORTED_TRAVEL_MODE" -> TrafficFailureReasons.UNSUPPORTED_TRAVEL_MODE
        "ROUTE_STALE" -> TrafficFailureReasons.ROUTE_STALE
        else -> TrafficFailureReasons.ETA_FALLBACK
    }
}

fun TrafficRequest.fallbackResult(reason: String): TrafficResult {
    val selectedMinutes = selectedRouteTravelMinutes
        ?.takeIf { it == fallbackTravelMinutes }
    return TrafficResult(
        travelMinutes = selectedMinutes ?: fallbackTravelMinutes,
        source = if (selectedMinutes != null) {
            TrafficSource.SELECTED_ROUTE
        } else {
            TrafficSource.SAVED_FALLBACK
        },
        stale = true,
        failureReason = sanitizeTrafficFailureReason(reason),
    )
}
