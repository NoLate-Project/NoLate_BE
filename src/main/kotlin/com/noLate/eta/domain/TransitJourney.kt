package com.noLate.eta.domain

import com.noLate.transit.domain.TransitCityCodeNamespace
import java.time.Instant
import java.time.Duration
import java.util.Locale

enum class TransitLegMode {
    WALK,
    BUS,
    SUBWAY,
    TRAIN,
    EXPRESS_BUS,
    INTERCITY_BUS,
    COACH,
    ETC;

    val isRide: Boolean get() = this in setOf(BUS, SUBWAY, TRAIN, EXPRESS_BUS, INTERCITY_BUS, COACH)
    val isIntercity: Boolean get() = this in setOf(TRAIN, EXPRESS_BUS, INTERCITY_BUS, COACH)
}

data class TransitStop(
    val providerStopId: String? = null,
    val localStopId: String? = null,
    val cityCode: String? = null,
    val cityCodeNamespace: TransitCityCodeNamespace = TransitCityCodeNamespace.UNKNOWN,
    val providerCode: String? = null,
    val arsId: String? = null,
    val name: String? = null,
) {
    /**
     * 공급자 내부 정류장 ID는 provider namespace와 결합해야
     * 서로 다른 API가 같은 ID를 사용해도 동일 정류장으로 오인하지 않는다.
     */
    fun stableIds(providerId: String?): Set<String> = buildSet {
        providerScopedStopId(providerId, providerStopId)?.let(::add)
        arsId?.filter(Char::isDigit)?.takeIf(String::isNotBlank)?.let { add("ARS:$it") }
        localStopId?.normalizedId()?.let { localId ->
            add(cityCode?.normalizedId()?.let { "CITY:$it:$localId" } ?: "LOCAL:$localId")
        }
    }
}

data class TransitLine(
    val providerRouteId: String? = null,
    val localRouteId: String? = null,
    val cityCode: String? = null,
    val providerCode: String? = null,
    val name: String? = null,
    /**
     * 선택 여정의 열차 종별. 노선명에 급행 표기가 없다는 사실은 일반열차의 증거가
     * 아니므로 기본값은 반드시 UNKNOWN이다.
     */
    val serviceClass: TransitServiceClass = TransitServiceClass.UNKNOWN,
    /** 공식 mode별 코드와 노선명이 일치할 때만 연결한다. Tmap 내부 ID는 변환하지 않는다. */
    val seoulSubwayLine: SeoulSubwayLine? = null,
)

enum class TransitServiceClass {
    LOCAL,
    EXPRESS,
    UNKNOWN,
}

data class TransitJourneyLeg(
    val sequence: Int,
    val mode: TransitLegMode,
    val durationMinutes: Int,
    val waitingMinutes: Int? = null,
    val scheduledDepartureAt: Instant? = null,
    val scheduledArrivalAt: Instant? = null,
    val from: TransitStop? = null,
    val to: TransitStop? = null,
    val line: TransitLine? = null,
    val directionName: String? = null,
    val directionCode: String? = null,
    val timingBasis: TransitLegTimingBasis = TransitLegTimingBasis.UNKNOWN,
    /** 공급자가 초 단위로 준 구간 소요시간. 실제 열차 운행시각의 증거는 아니다. */
    val durationSeconds: Long? = null,
    /** 공급자가 순서대로 제공한 첫 다음 역. 이름으로 인접 관계를 추정하지 않는다. */
    val nextStopName: String? = null,
    val timetable: TransitLegTimetable? = null,
    val walkingGeometry: TransitWalkingGeometry? = null,
    /** 원본 시간표와 구분한 첫 열차 관측. 지연으로 되살린 후보의 시간축에만 적용한다. */
    val firstBoardingObservation: TransitFirstBoardingObservation? = null,
    val walkingTiming: TransitWalkingTimingEvidence? = null,
) {
    init {
        require(walkingGeometry == null || mode == TransitLegMode.WALK)
        require(walkingTiming == null || (mode == TransitLegMode.WALK && walkingTiming.movementSeconds == movementDurationSeconds))
        timetable?.let {
            val observation = firstBoardingObservation
            require(timingBasis == TransitLegTimingBasis.TIMETABLE)
            if (observation == null) {
                require(scheduledArrivalAt == it.arrivalAt)
                require(scheduledDepartureAt != null && !scheduledDepartureAt.isAfter(it.boardingAt))
            } else {
                require(mode == TransitLegMode.SUBWAY && observation.provider == "seoul-openapi")
                require(observation.trainNumber in setOfNotNull(it.trainNumber, it.realtimeTrainNumber))
                require(observation.lineName == it.line.displayName)
                require(observation.expectedBoardingAt >= it.boardingAt)
                require(scheduledDepartureAt != null && scheduledDepartureAt <= observation.readyAt)
                require(scheduledArrivalAt == it.arrivalAt.plus(
                    Duration.between(it.boardingAt, observation.expectedBoardingAt)))
            }
        }
        require(firstBoardingObservation == null || timetable != null)
        // 초 값이 표시용 분 값과 충돌하면 서로 다른 길이로 승차·환승을 판단하지 않는다.
        durationSeconds?.let { seconds ->
            require(seconds >= 0 && (seconds / 60 + if (seconds % 60 == 0L) 0 else 1) == durationMinutes.toLong()) {
                "구간 소요시간의 초 값과 올림한 분 값이 일치해야 합니다."
            }
        }
    }

    /** ODsay처럼 분만 제공하는 공급자는 기존 해상도를 유지한다. 시각값으로 초를 추정하지 않는다. */
    val movementDurationSeconds: Long
        get() = durationSeconds ?: durationMinutes.toLong() * 60

    val isRide: Boolean
        get() = mode.isRide
}

/**
 * 구간 출발·도착시각의 근거다.
 *
 * TIMETABLE은 실제 운행 시간표,
 * ROUTE_ESTIMATE는 경로 소요시간을 기준으로 계산한 표시용 시각,
 * UNKNOWN은 시각 근거를 확인할 수 없는 경우다.
 */
enum class TransitLegTimingBasis {
    TIMETABLE,
    ROUTE_ESTIMATE,
    UNKNOWN,
}

data class TransitJourney(
    val provider: String,
    val requestedDepartureAt: Instant,
    val departureAt: Instant,
    val arrivalAt: Instant,
    val totalMinutes: Int,
    val legs: List<TransitJourneyLeg>,
    val fetchedAt: Instant,
) {
    val rideLegs: List<TransitJourneyLeg>
        get() = legs.filter(TransitJourneyLeg::isRide)

    /** 공급자와 무관하게 실제 승차 구간 앞·사이·뒤로 구분한다. 소요시간은 변경하지 않는다. */
    fun walkingScopeAt(index: Int): TransitWalkingScope? {
        if (legs[index].mode != TransitLegMode.WALK) return null
        val firstRide = legs.indexOfFirst(TransitJourneyLeg::isRide)
        val lastRide = legs.indexOfLast(TransitJourneyLeg::isRide)
        return when {
            firstRide < 0 -> TransitWalkingScope.UNKNOWN
            index < firstRide -> TransitWalkingScope.ACCESS
            index > lastRide -> TransitWalkingScope.EGRESS
            else -> TransitWalkingScope.TRANSFER
        }
    }
}

data class TransitJourneySearchRequest(
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val departureAt: Instant,
    val maxTravelMinutes: Int,
)

data class TransitRideSignature(
    val mode: TransitLegMode,
    val providerRouteId: String? = null,
    val lineName: String? = null,
    /**
     * 선택 당시 명시적으로 확인한 열차 종별. 기존 route JSON에는 이 필드가 없으므로
     * UNKNOWN을 기본값으로 유지하되, 지하철 재조회 매칭에는 증거로 사용하지 않는다.
     */
    val serviceClass: TransitServiceClass = TransitServiceClass.UNKNOWN,
    val fromIds: Set<String> = emptySet(),
    val fromName: String? = null,
    val toIds: Set<String> = emptySet(),
    val toName: String? = null,
    val directionName: String? = null,
    val directionCode: String? = null,
)

data class LegacyTransitBoardingPlan(
    val kind: TransitLegMode,
    val accessMinutes: Double,
    val travelMinutesWithoutFirstWait: Double,
    val stationName: String,
    val lineName: String,
    val directionName: String?,
    val directionCode: String?,
    val arsId: String?,
    val cityCode: String?,
    val cityCodeNamespace: TransitCityCodeNamespace = TransitCityCodeNamespace.UNKNOWN,
    val nodeId: String?,
)

data class SelectedTransitRoute(
    val provider: String?,
    val rides: List<TransitRideSignature>,
    val legacyBoardingPlan: LegacyTransitBoardingPlan?,
)

internal fun String.normalizedId(): String? =
    trim().takeIf(String::isNotBlank)

/**
 * provider를 알 수 없는 내부 ID는 출처를 증명할 수 없으므로 안정 ID로 사용하지 않는다.
 * 이 경우 ARS/CITY/LOCAL ID 또는 정류장 이름 비교로 안전하게 내려간다.
 */
internal fun providerScopedStopId(
    providerId: String?,
    stopId: String?,
): String? {
    val normalizedProvider = providerId
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotBlank)
        ?: return null

    val normalizedStopId = stopId?.normalizedId() ?: return null

    return "PROVIDER:$normalizedProvider:$normalizedStopId"
}
