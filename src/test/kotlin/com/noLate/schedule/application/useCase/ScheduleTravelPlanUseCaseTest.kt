package com.noLate.schedule.application.useCase

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.application.cache.ScheduleCalendarCacheInvalidationEvent
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.never
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.check
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher

@ExtendWith(MockitoExtension::class)
class ScheduleTravelPlanUseCaseTest {
    @Mock lateinit var travelPlanService: ScheduleTravelPlanService
    @Mock lateinit var scheduleService: ScheduleService
    @Mock lateinit var pushJobService: SchedulePushJobService
    @Mock lateinit var eventPublisher: ApplicationEventPublisher

    private lateinit var useCase: ScheduleTravelPlanUseCase

    @BeforeEach
    fun setUp() {
        useCase = ScheduleTravelPlanUseCase(
            travelPlanService,
            scheduleService,
            pushJobService,
            eventPublisher,
        )
    }

    @Test
    fun `saving an enabled personal alert registers only that members push job`() {
        val command = ScheduleTravelPlanUpsertCommand(notificationEnabled = true)
        val plan = plan(notificationEnabled = true)
        val schedule = schedule()
        whenever(travelPlanService.upsertMyTravelPlan(2L, 10L, command)).thenReturn(plan)
        whenever(scheduleService.getScheduleDetail(2L, 10L)).thenReturn(schedule)

        useCase.upsertMyTravelPlan(2L, 10L, command, 6L)

        verify(pushJobService).lockForTravelPlanEdit(10L, 2L, 6L)
        verify(pushJobService).registerFromTravelPlanDto(2L, schedule, plan)
        verify(pushJobService, never()).cancelByScheduleIdAndMemberId(10L, 2L)
        verify(eventPublisher).publishEvent(check<ScheduleCalendarCacheInvalidationEvent> {
            assertEquals(setOf(2L), it.memberIds)
            assertEquals("travel-plan-updated", it.reason)
        })
    }

    @Test
    fun `turning off a personal alert cancels only that members push job`() {
        val command = ScheduleTravelPlanUpsertCommand(notificationEnabled = false)
        val plan = plan(notificationEnabled = false)
        whenever(travelPlanService.upsertMyTravelPlan(2L, 10L, command)).thenReturn(plan)
        whenever(scheduleService.getScheduleDetail(2L, 10L)).thenReturn(schedule())

        useCase.upsertMyTravelPlan(2L, 10L, command, 6L)

        verify(pushJobService).lockForTravelPlanEdit(10L, 2L, 6L)
        verify(pushJobService).cancelByScheduleIdAndMemberId(10L, 2L)
        verify(pushJobService, never()).registerFromTravelPlanDto(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
        )
    }

    @Test
    fun `stale generation is rejected before travel plan or push job mutation`() {
        val command = ScheduleTravelPlanUpsertCommand(notificationEnabled = true)
        doThrow(BusinessException(ErrorCode.INVALID_TOKEN))
            .whenever(pushJobService)
            .lockForTravelPlanEdit(10L, 2L, 5L)

        val failure = assertThrows<BusinessException> {
            useCase.upsertMyTravelPlan(2L, 10L, command, 5L)
        }

        assertEquals(ErrorCode.INVALID_TOKEN, failure.errorCode)
        verify(travelPlanService, never()).upsertMyTravelPlan(2L, 10L, command)
        verify(pushJobService, never()).registerFromTravelPlanDto(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
        )
        verify(pushJobService, never()).cancelByScheduleIdAndMemberId(10L, 2L)
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
