package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleAlertMode
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobDto
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleNotificationInputFingerprint
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ScheduleEditMemberFence(
    val lockedMemberIds: Set<Long>,
) {
    fun requireContains(memberIds: Collection<Long>) {
        if (memberIds.any { it !in lockedMemberIds }) {
            throw ConcurrencyFailureException(
                "Schedule notification participants changed while the edit fence was being acquired.",
            )
        }
    }
}

@Service
class SchedulePushJobService private constructor(
    private val schedulePushJobRepository: SchedulePushJobRepository,
    private val memberRepository: MemberRepository?,
    private val departureAlarmSyncService: DepartureAlarmSyncService?,
    private val departureSnoozeMinutes: Long = 5,
    private val clock: Clock = Clock.systemUTC(),
    @Suppress("UNUSED_PARAMETER") legacyTestBoundary: Boolean,
) {
    @Autowired
    constructor(
        schedulePushJobRepository: SchedulePushJobRepository,
        memberRepository: MemberRepository,
        departureAlarmSyncServiceProvider: ObjectProvider<DepartureAlarmSyncService>,
        @Value("\${schedule.push.departure-snooze-minutes:5}")
        departureSnoozeMinutes: Long = 5,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        schedulePushJobRepository,
        memberRepository,
        departureAlarmSyncServiceProvider.getIfAvailable(),
        departureSnoozeMinutes,
        clock,
        false,
    )

    /** Unit/backfill fixture constructor. Runtime wiring always uses the member-fenced overload. */
    internal constructor(
        schedulePushJobRepository: SchedulePushJobRepository,
        departureSnoozeMinutes: Long = 5,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        schedulePushJobRepository,
        null,
        null,
        departureSnoozeMinutes,
        clock,
        true,
    )

    /** Legacy unit fixtures that exercise only member/job fencing do not require alarm sync. */
    internal constructor(
        schedulePushJobRepository: SchedulePushJobRepository,
        memberRepository: MemberRepository,
    ) : this(
        schedulePushJobRepository,
        memberRepository,
        null,
        5,
        Clock.systemUTC(),
        true,
    )

    /**
     * 일정/개인 경로 의미 입력을 수정하기 전에 관련 member를 정렬 잠금하고, 이어서
     * worker와 같은 job row/gap을 잠근다. 편집의 job lock이 먼저면 기존 generation의
     * provider fence가 거절되고, provider fence가 먼저면 기존 immutable event가 논리적으로
     * 먼저 발송된 뒤 편집이 새 generation을 연다.
     *
     * 현재 job이 하나도 없어도 요청자, 일정 소유자, 알림 활성 travel-plan 회원을 먼저 잠근 뒤
     * schedule_id 범위의 job row/gap을 잠근다. gap을 얻은 뒤 job 회원 집합을 재검증하며,
     * 선조회 뒤 새 회원 job이 나타났다면 gap 뒤에서 그 member를 추가로 잠그지 않고 전체
     * transaction을 재시도 가능 오류로 되돌린다. 따라서 withdrawal/backfill의 member -> job
     * 순서와 반대로 빈 job gap을 먼저 잡는 경로를 만들지 않는다.
     */
    @Transactional
    fun lockForScheduleEdit(
        scheduleId: Long,
        requiredMemberIds: Collection<Long>,
        actorMemberId: Long,
        presentedSessionGeneration: Long,
    ): ScheduleEditMemberFence {
        val memberIds = (
            requiredMemberIds +
                actorMemberId +
                schedulePushJobRepository.findMemberIdsByScheduleId(scheduleId)
            )
            .distinct()
            .sorted()
        val lockedMembers =
            if (memberIds.isNotEmpty()) {
                memberRepository?.findAllByIdsForUpdate(memberIds).orEmpty()
            } else {
                emptyList()
            }
        requireCurrentActorSession(
            lockedMembers.firstOrNull { it.id == actorMemberId },
            presentedSessionGeneration,
        )
        schedulePushJobRepository.findAllByScheduleIdOrderByIdAsc(scheduleId)
        val fence = ScheduleEditMemberFence(memberIds.toSet())
        fence.requireContains(schedulePushJobRepository.findMemberIdsByScheduleId(scheduleId))
        return fence
    }

    @Transactional
    fun lockForTravelPlanEdit(
        scheduleId: Long,
        memberId: Long,
        presentedSessionGeneration: Long,
    ) {
        requireCurrentActorSession(
            memberRepository?.findByIdForUpdate(memberId),
            presentedSessionGeneration,
        )
        schedulePushJobRepository.findByScheduleIdAndMemberIdForUpdate(scheduleId, memberId)
    }

    private fun requireCurrentActorSession(
        member: Member?,
        presentedSessionGeneration: Long,
    ) {
        // Runtime wiring always supplies MemberRepository. The nullable branch exists only for
        // legacy backfill fixture construction and must not authorize an authenticated mutation.
        if (
            member == null ||
            member.deleted ||
            member.sessionGeneration != presentedSessionGeneration
        ) {
            throw BusinessException(
                ErrorCode.INVALID_TOKEN,
                "종료된 로그인 세션입니다.",
            )
        }
    }


    @Transactional
    fun registerFromScheduleDto(
        memberId: Long,
        scheduleDto: ScheduleDto
    ): SchedulePushJobDto? {

        val schedule = scheduleDto.toEntity(memberId)
        val route = schedule.route ?: return null

        if (!route.notificationEnabled) {
            scheduleDto.id?.let { departureAlarmSyncService?.cancel(memberId, it) }
            return null
        }

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
            alertMode = route.alertMode,
            scheduleTitle = scheduleDto.title,
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
        if (!plan.notificationEnabled) {
            scheduleDto.id?.let { departureAlarmSyncService?.cancel(memberId, it) }
            return null
        }
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
            alertMode = plan.alertMode,
            scheduleTitle = scheduleDto.title,
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
        alertMode: ScheduleAlertMode,
        scheduleTitle: String?,
    ): SchedulePushJobDto? {
        if (memberRepository != null &&
            memberRepository.findByIdForUpdate(memberId)?.deleted != false
        ) {
            return null
        }
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

        val saved = schedulePushJobRepository.save(pushJob)
        departureAlarmSyncService?.synchronizeConfigured(
            memberId = memberId,
            scheduleId = scheduleId,
            notificationEnabled = true,
            alertMode = alertMode,
            triggerAt = departureAt,
            scheduleTitle = scheduleTitle,
        )
        return SchedulePushJobDto.fromEntity(saved)

    }

    @Transactional
    fun cancelByScheduleId(scheduleId: Long) {
        val memberIds = (
            schedulePushJobRepository.findAllByScheduleId(scheduleId)
                .map { it.memberId } +
                departureAlarmSyncService?.findMemberIdsForSchedule(scheduleId).orEmpty()
            )
            .distinct()
            .sorted()
        if (memberIds.isNotEmpty()) {
            memberRepository?.findAllByIdsForUpdate(memberIds)
        }
        schedulePushJobRepository.findAllByScheduleIdOrderByIdAsc(scheduleId).forEach { it.cancel() }
        departureAlarmSyncService?.cancelAllForSchedule(scheduleId)
    }

    @Transactional
    fun cancelByScheduleIdAndMemberId(scheduleId: Long, memberId: Long) {
        if (memberRepository != null &&
            memberRepository.findByIdForUpdate(memberId)?.deleted != false
        ) return
        schedulePushJobRepository.findByScheduleIdAndMemberIdForUpdate(scheduleId, memberId)?.cancel()
        departureAlarmSyncService?.cancel(memberId, scheduleId)
    }

    @Transactional
    fun snoozeDepartureReminder(memberId: Long, scheduleId: Long): Instant? {
        if (memberRepository != null &&
            memberRepository.findByIdForUpdate(memberId)?.deleted != false
        ) return null
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
        departureAlarmSyncService?.snooze(
            memberId = memberId,
            scheduleId = scheduleId,
            snoozedUntil = pushJob.snoozedUntil,
            scheduleTitle = null,
        )
        return pushJob.snoozedUntil
    }

    private fun parseInstant(value: String): Instant =
        runCatching { Instant.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value).toInstant() }
            .recoverCatching { LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Seoul")).toInstant() }
            .getOrThrow()
}
