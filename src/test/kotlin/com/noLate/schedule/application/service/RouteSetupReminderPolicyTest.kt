package com.noLate.schedule.application.service

import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import com.noLate.schedule.domain.ScheduleType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class RouteSetupReminderPolicyTest {
    private val policy = RouteSetupReminderPolicy()
    private val now = Instant.parse("2026-07-23T00:00:00Z")

    @Test
    fun `missing route becomes visible exactly 72 hours before start`() {
        val justOutside = routeSchedule(now.plusSeconds(72 * 60 * 60 + 1))
        val boundary = routeSchedule(now.plusSeconds(72 * 60 * 60))

        assertFalse(policy.requiresSetup(justOutside, travelEnabled = true, plan = null, now = now))
        assertTrue(policy.requiresSetup(boundary, travelEnabled = true, plan = null, now = now))
    }

    @Test
    fun `schedule only sharing never requests a personal route`() {
        assertFalse(
            policy.requiresSetup(
                schedule = routeSchedule(now.plusSeconds(60 * 60)),
                travelEnabled = false,
                plan = null,
                now = now,
            )
        )
    }

    @Test
    fun `valid current personal plan suppresses reminder but stale plan does not`() {
        val schedule = routeSchedule(now.plusSeconds(60 * 60))
        val plan = completePlan(schedule)

        assertFalse(policy.requiresSetup(schedule, travelEnabled = true, plan = plan, now = now))

        schedule.startAt = schedule.startAt.plusSeconds(60)
        assertTrue(policy.requiresSetup(schedule, travelEnabled = true, plan = plan, now = now))
    }

    @Test
    fun `past and normal schedules never request route setup`() {
        assertFalse(
            policy.requiresSetup(
                routeSchedule(now),
                travelEnabled = true,
                plan = null,
                now = now,
            )
        )
        assertFalse(
            policy.requiresSetup(
                routeSchedule(now.minusSeconds(1)),
                travelEnabled = true,
                plan = null,
                now = now,
            )
        )
        assertFalse(
            policy.requiresSetup(
                routeSchedule(now.plusSeconds(60)).apply { scheduleType = ScheduleType.NORMAL },
                travelEnabled = true,
                plan = null,
                now = now,
            )
        )
    }

    private fun routeSchedule(startAt: Instant): Schedule = Schedule(
        id = 10L,
        memberId = 1L,
        title = "공유 경로 일정",
        startAt = startAt,
        endAt = startAt.plusSeconds(60 * 60),
        scheduleType = ScheduleType.ROUTE,
    ).apply {
        updateRoute(
            travelMinutes = 30,
            departAt = null,
            departedAt = null,
            travelMode = ScheduleTravelMode.TRANSIT,
            locationName = "서울역",
            originName = "집",
            originAddress = null,
            originLat = 37.5,
            originLng = 127.0,
            destinationName = "서울역",
            destinationAddress = null,
            destinationLat = 37.55,
            destinationLng = 126.97,
            routeJson = "{}",
            notificationEnabled = false,
            notificationLeadMinutes = null,
            notificationIntervalMinutes = null,
        )
    }

    private fun completePlan(schedule: Schedule): ScheduleTravelPlan =
        ScheduleTravelPlan(scheduleId = requireNotNull(schedule.id), memberId = 2L).apply {
            replace(
                command = ScheduleTravelPlanUpsertCommand(
                    travelMinutes = 25,
                    travelMode = ScheduleTravelMode.TRANSIT,
                    originName = "회사",
                    originLat = 37.4,
                    originLng = 127.1,
                    routeJson = "{}",
                ),
                scheduleFingerprint = ScheduleTravelPlanFingerprint.calculate(schedule),
                departAt = null,
                routeJson = "{}",
                notificationLeadMinutes = null,
                notificationIntervalMinutes = null,
            )
        }
}
