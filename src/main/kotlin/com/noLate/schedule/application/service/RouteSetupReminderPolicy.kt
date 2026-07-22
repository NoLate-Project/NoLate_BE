package com.noLate.schedule.application.service

import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.domain.ScheduleType
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 경로 미설정 표시와 푸시 스캐너가 함께 사용하는 시간·완성도 정책이다.
 *
 * 저장 시점에 `routeSetupRequired=true`를 박아 두면 100개 일정의 D-3 진입을 갱신하는 별도
 * 배치가 필요하고, 공유 방식이 바뀐 뒤 오래된 값이 남는다. 대신 조회/스캔 시 현재 시각,
 * 유효한 공유 capability, 개인 계획 지문을 조합해 같은 결론을 계산한다.
 */
@Component
class RouteSetupReminderPolicy {

    fun requiresSetup(
        schedule: Schedule,
        travelEnabled: Boolean,
        plan: ScheduleTravelPlan?,
        now: Instant,
    ): Boolean = requiresSetup(
        schedule = schedule,
        travelEnabled = travelEnabled,
        routeReady = plan?.let { isCurrentCompletePlan(it, schedule) } == true,
        now = now,
    )

    fun requiresOwnerSetup(
        schedule: Schedule,
        travelEnabled: Boolean,
        ownerPlan: ScheduleTravelPlan?,
        now: Instant,
    ): Boolean {
        val ready = ownerPlan?.let { isCurrentCompletePlan(it, schedule) }
            ?: isCompleteLegacyOwnerRoute(schedule)
        return requiresSetup(schedule, travelEnabled, ready, now)
    }

    fun isWithinWindow(schedule: Schedule, now: Instant): Boolean {
        if (schedule.scheduleType != ScheduleType.ROUTE) return false
        if (!schedule.startAt.isAfter(now)) return false
        return Duration.between(now, schedule.startAt) <= REMINDER_WINDOW
    }

    fun isCurrentCompletePlan(plan: ScheduleTravelPlan, schedule: Schedule): Boolean {
        val destination = schedule.route
        return !plan.deleted &&
            ScheduleTravelPlanFingerprint.matches(plan, schedule) &&
            plan.travelMinutes != null &&
            plan.travelMode != null &&
            plan.originLat != null &&
            plan.originLng != null &&
            destination?.destinationLat != null &&
            destination.destinationLng != null
    }

    private fun requiresSetup(
        schedule: Schedule,
        travelEnabled: Boolean,
        routeReady: Boolean,
        now: Instant,
    ): Boolean = travelEnabled && isWithinWindow(schedule, now) && !routeReady

    private fun isCompleteLegacyOwnerRoute(schedule: Schedule): Boolean {
        val route = schedule.route ?: return false
        return route.travelMinutes != null &&
            route.travelMode != null &&
            route.originLat != null &&
            route.originLng != null &&
            route.destinationLat != null &&
            route.destinationLng != null
    }

    companion object {
        val REMINDER_WINDOW: Duration = Duration.ofDays(3)
    }
}
