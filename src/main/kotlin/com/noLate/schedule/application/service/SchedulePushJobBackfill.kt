package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.logging.BackgroundSchedulerSqlContext
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.domain.ScheduleTravelPlanStatus
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.SchedulePushJobAlarmBackfillCandidate
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

data class SchedulePushJobBackfillCandidate(
    val memberId: Long,
    val scheduleId: Long,
    val travelPlanId: Long? = null,
) {
    val ownerSchedule: Boolean
        get() = travelPlanId == null
}

/**
 * Startup scan은 짧은 read-only transaction에서 불변 PK만 꺼낸다. 실제 member/job lock은
 * 후보별 writer transaction이 담당하므로 backlog 전체를 하나의 persistence context나
 * transaction에 묶지 않는다.
 */
@Service
class SchedulePushJobBackfillCandidateReader(
    private val scheduleRepository: ScheduleRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val schedulePushJobRepository: SchedulePushJobRepository,
) {
    @Transactional(readOnly = true)
    fun findCandidates(now: Instant): List<SchedulePushJobBackfillCandidate> {
        val ownerCandidates = scheduleRepository
            .findNotificationEnabledWithoutPushJob(now)
            .mapNotNull { schedule ->
                schedule.id?.let {
                    SchedulePushJobBackfillCandidate(
                        memberId = schedule.memberId,
                        scheduleId = it,
                    )
                }
            }
        val participantCandidates = travelPlanRepository
            .findNotificationEnabledParticipantsWithoutPushJob(now)
            .mapNotNull { plan ->
                plan.id?.let {
                    SchedulePushJobBackfillCandidate(
                        memberId = plan.memberId,
                        scheduleId = plan.scheduleId,
                        travelPlanId = it,
                    )
                }
            }
        return (ownerCandidates + participantCandidates).sortedWith(
            compareBy<SchedulePushJobBackfillCandidate>(
                SchedulePushJobBackfillCandidate::memberId,
                SchedulePushJobBackfillCandidate::scheduleId,
            ).thenBy { it.travelPlanId ?: -1L },
        )
    }

    @Transactional(readOnly = true)
    fun findAlarmSyncCandidates(now: Instant): List<SchedulePushJobAlarmBackfillCandidate> =
        schedulePushJobRepository.findAlarmSyncBackfillCandidates(now)
}

/**
 * 후보 하나만 member -> schedule/plan -> push-job 순서로 재검증하고 commit한다.
 * 두 인스턴스가 같은 startup drain을 실행해도 member/job row가 직렬화 지점이 되며, scan 뒤
 * withdrawal/edit가 먼저 끝난 후보는 새 job을 만들지 않는다.
 */
@Service
class SchedulePushJobBackfillPairWriter(
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val schedulePushJobRepository: SchedulePushJobRepository,
    private val schedulePushJobService: SchedulePushJobService,
    private val scheduleAccessPolicy: ScheduleAccessPolicy,
    private val objectMapper: ObjectMapper,
    private val departureAlarmSyncService: DepartureAlarmSyncService? = null,
) {
    fun alarmSyncEnabled(): Boolean = departureAlarmSyncService != null

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun register(
        candidate: SchedulePushJobBackfillCandidate,
        now: Instant,
    ): Boolean {
        val member = memberRepository.findByIdForUpdate(candidate.memberId)
            ?.takeUnless { it.deleted }
            ?: return false
        if (member.id != candidate.memberId) return false

        val schedule = scheduleRepository.findById(candidate.scheduleId)
            .orElse(null)
            ?.takeUnless { it.deleted }
            ?.takeIf { it.startAt.isAfter(now) }
            ?: return false

        if (candidate.ownerSchedule) {
            if (schedule.memberId != candidate.memberId) return false
            schedule.route
                ?.takeIf { it.notificationEnabled && it.travelMinutes != null }
                ?: return false
            return schedulePushJobService.registerFromScheduleDto(
                memberId = candidate.memberId,
                scheduleDto = schedule.toDto(objectMapper),
            ) != null
        }

        // A retained legacy plan is not itself an authorization grant. The recipient member lock
        // above serializes this final policy read with direct/category/calendar revoke. If revoke
        // wins, startup must not recreate a participant job from the stale plan.
        if (!scheduleAccessPolicy.resolve(candidate.memberId, schedule).travelEnabled) {
            return false
        }

        val plan = travelPlanRepository.findById(requireNotNull(candidate.travelPlanId))
            .orElse(null)
            ?.takeUnless { it.deleted }
            ?.takeIf {
                it.memberId == candidate.memberId &&
                    it.scheduleId == candidate.scheduleId &&
                    it.memberId != schedule.memberId &&
                    it.notificationEnabled &&
                    it.travelMinutes != null
            }
            ?: return false
        if (!ScheduleTravelPlanFingerprint.matches(plan, schedule)) return false

        return schedulePushJobService.registerFromTravelPlanDto(
            memberId = candidate.memberId,
            scheduleDto = schedule.toDto(objectMapper),
            plan = plan.toBackfillDto(schedule, objectMapper),
        ) != null
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun synchronizeAlarm(
        candidate: SchedulePushJobAlarmBackfillCandidate,
        now: Instant,
    ): Boolean {
        val member = memberRepository.findByIdForUpdate(candidate.memberId)
            ?.takeUnless { it.deleted }
            ?: return false
        if (member.id != candidate.memberId) return false
        // Preserve the global member -> job order used by normal edits and workers.
        val job = schedulePushJobRepository.findByIdForUpdate(candidate.jobId)
            ?.takeIf {
                it.memberId == candidate.memberId &&
                    it.scheduleId == candidate.scheduleId &&
                    it.status == SchedulePushJobStatus.ACTIVE
            }
            ?: return false
        val schedule = scheduleRepository.findById(job.scheduleId)
            .orElse(null)
            ?.takeUnless { it.deleted }
            ?.takeIf { it.startAt.isAfter(now) }
            ?: return false

        val personal = travelPlanRepository
            .findByScheduleIdAndMemberIdAndDeletedFalse(job.scheduleId, job.memberId)
            ?.takeIf {
                it.notificationEnabled &&
                    it.travelMinutes != null &&
                    ScheduleTravelPlanFingerprint.matches(it, schedule)
            }
        val route = if (personal != null) {
            AlarmBackfillRoute(
                departureAt = personal.departAt
                    ?: schedule.startAt.minusSeconds(requireNotNull(personal.travelMinutes).toLong() * 60),
                alertMode = personal.alertMode,
            )
        } else {
            schedule.route
                ?.takeIf {
                    schedule.memberId == job.memberId &&
                        it.notificationEnabled &&
                        it.travelMinutes != null
                }
                ?.let {
                    AlarmBackfillRoute(
                        departureAt = it.departAt
                            ?: schedule.startAt.minusSeconds(requireNotNull(it.travelMinutes).toLong() * 60),
                        alertMode = it.alertMode,
                    )
                }
                ?: return false
        }

        return departureAlarmSyncService?.synchronizeConfigured(
            memberId = job.memberId,
            scheduleId = job.scheduleId,
            notificationEnabled = true,
            alertMode = route.alertMode,
            triggerAt = route.departureAt,
            scheduleTitle = schedule.title,
        ) != null
    }
}

@Component
class SchedulePushJobBackfill(
    private val candidateReader: SchedulePushJobBackfillCandidateReader,
    private val pairWriter: SchedulePushJobBackfillPairWriter,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun registerMissingJobs() = BackgroundSchedulerSqlContext.suppressSuccessfulSql {
        val now = Instant.now(clock)
        var ownerCount = 0
        var participantCount = 0
        var alarmSyncCount = 0
        candidateReader.findCandidates(now).forEach { candidate ->
            if (pairWriter.register(candidate, now)) {
                if (candidate.ownerSchedule) ownerCount += 1 else participantCount += 1
            }
        }
        if (pairWriter.alarmSyncEnabled()) {
            candidateReader.findAlarmSyncCandidates(now).forEach { candidate ->
                if (pairWriter.synchronizeAlarm(candidate, now)) {
                    alarmSyncCount += 1
                }
            }
        }

        val recoveredCount = ownerCount + participantCount + alarmSyncCount
        if (recoveredCount > 0) {
            log.info(
                "Recovered missing schedule push jobs/alarm states. " +
                    "ownerCount={}, participantCount={}, alarmSyncCount={}, totalCount={}",
                ownerCount,
                participantCount,
                alarmSyncCount,
                recoveredCount,
            )
        }
    }
}

private data class AlarmBackfillRoute(
    val departureAt: Instant,
    val alertMode: com.noLate.schedule.domain.ScheduleAlertMode,
)

/**
 * Backfill도 정상 저장 API와 같은 full plan DTO를 사용해야 runtime notification input
 * fingerprint가 일치한다. 일부 시각 필드만 조립한 migration fingerprint는 최초 동일 PUT을
 * 의미 변경으로 오인해 generation/check 상태를 reset할 수 있으므로 사용하지 않는다.
 */
private fun ScheduleTravelPlan.toBackfillDto(
    schedule: Schedule,
    objectMapper: ObjectMapper,
): ScheduleTravelPlanDto =
    ScheduleTravelPlanDto(
        id = id,
        scheduleId = scheduleId,
        memberId = memberId,
        status = ScheduleTravelPlanStatus.READY,
        canManageSchedule = false,
        travelMinutes = travelMinutes,
        departAt = departAt?.toString(),
        travelMode = travelMode,
        origin = backfillPlace(originName, originAddress, originLat, originLng),
        destination = schedule.route?.let {
            backfillPlace(
                it.destinationName,
                it.destinationAddress,
                it.destinationLat,
                it.destinationLng,
            )
        },
        route = routeJson?.takeIf(String::isNotBlank)?.let { objectMapper.readTree(it) },
        notificationEnabled = notificationEnabled,
        notificationLeadMinutes = notificationLeadMinutes,
        notificationIntervalMinutes = notificationIntervalMinutes,
        alertMode = alertMode,
        updatedAt = (updateDt ?: updatedAt)?.toString(),
    )

private fun backfillPlace(
    name: String?,
    address: String?,
    lat: Double?,
    lng: Double?,
): SchedulePlaceDto? {
    if (name == null && address == null && lat == null && lng == null) return null
    return SchedulePlaceDto(
        name = name,
        address = address,
        lat = lat,
        lng = lng,
    )
}
