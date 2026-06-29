package com.noLate.schedule.application.service

import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobDto
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class SchedulePushJobService(
    private val schedulePushJobRepository: SchedulePushJobRepository,
    @Value("\${schedule.push.departure-snooze-minutes:5}")
    private val departureSnoozeMinutes: Long = 5,
    private val clock: Clock = Clock.systemUTC(),
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

    @Transactional
    fun snoozeDepartureReminder(memberId: Long, scheduleId: Long) {
        val pushJob = schedulePushJobRepository.findByScheduleIdAndMemberId(scheduleId, memberId)
            ?: return
        val now = Instant.now(clock)

        if (pushJob.status == SchedulePushJobStatus.CANCELED) {
            return
        }

        if (!now.isBefore(pushJob.scheduleAt)) {
            pushJob.complete()
            return
        }

        val requestedSnoozeAt = now.plus(departureSnoozeMinutes, ChronoUnit.MINUTES)
        val latestUsefulReminderAt = pushJob.scheduleAt.minus(1, ChronoUnit.MINUTES)
        val nextCheckAt = minOf(requestedSnoozeAt, latestUsefulReminderAt)

        if (!nextCheckAt.isAfter(now)) {
            // 일정이 거의 시작된 경우에는 "5분 뒤" 알림이 의미 없으므로 다음 worker에서 종료되게 둔다.
            return
        }

        pushJob.snoozeUntil(nextCheckAt)
    }
}
