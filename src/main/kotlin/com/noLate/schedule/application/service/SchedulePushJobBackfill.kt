package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.domain.ScheduleTravelPlanStatus
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

@Component
class SchedulePushJobBackfill(
    private val scheduleRepository: ScheduleRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val schedulePushJobService: SchedulePushJobService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun registerMissingJobs() {
        val now = Instant.now(clock)
        val ownerSchedules = scheduleRepository.findNotificationEnabledWithoutPushJob(now)

        ownerSchedules.forEach { schedule ->
            schedulePushJobService.registerFromScheduleDto(
                memberId = schedule.memberId,
                scheduleDto = schedule.toDto(objectMapper),
            )
        }

        val participantPlans =
            travelPlanRepository.findNotificationEnabledParticipantsWithoutPushJob(now)
        val schedulesById = scheduleRepository.findAllById(
            participantPlans.map { it.scheduleId }.distinct(),
        ).associateBy { requireNotNull(it.id) }
        var participantCount = 0
        participantPlans.forEach { plan ->
            val schedule = schedulesById[plan.scheduleId] ?: return@forEach
            if (!ScheduleTravelPlanFingerprint.matches(plan, schedule)) {
                return@forEach
            }
            schedulePushJobService.registerFromTravelPlanDto(
                memberId = plan.memberId,
                scheduleDto = schedule.toDto(objectMapper),
                plan = plan.toBackfillDto(schedule),
            )
            participantCount += 1
        }

        val recoveredCount = ownerSchedules.size + participantCount
        if (recoveredCount > 0) {
            log.info(
                "Recovered missing schedule push jobs. ownerCount={}, participantCount={}, totalCount={}",
                ownerSchedules.size,
                participantCount,
                recoveredCount,
            )
        }
    }

    /**
     * Backfill도 정상 저장 API와 같은 full plan DTO를 사용해야 runtime notification input
     * fingerprint가 일치한다. 일부 시각 필드만 조립한 migration fingerprint는 최초 동일 PUT을
     * 의미 변경으로 오인해 generation/check 상태를 reset할 수 있으므로 사용하지 않는다.
     */
    private fun ScheduleTravelPlan.toBackfillDto(schedule: Schedule): ScheduleTravelPlanDto =
        ScheduleTravelPlanDto(
            id = id,
            scheduleId = scheduleId,
            memberId = memberId,
            status = ScheduleTravelPlanStatus.READY,
            canManageSchedule = false,
            travelMinutes = travelMinutes,
            departAt = departAt?.toString(),
            travelMode = travelMode,
            origin = place(originName, originAddress, originLat, originLng),
            destination = schedule.route?.let {
                place(
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
            updatedAt = (updateDt ?: updatedAt)?.toString(),
        )

    private fun place(
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
}
