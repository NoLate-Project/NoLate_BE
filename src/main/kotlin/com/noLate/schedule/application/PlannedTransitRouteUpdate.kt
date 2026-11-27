package com.noLate.schedule.application

import java.time.Instant
import com.fasterxml.jackson.annotation.JsonInclude

enum class PlannedTransitTimingFailure {
    TRANSFER_RECONNECTION_UNAVAILABLE,
    EARLY_BOARDING_UNCONFIRMED,
    TIMELINE_INCONSISTENT,
}

/** 저장된 경로 안에서도 환승편이 바뀔 수 있어 경고를 낸 편과 승차 시각을 함께 보존한다. */
data class PlannedTransferBoardingRecheck(val segmentSequence: Int, val trainNumber: String, val scheduledBoardingAt: Instant) {
    init { require(segmentSequence > 0 && trainNumber.isNotBlank()) }
}

/** 원래 출발 알림과 분리한 계획 여정의 갱신. 승차·환승을 실제로 했다는 증거는 아니다. */
data class PlannedTransitRouteUpdate(
    val routeIdentity: String,
    val sourceUpdatedAt: Instant,
    val route: EffectiveTransitRoute? = null,
    val failure: PlannedTransitTimingFailure? = null,
    val onlyLastRideObserved: Boolean = false,
    /** 첫 승차 여유가 사라졌을 때 같은 역의 더 최신 예보가 있어야 계획을 재개한다. */
    val requiresFirstBoardingRecheck: Boolean = false,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val transferBoardingRecheck: PlannedTransferBoardingRecheck? = null,
    /** 당시 신선했던 ARRIVED 상태만 보존한다. 만료된 미래 승차 예보는 넣지 않는다. */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val reportedBoardings: List<ReportedSubwayBoardingEvidence> = emptyList(),
    /** 앞 열차 관측과 후속 버스의 승하차 예보를 함께 대조한 계획이다. */
    @get:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val railToBusConnectionObserved: Boolean = false,
    /** 지난 하차역 상태와 이후의 환승 예보가 서로 다른 평가에 도착할 수 있다. */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val reportedAlightings: List<ReportedSubwayAlightingEvidence> = emptyList(),
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val reportedBusAlighting: ReportedBusAlightingEvidence? = null,
) {
    init {
        require((route != null) != (failure != null) ||
            route == null && failure == null && (reportedBoardings.isNotEmpty() || reportedAlightings.isNotEmpty()))
        require(route == null || route.identity == routeIdentity)
        require(reportedBoardings.size <= 32 && reportedBoardings.all { it.routeIdentity == routeIdentity })
        require(reportedBoardings.map { it.segmentSequence to it.service }.distinct().size == reportedBoardings.size)
        require(reportedAlightings.size <= 32 && reportedAlightings.all { it.routeIdentity == routeIdentity })
        require(reportedAlightings.map { it.segmentSequence }.distinct().size == reportedAlightings.size)
        require(reportedBusAlighting == null || reportedBusAlighting.routeIdentity == routeIdentity)
        require(!requiresFirstBoardingRecheck || failure == PlannedTransitTimingFailure.EARLY_BOARDING_UNCONFIRMED)
        require(transferBoardingRecheck == null || (!requiresFirstBoardingRecheck && failure == PlannedTransitTimingFailure.EARLY_BOARDING_UNCONFIRMED))
        require(!railToBusConnectionObserved || route != null && !onlyLastRideObserved)
    }

    internal fun finalBusAlightingReport(): ReportedBusAlightingEvidence? {
        val report = reportedBusAlighting ?: return null
        val finalRide = route?.segments?.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }
            ?.maxByOrNull { it.sequence } ?: return null
        return report.takeIf { finalRide.kind == EffectiveTransitRouteSegmentKind.BUS && finalRide.sequence == report.sequence &&
            finalRide.busRidePrediction?.reportedAlightingAt == report.reportedAt && sourceUpdatedAt == report.reportedAt }
    }

    internal fun finalTrainAlightingReport(): ReportedSubwayAlightingEvidence? {
        val finalRide = route?.segments?.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }
            ?.maxByOrNull { it.sequence } ?: return null
        val reportedAt = finalRide.rideTiming?.reportedAlightingAt ?: return null
        return reportedAlightings.singleOrNull { finalRide.kind == EffectiveTransitRouteSegmentKind.SUBWAY &&
            it.segmentSequence == finalRide.sequence && it.sourceUpdatedAt == reportedAt && sourceUpdatedAt == reportedAt }
    }

    internal fun hasFinalAlightingReport(): Boolean = finalBusAlightingReport() != null || finalTrainAlightingReport() != null
}
