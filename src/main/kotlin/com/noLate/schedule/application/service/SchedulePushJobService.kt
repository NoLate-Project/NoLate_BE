package com.noLate.schedule.application.service

import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobDto
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class SchedulePushJobService(
    private val schedulePushJobRepository: SchedulePushJobRepository
) {


    @Transactional
    fun registerFromScheduleDto(
        memberId: Long,
        scheduleDto: ScheduleDto
    ): SchedulePushJobDto? {

        val schedule = scheduleDto.toEntity(memberId)
        val route = schedule.route ?: return null

        if (!route.notificationEnabled) { return null }

        val scheduleId = requireNotNull(schedule.id) { "저장된 일정 ID가 없습니다." }
        val travelMinutes = requireNotNull(route.travelMinutes) { "출발 알림을 생성하려면 travelMinutes가 필요합니다." }
        val departureAt = route.departAt ?: schedule.startAt.minusSeconds(travelMinutes.toLong() * 60)
        val leadMinutes = route.notificationLeadMinutes ?: 60
        val monitorStartAt = departureAt.minusSeconds(leadMinutes.toLong() * 60)
        val intervalMinutes = route.notificationIntervalMinutes ?: 20

        val pushJob = schedulePushJobRepository.findByScheduleId(scheduleId)
            ?.apply {
                changeSchedule(
                    scheduleAt = schedule.startAt,
                    departureAt = departureAt,
                    monitorStartAt = monitorStartAt,
                    intervalMinutes = intervalMinutes,
                )
            }
            ?: SchedulePushJob.create(
                memberId = memberId,
                scheduleId = scheduleId,
                scheduleAt = schedule.startAt,
                departureAt = departureAt,
                monitorStartAt = monitorStartAt,
                intervalMinutes = intervalMinutes,
            )

        val savedPushJob = schedulePushJobRepository.save(pushJob)

        return SchedulePushJobDto.fromEntity(savedPushJob)

    }

    @Transactional
    fun cancelByScheduleId(scheduleId: Long) {
        schedulePushJobRepository.findByScheduleId(scheduleId)?.cancel()
    }
}
