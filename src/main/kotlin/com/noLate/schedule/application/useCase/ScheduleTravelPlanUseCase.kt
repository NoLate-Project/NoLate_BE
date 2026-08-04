package com.noLate.schedule.application.useCase

import com.noLate.schedule.application.cache.ScheduleCalendarCacheInvalidationEvent
import com.noLate.schedule.application.cache.ScheduleCalendarCacheAudienceResolver
import com.noLate.schedule.application.service.SchedulePushJobService
import com.noLate.schedule.application.service.ScheduleService
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import com.noLate.schedule.domain.ScheduleTravelPlanOverviewDto
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import jakarta.transaction.Transactional
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class ScheduleTravelPlanUseCase(
    private val travelPlanService: ScheduleTravelPlanService,
    private val scheduleService: ScheduleService,
    private val pushJobService: SchedulePushJobService,
    private val eventPublisher: ApplicationEventPublisher,
    private val cacheAudienceResolver: ScheduleCalendarCacheAudienceResolver,
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
        presentedSessionGeneration: Long,
    ): ScheduleTravelPlanDto {
        pushJobService.lockForTravelPlanEdit(
            scheduleId = scheduleId,
            memberId = memberId,
            presentedSessionGeneration = presentedSessionGeneration,
        )
        val plan = travelPlanService.upsertMyTravelPlan(memberId, scheduleId, command)
        val schedule = scheduleService.getScheduleDetail(memberId, scheduleId)
        if (plan.notificationEnabled) {
            pushJobService.registerFromTravelPlanDto(memberId, schedule, plan)
        } else {
            pushJobService.cancelByScheduleIdAndMemberId(scheduleId, memberId)
        }
        // 월 일정 DTO에는 조회자 본인의 이동 계획이 투영된다. DB 저장과 push job 변경이
        // 모두 성공한 transaction만 durable cache revision을 갱신한다. 상세 DTO의
        // travelPlanParticipants는 이 조회 경로에서 비어 있으므로 이를 audience로 사용하지
        // 않는다. direct/category/calendar의 schedule-only viewer까지 공통 목적지 변경의
        // 영향을 받으므로 공유 저장소 기반 resolver로 전체 visibility audience를 계산한다.
        val cacheAudienceMemberIds = cacheAudienceResolver.resolve(schedule) + memberId
        eventPublisher.publishEvent(
            ScheduleCalendarCacheInvalidationEvent(
                memberIds = cacheAudienceMemberIds,
                reason = "travel-plan-updated",
            )
        )
        return plan
    }
}
