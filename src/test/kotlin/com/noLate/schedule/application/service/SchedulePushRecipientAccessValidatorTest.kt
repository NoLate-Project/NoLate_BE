package com.noLate.schedule.application.service

import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.mock.env.MockEnvironment
import java.time.Instant
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class SchedulePushRecipientAccessValidatorTest {

    @Mock lateinit var scheduleRepository: ScheduleRepository
    @Mock lateinit var accessPolicy: ScheduleAccessPolicy
    @Mock lateinit var categoryRepository: ScheduleCategoryRepository
    @Mock lateinit var categoryShareRepository: ScheduleCategoryShareRepository
    @Mock lateinit var calendarRepository: ScheduleCalendarRepository
    @Mock lateinit var calendarMemberRepository: ScheduleCalendarMemberRepository

    @Test
    fun `global off rejects every sharing-only payload even when frozen identity is missing`() {
        listOf(
            "SCHEDULE_SHARE_RECEIVED",
            "CATEGORY_SHARE_RECEIVED",
            "CALENDAR_SHARE_RECEIVED",
            "SCHEDULE_PARTICIPANT_DEPARTED",
            "SCHEDULE_DEPARTURE_NUDGE",
        ).forEach { payloadType ->
            assertFalse(
                validator(sharingEnabled = false).canDispatch(
                    memberId = 2L,
                    scheduleId = null,
                    categoryId = null,
                    payloadType = payloadType,
                    calendarId = null,
                ),
                payloadType,
            )
        }

        verifyNoInteractions(
            scheduleRepository,
            accessPolicy,
            categoryRepository,
            categoryShareRepository,
            calendarRepository,
            calendarMemberRepository,
        )
    }

    @Test
    fun `global off preserves owner schedule push and rejects dormant shared recipient`() {
        val schedule = schedule()
        whenever(scheduleRepository.findById(10L)).thenReturn(Optional.of(schedule))
        val validator = validator(sharingEnabled = false)

        assertTrue(
            validator.canDispatch(1L, 10L, null, "SCHEDULE_DEPARTURE_REMINDER")
        )
        assertFalse(
            validator.canDispatch(2L, 10L, null, "SCHEDULE_DEPARTURE_REMINDER")
        )

        verify(accessPolicy, never()).resolve(2L, schedule)
    }

    @Test
    fun `global off rejects legacy schedule payload when frozen schedule identity is missing`() {
        listOf(
            SchedulePushPayloadAccessPolicy.SCHEDULE_PUSH_PAYLOAD_TYPE,
            "SCHEDULE_DETAIL",
            "SCHEDULE_TRAFFIC",
            "SCHEDULE_DEPARTURE_REMINDER",
            "ROUTE_SETUP_REMINDER",
        ).forEach { payloadType ->
            assertFalse(
                validator(sharingEnabled = false).canDispatch(
                    memberId = 2L,
                    scheduleId = null,
                    categoryId = null,
                    payloadType = payloadType,
                    calendarId = null,
                ),
                payloadType,
            )
        }

        assertTrue(
            validator(sharingEnabled = false).canDispatch(
                memberId = 2L,
                scheduleId = null,
                categoryId = null,
                payloadType = "GENERAL",
                calendarId = null,
            )
        )
        verifyNoInteractions(
            scheduleRepository,
            accessPolicy,
            categoryRepository,
            categoryShareRepository,
            calendarRepository,
            calendarMemberRepository,
        )
    }

    @Test
    fun `travel payload rejects participant whose category travel grant was revoked`() {
        val schedule = schedule()
        whenever(scheduleRepository.findById(10L)).thenReturn(Optional.of(schedule))
        whenever(accessPolicy.resolve(2L, schedule)).thenReturn(
            access(canView = false, travelEnabled = false)
        )

        assertFalse(
            validator().canDispatch(
                memberId = 2L,
                scheduleId = 10L,
                categoryId = null,
                payloadType = "SCHEDULE_DEPARTURE_REMINDER",
            )
        )
    }

    @Test
    fun `schedule share navigation keeps schedule-only access while travel payload is denied`() {
        val schedule = schedule()
        whenever(scheduleRepository.findById(10L)).thenReturn(Optional.of(schedule))
        whenever(accessPolicy.resolve(2L, schedule)).thenReturn(
            access(canView = true, travelEnabled = false)
        )

        assertTrue(
            validator().canDispatch(2L, 10L, null, "SCHEDULE_SHARE_RECEIVED")
        )
        assertFalse(
            validator().canDispatch(2L, 10L, null, "ROUTE_SETUP_REMINDER")
        )
        assertTrue(
            validator().canDispatch(2L, 10L, null, "SCHEDULE_DETAIL")
        )
        assertFalse(
            validator().canDispatch(2L, 10L, null, null)
        )
        assertFalse(
            validator().canDispatch(2L, 10L, null, "LEGACY_UNKNOWN_SCHEDULE_ALERT")
        )
    }

    @Test
    fun `deleted category rejects a retained active share source`() {
        val category = ScheduleCategory(
            id = 9L,
            memberId = 1L,
            title = "deleted",
        ).apply { softDelete() }
        whenever(categoryRepository.findById(9L)).thenReturn(Optional.of(category))

        assertFalse(
            validator().canDispatch(2L, null, 9L, "CATEGORY_SHARE_RECEIVED")
        )
    }

    @Test
    fun `calendar share requires the frozen calendar to remain active and visible to recipient`() {
        val calendar = ScheduleCalendar(
            id = 77L,
            ownerMemberId = 1L,
            title = "shared calendar",
        )
        val membership = ScheduleCalendarMember(
            id = 88L,
            calendarId = 77L,
            memberId = 2L,
            role = ScheduleCalendarRole.VIEWER,
        )
        whenever(calendarRepository.findByIdAndStatusAndDeletedFalse(77L))
            .thenReturn(calendar)
        whenever(
            calendarMemberRepository.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(
                77L,
                2L,
                ScheduleCalendarMemberStatus.ACTIVE,
            )
        ).thenReturn(membership)

        assertTrue(
            validator().canDispatch(
                memberId = 2L,
                scheduleId = null,
                categoryId = null,
                payloadType = "CALENDAR_SHARE_RECEIVED",
                calendarId = 77L,
            )
        )
    }

    @Test
    fun `calendar removal archive and missing frozen identity fail closed`() {
        whenever(calendarRepository.findByIdAndStatusAndDeletedFalse(77L))
            .thenReturn(null)

        assertFalse(
            validator().canDispatch(
                memberId = 2L,
                scheduleId = null,
                categoryId = null,
                payloadType = "CALENDAR_SHARE_RECEIVED",
                calendarId = 77L,
            )
        )
        assertFalse(
            validator().canDispatch(
                memberId = 2L,
                scheduleId = null,
                categoryId = null,
                payloadType = "CALENDAR_SHARE_RECEIVED",
                calendarId = null,
            )
        )
    }

    @Test
    fun `calendar share payload cannot bypass calendar membership with a conflicting schedule id`() {
        whenever(calendarRepository.findByIdAndStatusAndDeletedFalse(77L))
            .thenReturn(null)

        assertFalse(
            validator().canDispatch(
                memberId = 2L,
                scheduleId = 10L,
                categoryId = null,
                payloadType = "CALENDAR_SHARE_RECEIVED",
                calendarId = 77L,
            )
        )
        verify(scheduleRepository, never()).findById(10L)
    }

    private fun validator(sharingEnabled: Boolean = true) = SchedulePushRecipientAccessValidator(
        scheduleRepository = scheduleRepository,
        accessPolicy = accessPolicy,
        categoryRepository = categoryRepository,
        categoryShareRepository = categoryShareRepository,
        calendarRepository = calendarRepository,
        calendarMemberRepository = calendarMemberRepository,
        sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
            MockEnvironment().apply {
                if (sharingEnabled) {
                    withProperty("schedule.sharing.enabled", "true")
                }
            }
        ),
    )

    private fun schedule() = Schedule(
        id = 10L,
        memberId = 1L,
        categoryId = 9L,
        title = "shared",
        startAt = Instant.parse("2026-07-25T01:00:00Z"),
        endAt = Instant.parse("2026-07-25T02:00:00Z"),
    )

    private fun access(
        canView: Boolean,
        travelEnabled: Boolean,
    ) = ScheduleAccessDecision(
        canView = canView,
        canEdit = false,
        travelEnabled = travelEnabled,
        canViewAllTravelPlans = false,
    )
}
