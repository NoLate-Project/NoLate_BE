package com.noLate.schedule.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlanStatus
import com.noLate.schedule.domain.ScheduleType
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ScheduleTravelPlanLegacyStatusTest {
    private val mapper = jacksonObjectMapper()
    private val now = Instant.parse("2026-11-04T00:00:00Z")
    private val schedules = mock<ScheduleRepository>()
    private val service = ScheduleTravelPlanService(schedules, mock(), mock(), mock(), mock(), mapper, mock(),
        routeSetupReminderPolicy = RouteSetupReminderPolicy(), clock = Clock.fixed(now, ZoneOffset.UTC))

    private fun schedule(complete: Boolean = false) = Schedule(id = 363, memberId = 296,
        title = "이름만 있는 기존 도착지", startAt = now.plusSeconds(3600), endAt = now.plusSeconds(7200),
        scheduleType = ScheduleType.ROUTE).apply {
        updateRoute(travelMinutes = if (complete) 30 else null, departAt = null, departedAt = null,
            travelMode = if (complete) ScheduleTravelMode.TRANSIT else null,
            locationName = "신림역", originName = if (complete) "노원역" else null,
            originAddress = null, originLat = if (complete) 37.6551 else null,
            originLng = if (complete) 127.0610 else null, destinationName = "신림역", destinationAddress = null,
            destinationLat = if (complete) 37.4842 else null, destinationLng = if (complete) 126.9297 else null,
            routeJson = if (complete) "{}" else null, notificationEnabled = false,
            notificationLeadMinutes = null, notificationIntervalMinutes = null)
    }

    private fun view(schedule: Schedule) = service.personalizeScheduleDto(296, schedule, schedule.toDto(mapper), null)

    @Test
    fun `name-only legacy destination is not a ready route and remains selectable`() {
        val result = view(schedule())
        assertEquals(ScheduleTravelPlanStatus.NOT_CONFIGURED, result.travelPlanStatus)
        assertEquals(ScheduleTravelPlanStatus.NOT_CONFIGURED, result.myTravelPlan?.status)
        assertTrue(result.routeSetupRequired == true)
        assertEquals("신림역", result.destination?.name)
        assertNull(result.travelMinutes)
        assertNull(result.origin)
        assertNull(result.route)
    }

    @Test
    fun `a complete legacy owner route remains ready without a personal-plan row`() {
        val result = view(schedule(complete = true))
        assertEquals(ScheduleTravelPlanStatus.READY, result.travelPlanStatus)
        assertEquals(ScheduleTravelPlanStatus.READY, result.myTravelPlan?.status)
        assertFalse(result.routeSetupRequired == true)
        assertEquals(30, result.travelMinutes)
    }

    @Test
    fun `missing route inputs never become ready merely because a legacy route row exists`() {
        for (missing in listOf("duration", "mode", "originLat", "originLng", "destinationLat", "destinationLng")) {
            val schedule = schedule(complete = true)
            requireNotNull(schedule.route).apply {
                when (missing) {
                    "duration" -> travelMinutes = null
                    "mode" -> travelMode = null
                    "originLat" -> originLat = null
                    "originLng" -> originLng = null
                    "destinationLat" -> destinationLat = null
                    "destinationLng" -> destinationLng = null
                }
            }
            assertEquals(ScheduleTravelPlanStatus.NOT_CONFIGURED, view(schedule).travelPlanStatus, missing)
        }
    }

    @Test
    fun `reminder time window and route completeness remain separate`() {
        for (past in listOf(false, true)) {
            val schedule = schedule().apply { startAt = if (past) now.minusSeconds(1) else now.plusSeconds(4 * 86400) }
            val result = view(schedule)
            assertEquals(ScheduleTravelPlanStatus.NOT_CONFIGURED, result.travelPlanStatus)
            assertFalse(result.routeSetupRequired == true)
        }
    }

    @Test
    fun `participant overview and personal detail agree on a name-only legacy route`() {
        whenever(schedules.findScheduleDetail(363, 296)).thenReturn(schedule())
        val overview = service.getOverview(296, 363)
        assertEquals(ScheduleTravelPlanStatus.NOT_CONFIGURED, overview.myTravelPlan?.status)
        assertEquals(1, overview.participants.size)
        assertEquals(ScheduleTravelPlanStatus.NOT_CONFIGURED, overview.participants.single().status)
    }
}
