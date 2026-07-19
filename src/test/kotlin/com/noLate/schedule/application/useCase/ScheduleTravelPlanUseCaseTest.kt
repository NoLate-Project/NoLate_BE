package com.noLate.schedule.application.useCase

import com.noLate.schedule.application.service.SchedulePushJobService
import com.noLate.schedule.application.service.ScheduleService
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import com.noLate.schedule.domain.ScheduleTravelPlanStatus
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ScheduleTravelPlanUseCaseTest {
    @Mock lateinit var travelPlanService: ScheduleTravelPlanService
    @Mock lateinit var scheduleService: ScheduleService
    @Mock lateinit var pushJobService: SchedulePushJobService

    private lateinit var useCase: ScheduleTravelPlanUseCase

    @BeforeEach
    fun setUp() {
        useCase = ScheduleTravelPlanUseCase(travelPlanService, scheduleService, pushJobService)
    }

    @Test
    fun `saving an enabled personal alert registers only that members push job`() {
        val command = ScheduleTravelPlanUpsertCommand(notificationEnabled = true)
        val plan = plan(notificationEnabled = true)
        val schedule = schedule()
        whenever(travelPlanService.upsertMyTravelPlan(2L, 10L, command)).thenReturn(plan)
        whenever(scheduleService.getScheduleDetail(2L, 10L)).thenReturn(schedule)

        useCase.upsertMyTravelPlan(2L, 10L, command)

        verify(pushJobService).registerFromTravelPlanDto(2L, schedule, plan)
        verify(pushJobService, never()).cancelByScheduleIdAndMemberId(10L, 2L)
    }

    @Test
    fun `turning off a personal alert cancels only that members push job`() {
        val command = ScheduleTravelPlanUpsertCommand(notificationEnabled = false)
        val plan = plan(notificationEnabled = false)
        whenever(travelPlanService.upsertMyTravelPlan(2L, 10L, command)).thenReturn(plan)
        whenever(scheduleService.getScheduleDetail(2L, 10L)).thenReturn(schedule())

        useCase.upsertMyTravelPlan(2L, 10L, command)

        verify(pushJobService).cancelByScheduleIdAndMemberId(10L, 2L)
        verify(pushJobService, never()).registerFromTravelPlanDto(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
        )
    }

    private fun plan(notificationEnabled: Boolean) = ScheduleTravelPlanDto(
        scheduleId = 10L,
        memberId = 2L,
        status = ScheduleTravelPlanStatus.READY,
        notificationEnabled = notificationEnabled,
    )

    private fun schedule() = ScheduleDto(
        id = 10L,
        ownerMemberId = 1L,
        title = "공유 미팅",
        startAt = "2026-07-20T01:00:00Z",
        endAt = "2026-07-20T02:00:00Z",
        category = ScheduleCategoryDto(id = "1", title = "공유", color = "#2979FF"),
    )
}
