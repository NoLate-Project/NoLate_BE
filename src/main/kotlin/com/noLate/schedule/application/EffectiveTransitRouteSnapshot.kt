package com.noLate.schedule.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.eta.domain.TransitFirstBoardingObservation
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.domain.PlannedJourneyArrivalPrediction
import com.noLate.schedule.domain.PlannedJourneyArrivalBasis
import java.time.Instant

/**
 * `schedule_push_job`에 원자적으로 보관하는 마지막 대중교통 ETA 전달 스냅샷이다.
 *
 * DB 컬럼 하나에 경로와 계산 근거를 함께 넣어 API, 일반 푸시, Live Activity가 서로 다른
 * 여정을 표시하지 않도록 한다. 역직렬화 실패는 호출자가 null로 취급할 수 있게 fail-soft다.
 */
data class EffectiveTransitRouteSnapshot(
    val version: Int = CURRENT_VERSION,
    val evaluationCheckCount: Int? = null,
    /** 같은 check 번호가 새 알림 세대에서 재사용돼도 retry latch를 이어 붙이지 않는 경계. */
    val evaluationNotificationGeneration: Long? = null,
    val accepted: Boolean = false,
    val transitRouteProvenance: TransitRouteProvenance? = null,
    val transitTimingBasis: TransitTimingBasis? = null,
    val firstBoardingWaitMinutes: Int? = null,
    val routeChanged: Boolean = false,
    /** 일반 푸시를 한 번만 깨우는 직전 정상 경로 대비 transition. */
    val routeTransitioned: Boolean = false,
    /** 일시적인 provider degraded 회차를 넘어 중복 전환 푸시를 막는 마지막 정상 baseline. */
    val lastAcceptedRouteIdentity: String? = null,
    val lastAcceptedRouteProvenance: TransitRouteProvenance? = null,
    /** degraded 표시가 진단 후보의 ETA와 다른 경로를 섞지 않도록 보존한 마지막 정상 여정. */
    val lastAcceptedEffectiveTransitRoute: EffectiveTransitRoute? = null,
    val lastAcceptedFirstBoardingWaitMinutes: Int? = null,
    val lastAcceptedEtaSource: TrafficSource? = null,
    /** 출발 경계를 지난 뒤에는 새 출발지 경로로 바꾸지 않는, 마지막 정상 계획 여정. */
    val inProgressPlannedRoute: EffectiveTransitRoute? = null,
    /** 이번 평가에서 저장 계획의 동일 마지막 열차를 직접 대조한 결과. 다음 실패 회차로 승계하지 않는다. */
    val plannedArrivalObservation: PlannedJourneyArrivalObservation? = null,
    /** 현재 계획의 구간별 관측 및 환승 재연결. 출발 알림의 정상 ETA와 분리한다. */
    val plannedRouteUpdate: PlannedTransitRouteUpdate? = null,
    /** 관측이 만료돼도 이미 재연결한 계획을 예전 환승편으로 되돌리지 않는다. */
    val lastUpdatedPlannedRoute: EffectiveTransitRoute? = null,
    /** 실시간 미관측이어도 완전한 시간표로 검증한 계획. 정상 ETA로 승격하지 않는다. */
    val lastTimetablePlannedRoute: EffectiveTransitRoute? = null,
    val effectiveTransitRoute: EffectiveTransitRoute? = null,
    /** 현재 평가 관측만 저장한다. 이전 정상 관측은 degraded 회차로 승계하지 않는다. */
    val firstBoardingObservation: TransitFirstBoardingObservation? = null,
) {
    companion object {
        private const val CURRENT_VERSION = 1

        fun parse(objectMapper: ObjectMapper, json: String?): EffectiveTransitRouteSnapshot? =
            json
                ?.takeIf(String::isNotBlank)
                ?.let {
                    runCatching {
                        objectMapper.readValue(it, EffectiveTransitRouteSnapshot::class.java)
                    }.getOrNull()
                        ?.takeIf { snapshot -> snapshot.version == CURRENT_VERSION }
                }

        fun from(
            trafficResult: TrafficResult,
            previous: EffectiveTransitRouteSnapshot?,
            evaluationCheckCount: Int,
            notificationGeneration: Long = 0,
            evaluatedAt: Instant? = null,
            verifiedTimetablePlan: EffectiveTransitRoute? = null,
        ): EffectiveTransitRouteSnapshot? {
            require(notificationGeneration >= 0) {
                "effective route notification generation은 0 이상이어야 합니다."
            }
            val route = trafficResult.effectiveTransitRoute
            val hasTransitDetails = route != null ||
                trafficResult.transitRouteProvenance != null ||
                trafficResult.transitTimingBasis != null ||
                trafficResult.firstBoardingWaitMinutes != null ||
                trafficResult.firstBoardingObservation != null
            val previousAcceptedIdentity = previous?.acceptedRouteIdentity()
            val previousAcceptedProvenance = previous?.acceptedRouteProvenance()
            val previousAcceptedRoute = previous?.acceptedRoute()?.let { saved ->
                if (previous.accepted) saved.withAcceptedBoardingSource(previous.firstBoardingObservation) else saved
            }
            val previousAcceptedWaitMinutes = previous?.acceptedFirstBoardingWaitMinutes()
            val previousAcceptedEtaSource = previous?.lastAcceptedEtaSource
            val inProgressPlannedRoute = previous?.inProgressPlannedRoute
                ?: (previousAcceptedRoute ?: previous?.lastTimetablePlannedRoute)?.takeIf { route ->
                    evaluatedAt != null && !evaluatedAt.isBefore(route.departureAt)
                }
            if (!hasTransitDetails && previousAcceptedIdentity == null && inProgressPlannedRoute == null &&
                previous?.lastTimetablePlannedRoute == null) return null

            val accepted = trafficResult.accepted && route != null
            val currentRouteIdentity = route?.identity
            val routeChanged = accepted &&
                trafficResult.transitRouteProvenance ==
                TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE
            val routeTransitioned = accepted && when {
                previousAcceptedIdentity != null ->
                    previousAcceptedIdentity != currentRouteIdentity ||
                        previousAcceptedProvenance != trafficResult.transitRouteProvenance ||
                        (
                            previous.evaluationNotificationGeneration == notificationGeneration &&
                                previous.evaluationCheckCount == evaluationCheckCount &&
                                previous.routeTransitioned &&
                                previousAcceptedIdentity == currentRouteIdentity &&
                                previousAcceptedProvenance == trafficResult.transitRouteProvenance
                            )

                else ->
                    routeChanged
            }

            return EffectiveTransitRouteSnapshot(
                evaluationCheckCount = evaluationCheckCount,
                evaluationNotificationGeneration = notificationGeneration,
                accepted = accepted,
                transitRouteProvenance = trafficResult.transitRouteProvenance,
                transitTimingBasis = trafficResult.transitTimingBasis,
                firstBoardingWaitMinutes = trafficResult.firstBoardingWaitMinutes,
                routeChanged = routeChanged,
                routeTransitioned = routeTransitioned,
                lastAcceptedRouteIdentity = if (accepted) {
                    route?.identity
                } else {
                    previousAcceptedIdentity
                },
                lastAcceptedRouteProvenance = if (accepted) {
                    trafficResult.transitRouteProvenance
                } else {
                    previousAcceptedProvenance
                },
                lastAcceptedEffectiveTransitRoute = if (accepted) {
                    null
                } else {
                    previousAcceptedRoute
                },
                lastAcceptedFirstBoardingWaitMinutes = if (accepted) {
                    null
                } else {
                    previousAcceptedWaitMinutes
                },
                lastAcceptedEtaSource = if (accepted) {
                    trafficResult.source
                } else {
                    previousAcceptedEtaSource
                },
                inProgressPlannedRoute = inProgressPlannedRoute,
                lastTimetablePlannedRoute = verifiedTimetablePlan ?: previous?.lastTimetablePlannedRoute,
                lastUpdatedPlannedRoute = previous?.plannedRouteUpdate?.route ?: previous?.lastUpdatedPlannedRoute,
                // 같은 원본을 재조회해 새 관측이 없어도 출처 시각을 보존한다. 읽을 때 신선도를
                // 다시 판단하므로 캐시를 새 실시간값으로 꾸미지 않으며, 확인된 모순도 지우지 않는다.
                plannedRouteUpdate = previous?.plannedRouteUpdate?.takeIf { it.routeIdentity == inProgressPlannedRoute?.identity },
                effectiveTransitRoute = route,
                firstBoardingObservation = trafficResult.firstBoardingObservation,
            )
        }
    }

    private fun acceptedRouteIdentity(): String? =
        effectiveTransitRoute?.identity?.takeIf { accepted }
            ?: lastAcceptedRouteIdentity

    private fun acceptedRouteProvenance(): TransitRouteProvenance? =
        transitRouteProvenance?.takeIf { accepted }
            ?: lastAcceptedRouteProvenance

    fun acceptedRoute(): EffectiveTransitRoute? =
        if (accepted) effectiveTransitRoute else lastAcceptedEffectiveTransitRoute

    fun acceptedFirstBoardingWaitMinutes(): Int? =
        if (accepted) firstBoardingWaitMinutes else lastAcceptedFirstBoardingWaitMinutes

    /** 출발 후의 같은 편 실시간값만 잠정 도착시각에 반영한다. 실제 승차는 확인하지 않는다. */
    fun plannedJourneyArrivalPrediction(readAt: Instant, maxAgeSeconds: Long): PlannedJourneyArrivalPrediction? {
        val plan = plannedRouteAt(readAt) ?: return null
        if (plannedJourneyTimingFailureReason() != null) return null
        val updated = plannedRouteUpdate?.takeIf { it.routeIdentity == plan.identity && it.route != null &&
            it.sourceUpdatedAt in readAt.minusSeconds(maxAgeSeconds)..readAt.plusSeconds(5) }
        if (updated != null) {
            val route = requireNotNull(updated.route)
            if (route.arrivalAt <= readAt) return null
            return PlannedJourneyArrivalPrediction(route.arrivalAt, readAt, plan.identity,
                if (route.segments.any { it.rideTiming?.observedService != null }) PlannedJourneyArrivalBasis.OBSERVED_SERVICE_CONNECTION
                else if (updated.onlyLastRideObserved) PlannedJourneyArrivalBasis.MATCHED_TRAIN_REALTIME
                else PlannedJourneyArrivalBasis.MATCHED_RIDE_AND_ONWARD_TIMETABLE,
                effectiveTransitRoute = route, sourceUpdatedAt = updated.sourceUpdatedAt)
        }
        val preservedPlan = latestPreservedPlan(plan)
        val candidate = effectiveTransitRoute
        val oldestSourceAt = readAt.minusSeconds(maxAgeSeconds)
        val latestSourceAt = readAt.plusSeconds(5)
        // An intermediate train does not provide realtime evidence for arrival on the final train.
        val finalRideObservedAt = candidate?.takeIf { it.sameTrainsAs(preservedPlan) }
            ?.segments?.filter { it.kind == EffectiveTransitRouteSegmentKind.SUBWAY }
            ?.maxByOrNull { it.sequence }
            // 이른 승차역 도착만 반영한 후보는 마지막 하차를 시간표대로 유지한다.
            // 이 출처로 이미 확인된 목적지 지연을 지우거나 시간표를 실시간으로 표시할 수 없다.
            ?.rideTiming?.takeUnless { it.basis.keepsTimetableAlightingAfterEarlyBoarding }
            ?.observationSourceUpdatedAt?.takeIf { it in oldestSourceAt..latestSourceAt }
        val directObservation = plannedArrivalObservation?.takeIf { observation ->
            observation.routeIdentity == plan.identity &&
                observation.sourceUpdatedAt in oldestSourceAt..latestSourceAt
        }
        val arrivalAt = when {
            finalRideObservedAt != null && (directObservation == null || finalRideObservedAt > directObservation.sourceUpdatedAt) ->
                candidate.arrivalAt
            directObservation != null -> directObservation.predictedArrivalAt
            else -> preservedPlan.arrivalAt
        }
        if (!arrivalAt.isAfter(readAt)) return null
        return PlannedJourneyArrivalPrediction(
            predictedArrivalAt = arrivalAt,
            presentedAt = readAt,
            routeIdentity = plan.identity,
            basis = if (finalRideObservedAt != null || directObservation != null) PlannedJourneyArrivalBasis.MATCHED_TRAIN_REALTIME
                else PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN,
            sourceUpdatedAt = listOfNotNull(finalRideObservedAt, directObservation?.sourceUpdatedAt).maxOrNull(),
            effectiveTransitRoute = preservedPlan.asPlannedBaseline().takeIf {
                finalRideObservedAt == null && directObservation == null
            },
        )
    }

    fun plannedJourneyTimingFailureReason(): String? = plannedRouteUpdate
        ?.takeIf { it.routeIdentity == inProgressPlannedRoute?.identity }?.failure?.name

    fun plannedRouteAt(readAt: Instant): EffectiveTransitRoute? = inProgressPlannedRoute
        ?: lastTimetablePlannedRoute?.takeIf { it.departureAt <= readAt && it.arrivalAt > readAt }

    fun hasActivePlannedRouteAt(readAt: Instant, predictedArrivalAt: Instant?): Boolean {
        val original = plannedRouteAt(readAt) ?: return false
        val arrivalAt = predictedArrivalAt ?: latestPreservedPlan(original).arrivalAt
        return original.departureAt <= readAt && readAt < arrivalAt
    }

    private fun latestPreservedPlan(original: EffectiveTransitRoute): EffectiveTransitRoute =
        plannedRouteUpdate?.route?.takeIf { it.identity == original.identity }
            ?: lastUpdatedPlannedRoute?.takeIf { it.identity == original.identity } ?: original
}

/** 출발 전 응답에 따로 저장한 첫 승차 관측의 원본 시각을 같은 편의 재조회에 넘긴다. */
private fun EffectiveTransitRoute.withAcceptedBoardingSource(observation: TransitFirstBoardingObservation?): EffectiveTransitRoute {
    if (observation == null || observation.provider != "seoul-openapi" || observation.trainNumber == null ||
        observation.departureAt != departureAt ||
        observation.sourceUpdatedAt !in observation.evaluatedAt.minusSeconds(120)..observation.evaluatedAt.plusSeconds(5)) return this
    val first = segments.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }.minByOrNull { it.sequence } ?: return this
    val timing = first.rideTiming ?: return this
    val table = timing.timetable
    if (timing.boardingSourceAt() != null || first.fromName != observation.stationName ||
        table.line.displayName != observation.lineName || timing.predictedBoardingAt != observation.expectedBoardingAt ||
        observation.trainNumber !in setOfNotNull(table.trainNumber, table.realtimeTrainNumber)) return this
    val withSource = when (timing.basis) {
        EffectiveTransitRideTimingBasis.FIRST_BOARDING_REALTIME_ADJUSTED_TIMETABLE -> timing.copy(
            basis = EffectiveTransitRideTimingBasis.PLANNED_RIDE_BOARDING_REALTIME, observationSourceUpdatedAt = observation.sourceUpdatedAt)
        EffectiveTransitRideTimingBasis.FIRST_BOARDING_REALTIME_TIMETABLE_ALIGHTING ->
            timing.copy(observationSourceUpdatedAt = observation.sourceUpdatedAt)
        EffectiveTransitRideTimingBasis.FIRST_RIDE_BOARDING_AND_ALIGHTING_REALTIME ->
            timing.copy(boardingObservationSourceUpdatedAt = observation.sourceUpdatedAt)
        else -> return this
    }
    return copy(segments = segments.map { if (it === first) it.copy(rideTiming = withSource) else it })
}

/** 만료된 관측을 실시간으로 표시하지 않고, 마지막으로 연결한 열차와 계획 시각만 보존한다. */
private fun EffectiveTransitRoute.asPlannedBaseline(): EffectiveTransitRoute = copy(segments = segments.map { segment ->
    val timing = segment.rideTiming
    if (timing == null || timing.basis == EffectiveTransitRideTimingBasis.TIMETABLE) segment else segment.copy(
        rideTiming = timing.asPlannedBaseline())
})

data class PlannedJourneyArrivalObservation(
    val routeIdentity: String,
    val predictedArrivalAt: Instant,
    val sourceUpdatedAt: Instant,
)

/** 같은 경로 모양만으로는 부족하다. 운행일·편명·방향·승하차역·원본 시간표까지 일치해야 한다. */
private fun EffectiveTransitRoute.sameTrainsAs(other: EffectiveTransitRoute): Boolean {
    if (provider != other.provider || identity != other.identity) return false
    val rides = segments.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }
    val originalRides = other.segments.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }
    if (rides.isEmpty() || rides.size != originalRides.size) return false
    return rides.zip(originalRides).all { (current, original) ->
        if (current.kind != EffectiveTransitRouteSegmentKind.SUBWAY ||
            original.kind != EffectiveTransitRouteSegmentKind.SUBWAY) return@all false
        if (current.rideTiming?.observedService != original.rideTiming?.observedService ||
            current.fromName != original.fromName || current.toName != original.toName ||
            current.orderedStopNames != original.orderedStopNames) return@all false
        val a = current.rideTiming?.timetable ?: return@all false
        val b = original.rideTiming?.timetable ?: return@all false
        a.provider == b.provider && a.line == b.line && a.serviceDate == b.serviceDate &&
            a.trainNumber == b.trainNumber && a.realtimeTrainNumber == b.realtimeTrainNumber &&
            a.directionCode == b.directionCode && a.serviceClass == b.serviceClass &&
            a.fromStationCode == b.fromStationCode && a.toStationCode == b.toStationCode &&
            a.terminalName == b.terminalName && a.boardingAt == b.boardingAt && a.arrivalAt == b.arrivalAt
    }
}

fun EffectiveTransitRoute.toRouteDisplaySegments(): List<RouteDisplaySegment> {
    val ordered = segments
        .sortedBy(EffectiveTransitRouteSegment::sequence)
        .map { segment ->
            when (segment.kind) {
                EffectiveTransitRouteSegmentKind.WALK ->
                    RouteDisplaySegment("walk", "도보", "#9CA3AF")

                EffectiveTransitRouteSegmentKind.BUS ->
                    RouteDisplaySegment(
                        "bus",
                        segment.lineName?.trim()?.take(18)?.takeIf(String::isNotBlank) ?: "버스",
                        "#2979FF",
                    )

                EffectiveTransitRouteSegmentKind.SUBWAY ->
                    RouteDisplaySegment(
                        "subway",
                        segment.lineName?.trim()?.take(18)?.takeIf(String::isNotBlank) ?: "지하철",
                        "#00B140",
                    )

                EffectiveTransitRouteSegmentKind.TRAIN ->
                    RouteDisplaySegment("subway", segment.lineName?.take(18) ?: "기차", "#2563EB")
                EffectiveTransitRouteSegmentKind.EXPRESS_BUS, EffectiveTransitRouteSegmentKind.COACH ->
                    RouteDisplaySegment("bus", segment.lineName?.take(18) ?: "고속·시외버스", "#7C3AED")
                EffectiveTransitRouteSegmentKind.INTERCITY_BUS ->
                    RouteDisplaySegment("bus", segment.lineName?.take(18) ?: "시외버스", "#0F766E")
                EffectiveTransitRouteSegmentKind.ETC ->
                    RouteDisplaySegment("transfer", "환승", "#9CA3AF")
            }
        }
        .fold(mutableListOf<RouteDisplaySegment>()) { result, segment ->
            if (result.lastOrNull() != segment) result += segment
            result
        }
    if (ordered.size <= MAX_LIVE_ACTIVITY_MOVEMENT_SEGMENTS) return ordered

    val omitted = ordered.size - PRESERVED_EDGE_SEGMENTS * 2
    return ordered.take(PRESERVED_EDGE_SEGMENTS) +
        RouteDisplaySegment("transfer", "${omitted}구간 생략", "#9CA3AF") +
        ordered.takeLast(PRESERVED_EDGE_SEGMENTS)
}

private const val MAX_LIVE_ACTIVITY_MOVEMENT_SEGMENTS = 5
private const val PRESERVED_EDGE_SEGMENTS = 2
