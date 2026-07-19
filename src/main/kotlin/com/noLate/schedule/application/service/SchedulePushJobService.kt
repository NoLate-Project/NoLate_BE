package com.noLate.schedule.application.service

import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobDto
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

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

        return register(
            memberId = memberId,
            scheduleId = scheduleId,
            scheduleAt = schedule.startAt,
            departureAt = departureAt,
            monitorStartAt = monitorStartAt,
            intervalMinutes = intervalMinutes,
        )

    }

    /**
     * 공유 참가자의 개인 계획으로 PushJob을 등록한다. 기존 메서드와 같은 작업 엔티티를 쓰되
     * 조회 유일키에 memberId를 포함해 오너와 참가자 알림이 서로 갱신되거나 취소되지 않게 한다.
     */
    @Transactional
    fun registerFromTravelPlanDto(
        memberId: Long,
        scheduleDto: ScheduleDto,
        plan: ScheduleTravelPlanDto,
    ): SchedulePushJobDto? {
        if (!plan.notificationEnabled) return null
        val scheduleId = scheduleDto.id ?: return null
        val scheduleAt = parseInstant(scheduleDto.startAt)
        val travelMinutes = requireNotNull(plan.travelMinutes) {
            "출발 알림을 생성하려면 travelMinutes가 필요합니다."
        }
        val departureAt = plan.departAt?.let(::parseInstant)
            ?: scheduleAt.minusSeconds(travelMinutes.toLong() * 60)
        val leadMinutes = plan.notificationLeadMinutes ?: 60
        val intervalMinutes = plan.notificationIntervalMinutes ?: 20
        return register(
            memberId = memberId,
            scheduleId = scheduleId,
            scheduleAt = scheduleAt,
            departureAt = departureAt,
            monitorStartAt = departureAt.minusSeconds(leadMinutes.toLong() * 60),
            intervalMinutes = intervalMinutes,
        )
    }

    private fun register(
        memberId: Long,
        scheduleId: Long,
        scheduleAt: Instant,
        departureAt: Instant,
        monitorStartAt: Instant,
        intervalMinutes: Int,
    ): SchedulePushJobDto {
        val pushJob = schedulePushJobRepository.findByScheduleIdAndMemberId(scheduleId, memberId)
            ?.apply {
                changeSchedule(
                    scheduleAt = scheduleAt,
                    departureAt = departureAt,
                    monitorStartAt = monitorStartAt,
                    intervalMinutes = intervalMinutes,
                )
            }
            ?: SchedulePushJob.create(
                memberId = memberId,
                scheduleId = scheduleId,
                scheduleAt = scheduleAt,
                departureAt = departureAt,
                monitorStartAt = monitorStartAt,
                intervalMinutes = intervalMinutes,
            )

        return SchedulePushJobDto.fromEntity(schedulePushJobRepository.save(pushJob))

    }

    @Transactional
    fun cancelByScheduleId(scheduleId: Long) {
        schedulePushJobRepository.findAllByScheduleId(scheduleId).forEach { it.cancel() }
    }

    @Transactional
    fun cancelByScheduleIdAndMemberId(scheduleId: Long, memberId: Long) {
        schedulePushJobRepository.findByScheduleIdAndMemberId(scheduleId, memberId)?.cancel()
    }

    private fun parseInstant(value: String): Instant =
        runCatching { Instant.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value).toInstant() }
            .recoverCatching { LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Seoul")).toInstant() }
            .getOrThrow()
}
