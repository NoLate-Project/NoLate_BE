package com.noLate.schedule.application

import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import java.time.Instant

enum class TransitRouteProvenance {
    SELECTED_ROUTE_PRESERVED,
    ODSAY_ALTERNATIVE_ROUTE,
}

enum class TransitTimingBasis {
    FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE,
    FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN,
    TIMETABLE_ONLY,
    TIMETABLE_TRANSFER_UNKNOWN,
}

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
    /**
     * 시간표가 있는 대중교통은 일정 시각에서 소요시간을 단순 역산한 값과 실제 탑승 가능한
     * 출발시각이 다를 수 있다. provider가 안전 출발시각을 계산한 경우 그 결정을 보존한다.
     */
    val recommendedDepartureAt: Instant? = null,
    /** provider 여정과 실시간 overlay로 예측한 목적지 도착시각. */
    val predictedArrivalAt: Instant? = null,
    /** 선택 경로를 보존했는지, 같은 ODsay 응답의 대체 여정으로 전환했는지 나타낸다. */
    val transitRouteProvenance: TransitRouteProvenance? = null,
    /** 첫 승차만 실시간이고 미래 환승은 시간표임을 숨기지 않는 계산 근거다. */
    val transitTimingBasis: TransitTimingBasis? = null,
) {
    /**
     * 추천 출발 판단에 정상 provider 결과로 채택할 수 있는지 나타낸다.
     * provider provenance를 보존한 degraded 진단 결과는 source가 LIVE/TIMETABLE이어도 false다.
     */
    val accepted: Boolean
        get() = !stale && failureReason == null

    init {
        require(travelMinutes > 0) { "travelMinutes는 0보다 커야 합니다." }
        val providerSource = source == TrafficSource.LIVE_PROVIDER ||
            source == TrafficSource.TIMETABLE_PROVIDER
        require(!providerSource || fetchedAt != null) {
            "provider ETA 결과에는 fetchedAt이 필요합니다."
        }
        require(providerSource || fetchedAt == null) {
            "fallback 결과에는 provider fetchedAt을 기록할 수 없습니다."
        }
        require(
            recommendedDepartureAt == null ||
                predictedArrivalAt == null ||
                !recommendedDepartureAt.isAfter(predictedArrivalAt)
        ) {
            "추천 출발시각은 예측 도착시각보다 늦을 수 없습니다."
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
    /** 이 재계산을 수행하는 기준 시각. 실시간 도착정보의 catchable 여부 판단에 사용한다. */
    val evaluatedAt: Instant = Instant.now(),
    /**
     * 직전 계산 또는 사용자가 선택한 경로가 권장한 출발 시각.
     *
     * 대중교통 도착정보는 "지금부터 몇 분 뒤" 값이므로, 아직 출발 시각이 멀다면 현재
     * 도착 차량을 잘못 더하지 않도록 승차 가능 차량을 고르는 기준으로 사용한다.
     */
    val plannedDepartureAt: Instant? = null,
    /** 일정 시작시각 또는 사용자의 도착 여유를 차감한 도착 마감시각. */
    val targetArrivalAt: Instant? = null,
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

/**
 * TMAP 활성화 여부에 따라 선택되는 도로/도보 provider.
 *
 * 대중교통은 [TrafficClient]의 mode-aware 구현이 별도 실시간 도착 정책으로 처리한다.
 */
interface TrafficProviderClient {
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
    const val TRANSIT_ROUTE_METADATA_MISSING =
        "TRANSIT_ROUTE_METADATA_MISSING: 저장된 경로에서 첫 승차 구간을 식별할 수 없습니다."
    const val TRANSIT_ARRIVAL_UNAVAILABLE =
        "TRANSIT_ARRIVAL_UNAVAILABLE: 첫 승차 구간의 실시간 도착정보를 조회할 수 없습니다."
    const val TRANSIT_ARRIVAL_OUT_OF_HORIZON =
        "TRANSIT_ARRIVAL_OUT_OF_HORIZON: 계획한 승차 시각 이후의 도착 차량을 확인할 수 없습니다."
    const val TRANSIT_JOURNEY_PROVIDER_UNAVAILABLE =
        "TRANSIT_JOURNEY_PROVIDER_UNAVAILABLE: 선택한 대중교통 경로 공급자로 여정을 다시 조회할 수 없습니다."
    const val TRANSIT_SELECTED_ROUTE_NOT_FOUND =
        "TRANSIT_SELECTED_ROUTE_NOT_FOUND: 재조회 결과에서 사용자가 선택한 대중교통 경로를 찾을 수 없습니다."
    const val TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE =
        "TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE: 현재 확인된 동일 경로 차량으로는 일정 시각까지 도착할 수 없습니다."
    const val TRANSIT_TRANSFER_MISSED =
        "TRANSIT_TRANSFER_MISSED: 첫 승차 지연으로 선택 여정의 환승 차량을 탈 수 없습니다."
    const val TRANSIT_TRANSFER_TIMING_UNKNOWN =
        "TRANSIT_TRANSFER_TIMING_UNKNOWN: 환승 시간표 또는 안전 여유가 불충분해 환승 가능 여부를 확정할 수 없습니다."
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
        "TRANSIT_ROUTE_METADATA_MISSING" ->
            TrafficFailureReasons.TRANSIT_ROUTE_METADATA_MISSING
        "TRANSIT_ARRIVAL_UNAVAILABLE" ->
            TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE
        "TRANSIT_ARRIVAL_OUT_OF_HORIZON" ->
            TrafficFailureReasons.TRANSIT_ARRIVAL_OUT_OF_HORIZON
        "TRANSIT_JOURNEY_PROVIDER_UNAVAILABLE" ->
            TrafficFailureReasons.TRANSIT_JOURNEY_PROVIDER_UNAVAILABLE
        "TRANSIT_SELECTED_ROUTE_NOT_FOUND" ->
            TrafficFailureReasons.TRANSIT_SELECTED_ROUTE_NOT_FOUND
        "TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE" ->
            TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE
        "TRANSIT_TRANSFER_MISSED" -> TrafficFailureReasons.TRANSIT_TRANSFER_MISSED
        "TRANSIT_TRANSFER_TIMING_UNKNOWN" ->
            TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN
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
