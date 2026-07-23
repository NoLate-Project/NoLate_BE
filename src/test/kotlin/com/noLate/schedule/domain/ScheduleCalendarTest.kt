package com.noLate.schedule.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduleCalendarTest {

    @Test
    fun `calendar settings update without changing ownership`() {
        val calendar = ScheduleCalendar(
            ownerMemberId = 7L,
            title = "가족",
            color = "#2F80FF",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
        )

        calendar.updateSettings(
            title = "우리 가족",
            color = "#5A96FF",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_ONLY,
        )

        assertEquals(7L, calendar.ownerMemberId)
        assertEquals("우리 가족", calendar.title)
        assertEquals("#5A96FF", calendar.color)
        assertEquals(ScheduleShareContentMode.SCHEDULE_ONLY, calendar.defaultContentMode)
        assertEquals(ScheduleCalendarStatus.ACTIVE, calendar.status)
    }

    @Test
    fun `archive removes calendar from active access without deleting schedules`() {
        val calendar = ScheduleCalendar(ownerMemberId = 7L, title = "가족")

        calendar.archive()

        assertEquals(ScheduleCalendarStatus.ARCHIVED, calendar.status)
        assertTrue(calendar.deleted)
    }

    @Test
    fun `member reactivation reuses row and applies latest role`() {
        val member = ScheduleCalendarMember(
            calendarId = 11L,
            memberId = 22L,
            role = ScheduleCalendarRole.VIEWER,
        )
        member.remove()

        member.activate(ScheduleCalendarRole.EDITOR)

        assertEquals(ScheduleCalendarRole.EDITOR, member.role)
        assertEquals(ScheduleCalendarMemberStatus.ACTIVE, member.status)
        assertFalse(member.deleted)
    }

    @Test
    fun `travel content mode wins when grants overlap`() {
        assertEquals(
            ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            ScheduleShareContentMode.widest(
                ScheduleShareContentMode.SCHEDULE_ONLY,
                ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            ),
        )
        assertEquals(
            ScheduleShareContentMode.SCHEDULE_ONLY,
            ScheduleShareContentMode.widest(ScheduleShareContentMode.SCHEDULE_ONLY, null),
        )
    }
}
