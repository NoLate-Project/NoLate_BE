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
        if (Instant.now(clock).isAfter(snapshotDeadline)) return false
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
        val effectiveRouteSnapshot = EffectiveTransitRouteSnapshot.parse(
            objectMapper = objectMapper,
            json = job.lastEffectiveTransitRouteJson,
        )
        val readAt = Instant.now(clock)
        val inProgressPlan = effectiveRouteSnapshot
            ?.takeIf { job.checkCount > 0 && it.evaluationCheckCount == job.checkCount - 1 &&
                it.evaluationNotificationGeneration == job.notificationGeneration }
            ?.inProgressPlannedRoute
            ?.takeIf { !readAt.isBefore(it.departureAt) && readAt.isBefore(it.arrivalAt) }
        val currentObservation = effectiveRouteSnapshot
            ?.takeIf { job.checkCount > 0 && it.evaluationCheckCount == job.checkCount - 1 &&
                it.evaluationNotificationGeneration == job.notificationGeneration }
            ?.firstBoardingObservation
        val freshObservation = currentObservation
            ?.takeIf { it.isFreshAt(readAt, arrivalSourceMaxAgeSeconds) }
        // Job 스냅샷의 5분 grace는 차량 관측의 120초 유효기간을 연장하지 않는다.
        // 관측만 숨기고 전체 여정을 정상으로 남기면 앱이 만료된 보정을 계속 안내한다.
        // 조회 시점의 파생 진단만 변경하며 저장된 이력·보호된 알람 시각은 수정하지 않는다.
        val expiredTransferObservation = effectiveRouteSnapshot?.effectiveTransitRoute?.segments
            ?.any { segment ->
                val timing = segment.rideTiming
                val hasRealtimeTransfer = timing?.basis in setOf(
                    EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_ADJUSTED_TIMETABLE,
                    EffectiveTransitRideTimingBasis.TRANSFER_ALIGHTING_REALTIME,
                )
                hasRealtimeTransfer && (timing?.observationSourceUpdatedAt?.let {
                    it.isBefore(readAt.minusSeconds(arrivalSourceMaxAgeSeconds)) || it.isAfter(readAt.plusSeconds(5))
                } ?: true)
            } == true
        val expiredRealtimeJourney = effectiveRouteSnapshot?.accepted == true &&
            (expiredTransferObservation || (effectiveRouteSnapshot.transitTimingBasis in setOf(
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
                inProgressPlan == null && !expiredRealtimeJourney && it.accepted && it.effectiveTransitRoute != null
            }
        val evaluatedAt = listOfNotNull(job.lastCheckedAt, job.lastLiveFetchedAt)
            .maxOrNull()
            ?: Instant.now(clock)
        return ScheduleDepartureEtaStatusDto(
            scheduleId = scheduleId,
            // 출발 후에는 최신 TrafficResult가 '원래 출발지에서 지금 다시 출발'하는 가상 경로다.
            // 진행 중인 저장 여정의 이동시간/출처/실패로 섞지 않고 별도 진단에만 노출한다.
            travelMinutes = travelMinutes.takeUnless { inProgressPlan != null },
            recommendedDepartureAt = job.lastRecommendedDepartureAt
                ?: schedule.startAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES),
            evaluatedAt = evaluatedAt,
            liveFetchedAt = job.lastLiveFetchedAt.takeUnless { inProgressPlan != null },
            source = source.takeUnless { inProgressPlan != null },
            stale = inProgressPlan != null || expiredRealtimeJourney || requireNotNull(job.lastEtaStale),
            confidence = if (
                inProgressPlan != null || expiredRealtimeJourney || onTimeUnavailableDiagnostic ||
                job.lastEtaStale == true ||
                job.lastEtaFailureReason != null
            ) {
                ScheduleEtaConfidence.LOW
            } else {
                confidence(source, effectiveRouteSnapshot?.transitTimingBasis)
            },
            failureReason = sanitizeTrafficFailureReason(observationFailure ?: job.lastEtaFailureReason)
                .takeUnless { inProgressPlan != null },
            lastTrafficChangeMinutes = job.lastTrafficChangeMinutes.takeUnless { inProgressPlan != null },
            lastChangedAt = job.lastChangedAt.takeUnless { inProgressPlan != null },
            nextCheckAt = job.activeNextCheckAt(),
            preparationMinutes = null,
            preparationStartAt = null,
            safetyBufferMinutes = null,
            timeZone = serviceZone.id,
            predictedArrivalAt = job.lastPredictedArrivalAt.takeUnless { expiredRealtimeJourney || inProgressPlan != null },
            onTimeArrivalPossible = job.lastPredictedArrivalAt?.takeUnless { expiredRealtimeJourney || inProgressPlan != null }?.let {
                !it.isAfter(schedule.startAt)
            },
            transitRouteProvenance = acceptedEffectiveRouteSnapshot?.transitRouteProvenance,
            transitTimingBasis = acceptedEffectiveRouteSnapshot?.transitTimingBasis,
            firstBoardingWaitMinutes = acceptedEffectiveRouteSnapshot?.firstBoardingWaitMinutes,
            routeChanged = acceptedEffectiveRouteSnapshot?.routeChanged == true,
            effectiveTransitRoute = acceptedEffectiveRouteSnapshot?.effectiveTransitRoute,
            etaRefreshDueAt = job.lastEtaRefreshDueAt,
            // 정상 ETA와 필드를 분리한다. 현재 평가·알림 세대의 완전한 미래 시간표만 제공한다.
            // 이 미리보기를 통해 보호 중인 출발시각이나 알람 예약을 늦추지 않는다.
            futureTimetablePrediction = effectiveRouteSnapshot
                ?.takeIf { inProgressPlan == null && !it.accepted && source == TrafficSource.TIMETABLE_PROVIDER &&
                    job.lastEtaFailureReason == TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED &&
                    it.transitTimingBasis == TransitTimingBasis.TIMETABLE_ONLY && job.checkCount > 0 &&
                    it.evaluationCheckCount == job.checkCount - 1 &&
                    it.evaluationNotificationGeneration == job.notificationGeneration }
                ?.effectiveTransitRoute
                ?.takeIf { route ->
                    val rides = route.segments.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }
                    !route.departureAt.isBefore(readAt) && !route.arrivalAt.isAfter(schedule.startAt) &&
                        rides.isNotEmpty() && rides.all { segment ->
                            segment.kind == EffectiveTransitRouteSegmentKind.SUBWAY &&
                                segment.rideTiming?.basis == EffectiveTransitRideTimingBasis.TIMETABLE &&
                                !segment.rideTiming.timetable.fetchedAt.isAfter(evaluatedAt)
                        }
                },
            // 저장 경로를 유지하는 LOW 상태에도 현재 평가에서 검증한 첫 차량은 별도 제공한다.
            // 전체 ETA의 신뢰도/알람 결정은 바꾸지 않으며 오래된 차량은 API에서 숨긴다.
            firstBoardingObservation = freshObservation.takeUnless { inProgressPlan != null },
            // 출발 후 예측은 정상 출발 ETA와 별개다. 실제 승차는 확인되지 않았고,
            // 이번 평가가 같은 알림 세대/회차일 때만 저장한 계획 편을 읽는다.
            plannedJourneyArrivalPrediction = effectiveRouteSnapshot
                ?.takeIf { job.lastEtaTravelMode == ScheduleTravelMode.TRANSIT &&
                    readAt.isBefore(schedule.startAt) &&
                    it.evaluationCheckCount == job.checkCount - 1 &&
                    it.evaluationNotificationGeneration == job.notificationGeneration &&
                    (inProgressPlan != null ||
                        job.lastEtaFailureReason?.startsWith("TRANSIT_TRANSFER_MISSED:") != true) }
                ?.plannedJourneyArrivalPrediction(readAt, arrivalSourceMaxAgeSeconds),
            originReplanDiagnostic = inProgressPlan?.let {
                OriginReplanDiagnostic(
                    travelMinutes = travelMinutes,
                    source = source,
                    stale = requireNotNull(job.lastEtaStale),
                    failureReason = sanitizeTrafficFailureReason(job.lastEtaFailureReason),
                    providerFetchedAt = job.lastEtaProviderFetchedAt,
                )
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
            recommendedDepartureAt = selectedRoute.anchoredDepartureAt ?: schedule.startAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES),
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
