package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.application.SelectedRouteMetadata
import com.noLate.schedule.application.sanitizeTrafficFailureReason
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleDepartureEtaStatusDto
import com.noLate.schedule.domain.ScheduleEtaConfidence
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Service
class ScheduleDepartureEtaService(
    private val scheduleRepository: ScheduleRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val scheduleAccessPolicy: ScheduleAccessPolicy,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val serviceZone = ZoneId.of("Asia/Seoul")

    @Transactional(readOnly = true)
    fun getDepartureStatus(memberId: Long, scheduleId: Long): ScheduleDepartureEtaStatusDto {
        val schedule = scheduleRepository.findScheduleDetail(scheduleId, memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        val access = scheduleAccessPolicy.resolve(memberId, schedule)
        if (!access.canView) {
            throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        }
        if (!access.travelEnabled) {
            throw BusinessException(ErrorCode.FORBIDDEN, "이 일정은 이동 기능을 공유하지 않습니다.")
        }

        val job = pushJobRepository.findByScheduleIdAndMemberId(scheduleId, memberId)
        if (job?.lastTravelMinutes?.takeIf { it > 0 } != null) {
            return statusFromJob(schedule, scheduleId, job)
        }

        return statusFromSavedRoute(
            schedule = schedule,
            scheduleId = scheduleId,
            job = job,
            route = resolveRoute(schedule, memberId),
        )
    }

    private fun resolveRoute(schedule: Schedule, memberId: Long): DepartureRoute? {
        val scheduleId = requireNotNull(schedule.id)
        val personal = travelPlanRepository
            .findByScheduleIdAndMemberIdAndDeletedFalse(scheduleId, memberId)
        if (personal != null) {
            return DepartureRoute(
                travelMinutes = personal.travelMinutes,
                travelMode = personal.travelMode,
                routeJson = personal.routeJson,
                fallbackReason = if (ScheduleTravelPlanFingerprint.matches(personal, schedule)) {
                    null
                } else {
                    EtaStatusFailureReasons.ROUTE_STALE
                },
            )
        }

        val legacy = schedule.route?.takeIf { schedule.memberId == memberId } ?: return null
        return DepartureRoute(
            travelMinutes = legacy.travelMinutes,
            travelMode = legacy.travelMode,
            routeJson = legacy.routeJson,
        )
    }

    private fun statusFromJob(
        schedule: Schedule,
        scheduleId: Long,
        job: SchedulePushJob,
    ): ScheduleDepartureEtaStatusDto {
        val travelMinutes = requireNotNull(job.lastTravelMinutes)
        require(travelMinutes > 0) { "저장된 worker ETA는 0보다 커야 합니다." }
        val source = job.lastEtaSource
        val evaluatedAt = listOfNotNull(job.lastCheckedAt, job.lastLiveFetchedAt)
            .maxOrNull()
            ?: Instant.now(clock)
        return ScheduleDepartureEtaStatusDto(
            scheduleId = scheduleId,
            travelMinutes = travelMinutes,
            recommendedDepartureAt = job.lastRecommendedDepartureAt
                ?: schedule.startAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES),
            evaluatedAt = evaluatedAt,
            liveFetchedAt = job.lastLiveFetchedAt,
            source = source,
            stale = job.lastEtaStale ?: (source != TrafficSource.LIVE_PROVIDER),
            confidence = confidence(source),
            failureReason = sanitizeTrafficFailureReason(job.lastEtaFailureReason),
            lastTrafficChangeMinutes = job.lastTrafficChangeMinutes,
            lastChangedAt = job.lastChangedAt,
            nextCheckAt = job.activeNextCheckAt(),
            preparationMinutes = null,
            preparationStartAt = null,
            safetyBufferMinutes = null,
            timeZone = serviceZone.id,
        )
    }

    private fun statusFromSavedRoute(
        schedule: Schedule,
        scheduleId: Long,
        job: SchedulePushJob?,
        route: DepartureRoute?,
    ): ScheduleDepartureEtaStatusDto {
        val evaluatedAt = Instant.now(clock)
        if (route == null) {
            return unavailableStatus(
                scheduleId = scheduleId,
                evaluatedAt = evaluatedAt,
                job = job,
                failureReason = EtaStatusFailureReasons.ROUTE_NOT_CONFIGURED,
            )
        }
        val selectedRoute = SelectedRouteMetadata.parse(objectMapper, route.routeJson, route.travelMode)
        val selectedMinutes = selectedRoute.travelMinutes
        val savedMinutes = route.travelMinutes?.takeIf { it > 0 }
        val travelMinutes = selectedMinutes ?: savedMinutes
        if (travelMinutes == null) {
            return unavailableStatus(
                scheduleId = scheduleId,
                evaluatedAt = evaluatedAt,
                job = job,
                failureReason = EtaStatusFailureReasons.TRAVEL_TIME_MISSING,
            )
        }
        val source = if (selectedMinutes != null) {
            TrafficSource.SELECTED_ROUTE
        } else {
            TrafficSource.SAVED_FALLBACK
        }
        return ScheduleDepartureEtaStatusDto(
            scheduleId = scheduleId,
            travelMinutes = travelMinutes,
            recommendedDepartureAt = schedule.startAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES),
            evaluatedAt = evaluatedAt,
            liveFetchedAt = null,
            source = source,
            stale = true,
            confidence = confidence(source),
            failureReason = route.fallbackReason ?: if (source == TrafficSource.SELECTED_ROUTE) {
                EtaStatusFailureReasons.SELECTED_ROUTE_SNAPSHOT
            } else {
                EtaStatusFailureReasons.SAVED_ROUTE_SNAPSHOT
            },
            lastTrafficChangeMinutes = job?.lastTrafficChangeMinutes,
            lastChangedAt = job?.lastChangedAt,
            nextCheckAt = job.activeNextCheckAt(),
            preparationMinutes = null,
            preparationStartAt = null,
            safetyBufferMinutes = null,
            timeZone = serviceZone.id,
        )
    }

    private fun unavailableStatus(
        scheduleId: Long,
        evaluatedAt: Instant,
        job: SchedulePushJob?,
        failureReason: String,
    ) = ScheduleDepartureEtaStatusDto(
        scheduleId = scheduleId,
        travelMinutes = null,
        recommendedDepartureAt = null,
        evaluatedAt = evaluatedAt,
        liveFetchedAt = null,
        source = null,
        stale = true,
        confidence = null,
        failureReason = failureReason,
        lastTrafficChangeMinutes = job?.lastTrafficChangeMinutes,
        lastChangedAt = job?.lastChangedAt,
        nextCheckAt = job.activeNextCheckAt(),
        preparationMinutes = null,
        preparationStartAt = null,
        safetyBufferMinutes = null,
        timeZone = serviceZone.id,
    )

    private fun confidence(source: TrafficSource?): ScheduleEtaConfidence? = when (source) {
        TrafficSource.LIVE_PROVIDER -> ScheduleEtaConfidence.HIGH
        TrafficSource.SELECTED_ROUTE -> ScheduleEtaConfidence.MEDIUM
        TrafficSource.SAVED_FALLBACK -> ScheduleEtaConfidence.LOW
        null -> null
    }

    private fun SchedulePushJob?.activeNextCheckAt(): Instant? =
        this?.nextCheckAt?.takeIf {
            status == SchedulePushJobStatus.ACTIVE || status == SchedulePushJobStatus.PROCESSING
        }
}

private data class DepartureRoute(
    val travelMinutes: Int?,
    val travelMode: ScheduleTravelMode?,
    val routeJson: String?,
    val fallbackReason: String? = null,
)

private object EtaStatusFailureReasons {
    const val ROUTE_NOT_CONFIGURED =
        "ROUTE_NOT_CONFIGURED: 현재 회원에게 저장된 이동 계획이 없습니다."
    const val TRAVEL_TIME_MISSING =
        "TRAVEL_TIME_MISSING: 저장된 이동 시간이 없어 출발 시각을 계산할 수 없습니다."
    const val ROUTE_STALE =
        com.noLate.schedule.application.TrafficFailureReasons.ROUTE_STALE
    const val SELECTED_ROUTE_SNAPSHOT =
        "SELECTED_ROUTE_SNAPSHOT: worker ETA가 없어 사용자가 선택한 경로 시간을 사용합니다."
    const val SAVED_ROUTE_SNAPSHOT =
        "SAVED_ROUTE_SNAPSHOT: worker ETA가 없어 저장된 이동 시간을 사용합니다."
}
