package com.noLate.schedule.application.service

import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobDto
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleNotificationInputFingerprint
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Service
class SchedulePushJobService(
    private val schedulePushJobRepository: SchedulePushJobRepository,
    @Value("\${schedule.push.departure-snooze-minutes:5}")
    private val departureSnoozeMinutes: Long = 5,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * 일정/개인 경로 의미 입력을 수정하기 전에 worker와 같은 job row를 먼저 잠근다.
     * 이 lock이 먼저면 기존 generation의 provider fence가 거절되고, provider fence가
     * 먼저면 기존 immutable event가 논리적으로 먼저 발송된 뒤 편집이 새 generation을 연다.
     */
    @Transactional
    fun lockForScheduleEdit(scheduleId: Long) {
        schedulePushJobRepository.findAllByScheduleIdOrderByIdAsc(scheduleId)
    }

    @Transactional
    fun lockForTravelPlanEdit(scheduleId: Long, memberId: Long) {
        schedulePushJobRepository.findByScheduleIdAndMemberIdForUpdate(scheduleId, memberId)
    }


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
            notificationInputFingerprint =
                ScheduleNotificationInputFingerprint.fromSchedule(memberId, scheduleDto),
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
            notificationInputFingerprint =
                ScheduleNotificationInputFingerprint.fromTravelPlan(memberId, scheduleDto, plan),
        )
    }

    private fun register(
        memberId: Long,
        scheduleId: Long,
        scheduleAt: Instant,
        departureAt: Instant,
        monitorStartAt: Instant,
        intervalMinutes: Int,
        notificationInputFingerprint: String,
    ): SchedulePushJobDto {
        val pushJob = schedulePushJobRepository.findByScheduleIdAndMemberIdForUpdate(scheduleId, memberId)
            ?.apply {
                changeSchedule(
                    scheduleAt = scheduleAt,
                    departureAt = departureAt,
                    monitorStartAt = monitorStartAt,
                    intervalMinutes = intervalMinutes,
                    notificationInputFingerprint = notificationInputFingerprint,
                )
            }
            ?: SchedulePushJob.create(
                memberId = memberId,
                scheduleId = scheduleId,
                scheduleAt = scheduleAt,
                departureAt = departureAt,
                monitorStartAt = monitorStartAt,
                intervalMinutes = intervalMinutes,
                notificationInputFingerprint = notificationInputFingerprint,
            )

        return SchedulePushJobDto.fromEntity(schedulePushJobRepository.save(pushJob))

    }

    @Transactional
    fun cancelByScheduleId(scheduleId: Long) {
        schedulePushJobRepository.findAllByScheduleIdOrderByIdAsc(scheduleId).forEach { it.cancel() }
    }

    @Transactional
    fun cancelByScheduleIdAndMemberId(scheduleId: Long, memberId: Long) {
        schedulePushJobRepository.findByScheduleIdAndMemberIdForUpdate(scheduleId, memberId)?.cancel()
    }

    @Transactional
    fun snoozeDepartureReminder(memberId: Long, scheduleId: Long): Instant? {
        val pushJob = schedulePushJobRepository.findByScheduleIdAndMemberIdForUpdate(scheduleId, memberId)
            ?: return null
        val now = Instant.now(clock)

        if (pushJob.status == SchedulePushJobStatus.CANCELED) {
            return pushJob.snoozedUntil
        }

        if (!now.isBefore(pushJob.scheduleAt)) {
            pushJob.complete()
            return null
        }

        val requestedSnoozeAt = now.plus(departureSnoozeMinutes, ChronoUnit.MINUTES)
        val latestUsefulReminderAt = pushJob.scheduleAt.minus(1, ChronoUnit.MINUTES)
        val nextCheckAt = minOf(requestedSnoozeAt, latestUsefulReminderAt)

        if (!nextCheckAt.isAfter(now)) {
            return pushJob.snoozedUntil
        }

        pushJob.snoozeUntil(nextCheckAt)
        return pushJob.snoozedUntil
    }

    private fun parseInstant(value: String): Instant =
        runCatching { Instant.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value).toInstant() }
            .recoverCatching { LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Seoul")).toInstant() }
            .getOrThrow()
}
