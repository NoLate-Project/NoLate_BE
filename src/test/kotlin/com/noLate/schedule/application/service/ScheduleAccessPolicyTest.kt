package com.noLate.schedule.application.service

import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.domain.ScheduleType
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ScheduleAccessPolicyTest {
    private val directShares = mock<ScheduleShareRepository>()
    private val categoryShares = mock<ScheduleCategoryShareRepository>()
    private val calendars = mock<ScheduleCalendarRepository>()
    private val calendarMembers = mock<ScheduleCalendarMemberRepository>()
    private lateinit var policy: ScheduleAccessPolicy

    @BeforeEach
    fun setUp() {
        policy = ScheduleAccessPolicy(directShares, categoryShares, calendars, calendarMembers)
    }

    @Test
    fun `direct and calendar grants combine strongest permission and widest content`() {
        val schedule = routeSchedule(ownerId = 1L, calendarId = 30L)
        whenever(directShares.findByScheduleIdAndTargetMemberId(10L, 2L)).thenReturn(
            ScheduleShare(
                scheduleId = 10L,
                ownerMemberId = 1L,
                targetMemberId = 2L,
                permission = ScheduleSharePermission.EDITOR,
                contentMode = ScheduleShareContentMode.SCHEDULE_ONLY,
            )
        )
        whenever(calendarMembers.findByCalendarIdAndMemberId(30L, 2L)).thenReturn(
            ScheduleCalendarMember(
                calendarId = 30L,
                memberId = 2L,
                role = ScheduleCalendarRole.VIEWER,
            )
        )
        whenever(calendars.findByIdAndStatusAndDeletedFalse(30L)).thenReturn(
            ScheduleCalendar(
                id = 30L,
                ownerMemberId = 1L,
                title = "가족",
                defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            )
        )

        val access = policy.resolve(memberId = 2L, schedule = schedule)

        assertTrue(access.canView)
        assertTrue(access.canEdit)
        assertTrue(access.travelEnabled)
        assertTrue(access.canViewAllTravelPlans)
        assertEquals(ScheduleSharePermission.EDITOR, access.effectivePermission)
        assertEquals(ScheduleShareContentMode.SCHEDULE_AND_TRAVEL, access.effectiveContentMode)
    }

    @Test
    fun `normal schedule never enables travel even with travel share`() {
        val schedule = routeSchedule(ownerId = 1L, calendarId = null).apply {
            scheduleType = ScheduleType.NORMAL
        }
        whenever(directShares.findByScheduleIdAndTargetMemberId(10L, 2L)).thenReturn(
            ScheduleShare(
                scheduleId = 10L,
                ownerMemberId = 1L,
                targetMemberId = 2L,
                permission = ScheduleSharePermission.VIEWER,
                contentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            )
        )

        val access = policy.resolve(memberId = 2L, schedule = schedule)

        assertTrue(access.canView)
        assertFalse(access.travelEnabled)
    }

    @Test
    fun `revoked direct share without calendar membership grants no access`() {
        val schedule = routeSchedule(ownerId = 1L, calendarId = null)
        whenever(directShares.findByScheduleIdAndTargetMemberId(10L, 2L)).thenReturn(
            ScheduleShare(
                scheduleId = 10L,
                ownerMemberId = 1L,
                targetMemberId = 2L,
                permission = ScheduleSharePermission.EDITOR,
                status = ScheduleShareStatus.REVOKED,
            )
        )

        val access = policy.resolve(memberId = 2L, schedule = schedule)

        assertFalse(access.canView)
        assertFalse(access.canEdit)
        assertFalse(access.travelEnabled)
    }

    @Test
    fun `owner always keeps route management regardless of share rows`() {
        val schedule = routeSchedule(ownerId = 1L, calendarId = 30L)

        val access = policy.resolve(memberId = 1L, schedule = schedule)

        assertTrue(access.canView)
        assertTrue(access.canEdit)
        assertTrue(access.travelEnabled)
        assertTrue(access.canViewAllTravelPlans)
        assertEquals(ScheduleSharePermission.OWNER, access.effectivePermission)
    }

    @Test
    fun `calendar reminder opt out is honored unless direct travel sharing overlaps`() {
        val schedule = routeSchedule(ownerId = 1L, calendarId = 30L)
        val membership = ScheduleCalendarMember(
            calendarId = 30L,
            memberId = 2L,
            role = ScheduleCalendarRole.VIEWER,
        ).apply { updateRouteReminder(false) }
        whenever(calendarMembers.findByCalendarIdAndMemberId(30L, 2L)).thenReturn(membership)
        whenever(calendars.findByIdAndStatusAndDeletedFalse(30L)).thenReturn(
            ScheduleCalendar(
                id = 30L,
                ownerMemberId = 1L,
                title = "가족",
                defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            )
        )

        assertFalse(policy.routeReminderEnabled(2L, schedule))

        whenever(directShares.findByScheduleIdAndTargetMemberId(10L, 2L)).thenReturn(
            ScheduleShare(
                scheduleId = 10L,
                ownerMemberId = 1L,
                targetMemberId = 2L,
                contentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            )
        )
        assertTrue(policy.routeReminderEnabled(2L, schedule))
    }

    @Test
    fun `calendar owner reminder opt out is honored`() {
        val schedule = routeSchedule(ownerId = 1L, calendarId = 30L)
        val ownerMembership = ScheduleCalendarMember(
            calendarId = 30L,
            memberId = 1L,
            role = ScheduleCalendarRole.OWNER,
        ).apply { updateRouteReminder(false) }
        whenever(calendarMembers.findByCalendarIdAndMemberId(30L, 1L)).thenReturn(ownerMembership)

        assertFalse(policy.routeReminderEnabled(1L, schedule))
    }

    @Test
    fun `batch reminder members exclude an opted out calendar owner`() {
        val schedule = routeSchedule(ownerId = 1L, calendarId = 30L)
        val ownerMembership = ScheduleCalendarMember(
            calendarId = 30L,
            memberId = 1L,
            role = ScheduleCalendarRole.OWNER,
        ).apply { updateRouteReminder(false) }
        whenever(
            directShares.findAllByScheduleIdInAndStatusAndDeletedFalseOrderByScheduleIdAscIdAsc(
                listOf(10L),
                ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(emptyList())
        whenever(calendars.findAllById(listOf(30L))).thenReturn(
            listOf(
                ScheduleCalendar(
                    id = 30L,
                    ownerMemberId = 1L,
                    title = "가족",
                    defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
                )
            )
        )
        whenever(
            calendarMembers.findAllByCalendarIdInAndStatusAndDeletedFalseOrderByCalendarIdAscIdAsc(
                listOf(30L)
            )
        ).thenReturn(
            listOf(
                ownerMembership,
                ScheduleCalendarMember(
                    calendarId = 30L,
                    memberId = 2L,
                    role = ScheduleCalendarRole.VIEWER,
                ),
            )
        )

        val result = policy.routeReminderMemberIdsAll(listOf(schedule))

        assertEquals(listOf(2L), result[10L])
    }

    @Test
    fun `one hundred calendar schedules use one batch query per grant repository`() {
        val schedules = (1L..100L).map { scheduleId ->
            Schedule(
                id = scheduleId,
                memberId = 1L,
                calendarId = 30L,
                title = "일정 $scheduleId",
                scheduleType = ScheduleType.ROUTE,
            )
        }
        whenever(
            directShares.findAllByScheduleIdInAndStatusAndDeletedFalseOrderByScheduleIdAscIdAsc(
                schedules.mapNotNull { it.id },
                ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(emptyList())
        whenever(calendars.findAllById(listOf(30L))).thenReturn(
            listOf(
                ScheduleCalendar(
                    id = 30L,
                    ownerMemberId = 1L,
                    title = "가족",
                    defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
                )
            )
        )
        whenever(
            calendarMembers.findAllByCalendarIdInAndStatusAndDeletedFalseOrderByCalendarIdAscIdAsc(
                listOf(30L)
            )
        ).thenReturn(
            listOf(
                ScheduleCalendarMember(
                    calendarId = 30L,
                    memberId = 1L,
                    role = ScheduleCalendarRole.OWNER,
                ),
                ScheduleCalendarMember(
                    calendarId = 30L,
                    memberId = 2L,
                    role = ScheduleCalendarRole.VIEWER,
                ),
            )
        )

        val result = policy.routeReminderMemberIdsAll(schedules)

        assertEquals(100, result.size)
        assertTrue(result.values.all { it == listOf(1L, 2L) })
        verify(directShares, times(1))
            .findAllByScheduleIdInAndStatusAndDeletedFalseOrderByScheduleIdAscIdAsc(
                schedules.mapNotNull { it.id },
                ScheduleShareStatus.ACTIVE,
            )
        verify(calendarMembers, times(1))
            .findAllByCalendarIdInAndStatusAndDeletedFalseOrderByCalendarIdAscIdAsc(listOf(30L))
    }

    private fun routeSchedule(ownerId: Long, calendarId: Long?) = Schedule(
        id = 10L,
        memberId = ownerId,
        calendarId = calendarId,
        title = "약속",
        scheduleType = ScheduleType.ROUTE,
    )
}
