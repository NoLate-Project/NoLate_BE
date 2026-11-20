package com.noLate.schedule.application.service.policy

import com.noLate.schedule.application.EffectiveTransitRouteSnapshot
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 조회 창만 조정한다. 시간표·관측의 채택, 출발 알림과 도보·환승 여유는 바꾸지 않는다. */
object PlannedTransitRefreshCadencePolicy {
    private const val LOOKAHEAD_SECONDS = 15 * 60L
    private const val LATE_OBSERVATION_SECONDS = 20 * 60L

    fun nextRefreshAt(now: Instant, snapshot: EffectiveTransitRouteSnapshot?): Instant? {
        val state = snapshot ?: return null
        val original = state.plannedRouteAt(now) ?: return null
        if (original.departureAt > now) return null
        fun matchesOriginal(route: com.noLate.schedule.application.EffectiveTransitRoute) =
            route.identity == original.identity && route.provider == original.provider && route.departureAt == original.departureAt
        val route = state.plannedRouteUpdate?.route?.takeIf(::matchesOriginal)
            ?: state.lastUpdatedPlannedRoute?.takeIf(::matchesOriginal) ?: original
        val rides = route.segments.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }
        if (rides.isEmpty() || rides.any { it.kind != EffectiveTransitRouteSegmentKind.SUBWAY || it.rideTiming == null }) return null
        if (rides.any { !requireNotNull(it.rideTiming).timetable.isObservableServiceDay(original.departureAt, now) }) return null
        val events = rides.flatMap { segment -> requireNotNull(segment.rideTiming).let {
            listOf(it.predictedBoardingAt, it.predictedArrivalAt)
        } }.filter { it >= now.minusSeconds(LATE_OBSERVATION_SECONDS) }
        if (events.isEmpty()) return null
        // 공급자의 15분 미래 조회·20분 지연 대조 창 안에서는 120초 신선도보다 짧게 확인한다.
        // 멀리 있는 여정은 기존 저빈도 주기를 유지하되 관측 창이 열리는 경계는 넘기지 않는다.
        return if (events.any { it <= now.plusSeconds(LOOKAHEAD_SECONDS) }) now.plus(1, ChronoUnit.MINUTES)
            else events.minOf { it.minusSeconds(LOOKAHEAD_SECONDS) }
    }
}
