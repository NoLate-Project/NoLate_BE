package com.noLate.schedule.application

import com.noLate.schedule.domain.TrafficSource
import java.time.Duration
import java.time.Instant

/** 완전한 미래 시간표는 출발을 앞당기는 계획 근거다. 실시간 ETA 채택과는 분리한다. */
object TimetableDeparturePlanPolicy {
    fun verifiedRoute(result: TrafficResult, now: Instant, target: Instant, boardingBufferSeconds: Long,
                      transferMarginSeconds: Long = 60): EffectiveTransitRoute? {
        result.futureTimetablePlanEvidence?.let { evidence ->
            // 주 진단은 그대로 두고, 별도 후보도 같은 엄격한 시간표 검증을 다시 거친다.
            // 정상 실시간 ETA나 다른 경로의 계획으로 승격하지 않는다.
            if (result.source != TrafficSource.TIMETABLE_PROVIDER || !result.stale ||
                evidence.fetchedAt > now || result.effectiveTransitRoute?.identity != evidence.route.identity ||
                result.transitRouteProvenance != evidence.provenance) return null
            val candidate = result.copy(travelMinutes = evidence.route.totalMinutes,
                fetchedAt = evidence.fetchedAt, failureReason = evidence.failureReason,
                recommendedDepartureAt = evidence.route.departureAt, predictedArrivalAt = evidence.route.arrivalAt,
                firstBoardingObservation = null,
                transitTimingBasis = TransitTimingBasis.TIMETABLE_ONLY,
                effectiveTransitRoute = evidence.route, futureTimetablePlanEvidence = null)
            return verifiedRoute(candidate, now, target, boardingBufferSeconds, transferMarginSeconds)
        }
        if (result.source != TrafficSource.TIMETABLE_PROVIDER || !result.stale ||
            result.failureReason !in setOf(TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED,
                TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE) ||
            result.transitTimingBasis != TransitTimingBasis.TIMETABLE_ONLY ||
            result.transitRouteProvenance != TransitRouteProvenance.SELECTED_ROUTE_PRESERVED) return null
        val route = result.effectiveTransitRoute ?: return null
        if (route.departureAt <= now || route.arrivalAt > target || route.arrivalAt <= route.departureAt) return null
        val ordered = route.segments.sortedBy { it.sequence }
        var cursor = route.departureAt
        var previousArrival: Instant? = null
        var walkingSeconds = 0L
        var rides = 0
        for (segment in ordered) {
            if (segment.kind == EffectiveTransitRouteSegmentKind.WALK) {
                val seconds = segment.durationSeconds ?: segment.durationMinutes.toLong() * 60
                if (seconds < 0) return null
                walkingSeconds += seconds
                cursor = cursor.plusSeconds(seconds)
                continue
            }
            if (segment.kind != EffectiveTransitRouteSegmentKind.SUBWAY) return null
            val timing = segment.rideTiming ?: return null
            val table = timing.timetable
            val localBoarding = table.boardingAt.atZone(java.time.ZoneId.of("Asia/Seoul"))
            val sameServiceDay = table.serviceDate == localBoarding.toLocalDate() ||
                (table.serviceDate.plusDays(1) == localBoarding.toLocalDate() && localBoarding.hour < 4)
            if (timing.basis != EffectiveTransitRideTimingBasis.TIMETABLE ||
                timing.predictedBoardingAt != table.boardingAt || timing.predictedArrivalAt != table.arrivalAt ||
                table.fetchedAt > now || !sameServiceDay ||
                table.boardingAt < cursor) return null
            if (previousArrival == null) {
                if (cursor.plusSeconds(boardingBufferSeconds) > (table.boardingStationArrivalAt ?: table.boardingAt)) return null
            } else {
                val transfer = segment.transferTiming
                if (transfer != null && (transfer.previousArrivalAt != previousArrival || transfer.walkingSeconds != walkingSeconds ||
                    transfer.boardingAt != table.boardingAt)) return null
                // 초기 시간표 경로에는 표시용 transferTiming이 없을 수 있다. 원본 시각과
                // 기존 도보·설정 여유로 직접 검증하며 누락을 0초 여유로 해석하지 않는다.
                val margin = maxOf(transferMarginSeconds, transfer?.requiredMarginSeconds ?: 0)
                if (cursor.plusSeconds(margin) > table.boardingAt) return null
            }
            cursor = table.arrivalAt
            previousArrival = cursor
            walkingSeconds = 0
            rides++
        }
        val seconds = Duration.between(route.departureAt, cursor).seconds
        return route.takeIf { rides > 0 && cursor == route.arrivalAt && (seconds + 59) / 60 == route.totalMinutes.toLong() }
    }
}
