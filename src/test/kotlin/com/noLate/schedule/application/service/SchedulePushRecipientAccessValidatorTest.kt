package com.noLate.schedule.application.service

import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class SchedulePushRecipientAccessValidatorTest {

    @Mock lateinit var scheduleRepository: ScheduleRepository
    @Mock lateinit var accessPolicy: ScheduleAccessPolicy
    @Mock lateinit var categoryRepository: ScheduleCategoryRepository
    @Mock lateinit var categoryShareRepository: ScheduleCategoryShareRepository

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

    private fun validator() = SchedulePushRecipientAccessValidator(
        scheduleRepository = scheduleRepository,
        accessPolicy = accessPolicy,
        categoryRepository = categoryRepository,
        categoryShareRepository = categoryShareRepository,
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
