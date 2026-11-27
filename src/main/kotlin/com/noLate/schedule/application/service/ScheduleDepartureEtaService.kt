package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.application.EtaTravelTimePolicy
import com.noLate.schedule.application.EffectiveTransitRouteSnapshot
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind
import com.noLate.schedule.application.EffectiveTransitRideTimingBasis
import com.noLate.schedule.application.TransitTimingBasis
import com.noLate.schedule.application.SelectedRouteMetadata
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.sanitizeTrafficFailureReason
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleDepartureEtaStatusDto
import com.noLate.schedule.domain.OriginReplanDiagnostic
import com.noLate.schedule.domain.PlannedJourneyArrivalBasis
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
    @Value("\${schedule.traffic.departure-status-snapshot-grace-minutes:5}")
    private val departureStatusSnapshotGraceMinutes: Long =
        DEFAULT_DEPARTURE_STATUS_SNAPSHOT_GRACE_MINUTES,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${eta.transit.arrival-source-max-age-seconds:120}")
    private val arrivalSourceMaxAgeSeconds: Long = 120,
) {
    private val serviceZone = ZoneId.of("Asia/Seoul")

    init {
        EtaTravelTimePolicy.requireValidMaximum(maxTravelMinutes)
        require(arrivalSourceMaxAgeSeconds in 1..900)
        require(
            departureStatusSnapshotGraceMinutes in
                0..MAX_DEPARTURE_STATUS_SNAPSHOT_GRACE_MINUTES
        ) {
            "departure status snapshot grace minutes must be between 0 and " +
                MAX_DEPARTURE_STATUS_SNAPSHOT_GRACE_MINUTES
        }
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
        val anchored = route?.let { SelectedRouteMetadata.parse(objectMapper, it.routeJson, it.travelMode, maxTravelMinutes).anchoredDepartureAt }
        if (anchored == null && job != null && route != null && job.isUsableSnapshot(schedule, route)) {
            return statusFromJob(schedule, scheduleId, job, route)
        }
        if (anchored == null && job != null && route?.travelMode == ScheduleTravelMode.TRANSIT &&
            job.isUsableSnapshot(schedule, route, allowExpiredPlan = true)) {
            return statusFromExpiredTransitSnapshot(schedule, scheduleId, job, route)
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
                savedDepartureAt = personal.departAt,
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
            savedDepartureAt = legacy.departAt,
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
        allowExpiredPlan: Boolean = false,
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
        val evaluatedAt = lastCheckedAt ?: return false
        val etaRefreshDueAt = lastEtaRefreshDueAt ?: return false
        if (etaRefreshDueAt.isBefore(evaluatedAt)) return false
        val evaluationCadenceMinutes = intervalMinutes.takeIf {
            it in MIN_SNAPSHOT_EVALUATION_CADENCE_MINUTES..MAX_SNAPSHOT_EVALUATION_CADENCE_MINUTES &&
                it % SNAPSHOT_EVALUATION_CADENCE_STEP_MINUTES == 0
        } ?: return false
        val latestValidEtaRefreshDueAt = runCatching {
            evaluatedAt.plus(evaluationCadenceMinutes.toLong(), ChronoUnit.MINUTES)
        }.getOrNull() ?: return false
        if (etaRefreshDueAt.isAfter(latestValidEtaRefreshDueAt)) return false
        // nextCheckAt은 push retry와 PROCESSING timeout 복구에도 쓰인다. 실제 ETA 평가가 저장한
        // 불변 갱신 경계만 freshness 근거로 사용해야 실행 재예약이 snapshot을 되살릴 수 없다.
        val snapshotDeadline = runCatching {
            etaRefreshDueAt.plus(departureStatusSnapshotGraceMinutes, ChronoUnit.MINUTES)
        }.getOrNull() ?: return false
        if (!allowExpiredPlan && Instant.now(clock).isAfter(snapshotDeadline)) return false
        val travelMinutes = lastTravelMinutes
        if (!EtaTravelTimePolicy.isValid(travelMinutes, maxTravelMinutes)) return false
        if (lastRecommendedDepartureAt == null) return false
        val recommendedDepartureAt = requireNotNull(lastRecommendedDepartureAt)
        if (recommendedDepartureAt.isAfter(schedule.startAt)) return false
        val source = lastEtaSource ?: return false
        val stale = lastEtaStale ?: return false
        val onTimeUnavailableDiagnostic = isOnTimeUnavailableDiagnostic(schedule)
        val savedRouteDiagnostic =
            (source == TrafficSource.SELECTED_ROUTE || source == TrafficSource.SAVED_FALLBACK) &&
                stale && lastEtaFailureReason != null
        // 채택된 ETA와 최신 실패 진단은 서로 다른 계약이다. 공급자 조회는 성공했지만
        // 실시간 보정이 불가능한 회차도 취득시각이 확인되면 LOW 진단으로 전달한다.
        // 만료·경로 지문·알림 활성화 검사는 위에서 그대로 적용하며 확정 ETA로 승격하지 않는다.
        val providerFetchedAt = lastEtaProviderFetchedAt
        val providerDiagnostic =
            source in setOf(TrafficSource.LIVE_PROVIDER, TrafficSource.TIMETABLE_PROVIDER) &&
                stale && !lastEtaFailureReason.isNullOrBlank() &&
                lastPredictedArrivalAt == null && providerFetchedAt != null &&
                !providerFetchedAt.isAfter(evaluatedAt)
        val predictedArrivalAt = lastPredictedArrivalAt
        // 계산된 도착시각은 초 단위 원본으로 검증한다. 표시용으로 올림한 travelMinutes를
        // 다시 더하면 64분 48초인 정상 여정도 12초 늦는 것으로 잘못 판단할 수 있다.
        if (predictedArrivalAt?.isBefore(recommendedDepartureAt) == true) return false
        if (
            predictedArrivalAt == null &&
            // degraded 회차의 출발시각은 이전 알람에서 보존한 값이다. 이번 표시용 소요시간과
            // 한 쌍의 새 ETA처럼 역산하지 않아야 최신 오류 사유와 재조회 경계를 잃지 않는다.
            !savedRouteDiagnostic && !providerDiagnostic &&
            recommendedDepartureAt
                .plus(requireNotNull(travelMinutes).toLong(), ChronoUnit.MINUTES)
                .isAfter(schedule.startAt) &&
            !onTimeUnavailableDiagnostic
        ) return false
        if (
            predictedArrivalAt?.isAfter(schedule.startAt) == true &&
            !onTimeUnavailableDiagnostic
        ) return false
        val livePairComplete = (lastLiveTravelMinutes == null) == (lastLiveFetchedAt == null)
        if (!livePairComplete) return false
        if (lastLiveFetchedAt != null && evaluatedAt.isBefore(lastLiveFetchedAt)) {
            return false
        }
        if (
            lastLiveTravelMinutes != null &&
            !EtaTravelTimePolicy.isValid(lastLiveTravelMinutes, maxTravelMinutes)
        ) {
            return false
        }
        // 현재 진단의 소요시간은 보존된 알람 출발시각과 새 도착시각을 구성하지 않는다.
        // predictedArrivalAt=null, stale=true 계약을 유지해 앱도 기존 경로를 계속 표시한다.
        if (providerDiagnostic) return true
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

    /** 관측 TTL은 정상 ETA만 만료시킨다. 보호한 더 이른 출발과 명시적 이전 계획은 별개다. */
    private fun statusFromExpiredTransitSnapshot(
        schedule: Schedule, scheduleId: Long, job: SchedulePushJob, route: DepartureRoute,
    ): ScheduleDepartureEtaStatusDto {
        val fallback = statusFromSavedRoute(schedule, scheduleId, route)
        val readAt = Instant.now(clock)
        val protectedDeparture = listOfNotNull(fallback.recommendedDepartureAt, job.lastRecommendedDepartureAt).minOrNull()
        val snapshot = EffectiveTransitRouteSnapshot.parse(objectMapper, job.lastEffectiveTransitRouteJson)
            ?.takeIf { job.checkCount > 0 && it.evaluationCheckCount == job.checkCount - 1 &&
                it.evaluationNotificationGeneration == job.notificationGeneration }
        val plan = snapshot?.plannedRouteAt(readAt) ?: snapshot?.acceptedRoute()?.takeIf {
            it.departureAt <= readAt && it.arrivalAt > readAt
        }
        // 전체 ETA가 만료되면 차량 예보는 내리고, 기록된 마지막 버스의 과거 정류장 도착은 보존한다.
        val baseline = snapshot?.copy(accepted = false, inProgressPlannedRoute = plan,
            effectiveTransitRoute = null, plannedArrivalObservation = null,
            lastUpdatedPlannedRoute = snapshot.plannedRouteUpdate?.route ?: snapshot.lastUpdatedPlannedRoute,
            plannedRouteUpdate = snapshot.plannedRouteUpdate?.takeIf { it.failure != null || it.finalBusAlightingReport() != null })
        val plannedPrediction = baseline?.plannedJourneyArrivalPrediction(readAt, arrivalSourceMaxAgeSeconds)
        val hasActivePlan = baseline?.hasActivePlannedRouteAt(readAt, plannedPrediction?.predictedArrivalAt) == true
        val finalBusReported = plannedPrediction?.basis == PlannedJourneyArrivalBasis.FINAL_BUS_ALIGHTING_REPORTED
        return fallback.copy(
            recommendedDepartureAt = if (finalBusReported) job.lastRecommendedDepartureAt else protectedDeparture,
            source = fallback.source.takeUnless { hasActivePlan },
            travelMinutes = fallback.travelMinutes.takeUnless { hasActivePlan },
            confidence = ScheduleEtaConfidence.LOW,
            failureReason = EtaStatusFailureReasons.ETA_SNAPSHOT_EXPIRED.takeUnless { hasActivePlan },
            plannedJourneyArrivalPrediction = plannedPrediction.takeIf { hasActivePlan },
            plannedJourneyTimingFailureReason = baseline?.takeIf { hasActivePlan }?.plannedJourneyTimingFailureReason(readAt),
            pendingTransferRailPlan = baseline?.takeIf { hasActivePlan }?.pendingTransferRailPlan(readAt),
            originReplanDiagnostic = if (hasActivePlan && job.lastEtaFailureReason != TrafficFailureReasons.TRANSIT_TRACKING_SAVED_ROUTE)
                OriginReplanDiagnostic(requireNotNull(job.lastTravelMinutes),
                requireNotNull(job.lastEtaSource), true, EtaStatusFailureReasons.ETA_SNAPSHOT_EXPIRED,
                job.lastEtaProviderFetchedAt) else null,
            savedRouteEstimatedDepartureAt = fallback.recommendedDepartureAt,
        )
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
        savedRoute: DepartureRoute,
    ): ScheduleDepartureEtaStatusDto {
        val travelMinutes = requireNotNull(job.lastTravelMinutes)
        val source = requireNotNull(job.lastEtaSource)
        val onTimeUnavailableDiagnostic = job.isOnTimeUnavailableDiagnostic(schedule)
        val effectiveRouteSnapshot = EffectiveTransitRouteSnapshot.parse(
            objectMapper = objectMapper,
            json = job.lastEffectiveTransitRouteJson,
        )
        val readAt = Instant.now(clock)
        val currentSnapshot = effectiveRouteSnapshot
            ?.takeIf { job.checkCount > 0 && it.evaluationCheckCount == job.checkCount - 1 &&
                it.evaluationNotificationGeneration == job.notificationGeneration }
        val plannedPrediction = currentSnapshot
            ?.takeIf { job.lastEtaTravelMode == ScheduleTravelMode.TRANSIT && readAt.isBefore(schedule.startAt) }
            ?.plannedJourneyArrivalPrediction(readAt, arrivalSourceMaxAgeSeconds)
        val inProgressPlan = currentSnapshot?.hasActivePlannedRouteAt(readAt, plannedPrediction?.predictedArrivalAt) == true
        val currentObservation = currentSnapshot?.firstBoardingObservation
        val freshObservation = currentObservation
            ?.takeIf { it.isFreshAt(readAt, arrivalSourceMaxAgeSeconds) }
        // Job 스냅샷의 5분 grace는 차량 관측의 120초 유효기간을 연장하지 않는다.
        // 관측만 숨기고 전체 여정을 정상으로 남기면 앱이 만료된 보정을 계속 안내한다.
        // 조회 시점의 파생 진단만 변경하며 저장된 이력·보호된 알람 시각은 수정하지 않는다.
        val expiredTransferObservation = effectiveRouteSnapshot?.effectiveTransitRoute?.segments
            ?.any { segment ->
                val timing = segment.rideTiming
                val hasRealtimeRideObservation = timing?.basis in setOf(
                    EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_ADJUSTED_TIMETABLE,
                    EffectiveTransitRideTimingBasis.TRANSFER_ALIGHTING_REALTIME,
                    EffectiveTransitRideTimingBasis.FIRST_RIDE_BOARDING_AND_ALIGHTING_REALTIME,
                )
                val expiredBus = segment.busRidePrediction?.sourceUpdatedAt?.let {
                    it.isBefore(readAt.minusSeconds(arrivalSourceMaxAgeSeconds)) || it.isAfter(readAt.plusSeconds(5))
                } == true
                expiredBus || hasRealtimeRideObservation && (timing?.observationSourceUpdatedAt?.let {
                    it.isBefore(readAt.minusSeconds(arrivalSourceMaxAgeSeconds)) || it.isAfter(readAt.plusSeconds(5))
                } ?: true)
            } == true
        val expiredRealtimeJourney = effectiveRouteSnapshot?.accepted == true &&
            (expiredTransferObservation || (effectiveRouteSnapshot.transitTimingBasis in setOf(
                TransitTimingBasis.SAME_BUS_BOARDING_AND_ALIGHTING_REALTIME,
                TransitTimingBasis.SAME_SUBWAY_BOARDING_AND_ALIGHTING_REALTIME,
                TransitTimingBasis.BUS_TRANSFER_BOARDING_AND_ALIGHTING_REALTIME,
                TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE,
                TransitTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_OBSERVED,
                TransitTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN,
                TransitTimingBasis.FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE,
            ) && freshObservation == null))
        val observationFailure = if (expiredRealtimeJourney) {
            if (expiredTransferObservation || currentObservation != null && (
                currentObservation.sourceUpdatedAt.isBefore(readAt.minusSeconds(arrivalSourceMaxAgeSeconds)) ||
                    currentObservation.evaluatedAt.isBefore(readAt.minusSeconds(arrivalSourceMaxAgeSeconds))
                )) TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE
            else TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE
        } else null
        val acceptedEffectiveRouteSnapshot = effectiveRouteSnapshot
            ?.takeIf {
                !inProgressPlan && !expiredRealtimeJourney && it.accepted && it.effectiveTransitRoute != null
            }
        val evaluatedAt = listOfNotNull(job.lastCheckedAt, job.lastLiveFetchedAt)
            .maxOrNull()
            ?: Instant.now(clock)
        val stale = inProgressPlan || expiredRealtimeJourney || requireNotNull(job.lastEtaStale)
        val savedRouteEstimatedDepartureAt = if (savedRoute.travelMode == ScheduleTravelMode.TRANSIT &&
            stale && !inProgressPlan && !onTimeUnavailableDiagnostic) {
            statusFromSavedRoute(schedule, scheduleId, savedRoute).recommendedDepartureAt
        } else null
        val futureTimetable = currentSnapshot
            ?.takeIf { !inProgressPlan && !it.accepted && source == TrafficSource.TIMETABLE_PROVIDER &&
                job.lastEtaFailureReason in setOf(
                    TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED,
                    TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE,
                    TrafficFailureReasons.TRANSIT_ARRIVAL_OUT_OF_HORIZON,
                ) &&
                it.transitTimingBasis == TransitTimingBasis.TIMETABLE_ONLY }
            ?.let { snapshot -> snapshot.lastTimetablePlannedRoute ?: snapshot.effectiveTransitRoute
                ?.takeIf { job.lastEtaFailureReason != TrafficFailureReasons.TRANSIT_ARRIVAL_OUT_OF_HORIZON } }
            ?.takeIf { route ->
                val rides = route.segments.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }
                !route.departureAt.isBefore(readAt) && !route.arrivalAt.isAfter(schedule.startAt) &&
                    rides.isNotEmpty() && rides.all { segment ->
                        segment.kind == EffectiveTransitRouteSegmentKind.SUBWAY &&
                            segment.rideTiming?.basis == EffectiveTransitRideTimingBasis.TIMETABLE &&
                            !segment.rideTiming.timetable.fetchedAt.isAfter(evaluatedAt)
                    }
            }
        return ScheduleDepartureEtaStatusDto(
            scheduleId = scheduleId,
            // 저장한 차량 추적과 출발지에서 다시 계산한 여정을 정상 출발 ETA로 노출하지 않는다.
            travelMinutes = travelMinutes.takeUnless { inProgressPlan },
            recommendedDepartureAt = job.lastRecommendedDepartureAt
                ?: schedule.startAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES),
            evaluatedAt = evaluatedAt,
            liveFetchedAt = job.lastLiveFetchedAt.takeUnless { inProgressPlan },
            source = source.takeUnless { inProgressPlan },
            stale = stale,
            confidence = if (
                inProgressPlan || expiredRealtimeJourney || onTimeUnavailableDiagnostic ||
                job.lastEtaStale == true ||
                job.lastEtaFailureReason != null
            ) {
                ScheduleEtaConfidence.LOW
            } else {
                confidence(source, effectiveRouteSnapshot?.transitTimingBasis)
            },
            failureReason = sanitizeTrafficFailureReason(observationFailure ?: job.lastEtaFailureReason)
                .takeUnless { inProgressPlan || it == TrafficFailureReasons.TRANSIT_TRACKING_SAVED_ROUTE },
            lastTrafficChangeMinutes = job.lastTrafficChangeMinutes.takeUnless { inProgressPlan },
            lastChangedAt = job.lastChangedAt.takeUnless { inProgressPlan },
            nextCheckAt = job.activeNextCheckAt(),
            preparationMinutes = null,
            preparationStartAt = null,
            safetyBufferMinutes = null,
            timeZone = serviceZone.id,
            predictedArrivalAt = job.lastPredictedArrivalAt.takeUnless { expiredRealtimeJourney || inProgressPlan },
            onTimeArrivalPossible = job.lastPredictedArrivalAt?.takeUnless { expiredRealtimeJourney || inProgressPlan }?.let {
                !it.isAfter(schedule.startAt)
            },
            transitRouteProvenance = acceptedEffectiveRouteSnapshot?.transitRouteProvenance,
            transitTimingBasis = acceptedEffectiveRouteSnapshot?.transitTimingBasis,
            firstBoardingWaitMinutes = acceptedEffectiveRouteSnapshot?.firstBoardingWaitMinutes,
            routeChanged = acceptedEffectiveRouteSnapshot?.routeChanged == true,
            effectiveTransitRoute = acceptedEffectiveRouteSnapshot?.effectiveTransitRoute,
            etaRefreshDueAt = job.lastEtaRefreshDueAt,
            futureTimetablePrediction = futureTimetable,
            futureTimetableRouteProvenance = currentSnapshot?.transitRouteProvenance?.takeIf { futureTimetable != null },
            // 저장 경로를 유지하는 LOW 상태에도 현재 평가에서 검증한 첫 차량은 별도 제공한다.
            // 전체 ETA의 신뢰도/알람 결정은 바꾸지 않으며 오래된 차량은 API에서 숨긴다.
            firstBoardingObservation = freshObservation.takeUnless { inProgressPlan },
            // 출발 후 예측은 정상 출발 ETA와 별개다. 실제 승차는 확인되지 않았고,
            // 이번 평가가 같은 알림 세대/회차일 때만 저장한 계획 편을 읽는다.
            plannedJourneyArrivalPrediction = plannedPrediction.takeIf { inProgressPlan },
            plannedJourneyTimingFailureReason = currentSnapshot?.takeIf { inProgressPlan }?.plannedJourneyTimingFailureReason(readAt),
            pendingTransferRailPlan = currentSnapshot?.takeIf { inProgressPlan }?.pendingTransferRailPlan(readAt),
            originReplanDiagnostic = if (inProgressPlan && job.lastEtaFailureReason != TrafficFailureReasons.TRANSIT_TRACKING_SAVED_ROUTE) {
                OriginReplanDiagnostic(
                    travelMinutes = travelMinutes,
                    source = source,
                    stale = requireNotNull(job.lastEtaStale),
                    failureReason = sanitizeTrafficFailureReason(job.lastEtaFailureReason),
                    providerFetchedAt = job.lastEtaProviderFetchedAt,
                )
            } else null,
            savedRouteEstimatedDepartureAt = savedRouteEstimatedDepartureAt,
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
        val savedBusPreview = selectedRoute.odsayBusPreview?.takeIf {
            route.current && it.matches(schedule.startAt, route.savedDepartureAt, canonicalMinutes)
        }
        val trustedSelectedMinutes = (savedBusPreview?.travelMinutes ?: selectedRoute.travelMinutes)
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
        val estimatedDeparture = selectedRoute.odsayEstimatedDepartureAt(schedule.startAt, travelMinutes)
            ?.takeIf { route.current && trustedSelectedMinutes == travelMinutes }
            ?: schedule.startAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES)
        val protectedSavedDeparture = if (route.current && route.travelMode == ScheduleTravelMode.TRANSIT) {
            route.savedDepartureAt?.takeIf { it < estimatedDeparture }
                ?: selectedRoute.odsayTimetable?.departureIfCompatible(schedule.startAt, route.savedDepartureAt, travelMinutes)
        } else null
        return ScheduleDepartureEtaStatusDto(
            scheduleId = scheduleId,
            travelMinutes = travelMinutes,
            recommendedDepartureAt = selectedRoute.anchoredDepartureAt ?: savedBusPreview?.departureAt
                ?: protectedSavedDeparture ?: estimatedDeparture,
            evaluatedAt = evaluatedAt,
            liveFetchedAt = null,
            source = source,
            stale = true,
            confidence = if (selectedRoute.anchoredDepartureAt != null) ScheduleEtaConfidence.LOW else confidence(source),
            failureReason = route.fallbackReason ?: if (selectedRoute.anchoredDepartureAt != null) {
                TrafficFailureReasons.TRANSIT_INTERCITY_TIMETABLE_UNAVAILABLE
            } else if (source == TrafficSource.SELECTED_ROUTE) {
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

    private fun confidence(
        source: TrafficSource?,
        transitTimingBasis: TransitTimingBasis? = null,
    ): ScheduleEtaConfidence? = when {
        // 첫 승차가 실시간이어도 미래 환승편은 시간표 예측이다. 전체 ETA를 HIGH로 표시하지 않는다.
        source == TrafficSource.LIVE_PROVIDER &&
            transitTimingBasis in setOf(
                TransitTimingBasis.SAME_BUS_BOARDING_AND_ALIGHTING_REALTIME,
                TransitTimingBasis.SAME_SUBWAY_BOARDING_AND_ALIGHTING_REALTIME,
                TransitTimingBasis.BUS_TRANSFER_BOARDING_AND_ALIGHTING_REALTIME,
                TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE,
                TransitTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_OBSERVED,
            ) ->
            ScheduleEtaConfidence.MEDIUM
        source == TrafficSource.LIVE_PROVIDER && transitTimingBasis in setOf(
            TransitTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN,
            TransitTimingBasis.FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE,
        ) -> ScheduleEtaConfidence.LOW
        else -> confidenceBySource(source)
    }

    private fun confidenceBySource(source: TrafficSource?): ScheduleEtaConfidence? = when (source) {
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

    private companion object {
        const val DEFAULT_DEPARTURE_STATUS_SNAPSHOT_GRACE_MINUTES = 5L
        const val MAX_DEPARTURE_STATUS_SNAPSHOT_GRACE_MINUTES = 60L
        const val MIN_SNAPSHOT_EVALUATION_CADENCE_MINUTES = 10
        const val MAX_SNAPSHOT_EVALUATION_CADENCE_MINUTES = 60
        const val SNAPSHOT_EVALUATION_CADENCE_STEP_MINUTES = 5
    }
}

private data class DepartureRoute(
    val travelMinutes: Int?,
    val savedDepartureAt: Instant?,
    val travelMode: ScheduleTravelMode?,
    val routeJson: String?,
    val notificationEnabled: Boolean,
    val current: Boolean,
    val fingerprint: String,
    val fallbackReason: String? = null,
)

private object EtaStatusFailureReasons {
    const val ETA_SNAPSHOT_EXPIRED =
        "ETA_SNAPSHOT_EXPIRED: 재조회 결과가 만료되어 보호한 출발시각과 이전 계획만 표시합니다."
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
