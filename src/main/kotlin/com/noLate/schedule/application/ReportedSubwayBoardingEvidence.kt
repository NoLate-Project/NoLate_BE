package com.noLate.schedule.application

import java.time.Duration
import java.time.Instant

/**
 * 신선한 원본에서 확인한 승차역 ARRIVED 상태를 보존한다.
 * 미래 도착 예보의 만료나 실제 승객의 탑승 확인을 뜻하지 않는다.
 * sourceUpdatedAt은 공급자 상태 게시시각이며 정확한 물리 도착 초가 아니다.
 */
data class ReportedSubwayBoardingEvidence(
    val routeIdentity: String,
    val routeDepartureAt: Instant,
    val segmentSequence: Int,
    val service: ObservedTransitSubwayService,
    val sourceUpdatedAt: Instant,
    /** 이 상태를 판단한 계산 기준시각. 실제 HTTP 수신시각과 구분한다. */
    val checkedAt: Instant,
    /** 원본 DTO observedAt을 그대로 보존한다. 계산 시작시각으로 대신 채우지 않는다. */
    val recordedAt: Instant,
) {
    init {
        require(routeIdentity.matches(Regex("[0-9a-f]{64}")))
        require(segmentSequence >= 0)
        require(sourceUpdatedAt >= routeDepartureAt)
        // 원본은 기록 시점에 신선해야 한다. 이후 보존된 상태를 '현재 예보'로 승격하지 않는다.
        require(sourceUpdatedAt <= checkedAt && Duration.between(sourceUpdatedAt, checkedAt) <= Duration.ofSeconds(120))
        require(sourceUpdatedAt <= recordedAt && Duration.between(sourceUpdatedAt, recordedAt) <= Duration.ofSeconds(120))
        require(recordedAt <= checkedAt.plusSeconds(5))
    }

    fun matches(route: EffectiveTransitRoute, segment: EffectiveTransitRouteSegment, evaluatedAt: Instant): Boolean {
        val table = segment.rideTiming?.timetable ?: return false
        return routeIdentity == route.identity && routeDepartureAt == route.departureAt &&
            segmentSequence == segment.sequence && segment.kind == EffectiveTransitRouteSegmentKind.SUBWAY &&
            service.matchesReference(table) && service.matchesOrderedRide(segment.fromName, segment.toName, segment.orderedStopNames) &&
            checkedAt <= evaluatedAt && (recordedAt <= evaluatedAt || checkedAt == evaluatedAt) &&
            evaluatedAt <= route.arrivalAt.plusSeconds(20 * 60L) &&
            table.isObservableServiceDay(route.departureAt, recordedAt) &&
            table.isObservableServiceDay(route.departureAt, evaluatedAt)
    }
}
