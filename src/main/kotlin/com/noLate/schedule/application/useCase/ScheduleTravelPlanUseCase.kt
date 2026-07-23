package com.noLate.schedule.application.useCase

import com.noLate.schedule.application.service.SchedulePushJobService
import com.noLate.schedule.application.service.ScheduleService
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import com.noLate.schedule.domain.ScheduleTravelPlanOverviewDto
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import jakarta.transaction.Transactional
import org.springframework.stereotype.Component

@Component
class ScheduleTravelPlanUseCase(
    private val travelPlanService: ScheduleTravelPlanService,
    private val scheduleService: ScheduleService,
    private val pushJobService: SchedulePushJobService,
) {
    fun getOverview(memberId: Long, scheduleId: Long): ScheduleTravelPlanOverviewDto =
        travelPlanService.getOverview(memberId, scheduleId)

    fun getTravelPlan(
        requesterMemberId: Long,
        scheduleId: Long,
        targetMemberId: Long,
    ): ScheduleTravelPlanDto = travelPlanService.getTravelPlan(
        requesterMemberId = requesterMemberId,
        scheduleId = scheduleId,
        targetMemberId = targetMemberId,
    )

    /**
     * 개인 계획 저장과 PushJob 갱신을 하나의 트랜잭션으로 묶는다. 일정 오너의 알림 작업과
     * 공유 참가자의 알림 작업은 `(scheduleId, memberId)`로 구분되므로 한 사용자가 알림을
     * 끄더라도 다른 참가자의 작업은 취소되지 않는다.
     */
    @Transactional
    fun upsertMyTravelPlan(
        memberId: Long,
        scheduleId: Long,
        command: ScheduleTravelPlanUpsertCommand,
    ): ScheduleTravelPlanDto {
        val plan = travelPlanService.upsertMyTravelPlan(memberId, scheduleId, command)
        val schedule = scheduleService.getScheduleDetail(memberId, scheduleId)
        if (plan.notificationEnabled) {
            pushJobService.registerFromTravelPlanDto(memberId, schedule, plan)
        } else {
            pushJobService.cancelByScheduleIdAndMemberId(scheduleId, memberId)
        }
        return plan
    }
}
