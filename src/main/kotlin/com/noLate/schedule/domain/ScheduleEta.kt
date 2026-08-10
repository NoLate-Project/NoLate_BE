package com.noLate.schedule.domain

import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.TransitRouteProvenance
import com.noLate.schedule.application.TransitTimingBasis
import java.time.Instant

enum class TrafficSource {
    LIVE_PROVIDER,
    /** 방금 조회한 시간표 기반 전체 여정이며 차량 실시간 도착정보는 적용되지 않은 상태다. */
    TIMETABLE_PROVIDER,
    SELECTED_ROUTE,
    SAVED_FALLBACK,
}

/**
 * 도착 예측이 어떤 시간축을 기준으로 만들어졌는지 구분한다.
 *
 * PROVIDER_ABSOLUTE는 시간표/실시간 차량 overlay가 반환한 절대 도착시각이고,
 * DEPARTURE_ANCHORED_DURATION은 출발 완료 시각에 provider 이동시간을 더한 예측이다.
 */
enum class EtaPredictionBasis {
    PROVIDER_ABSOLUTE,
    DEPARTURE_ANCHORED_DURATION,
}

/**
 * ETA 계산 규칙의 낮은 cardinality 버전이다.
 *
 * 배포 커밋이나 provider request id처럼 무한히 늘어나는 값을 metric tag로 사용하지 않고,
 * 의미 있는 계산 규칙 변경 때만 새 enum 값을 추가한다. 출발 snapshot에 동결되므로 이후
 * 일정이나 계산 코드가 바뀌어도 과거 ground-truth 표본을 같은 규칙끼리 비교할 수 있다.
 */
enum class EtaAlgorithmVersion {
    /** Historical cohort before live route remapping and transfer-confidence margin hardening. */
    TRANSIT_REALTIME_V2,
    /** Historical timetable cohort before route remapping and transfer-confidence hardening. */
    TRANSIT_TIMETABLE_V2,
    TRANSIT_REALTIME_V3,
    TRANSIT_TIMETABLE_V3,
    ROAD_LIVE_V1,
    SELECTED_ROUTE_V1,
    SAVED_FALLBACK_V1,
    UNKNOWN;

    companion object {
        fun infer(
            source: TrafficSource,
            travelMode: ScheduleTravelMode?,
        ): EtaAlgorithmVersion = when {
            travelMode == ScheduleTravelMode.TRANSIT &&
                source == TrafficSource.LIVE_PROVIDER -> TRANSIT_REALTIME_V3
            travelMode == ScheduleTravelMode.TRANSIT &&
                source == TrafficSource.TIMETABLE_PROVIDER -> TRANSIT_TIMETABLE_V3
            source == TrafficSource.LIVE_PROVIDER -> ROAD_LIVE_V1
            source == TrafficSource.SELECTED_ROUTE -> SELECTED_ROUTE_V1
            source == TrafficSource.SAVED_FALLBACK -> SAVED_FALLBACK_V1
            else -> UNKNOWN
        }
    }
}

/** 목표 도착시각을 기준으로 한 예측/실제 정시 판정의 2x2 결과. */
enum class EtaOnTimeOutcome {
    PREDICTED_ON_TIME_ACTUAL_ON_TIME,
    /** 사용자에게 안전하다고 안내했지만 실제로는 늦은 가장 위험한 false-safe 표본. */
    PREDICTED_ON_TIME_ACTUAL_LATE,
    PREDICTED_LATE_ACTUAL_ON_TIME,
    PREDICTED_LATE_ACTUAL_LATE;

    companion object {
        fun of(predictedOnTime: Boolean, actualOnTime: Boolean): EtaOnTimeOutcome = when {
            predictedOnTime && actualOnTime -> PREDICTED_ON_TIME_ACTUAL_ON_TIME
            predictedOnTime -> PREDICTED_ON_TIME_ACTUAL_LATE
            actualOnTime -> PREDICTED_LATE_ACTUAL_ON_TIME
            else -> PREDICTED_LATE_ACTUAL_LATE
        }
    }
}

/** 낮은 cardinality로 고정한 ETA provider 식별자. 운영 metric tag로 안전하게 사용할 수 있다. */
enum class EtaProviderId {
    ODSAY_TRANSIT,
    TMAP,
    SELECTED_ROUTE,
    SAVED_FALLBACK,
    UNKNOWN;

    companion object {
        fun infer(source: TrafficSource, travelMode: ScheduleTravelMode?): EtaProviderId = when {
            travelMode == ScheduleTravelMode.TRANSIT &&
                source in setOf(TrafficSource.LIVE_PROVIDER, TrafficSource.TIMETABLE_PROVIDER) ->
                ODSAY_TRANSIT

            source == TrafficSource.LIVE_PROVIDER -> TMAP
            source == TrafficSource.SELECTED_ROUTE -> SELECTED_ROUTE
            source == TrafficSource.SAVED_FALLBACK -> SAVED_FALLBACK
            else -> UNKNOWN
        }
    }
}

enum class ScheduleEtaConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

/**
 * 한 회원의 현재 출발 판단에 필요한 ETA 상태.
 *
 * 준비 시간과 안전 버퍼는 아직 제품 저장 모델이 없으므로 null을 반환한다. 0으로 채우면
 * 사용자가 명시적으로 0분을 선택했다는 잘못된 의미가 생기기 때문이다.
 */
data class ScheduleDepartureEtaStatusDto(
    val scheduleId: Long,
    val travelMinutes: Int?,
    val recommendedDepartureAt: Instant?,
    val evaluatedAt: Instant,
    val liveFetchedAt: Instant?,
    /** 이동 계획 자체가 없으면 출처도 없으므로 null이다. */
    val source: TrafficSource?,
    val stale: Boolean,
    /** 계산 가능한 ETA가 없으면 신뢰도도 평가하지 않아 null이다. */
    val confidence: ScheduleEtaConfidence?,
    val failureReason: String?,
    val lastTrafficChangeMinutes: Int?,
    val lastChangedAt: Instant?,
    val nextCheckAt: Instant?,
    val preparationMinutes: Int?,
    val preparationStartAt: Instant?,
    val safetyBufferMinutes: Int?,
    val timeZone: String,
    /** Provider 여정/실시간 overlay가 계산한 절대 목적지 도착시각. */
    val predictedArrivalAt: Instant? = null,
    /** null은 절대 도착시각을 계산하지 못한 상태다. */
    val onTimeArrivalPossible: Boolean? = null,
    /** 이번 ETA에서 실제 사용한 선택/대체 대중교통 경로의 출처다. */
    val transitRouteProvenance: TransitRouteProvenance? = null,
    /** 첫 승차 실시간 도착과 미래 환승 시간표를 어떤 방식으로 결합했는지 나타낸다. */
    val transitTimingBasis: TransitTimingBasis? = null,
    /** 총 이동시간에 이미 포함된 첫 탑승 대기시간이다. */
    val firstBoardingWaitMinutes: Int? = null,
    /** 현재 채택한 여정이 사용자가 저장한 선택 경로와 다른 대체 여정인지 여부다. */
    val routeChanged: Boolean = false,
    /** 이번 정상 ETA에서 실제 채택한 대중교통 여정. 저장 지도 경로와 별개다. */
    val effectiveTransitRoute: EffectiveTransitRoute? = null,
    /** 이 ETA 평가가 약속한 다음 갱신 시각. push retry용 [nextCheckAt]과 분리된다. */
    val etaRefreshDueAt: Instant? = null,
)
