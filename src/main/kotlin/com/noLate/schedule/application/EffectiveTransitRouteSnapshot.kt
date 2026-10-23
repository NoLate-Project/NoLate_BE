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
            val previousAcceptedRoute = previous?.acceptedRoute()
            val previousAcceptedWaitMinutes = previous?.acceptedFirstBoardingWaitMinutes()
            val previousAcceptedEtaSource = previous?.lastAcceptedEtaSource
            val inProgressPlannedRoute = previous?.inProgressPlannedRoute
                ?: previousAcceptedRoute?.takeIf { route ->
                    evaluatedAt != null && !evaluatedAt.isBefore(route.departureAt)
                }
            if (!hasTransitDetails && previousAcceptedIdentity == null) return null

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
        val plan = inProgressPlannedRoute ?: return null
        val candidate = effectiveTransitRoute
        val matchingObservation = candidate?.takeIf { it.sameTrainsAs(plan) }
            ?.segments?.any { segment ->
                segment.rideTiming?.observationSourceUpdatedAt?.let { observedAt ->
                    !observedAt.isBefore(readAt.minusSeconds(maxAgeSeconds)) &&
                        !observedAt.isAfter(readAt.plusSeconds(5))
                } == true
            } == true
        val directObservation = plannedArrivalObservation?.takeIf { observation ->
            observation.routeIdentity == plan.identity &&
                !observation.sourceUpdatedAt.isBefore(readAt.minusSeconds(maxAgeSeconds)) &&
                !observation.sourceUpdatedAt.isAfter(readAt.plusSeconds(5))
        }
        val arrivalAt = when {
            matchingObservation -> candidate!!.arrivalAt
            directObservation != null -> directObservation.predictedArrivalAt
            else -> plan.arrivalAt
        }
        if (!arrivalAt.isAfter(readAt)) return null
        return PlannedJourneyArrivalPrediction(
            predictedArrivalAt = arrivalAt,
            presentedAt = readAt,
            routeIdentity = plan.identity,
            basis = if (matchingObservation || directObservation != null) PlannedJourneyArrivalBasis.MATCHED_TRAIN_REALTIME
                else PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN,
        )
    }
}

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
