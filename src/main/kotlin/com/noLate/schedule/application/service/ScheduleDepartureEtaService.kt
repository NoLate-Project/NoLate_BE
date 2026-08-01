package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.application.EtaTravelTimePolicy
import com.noLate.schedule.application.SelectedRouteMetadata
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.sanitizeTrafficFailureReason
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleDepartureEtaStatusDto
import com.noLate.schedule.domain.ScheduleEtaRouteFingerprint
import com.noLate.schedule.domain.ScheduleEtaConfidence
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.springframework.beans.factory.annotation.Value
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
    @Value("\${schedule.traffic.max-travel-minutes:1440}")
    private val maxTravelMinutes: Int = EtaTravelTimePolicy.DEFAULT_MAX_TRAVEL_MINUTES,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val serviceZone = ZoneId.of("Asia/Seoul")

    init {
        EtaTravelTimePolicy.requireValidMaximum(maxTravelMinutes)
    }

    @Transactional(readOnly = true)
    fun getDepartureStatus(memberId: Long, scheduleId: Long): ScheduleDepartureEtaStatusDto {
        // ETA 계산 계약은 그대로 두고, sharing-off에서만 dormant grant를 포함하는 native
        // detail query를 선택하지 않아 타 회원 일정의 존재 자체를 노출하지 않는다.
        val schedule = if (scheduleAccessPolicy.isSharingDisabled()) {
            scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId)
        } else {
            scheduleRepository.findScheduleDetail(scheduleId, memberId)
        }
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        val access = scheduleAccessPolicy.resolve(memberId, schedule)
        if (!access.canView) {
            throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        }
        if (!access.travelEnabled) {
            throw BusinessException(ErrorCode.FORBIDDEN, "이 일정은 이동 기능을 공유하지 않습니다.")
        }

        val route = resolveRoute(schedule, memberId)
        val job = pushJobRepository.findByScheduleIdAndMemberId(scheduleId, memberId)
        if (job != null && route != null && job.isUsableSnapshot(schedule, route)) {
            return statusFromJob(schedule, scheduleId, job)
        }

        return statusFromSavedRoute(
            schedule = schedule,
            scheduleId = scheduleId,
            route = route,
        )
    }

    private fun resolveRoute(schedule: Schedule, memberId: Long): DepartureRoute? {
        val scheduleId = requireNotNull(schedule.id)
        schedule.route ?: return null
        val personal = travelPlanRepository
            .findByScheduleIdAndMemberIdAndDeletedFalse(scheduleId, memberId)
        if (personal != null) {
            val current = ScheduleTravelPlanFingerprint.matches(personal, schedule)
            return DepartureRoute(
                travelMinutes = personal.travelMinutes,
                travelMode = personal.travelMode,
                routeJson = personal.routeJson,
                notificationEnabled = personal.notificationEnabled,
                current = current,
                fingerprint = ScheduleEtaRouteFingerprint.calculate(
                    schedule = schedule,
                    travelMinutes = personal.travelMinutes,
                    travelMode = personal.travelMode,
                    originLat = personal.originLat,
                    originLng = personal.originLng,
                    routeJson = personal.routeJson,
                ),
                fallbackReason = if (current) {
                    EtaStatusFailureReasons.NOTIFICATION_DISABLED
                        .takeIf { !personal.notificationEnabled }
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
            notificationEnabled = legacy.notificationEnabled,
            current = true,
            fingerprint = ScheduleEtaRouteFingerprint.calculate(
                schedule = schedule,
                travelMinutes = legacy.travelMinutes,
                travelMode = legacy.travelMode,
                originLat = legacy.originLat,
                originLng = legacy.originLng,
                routeJson = legacy.routeJson,
            ),
            fallbackReason = EtaStatusFailureReasons.NOTIFICATION_DISABLED
                .takeIf { !legacy.notificationEnabled },
        )
    }

    private fun SchedulePushJob.isUsableSnapshot(
        schedule: Schedule,
        route: DepartureRoute,
    ): Boolean {
        if (status != SchedulePushJobStatus.ACTIVE && status != SchedulePushJobStatus.PROCESSING) {
            return false
        }
        if (scheduleAt != schedule.startAt || !route.current || !route.notificationEnabled) {
            return false
        }
        if (lastEtaRouteFingerprint == null || lastEtaRouteFingerprint != route.fingerprint) {
            return false
        }
        val travelMinutes = lastTravelMinutes
        if (!EtaTravelTimePolicy.isValid(travelMinutes, maxTravelMinutes)) return false
        if (lastCheckedAt == null || lastRecommendedDepartureAt == null) return false
        val recommendedDepartureAt = requireNotNull(lastRecommendedDepartureAt)
        if (recommendedDepartureAt.isAfter(schedule.startAt)) return false
        val source = lastEtaSource ?: return false
        val stale = lastEtaStale ?: return false
        val onTimeUnavailableDiagnostic = isOnTimeUnavailableDiagnostic(schedule)
        if (
            recommendedDepartureAt
                .plus(requireNotNull(travelMinutes).toLong(), ChronoUnit.MINUTES)
                .isAfter(schedule.startAt) &&
            !onTimeUnavailableDiagnostic
        ) return false
        if (
            lastPredictedArrivalAt?.isAfter(schedule.startAt) == true &&
            !onTimeUnavailableDiagnostic
        ) return false
        val livePairComplete = (lastLiveTravelMinutes == null) == (lastLiveFetchedAt == null)
        if (!livePairComplete) return false
        if (lastLiveFetchedAt != null && requireNotNull(lastCheckedAt).isBefore(lastLiveFetchedAt)) {
            return false
        }
        if (
            lastLiveTravelMinutes != null &&
            !EtaTravelTimePolicy.isValid(lastLiveTravelMinutes, maxTravelMinutes)
        ) {
            return false
        }
        if (onTimeUnavailableDiagnostic) {
            return when (source) {
                TrafficSource.LIVE_PROVIDER ->
                    lastLiveFetchedAt != null && lastLiveTravelMinutes == travelMinutes
                TrafficSource.TIMETABLE_PROVIDER -> true
                TrafficSource.SELECTED_ROUTE,
                TrafficSource.SAVED_FALLBACK -> false
            }
        }
        return when (source) {
            TrafficSource.LIVE_PROVIDER ->
                !stale &&
                    lastEtaFailureReason == null &&
                    lastLiveFetchedAt != null &&
                    lastLiveTravelMinutes == travelMinutes
            TrafficSource.TIMETABLE_PROVIDER ->
                !stale && lastEtaFailureReason == null
            TrafficSource.SELECTED_ROUTE,
            TrafficSource.SAVED_FALLBACK ->
                stale && lastEtaFailureReason != null
        }
    }

    private fun SchedulePushJob.isOnTimeUnavailableDiagnostic(schedule: Schedule): Boolean {
        val failureCodePrefix =
            TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE.substringBefore(':') + ":"
        return (lastEtaSource == TrafficSource.LIVE_PROVIDER ||
            lastEtaSource == TrafficSource.TIMETABLE_PROVIDER) &&
            lastEtaStale == true &&
            lastEtaFailureReason?.startsWith(failureCodePrefix) == true &&
            lastPredictedArrivalAt?.isAfter(schedule.startAt) == true
    }

    private fun statusFromJob(
        schedule: Schedule,
        scheduleId: Long,
        job: SchedulePushJob,
    ): ScheduleDepartureEtaStatusDto {
        val travelMinutes = requireNotNull(job.lastTravelMinutes)
        val source = requireNotNull(job.lastEtaSource)
        val onTimeUnavailableDiagnostic = job.isOnTimeUnavailableDiagnostic(schedule)
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
            stale = requireNotNull(job.lastEtaStale),
            confidence = if (
                onTimeUnavailableDiagnostic ||
                job.lastEtaStale == true ||
                job.lastEtaFailureReason != null
            ) {
                ScheduleEtaConfidence.LOW
            } else {
                confidence(source)
            },
            failureReason = sanitizeTrafficFailureReason(job.lastEtaFailureReason),
            lastTrafficChangeMinutes = job.lastTrafficChangeMinutes,
            lastChangedAt = job.lastChangedAt,
            nextCheckAt = job.activeNextCheckAt(),
            preparationMinutes = null,
            preparationStartAt = null,
            safetyBufferMinutes = null,
            timeZone = serviceZone.id,
            predictedArrivalAt = job.lastPredictedArrivalAt,
            onTimeArrivalPossible = job.lastPredictedArrivalAt?.let {
                !it.isAfter(schedule.startAt)
            },
        )
    }

    private fun statusFromSavedRoute(
        schedule: Schedule,
        scheduleId: Long,
        route: DepartureRoute?,
    ): ScheduleDepartureEtaStatusDto {
        val evaluatedAt = Instant.now(clock)
        if (route == null) {
            return unavailableStatus(
                scheduleId = scheduleId,
                evaluatedAt = evaluatedAt,
                failureReason = EtaStatusFailureReasons.ROUTE_NOT_CONFIGURED,
            )
        }
        val selectedRoute = SelectedRouteMetadata.parse(
            objectMapper = objectMapper,
            routeJson = route.routeJson,
            travelMode = route.travelMode,
            maxTravelMinutes = maxTravelMinutes,
        )
        val canonicalMinutes = route.travelMinutes?.takeIf {
            EtaTravelTimePolicy.isValid(it, maxTravelMinutes)
        }
        if (route.travelMinutes != null && canonicalMinutes == null) {
            return unavailableStatus(
                scheduleId = scheduleId,
                evaluatedAt = evaluatedAt,
                failureReason = route.fallbackReason ?: EtaStatusFailureReasons.TRAVEL_TIME_INVALID,
            )
        }
        val trustedSelectedMinutes = selectedRoute.travelMinutes
            ?.takeIf { canonicalMinutes == null || it == canonicalMinutes }
        val travelMinutes = canonicalMinutes ?: trustedSelectedMinutes
        if (travelMinutes == null) {
            return unavailableStatus(
                scheduleId = scheduleId,
                evaluatedAt = evaluatedAt,
                failureReason = EtaStatusFailureReasons.TRAVEL_TIME_MISSING,
            )
        }
        val source = if (trustedSelectedMinutes != null) {
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
            lastTrafficChangeMinutes = null,
            lastChangedAt = null,
            nextCheckAt = null,
            preparationMinutes = null,
            preparationStartAt = null,
            safetyBufferMinutes = null,
            timeZone = serviceZone.id,
        )
    }

    private fun unavailableStatus(
        scheduleId: Long,
        evaluatedAt: Instant,
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
        lastTrafficChangeMinutes = null,
        lastChangedAt = null,
        nextCheckAt = null,
        preparationMinutes = null,
        preparationStartAt = null,
        safetyBufferMinutes = null,
        timeZone = serviceZone.id,
    )

    private fun confidence(source: TrafficSource?): ScheduleEtaConfidence? = when (source) {
        TrafficSource.LIVE_PROVIDER -> ScheduleEtaConfidence.HIGH
        TrafficSource.TIMETABLE_PROVIDER -> ScheduleEtaConfidence.MEDIUM
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
    val notificationEnabled: Boolean,
    val current: Boolean,
    val fingerprint: String,
    val fallbackReason: String? = null,
)

private object EtaStatusFailureReasons {
    const val ROUTE_NOT_CONFIGURED =
        "ROUTE_NOT_CONFIGURED: 현재 회원에게 저장된 이동 계획이 없습니다."
    const val TRAVEL_TIME_MISSING =
        "TRAVEL_TIME_MISSING: 저장된 이동 시간이 없어 출발 시각을 계산할 수 없습니다."
    const val TRAVEL_TIME_INVALID =
        "TRAVEL_TIME_INVALID: 저장된 이동 시간이 제품 허용 범위를 벗어났습니다."
    const val ROUTE_STALE =
        com.noLate.schedule.application.TrafficFailureReasons.ROUTE_STALE
    const val SELECTED_ROUTE_SNAPSHOT =
        "SELECTED_ROUTE_SNAPSHOT: worker ETA가 없어 사용자가 선택한 경로 시간을 사용합니다."
    const val SAVED_ROUTE_SNAPSHOT =
        "SAVED_ROUTE_SNAPSHOT: worker ETA가 없어 저장된 이동 시간을 사용합니다."
    const val NOTIFICATION_DISABLED =
        "NOTIFICATION_DISABLED: 출발 알림이 비활성화되어 저장된 이동 시간을 사용합니다."
}
