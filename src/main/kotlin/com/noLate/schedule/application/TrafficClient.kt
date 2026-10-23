package com.noLate.schedule.application

import com.noLate.eta.domain.TransitFirstBoardingObservation
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.eta.domain.TransitWalkingGeometry
import com.noLate.eta.domain.TransitWalkingScope

import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import java.time.Instant

enum class TransitRouteProvenance {
    SELECTED_ROUTE_PRESERVED,
    ODSAY_ALTERNATIVE_ROUTE,
}

enum class TransitTimingBasis {
    FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE,
    /** 동일 환승 열차의 신선한 승차 또는 하차역 도착 예측을 일부 구간에 반영했다. */
    FIRST_BOARDING_REALTIME_TRANSFER_OBSERVED,
    /** 환승편은 관측됐지만 첫 승차는 시간표뿐이라 출발 알림에는 채택하지 않는다. */
    FIRST_BOARDING_TIMETABLE_TRANSFER_OBSERVED,
    FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN,
    /** 첫 차량 도착만 실시간이며 나머지 구간은 경로 추정값이다. */
    FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE,
    ROUTE_ESTIMATE_ONLY,
    TIMETABLE_ONLY,
    TIMETABLE_TRANSFER_UNKNOWN,
}

/** 재조회에서 실제 ETA 계산에 사용한 대중교통 여정의 표시용 스냅샷. */
data class EffectiveTransitRoute(
    val provider: String,
    /** 같은 노선/승하차/방향 조합을 안정적으로 식별하는 SHA-256 값이다. */
    val identity: String,
    val departureAt: Instant,
    val arrivalAt: Instant,
    val totalMinutes: Int,
    val segments: List<EffectiveTransitRouteSegment>,
) {
    init {
        require(provider.isNotBlank()) { "effective transit provider는 비어 있을 수 없습니다." }
        require(identity.matches(SHA_256_HEX_PATTERN)) {
            "effective transit identity는 SHA-256 hex여야 합니다."
        }
        require(!departureAt.isAfter(arrivalAt)) {
            "effective transit departureAt은 arrivalAt보다 늦을 수 없습니다."
        }
        require(totalMinutes > 0) { "effective transit totalMinutes는 0보다 커야 합니다." }
        require(segments.isNotEmpty()) { "effective transit segments는 비어 있을 수 없습니다." }
        require(segments.map(EffectiveTransitRouteSegment::sequence).distinct().size == segments.size) {
            "effective transit segment sequence는 중복될 수 없습니다."
        }
    }

    private companion object {
        val SHA_256_HEX_PATTERN = Regex("[0-9a-f]{64}")
    }
}

enum class EffectiveTransitRouteSegmentKind {
    WALK,
    BUS,
    SUBWAY,
    TRAIN,
    EXPRESS_BUS,
    INTERCITY_BUS,
    COACH,
    ETC,
}

enum class EffectiveTransitRideTimingBasis {
    TIMETABLE,
    FIRST_BOARDING_REALTIME_ADJUSTED_TIMETABLE,
    TRANSFER_BOARDING_REALTIME_ADJUSTED_TIMETABLE,
    TRANSFER_ALIGHTING_REALTIME,
}

/** 비교용 원본 시간표와 이번 계산의 승하차 예측을 분리한다. 미래 환승은 실시간이 아니다. */
data class EffectiveTransitRideTiming(
    val timetable: TransitLegTimetable,
    val predictedBoardingAt: Instant,
    val predictedArrivalAt: Instant,
    val basis: EffectiveTransitRideTimingBasis,
    val observationSourceUpdatedAt: Instant? = null,
) {
    init {
        require(predictedArrivalAt.isAfter(predictedBoardingAt))
        // 첫 열차의 관측 지연만 승하차 양쪽에 한 번 적용한다. 운행시간을 임의 보정하지 않는다.
        val shift = java.time.Duration.between(timetable.boardingAt, predictedBoardingAt)
        if (basis != EffectiveTransitRideTimingBasis.TRANSFER_ALIGHTING_REALTIME) {
            require(predictedArrivalAt == timetable.arrivalAt.plus(shift))
        } else require(shift.isZero)
        require(basis != EffectiveTransitRideTimingBasis.TIMETABLE || shift.isZero)
        require(
            (basis == EffectiveTransitRideTimingBasis.TRANSFER_ALIGHTING_REALTIME ||
                basis == EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_ADJUSTED_TIMETABLE) ==
                (observationSourceUpdatedAt != null)
        )
    }
}

data class EffectiveTransitRouteSegment(
    val sequence: Int,
    val kind: EffectiveTransitRouteSegmentKind,
    val durationMinutes: Int,
    val waitingMinutes: Int? = null,
    val lineName: String? = null,
    val fromName: String? = null,
    val toName: String? = null,
    val directionName: String? = null,
    /** 기존 스냅샷에는 없으므로 null을 유지한다. 임의로 접근/환승 경계를 추정하지 않는다. */
    val walkingScope: TransitWalkingScope? = null,
    /** 공급자가 초 단위로 제공한 WALK 값만 전달한다. 분 값에서 역으로 만들지 않는다. */
    val durationSeconds: Long? = null,
    val walkingGeometry: TransitWalkingGeometry? = null,
    /** 기존/추정 경로에는 null. 반올림한 분 값으로 열차 시각을 역산하지 않는다. */
    val rideTiming: EffectiveTransitRideTiming? = null,
    val walkingTiming: com.noLate.eta.domain.TransitWalkingTimingEvidence? = null,
    val transferTiming: com.noLate.eta.domain.TransitTransferTimingEvidence? = null,
) {
    init {
        require(sequence >= 0) { "effective transit segment sequence는 0 이상이어야 합니다." }
        require(durationMinutes >= 0) { "effective transit segment durationMinutes는 0 이상이어야 합니다." }
        require(waitingMinutes == null || waitingMinutes >= 0) {
            "effective transit segment waitingMinutes는 null 또는 0 이상이어야 합니다."
        }
        require(kind == EffectiveTransitRouteSegmentKind.WALK ||
            (walkingScope == null && durationSeconds == null && walkingGeometry == null))
        require(rideTiming == null || kind == EffectiveTransitRouteSegmentKind.SUBWAY)
        require(walkingTiming == null || (kind == EffectiveTransitRouteSegmentKind.WALK &&
            walkingTiming.movementSeconds == (durationSeconds ?: durationMinutes.toLong() * 60)))
        require(transferTiming == null || kind == EffectiveTransitRouteSegmentKind.SUBWAY || kind == EffectiveTransitRouteSegmentKind.BUS)
        durationSeconds?.let {
            require(it >= 0 && it / 60 + (if (it % 60 == 0L) 0 else 1) == durationMinutes.toLong())
        }
    }
}

/**
 * ETA 계산값과 그 값이 어디에서 왔는지를 함께 전달한다.
 *
 * [fetchedAt]은 외부 provider가 실제 응답한 경우에만 존재한다. 애플리케이션이 계산을 시도한
 * 시각(lastCheckedAt/evaluatedAt)과 provider 데이터 취득 시각을 같다고 가정하지 않는다.
 * 재조회는 성공했으나 시각 근거가 부족한 fallback도 이 시각을 보존한다. 조회 성공만으로
 * [source]를 LIVE/TIMETABLE로 올리거나 [accepted]로 취급하지 않는다.
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
    /** 첫 승차 실시간 정보와 나머지 구간의 시간표·추정값을 구분하는 계산 근거다. */
    val transitTimingBasis: TransitTimingBasis? = null,
    /**
     * 첫 탑승 대기시간의 설명용 구성요소. [travelMinutes]에 이미 포함되어 있으므로 호출자는
     * 이 값을 총 ETA에 다시 더하면 안 된다.
     */
    val firstBoardingWaitMinutes: Int? = null,
    /** 저장 경로가 아니라 이번 계산에서 실제 채택·진단한 여정이다. */
    val effectiveTransitRoute: EffectiveTransitRoute? = null,
    /** 별도로 검증한 첫 차량. 전체 ETA 채택이나 총 소요시간에 합산된 대기를 의미하지 않는다. */
    val firstBoardingObservation: TransitFirstBoardingObservation? = null,
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
        require(providerSource || fetchedAt == null || (stale && !failureReason.isNullOrBlank())) {
            "fallback의 provider fetchedAt은 실패 사유가 있는 저신뢰도 조회 진단에만 기록합니다."
        }
        require(
            recommendedDepartureAt == null ||
                predictedArrivalAt == null ||
                !recommendedDepartureAt.isAfter(predictedArrivalAt)
        ) {
            "추천 출발시각은 예측 도착시각보다 늦을 수 없습니다."
        }
        require(firstBoardingWaitMinutes == null || firstBoardingWaitMinutes >= 0) {
            "firstBoardingWaitMinutes는 null 또는 0 이상이어야 합니다."
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
    const val TRANSIT_INTERCITY_TIMETABLE_UNAVAILABLE =
        "TRANSIT_INTERCITY_TIMETABLE_UNAVAILABLE: 시외 구간의 실제 운행시각을 확인해야 합니다."
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
    const val TRANSIT_TRAIN_NOT_YET_OBSERVED =
        "TRANSIT_TRAIN_NOT_YET_OBSERVED: 시간표의 미래 열차가 현재 실시간 목록에서 아직 확인되지 않았습니다."
    const val TRANSIT_ARRIVAL_SOURCE_STALE =
        "TRANSIT_ARRIVAL_SOURCE_STALE: 실시간 차량의 원천 관측시각이 오래되어 보정에 사용할 수 없습니다."
    const val TRANSIT_ARRIVAL_SOURCE_UNVERIFIED =
        "TRANSIT_ARRIVAL_SOURCE_UNVERIFIED: 실시간 차량의 원천 관측시각을 검증할 수 없습니다."
    const val TRANSIT_ARRIVAL_API_ERROR =
        "TRANSIT_ARRIVAL_API_ERROR: 실시간 도착정보 API 요청이 실패했습니다."
    const val TRANSIT_ARRIVAL_RATE_LIMITED =
        "TRANSIT_ARRIVAL_RATE_LIMITED: 실시간 도착정보 API 호출 한도에 도달했습니다."
    const val TRANSIT_ARRIVAL_API_TIMEOUT =
        "TRANSIT_ARRIVAL_API_TIMEOUT: 실시간 도착정보 API 응답 시간이 초과되었습니다."
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
    const val TRANSIT_TRANSFER_REALTIME_DELAY =
        "TRANSIT_TRANSFER_REALTIME_DELAY: 선택한 환승 열차의 실시간 지연으로 시간표 도착시각을 사용할 수 없습니다."
    const val TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY =
        "TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY: 경로 추정시각만 있어 전체 여정의 도착시각을 확정할 수 없습니다."
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
        "TRANSIT_INTERCITY_TIMETABLE_UNAVAILABLE" -> TrafficFailureReasons.TRANSIT_INTERCITY_TIMETABLE_UNAVAILABLE
        "TRANSIT_TRAIN_NOT_YET_OBSERVED" -> TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED
        "TRANSIT_ARRIVAL_SOURCE_STALE" -> TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE
        "TRANSIT_ARRIVAL_SOURCE_UNVERIFIED" -> TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_UNVERIFIED
        "TRANSIT_ARRIVAL_API_ERROR" -> TrafficFailureReasons.TRANSIT_ARRIVAL_API_ERROR
        "TRANSIT_ARRIVAL_RATE_LIMITED" -> TrafficFailureReasons.TRANSIT_ARRIVAL_RATE_LIMITED
        "TRANSIT_ARRIVAL_API_TIMEOUT" -> TrafficFailureReasons.TRANSIT_ARRIVAL_API_TIMEOUT
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
        "TRANSIT_TRANSFER_REALTIME_DELAY" ->
            TrafficFailureReasons.TRANSIT_TRANSFER_REALTIME_DELAY
        "TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY" ->
            TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY
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
