package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.application.SelectedRouteMetadata
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
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
    private val trafficClient: TrafficClient,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val serviceZone = ZoneId.of("Asia/Seoul")

    @Transactional(readOnly = true)
    fun getDepartureStatus(memberId: Long, scheduleId: Long): ScheduleDepartureEtaStatusDto {
        val evaluatedAt = Instant.now(clock)
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
        val route = resolveRoute(schedule, memberId)
            ?: return unavailableStatus(
                schedule = schedule,
                scheduleId = scheduleId,
                evaluatedAt = evaluatedAt,
                job = job,
                failureReason = EtaStatusFailureReasons.ROUTE_NOT_CONFIGURED,
            )
        val selectedRoute = SelectedRouteMetadata.parse(objectMapper, route.routeJson, route.travelMode)
        val fallbackMinutes = selectedRoute.travelMinutes ?: route.travelMinutes?.takeIf { it > 0 }
        if (fallbackMinutes == null) {
            return unavailableStatus(
                schedule = schedule,
                scheduleId = scheduleId,
                evaluatedAt = evaluatedAt,
                job = job,
                failureReason = EtaStatusFailureReasons.TRAVEL_TIME_MISSING,
                selectedRoute = selectedRoute,
            )
        }

        val missingLiveInput = route.missingLiveInputReason()
        val result = if (missingLiveInput != null) {
            snapshotResult(
                fallbackTravelMinutes = fallbackMinutes,
                selectedRouteTravelMinutes = selectedRoute.travelMinutes,
                failureReason = missingLiveInput,
            )
        } else {
            trafficClient.getTravelMinutes(
                TrafficRequest(
                    originLat = requireNotNull(route.originLat),
                    originLng = requireNotNull(route.originLng),
                    destinationLat = requireNotNull(route.destinationLat),
                    destinationLng = requireNotNull(route.destinationLng),
                    travelMode = requireNotNull(route.travelMode),
                    fallbackTravelMinutes = fallbackMinutes,
                    selectedRouteJson = route.routeJson,
                    selectedRouteTravelMinutes = selectedRoute.travelMinutes,
                    selectedRouteOption = selectedRoute.routeOption,
                    selectedTransitItineraryJson = selectedRoute.transitItineraryJson,
                    liveRefreshBlockedReason = route.liveRefreshBlockedReason,
                )
            )
        }

        return toStatus(
            schedule = schedule,
            scheduleId = scheduleId,
            evaluatedAt = evaluatedAt,
            result = result,
            job = job,
        )
    }

    private fun resolveRoute(schedule: Schedule, memberId: Long): DepartureRoute? {
        val scheduleId = requireNotNull(schedule.id)
        val personal = travelPlanRepository
            .findByScheduleIdAndMemberIdAndDeletedFalse(scheduleId, memberId)
        if (personal != null) {
            val destination = schedule.route
            return DepartureRoute(
                travelMinutes = personal.travelMinutes,
                travelMode = personal.travelMode,
                originLat = personal.originLat,
                originLng = personal.originLng,
                destinationLat = destination?.destinationLat,
                destinationLng = destination?.destinationLng,
                routeJson = personal.routeJson,
                liveRefreshBlockedReason = if (ScheduleTravelPlanFingerprint.matches(personal, schedule)) {
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
            originLat = legacy.originLat,
            originLng = legacy.originLng,
            destinationLat = legacy.destinationLat,
            destinationLng = legacy.destinationLng,
            routeJson = legacy.routeJson,
        )
    }

    private fun unavailableStatus(
        schedule: Schedule,
        scheduleId: Long,
        evaluatedAt: Instant,
        job: SchedulePushJob?,
        failureReason: String,
        selectedRoute: SelectedRouteMetadata = SelectedRouteMetadata(),
    ): ScheduleDepartureEtaStatusDto {
        val result = selectedRoute.travelMinutes?.let {
            snapshotResult(
                fallbackTravelMinutes = it,
                selectedRouteTravelMinutes = it,
                failureReason = failureReason,
            )
        }
        return if (result != null) {
            toStatus(schedule, scheduleId, evaluatedAt, result, job)
        } else {
            ScheduleDepartureEtaStatusDto(
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
        }
    }

    private fun toStatus(
        schedule: Schedule,
        scheduleId: Long,
        evaluatedAt: Instant,
        result: TrafficResult,
        job: SchedulePushJob?,
    ): ScheduleDepartureEtaStatusDto {
        return ScheduleDepartureEtaStatusDto(
            scheduleId = scheduleId,
            travelMinutes = result.travelMinutes,
            recommendedDepartureAt = schedule.startAt.minus(
                result.travelMinutes.toLong(),
                ChronoUnit.MINUTES,
            ),
            evaluatedAt = evaluatedAt,
            liveFetchedAt = result.fetchedAt,
            source = result.source,
            stale = result.stale,
            confidence = when (result.source) {
                TrafficSource.LIVE_PROVIDER -> ScheduleEtaConfidence.HIGH
                TrafficSource.SELECTED_ROUTE -> ScheduleEtaConfidence.MEDIUM
                TrafficSource.SAVED_FALLBACK -> ScheduleEtaConfidence.LOW
            },
            failureReason = result.failureReason,
            // GET 평가 자체는 상태를 저장하지 않는다. 실제 변경 이력이 없는 경우 현재 값과
            // 과거 값을 비교해 lastChangedAt을 만들어 내지 않고 null을 유지한다.
            lastTrafficChangeMinutes = job?.lastTrafficChangeMinutes,
            lastChangedAt = job?.lastChangedAt,
            nextCheckAt = job.activeNextCheckAt(),
            preparationMinutes = null,
            preparationStartAt = null,
            safetyBufferMinutes = null,
            timeZone = serviceZone.id,
        )
    }

    private fun snapshotResult(
        fallbackTravelMinutes: Int,
        selectedRouteTravelMinutes: Int?,
        failureReason: String,
    ): TrafficResult {
        val selected = selectedRouteTravelMinutes?.takeIf { it > 0 }
        return TrafficResult(
            travelMinutes = selected ?: fallbackTravelMinutes,
            source = if (selected != null) TrafficSource.SELECTED_ROUTE else TrafficSource.SAVED_FALLBACK,
            stale = true,
            failureReason = failureReason,
        )
    }

    private fun SchedulePushJob?.activeNextCheckAt(): Instant? =
        this?.nextCheckAt?.takeIf {
            status == SchedulePushJobStatus.ACTIVE || status == SchedulePushJobStatus.PROCESSING
        }
}

private data class DepartureRoute(
    val travelMinutes: Int?,
    val travelMode: ScheduleTravelMode?,
    val originLat: Double?,
    val originLng: Double?,
    val destinationLat: Double?,
    val destinationLng: Double?,
    val routeJson: String?,
    val liveRefreshBlockedReason: String? = null,
) {
    fun missingLiveInputReason(): String? = when {
        travelMode == null -> EtaStatusFailureReasons.TRAVEL_MODE_MISSING
        originLat == null || originLng == null -> EtaStatusFailureReasons.ORIGIN_COORDINATES_MISSING
        destinationLat == null || destinationLng == null ->
            EtaStatusFailureReasons.DESTINATION_COORDINATES_MISSING
        else -> null
    }
}

private object EtaStatusFailureReasons {
    const val ROUTE_NOT_CONFIGURED =
        "ROUTE_NOT_CONFIGURED: 현재 회원에게 저장된 이동 계획이 없습니다."
    const val TRAVEL_TIME_MISSING =
        "TRAVEL_TIME_MISSING: 저장된 이동 시간이 없어 출발 시각을 계산할 수 없습니다."
    const val ROUTE_STALE =
        "ROUTE_STALE: 저장된 경로가 변경 전 일정 시각 또는 목적지를 기준으로 합니다."
    const val TRAVEL_MODE_MISSING =
        "TRAVEL_MODE_MISSING: 저장된 이동 수단이 없어 실시간 ETA를 조회할 수 없습니다."
    const val ORIGIN_COORDINATES_MISSING =
        "ORIGIN_COORDINATES_MISSING: 저장된 출발지 좌표가 없어 실시간 ETA를 조회할 수 없습니다."
    const val DESTINATION_COORDINATES_MISSING =
        "DESTINATION_COORDINATES_MISSING: 저장된 도착지 좌표가 없어 실시간 ETA를 조회할 수 없습니다."
}
