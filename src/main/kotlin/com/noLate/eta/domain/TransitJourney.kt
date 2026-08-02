package com.noLate.eta.domain

import com.noLate.transit.domain.TransitCityCodeNamespace
import java.time.Instant

enum class TransitLegMode {
    WALK,
    BUS,
    SUBWAY,
    ETC,
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
    fun stableIds(): Set<String> = buildSet {
        providerStopId?.normalizedId()?.let { add("ODsay:$it") }
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
    /** ODsay의 rps 시각은 시간표이며 실시간 차량 관측값이 아니다. */
    val timingBasis: TransitLegTimingBasis = TransitLegTimingBasis.UNKNOWN,
) {
    val isRide: Boolean
        get() = mode == TransitLegMode.BUS || mode == TransitLegMode.SUBWAY
}

enum class TransitLegTimingBasis {
    TIMETABLE,
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
