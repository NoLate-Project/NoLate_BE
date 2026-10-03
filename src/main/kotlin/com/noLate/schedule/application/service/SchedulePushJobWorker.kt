package com.noLate.schedule.application.service

import com.noLate.schedule.application.service.policy.EtaRefreshCadencePolicy

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.observability.EtaJobMetricOutcome
import com.noLate.global.observability.EtaWorkerMetricEvent
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.global.observability.recordSafely
import com.noLate.notification.application.service.AppNotificationSnapshot
import com.noLate.notification.application.service.PushDispatchFence
import com.noLate.notification.application.service.DepartureAlarmReminderCoverageSelector
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.liveactivity.application.ScheduleLiveActivityEtaProjection
import com.noLate.notification.liveactivity.application.ScheduleLiveActivityService
import com.noLate.schedule.application.EtaTravelTimePolicy
import com.noLate.schedule.application.EffectiveTransitRouteSnapshot
import com.noLate.schedule.application.SelectedRouteMetadata
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.application.sanitizeTrafficFailureReason
import com.noLate.schedule.application.toRouteDisplaySegments
import com.noLate.schedule.application.service.policy.DepartureReminderPolicy
import com.noLate.schedule.application.service.policy.DepartureReminderDecision
import com.noLate.schedule.application.service.policy.PeriodicPushPolicy
import com.noLate.schedule.application.service.policy.TrafficChangePolicy
import com.noLate.schedule.application.toEvaluationHistory
import com.noLate.schedule.domain.ScheduleEtaRouteFingerprint
import com.noLate.schedule.domain.ScheduleAlertMode
import com.noLate.schedule.domain.ScheduleEtaEvaluationHistory
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

@Component
class SchedulePushJobWorker(
    private val scheduleRepository: ScheduleRepository,
    private val objectMapper: ObjectMapper,
    private val trafficClient: TrafficClient,
    private val notificationUseCase: NotificationUseCase,
    private val periodicPushPolicy: PeriodicPushPolicy,
    private val departureReminderPolicy: DepartureReminderPolicy,
    private val trafficChangePolicy: TrafficChangePolicy,
    private val pushJobCoordinator: SchedulePushJobCoordinator,
    @Value("\${schedule.push.enabled:true}") private val enabled: Boolean = true,
    @Value("\${schedule.push.batch-size:50}") private val batchSize: Int,
    @Value("\${schedule.push.retry-delay-minutes:1}") private val retryDelayMinutes: Long,
    @Value("\${schedule.push.max-retry-count:3}") private val maxRetryCount: Int,
    @Value("\${schedule.push.delivery-grace-minutes:10}") private val deliveryGraceMinutes: Long = 10,
    @Value("\${schedule.push.departure-alert-lead-minutes:15}") private val departureAlertLeadMinutes: Int,
    @Value("\${schedule.push.departure-reminder-interval-minutes:5}") private val departureReminderIntervalMinutes: Int,
    @Value("\${schedule.push.departure-snooze-minutes:5}") private val departureSnoozeMinutes: Int = 5,
    @Value("\${schedule.push.processing-timeout-minutes:10}") private val processingTimeoutMinutes: Long,
    @Value("\${schedule.traffic.max-travel-minutes:1440}")
    private val maxTravelMinutes: Int = EtaTravelTimePolicy.DEFAULT_MAX_TRAVEL_MINUTES,
    @Value("\${schedule.traffic.live-comparator-max-age-minutes:60}")
    private val liveComparatorMaxAgeMinutes: Long =
        SchedulePushJob.DEFAULT_LIVE_COMPARATOR_MAX_AGE_MINUTES,
    @Value("\${schedule.push.eta-event-ttl-seconds:120}")
    private val etaEventTtlSeconds: Long = DEFAULT_ETA_EVENT_TTL_SECONDS,
    private val travelPlanRepository: ScheduleTravelPlanRepository? = null,
    private val scheduleAccessPolicy: ScheduleAccessPolicy? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val operationalMetrics: NoLateOperationalMetrics? = null,
    private val departureAlarmSyncService: DepartureAlarmSyncService? = null,
    private val scheduleLiveActivityService: ScheduleLiveActivityService? = null,

    // 출발 상태 API와 같은 유예시간 설정을 사용한다.
    @Value("\${schedule.traffic.departure-status-snapshot-grace-minutes:5}")
    private val departureStatusSnapshotGraceMinutes: Long = 5L,
) {
    init {
        EtaTravelTimePolicy.requireValidMaximum(maxTravelMinutes)
        require(
            liveComparatorMaxAgeMinutes in 1..SchedulePushJob.MAX_LIVE_COMPARATOR_AGE_MINUTES
        ) {
            "schedule.traffic.live-comparator-max-age-minutes는 " +
                "1~${SchedulePushJob.MAX_LIVE_COMPARATOR_AGE_MINUTES} 사이여야 합니다."
        }
        require(etaEventTtlSeconds in MIN_ETA_EVENT_TTL_SECONDS..MAX_ETA_EVENT_TTL_SECONDS) {
            "schedule.push.eta-event-ttl-seconds는 " +
                "$MIN_ETA_EVENT_TTL_SECONDS~$MAX_ETA_EVENT_TTL_SECONDS 사이여야 합니다."
        }

        // 출발 상태 API와 동일한 범위로 유예시간을 제한한다.
        require(departureStatusSnapshotGraceMinutes in 0L..60L) {
            "schedule.traffic.departure-status-snapshot-grace-minutes는 0~60 사이여야 합니다."
        }
        require(retryDelayMinutes >= 1 && retryDelayMinutes * 60 < etaEventTtlSeconds) {
            "schedule.push.retry-delay-minutes는 ETA event TTL보다 짧아야 합니다."
        }
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val workerId = "schedule-push-${UUID.randomUUID()}"

    @Scheduled(fixedDelayString = "\${schedule.push.fixed-delay-ms:60000}")
    fun runDueJobs() {
        runDueJobs(Instant.now(clock))
    }

    fun runDueJobs(now: Instant): Int {
        if (!enabled) return 0

        pushJobCoordinator.alignLegacyEtaCadences(now, batchSize)

        val recoveryAt = currentAtOrAfter(now)
        val recoveredCount = pushJobCoordinator.recoverStaleProcessingJobs(
            now = recoveryAt,
            processingTimeoutMinutes = processingTimeoutMinutes,
            deliveryGraceMinutes = deliveryGraceMinutes,
            batchSize = batchSize,
        )
        operationalMetrics.recordSafely {
            recordEtaJob(EtaJobMetricOutcome.STALE_LEASE_RECOVERED, recoveredCount)
        }
        if (recoveredCount > 0) {
            log.warn(
                "Recovered stale schedule push jobs. count={}, timeoutMinutes={}, checkedAt={}",
                recoveredCount,
                processingTimeoutMinutes,
                recoveryAt,
            )
        }

        var claimedCount = 0
        repeat(batchSize.coerceIn(1, 200)) {
            // Each tail job is leased immediately before processing using the current clock.
            // A slow provider for the previous job must not backdate this new lease.
            val claimAt = currentAtOrAfter(now)
            val job = pushJobCoordinator.claimNextDueJob(claimAt, workerId)
                ?: return claimedCount
            claimedCount += 1
            operationalMetrics.recordSafely { recordEtaJob(EtaJobMetricOutcome.CLAIMED) }
            log.info(
                "Claimed schedule push job. jobId={}, workerId={}, checkedAt={}",
                job.id,
                workerId,
                claimAt,
            )
            pushJobCoordinator.execute { process(job, claimAt) }
        }
        return claimedCount
    }

    private fun currentAtOrAfter(notBefore: Instant): Instant =
        maxOf(notBefore, Instant.now(clock))

    private fun process(job: SchedulePushJob, now: Instant) {
        var liveActivityProjection: ScheduleLiveActivityEtaProjection? = null
        var preserveFrozenEtaState = false

        var scheduleEtaEvaluationHistory : ScheduleEtaEvaluationHistory? = null
        try {
            // 일정이 시작된 뒤에는 "출발" 알림의 의미가 사라지므로 남은 후속 푸시를 종료한다.
            if (job.isPastDeliveryWindow(now, deliveryGraceMinutes)) {
                job.complete()
                persistMeasuredTransition(
                    job = job,
                    outcome = EtaJobMetricOutcome.PROCESSED,
                    alarmIntent = job.cancelAlarmIntent(),
                    liveActivityEndReason = "DELIVERY_WINDOW_EXPIRED",
                    scheduleEtaEvaluationHistory = scheduleEtaEvaluationHistory
                )
                return
            }

            val schedule = scheduleRepository.findScheduleDetail(job.scheduleId, job.memberId)
                ?: run {
                    job.cancel()
                    persistMeasuredTransition(
                        job = job,
                        outcome = EtaJobMetricOutcome.PROCESSED,
                        alarmIntent = job.cancelAlarmIntent(),
                        liveActivityEndReason = "SCHEDULE_REMOVED",
                        scheduleEtaEvaluationHistory = scheduleEtaEvaluationHistory
                    )
                    return
                }
            // 공유 범위 축소와 이미 선점된 worker가 경합해도 발송 직전 유효 이동 권한을 다시
            // 확인한다. 일정 조회 권한만 남은 사용자는 기존 개인 계획이 있어도 알림을 받지 않는다.
            if (scheduleAccessPolicy?.resolve(job.memberId, schedule)?.travelEnabled == false) {
                job.cancel()
                persistMeasuredTransition(
                    job = job,
                    outcome = EtaJobMetricOutcome.PROCESSED,
                    alarmIntent = job.cancelAlarmIntent(),
                    liveActivityEndReason = "TRAVEL_ACCESS_REVOKED",
                    scheduleEtaEvaluationHistory = scheduleEtaEvaluationHistory
                )
                return
            }
            val route = resolveRouteSource(schedule, job.memberId)
                ?: run {
                    job.cancel()
                    persistMeasuredTransition(
                        job = job,
                        outcome = EtaJobMetricOutcome.PROCESSED,
                        alarmIntent = job.cancelAlarmIntent(),
                        liveActivityEndReason = "NOTIFICATION_DISABLED",
                        scheduleEtaEvaluationHistory = scheduleEtaEvaluationHistory
                    )
                    return
                }
            // An immutable A event can finish after a newer B evaluation was observed. Persist the
            // correction intent explicitly so restarts/retries cannot lose the cN+1 B payload.
            val semanticCatchUpDue = job.semanticCatchUpPending

            val selectedRoute = SelectedRouteMetadata.parse(
                objectMapper = objectMapper,
                routeJson = route.routeJson,
                travelMode = route.travelMode,
                maxTravelMinutes = maxTravelMinutes,
            )
            val canonicalMinutes = route.travelMinutes?.let {
                require(EtaTravelTimePolicy.isValid(it, maxTravelMinutes)) {
                    "저장된 이동 시간은 1~$maxTravelMinutes 사이여야 합니다."
                }
                it
            }
            val trustedSelectedMinutes = selectedRoute.travelMinutes
                ?.takeIf { canonicalMinutes == null || it == canonicalMinutes }
            val fallbackMinutes = canonicalMinutes ?: trustedSelectedMinutes
                ?: error("교통 조회 fallback 이동 시간이 없습니다.")
            val routeFingerprint = ScheduleEtaRouteFingerprint.calculate(
                schedule = schedule,
                travelMinutes = route.travelMinutes,
                travelMode = route.travelMode,
                originLat = route.originLat,
                originLng = route.originLng,
                routeJson = route.routeJson,
            )
            val request = TrafficRequest(
                originLat = requireNotNull(route.originLat) { "출발지 위도가 없습니다." },
                originLng = requireNotNull(route.originLng) { "출발지 경도가 없습니다." },
                destinationLat = requireNotNull(route.destinationLat) { "도착지 위도가 없습니다." },
                destinationLng = requireNotNull(route.destinationLng) { "도착지 경도가 없습니다." },
                travelMode = requireNotNull(route.travelMode) { "이동 수단이 없습니다." },
                fallbackTravelMinutes = fallbackMinutes,
                selectedRouteJson = route.routeJson,
                selectedRouteTravelMinutes = trustedSelectedMinutes,
                selectedRouteOption = selectedRoute.routeOption,
                selectedTransitItineraryJson = selectedRoute.transitItineraryJson,
                evaluatedAt = now,
                plannedDepartureAt = maxOf(
                    now,
                    job.lastRecommendedDepartureAt ?: job.departureAt,
                ),
                targetArrivalAt = schedule.startAt,
                maxTravelMinutes = maxTravelMinutes,
            )
            val evaluationId = UUID.randomUUID().toString()

            val trafficResult = trafficClient.getTravelMinutes(request)

            scheduleEtaEvaluationHistory = trafficResult.toEvaluationHistory(
                evaluationId = evaluationId,
                jobId = requireNotNull(job.id) {
                    "ETA 평가 대상 작업에는 저장된 ID가 필요합니다."
                },
                scheduleId = job.scheduleId,
                memberId = job.memberId,
                request = request,
            )
            val previousEffectiveRouteSnapshot = EffectiveTransitRouteSnapshot.parse(
                objectMapper = objectMapper,
                json = job.lastEffectiveTransitRouteJson,
            )
            val effectiveRouteSnapshot = if (route.travelMode == ScheduleTravelMode.TRANSIT) {
                EffectiveTransitRouteSnapshot.from(
                    trafficResult = trafficResult,
                    previous = previousEffectiveRouteSnapshot,
                    evaluationCheckCount = job.checkCount,
                    notificationGeneration = job.notificationGeneration,
                )
            } else {
                null
            }
            val effectiveRouteSnapshotJson = effectiveRouteSnapshot
                ?.let(objectMapper::writeValueAsString)
            val verifiedEffectiveRoute = effectiveRouteSnapshot
                ?.takeIf(EffectiveTransitRouteSnapshot::accepted)
                ?.effectiveTransitRoute
            val routeChanged = effectiveRouteSnapshot?.routeChanged == true
            val routeTransitioned = effectiveRouteSnapshot?.routeTransitioned == true
            val effectiveRouteSummary = verifiedEffectiveRoute
                ?.segments
                ?.asSequence()
                ?.mapNotNull { it.lineName?.trim()?.take(24)?.takeIf(String::isNotBlank) }
                ?.distinct()
                ?.take(3)
                ?.toList()
                ?.takeIf(List<String>::isNotEmpty)
                ?.joinToString(" → ")
            val etaFailureReason = sanitizeTrafficFailureReason(trafficResult.failureReason)
            val previousEtaFailureReason = sanitizeTrafficFailureReason(job.lastEtaFailureReason)
            val transferFailureReason = etaFailureReason.takeIf { it.isTransitTransferFailure() }
            val previousTransferFailureReason = previousEtaFailureReason
                .takeIf { it.isTransitTransferFailure() }
            val transferFailureTransition = transferFailureReason != null &&
                transferFailureReason != previousTransferFailureReason
            val predictedArrivalAt = trafficResult.userVisiblePredictedArrivalAt(schedule.startAt)
            operationalMetrics.recordSafely {
                recordEtaResolution(
                    source = trafficResult.source,
                    degraded = trafficResult.stale || trafficResult.failureReason != null,
                )
            }
            val travelMinutes = trafficResult.travelMinutes
            require(EtaTravelTimePolicy.isValid(travelMinutes, maxTravelMinutes)) {
                "교통 조회 결과는 1~$maxTravelMinutes 사이여야 합니다."
            }
            val comparableLiveTravelMinutes = trafficResult.fetchedAt
                ?.takeIf { trafficResult.source == TrafficSource.LIVE_PROVIDER }
                ?.let {
                    job.comparableLiveTravelMinutes(
                        currentLiveFetchedAt = it,
                        maxAgeMinutes = liveComparatorMaxAgeMinutes,
                        routeFingerprint = routeFingerprint,
                    )
                }
            val previousRecommendedDepartureAt =
                (job.lastRecommendedDepartureAt ?: job.departureAt)
                    .takeUnless { it.isAfter(schedule.startAt) }

            val candidateRecommendedDepartureAt =
                trafficResult.actionableRecommendedDepartureAt()
                    ?.takeUnless { it.isAfter(schedule.startAt) }
                // Provider timeout/degraded 결과는 직전 알림시각을 유지한다.
                    ?: previousRecommendedDepartureAt
                        ?.takeIf { !trafficResult.accepted }
                    ?: schedule.startAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES)

            // 대중교통 재조회 결과가 기존 알림을 뒤로 미루지 못하게 수정
            val recommendedDepartureAt = preventUnsafeTransitPostponement(
                travelMode = request.travelMode,
                previousRecommendedDepartureAt = previousRecommendedDepartureAt,
                candidateRecommendedDepartureAt = candidateRecommendedDepartureAt,
            )
            val degradedTransitPresentation =
                route.travelMode == ScheduleTravelMode.TRANSIT && !trafficResult.accepted
            val preservedAcceptedRoute = effectiveRouteSnapshot
                ?.acceptedRoute()
                ?.takeIf { degradedTransitPresentation }
            val liveActivityTravelMinutes = when {
                preservedAcceptedRoute != null -> preservedAcceptedRoute.totalMinutes
                degradedTransitPresentation -> fallbackMinutes
                else -> travelMinutes
            }
            val liveActivityRecommendedDepartureAt = when {
                preservedAcceptedRoute != null -> preservedAcceptedRoute.departureAt
                degradedTransitPresentation ->
                    schedule.startAt.minus(fallbackMinutes.toLong(), ChronoUnit.MINUTES)
                else -> recommendedDepartureAt
            }
            val liveActivityPredictedArrivalAt = when {
                preservedAcceptedRoute != null -> preservedAcceptedRoute.arrivalAt
                degradedTransitPresentation -> null
                else -> predictedArrivalAt
            }
            val liveActivityFirstBoardingWaitMinutes = when {
                preservedAcceptedRoute != null ->
                    effectiveRouteSnapshot.acceptedFirstBoardingWaitMinutes()
                degradedTransitPresentation -> null
                else -> trafficResult.firstBoardingWaitMinutes
            }
            val liveActivityEtaSource = when {
                preservedAcceptedRoute != null ->
                    effectiveRouteSnapshot.lastAcceptedEtaSource ?: TrafficSource.SELECTED_ROUTE
                degradedTransitPresentation && trustedSelectedMinutes != null ->
                    TrafficSource.SELECTED_ROUTE
                degradedTransitPresentation -> TrafficSource.SAVED_FALLBACK
                else -> trafficResult.source
            }
            val liveActivityRouteSegments = when {
                preservedAcceptedRoute != null -> preservedAcceptedRoute.toRouteDisplaySegments()
                degradedTransitPresentation -> selectedRoute.routeSegments
                else -> verifiedEffectiveRoute
                    ?.toRouteDisplaySegments()
                    ?: selectedRoute.routeSegments
            }
            liveActivityProjection = ScheduleLiveActivityEtaProjection(
                memberId = job.memberId,
                scheduleId = job.scheduleId,
                notificationGeneration = job.notificationGeneration,
                checkCount = job.checkCount,
                evaluatedAt = maxOf(now, trafficResult.fetchedAt ?: now),
                scheduleTitle = schedule.title,
                destinationName = route.destinationName,
                scheduleStartAt = schedule.startAt,
                travelMinutes = liveActivityTravelMinutes,
                recommendedDepartureAt = liveActivityRecommendedDepartureAt,
                predictedArrivalAt = liveActivityPredictedArrivalAt,
                firstBoardingWaitMinutes = liveActivityFirstBoardingWaitMinutes,
                etaSource = liveActivityEtaSource,
                etaStale = trafficResult.stale || degradedTransitPresentation,
                // 실패/불확실 후보는 추천 경로처럼 표시하지 않는다. 검증된 여정이 없으면
                // 마지막 검증 여정 전체 또는 저장 경로 전체로 ETA/대기/경로바를 함께 되돌린다.
                routeSegments = liveActivityRouteSegments,
                // 다음 조회시각은 아직 확정되지 않았다. 실제 전이 상태를 저장한 뒤,
                // Live Activity에 전달하기 직전에 유효시간을 반드시 채운다.
                staleAt = null,
            )
            val alarmIntent = departureAlarmSyncService?.let {
                SchedulePushAlarmIntent.AutomaticEta(
                    memberId = job.memberId,
                    scheduleId = job.scheduleId,

                    // UNKNOWN은 시간표 증거 부족일 뿐 확정 환승 실패가 아니다.
                    // 실제 환승 실패가 확인된 MISSED에서만 기존 알람을 취소한다.
                    notificationEnabled =
                        etaFailureReason != TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,

                    alertMode = route.alertMode,
                    recommendedDepartureAt = recommendedDepartureAt,
                    scheduleTitle = schedule.title,

                    // Worker가 MISSED로 인해 직접 취소했던 알람만 정상 회복 시 복구한다.
                    // UNKNOWN에서는 알람을 취소하지 않으므로 복구 대상도 아니다.
                    resumeCanceledAfterTransitTransferFailure =
                        etaFailureReason == null &&
                                previousEtaFailureReason ==
                                TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
                )
            }

            val reminderDecision = departureReminderPolicy.decide(
                now = now,
                recommendedDepartureAt = recommendedDepartureAt,
                scheduleAt = schedule.startAt,
                lastNotifiedDepartureAt =
                    job.lastHandledDepartureAt ?: job.lastNotifiedDepartureAt,
                lastReminderBoundaryAt =
                    job.lastHandledReminderBoundaryAt ?: job.lastReminderBoundaryAt,
                departureNoticeSentAt =
                    job.handledDepartureNoticeAt ?: job.departureNoticeSentAt,
                lastDepartureReminderBoundaryAt =
                    job.lastHandledDepartureReminderBoundaryAt
                        ?: job.lastDepartureReminderBoundaryAt,
                snoozedUntil = job.snoozedUntil,
                alertLeadMinutes = departureAlertLeadMinutes,
                reminderIntervalMinutes = departureReminderIntervalMinutes,
            )
            val reminderBoundaryAt = if (reminderDecision == DepartureReminderDecision.ADVANCE_NOTICE) {
                departureReminderPolicy.reminderBoundaryAt(
                    now = now,
                    recommendedDepartureAt = recommendedDepartureAt,
                    alertLeadMinutes = departureAlertLeadMinutes,
                    reminderIntervalMinutes = departureReminderIntervalMinutes,
                )
            } else {
                null
            }
            val trafficChangeMinutes = trafficChangeMinutes(
                previousTravelMinutes = comparableLiveTravelMinutes,
                currentTravelMinutes = travelMinutes,
            )
            val departureAdvanceMinutes = job.lastRecommendedDepartureAt
                ?.takeIf {
                    trafficResult.recommendedDepartureAt != null &&
                        !trafficResult.stale &&
                        trafficResult.source in setOf(
                            TrafficSource.LIVE_PROVIDER,
                            TrafficSource.TIMETABLE_PROVIDER,
                        )
                }
                ?.takeIf { recommendedDepartureAt.isBefore(it) }
                ?.let { previous ->
                    Duration.between(recommendedDepartureAt, previous).toMinutes().toInt()
                }
                ?.coerceAtLeast(0)
                ?: 0
            val showDepartureActions = reminderDecision.departNowAction ||
                (
                    (job.handledDepartureNoticeAt ?: job.departureNoticeSentAt) != null &&
                        !now.isBefore(recommendedDepartureAt)
                    )
            // 이동 시간이 늘어난 경우만 즉시 보낸다. 줄어든 경우는 다음 경계 시각만 재계산해 불필요한 알림을 줄인다.
            val deduplicationKey = schedulePushInboxDeduplicationKey(job)
            val persistedEvent = notificationUseCase.findPersistedEvent(job.memberId, deduplicationKey)
            val dispatchCheckedAt = currentAtOrAfter(now)
            val persistedEventExpiresAt = persistedEvent?.data
                ?.get(SCHEDULE_ETA_EVENT_EXPIRES_AT)
                ?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }
            val persistedEventExpired = persistedEvent != null && (
                persistedEventExpiresAt == null ||
                    !dispatchCheckedAt.isBefore(persistedEventExpiresAt)
                )
            val activePersistedEvent = persistedEvent?.takeUnless { persistedEventExpired }
            val malformedPersistedEvent = activePersistedEvent != null &&
                !activePersistedEvent.matchesFrozenEtaState(
                    job = job,
                    scheduleAt = schedule.startAt,
                    effectiveRouteSnapshot = previousEffectiveRouteSnapshot,
                )
            preserveFrozenEtaState = activePersistedEvent != null
            if (malformedPersistedEvent) {
                // The event outbox and source row did not commit the same A snapshot (for example,
                // a crash after the independent outbox commit). Sending either the frozen event A
                // or the newly evaluated B state would create a mixed user-visible snapshot.
                log.warn(
                    "Frozen schedule ETA event does not match its source state; " +
                        "closing check without dispatch. jobId={}, scheduleId={}, generation={}, checkCount={}",
                    job.id,
                    job.scheduleId,
                    job.notificationGeneration,
                    job.checkCount,
                )
            }
            val eventExpiresAt = persistedEventExpiresAt
                ?: dispatchCheckedAt.plusSeconds(etaEventTtlSeconds)
            val shouldPush =
                !persistedEventExpired && !malformedPersistedEvent && (
                    persistedEvent != null ||
                    reminderDecision != DepartureReminderDecision.NONE ||
                    trafficChangeMinutes > 0 ||
                    departureAdvanceMinutes > 0 ||
                    semanticCatchUpDue ||
                    transferFailureTransition ||
                    routeTransitioned
                    )

            val pushOutcome = if (persistedEventExpired || malformedPersistedEvent) {
                // The immutable payload is expired or cannot be proven coherent with its source.
                // Close cN without dispatch and make its outbox key stale; the next scheduler tick
                // recalculates a fresh cN+1 snapshot.
                SchedulePushOutcome(catchUpRequired = true)
            } else if (shouldPush) {
                val onTimeArrivalPossible = predictedArrivalAt?.let {
                    !it.isAfter(schedule.startAt)
                }
                val alarmOccurrence = alarmReminderOccurrence(
                    alertMode = route.alertMode,
                    decision = reminderDecision,
                    reminderBoundaryAt = reminderBoundaryAt,
                    recommendedDepartureAt = recommendedDepartureAt,
                )
                val semanticWarningVisible =
                    trafficChangeMinutes > 0 ||
                        departureAdvanceMinutes > 0 ||
                        semanticCatchUpDue ||
                        routeTransitioned ||
                        transferFailureReason != null ||
                        onTimeArrivalPossible == false
                val nativeAlarmCoverageEligible =
                    alarmOccurrence != null &&
                        !dispatchCheckedAt.isAfter(
                            alarmOccurrence.triggerAt.plusSeconds(
                                NATIVE_ALARM_REPLACEMENT_LATE_GRACE_SECONDS,
                            )
                        )
                val useNativeAlarmReplacement =
                    nativeAlarmCoverageEligible && !semanticWarningVisible
                val liveMessage = if (useNativeAlarmReplacement) {
                    val occurrence = requireNotNull(alarmOccurrence)
                    trafficChangePolicy.createCanonicalDepartureReminderMessage(
                        scheduleTitle = schedule.title,
                        recommendedDepartureAt = recommendedDepartureAt,
                        decision = reminderDecision,
                        minutesBeforeDeparture = occurrence.minutesBeforeDeparture,
                    )
                } else {
                    trafficChangePolicy.createMessage(
                        scheduleTitle = schedule.title,
                        previousTravelMinutes = comparableLiveTravelMinutes,
                        currentTravelMinutes = travelMinutes,
                        recommendedDepartureAt = recommendedDepartureAt,
                        decision = reminderDecision,
                        alertLeadMinutes = departureAlertLeadMinutes,
                        reminderMinutesBeforeDeparture = reminderMinutesBeforeDeparture(
                            reminderBoundaryAt = reminderBoundaryAt,
                            recommendedDepartureAt = recommendedDepartureAt,
                        ),
                        departureAdvanceMinutes = departureAdvanceMinutes,
                        onTimeArrivalPossible = onTimeArrivalPossible,
                        predictedArrivalAt = predictedArrivalAt,
                        transferFailureReason = transferFailureReason,
                        routeTransitioned = routeTransitioned,
                        usingAlternativeRoute = routeChanged,
                        effectiveRouteSummary = effectiveRouteSummary,
                    )
                }
                val nativeAlarmCoverageSelector = alarmOccurrence
                    // A retry must dispatch the frozen event itself. A selector calculated from
                    // the current B evaluation could otherwise replace A with a B native alarm.
                    ?.takeIf { nativeAlarmCoverageEligible && activePersistedEvent == null }
                    ?.let { occurrence ->
                        DepartureAlarmReminderCoverageSelector(
                            memberId = job.memberId,
                            scheduleId = job.scheduleId,
                            recommendedDepartureAt = recommendedDepartureAt,
                            occurrenceId = occurrence.occurrenceId,
                            occurrenceTriggerAt = occurrence.triggerAt,
                            // The native alarm replaces only the ordinary reminder. Safety and
                            // traffic-change information remains visible on every active device.
                            semanticWarningVisible = semanticWarningVisible,
                        )
                    }
                val liveData = mapOf(
                    "type" to pushPayloadType(reminderDecision, showDepartureActions),
                    "scheduleId" to job.scheduleId.toString(),
                    "travelMinutes" to travelMinutes.toString(),
                    "recommendedDepartureAt" to recommendedDepartureAt.toString(),
                    "predictedArrivalAt" to (predictedArrivalAt?.toString() ?: ""),
                    "onTimeArrivalPossible" to (onTimeArrivalPossible?.toString() ?: ""),
                    "etaSource" to trafficResult.source.name,
                    "etaStale" to trafficResult.stale.toString(),
                    "etaFailureReason" to (etaFailureReason ?: ""),
                    "transitTransferFeasibility" to transferFailureReason.toTransferFeasibilityPayload(),
                    "etaRouteProvenance" to (
                        trafficResult.transitRouteProvenance?.name ?: ""
                    ),
                    "etaTimingBasis" to (trafficResult.transitTimingBasis?.name ?: ""),
                    "firstBoardingWaitMinutes" to (
                        trafficResult.firstBoardingWaitMinutes?.toString() ?: ""
                    ),
                    "routeChanged" to routeChanged.toString(),
                    "effectiveRouteIdentity" to (verifiedEffectiveRoute?.identity ?: ""),
                    "departNow" to showDepartureActions.toString(),
                    "departureReminderDecision" to reminderDecision.name,
                    "reminderBoundaryAt" to (reminderBoundaryAt?.toString() ?: ""),
                    "snoozeMinutes" to departureSnoozeMinutes.toString(),
                    "trafficChangeMinutes" to (liveMessage.trafficChangeMinutes?.toString() ?: "0"),
                    "departureAdvanceMinutes" to departureAdvanceMinutes.toString(),
                    "schedulePushJobId" to (job.id?.toString() ?: ""),
                    "schedulePushCheckCount" to job.checkCount.toString(),
                    "notificationGeneration" to job.notificationGeneration.toString(),
                    "notificationInputFingerprint" to job.notificationInputFingerprint,
                    SCHEDULE_ETA_EVENT_EXPIRES_AT to eventExpiresAt.toString(),
                )
                val persistedMeaningChanged = persistedEvent != null &&
                    ETA_PUSH_SEMANTIC_KEYS.any { key -> persistedEvent.data[key] != liveData[key] }
                val dispatchFence = job.id?.let {
                    PushDispatchFence(
                        jobId = it,
                        workerId = workerId,
                        jobVersion = requireNotNull(job.version),
                        notificationGeneration = job.notificationGeneration,
                        notificationInputFingerprint = job.notificationInputFingerprint,
                        expectedCheckCount = job.checkCount,
                        sourceExpiresAt = eventExpiresAt,
                        expectedMemberId = job.memberId,
                        expectedScheduleId = job.scheduleId,
                    )
                }
                val eventTitle = persistedEvent?.title ?: liveMessage.title
                val eventBody = persistedEvent?.body ?: liveMessage.body
                val eventData = persistedEvent?.data ?: liveData
                val sendResult = when {
                    dispatchFence == null && nativeAlarmCoverageSelector == null ->
                        notificationUseCase.sendToMember(
                            memberId = job.memberId,
                            title = eventTitle,
                            body = eventBody,
                            data = eventData,
                            inboxDeduplicationKey = deduplicationKey,
                        )
                    dispatchFence == null ->
                        notificationUseCase.sendToMemberWithNativeAlarmCoverage(
                            memberId = job.memberId,
                            title = eventTitle,
                            body = eventBody,
                            data = eventData,
                            inboxDeduplicationKey = deduplicationKey,
                            nativeAlarmCoverageSelector = requireNotNull(nativeAlarmCoverageSelector),
                        )
                    nativeAlarmCoverageSelector == null ->
                        notificationUseCase.sendToMemberFenced(
                            memberId = job.memberId,
                            title = eventTitle,
                            body = eventBody,
                            data = eventData,
                            // push 실패 재시도 중에는 checkCount가 증가하지 않는다. 같은 회차는 한 알림으로
                            // 합치되, 다음 ETA 확인 회차는 별도 알림이 되도록 job과 checkCount를 함께 사용한다.
                            inboxDeduplicationKey = deduplicationKey,
                            dispatchFence = dispatchFence,
                        )
                    else ->
                        notificationUseCase.sendToMemberFencedWithNativeAlarmCoverage(
                            memberId = job.memberId,
                            title = eventTitle,
                            body = eventBody,
                            data = eventData,
                            inboxDeduplicationKey = deduplicationKey,
                            dispatchFence = dispatchFence,
                            nativeAlarmCoverageSelector = nativeAlarmCoverageSelector,
                        )
                }
                // A persisted event is the semantic authority for this check. A provider-returned
                // snapshot must not replace it while retrying the same immutable event.
                val eventSnapshot = activePersistedEvent ?: sendResult.eventSnapshot
                val eventDecision = eventSnapshot?.data
                    ?.get("departureReminderDecision")
                    ?.let { runCatching { DepartureReminderDecision.valueOf(it) }.getOrNull() }
                    ?: reminderDecision
                val eventRecommendedDepartureAt = eventSnapshot?.data
                    ?.get("recommendedDepartureAt")
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: recommendedDepartureAt
                val eventReminderBoundaryAt = eventSnapshot?.data
                    ?.get("reminderBoundaryAt")
                    ?.takeIf(String::isNotBlank)
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: reminderBoundaryAt
                if (sendResult.fenceRejected) {
                    // Lease/generation을 잃었다면 아래 persist가 현재 source row 검증에서 no-op이
                    // 된다. 여전히 lease를 소유하고 ETA TTL만 만료된 경우에는 앞 기기의 확정/모호
                    // 결과를 보존하며 cN을 닫고 다음 tick에 fresh cN+1을 만든다.
                    log.info(
                        "Schedule push fence rejected during dispatch. " +
                            "jobId={}, generation={}, workerId={}, handled={}",
                        job.id,
                        job.notificationGeneration,
                        workerId,
                        sendResult.durablyHandledCount,
                    )
                    SchedulePushOutcome(
                        notificationHandled = sendResult.durablyHandledCount > 0,
                        nativeAlarmCovered = sendResult.nativeAlarmCoveredCount > 0,
                        confirmedSuccess = sendResult.confirmedSuccessCount > 0,
                        uncertain = sendResult.ambiguousCount > 0,
                        confirmedAt = when {
                            sendResult.sentCount > 0 -> now
                            sendResult.alreadyDeliveredCount > 0 -> sendResult.alreadyDeliveredAt
                            else -> null
                        },
                        decision = eventDecision,
                        notifiedDepartureAt = eventRecommendedDepartureAt,
                        reminderBoundaryAt = eventReminderBoundaryAt,
                        catchUpRequired = true,
                    )
                } else {
                    log.info(
                        "Schedule push handled. jobId={}, scheduleId={}, decision={}, travelMinutes={}, requested={}, nativeAlarmCovered={}, attempted={}, sent={}, alreadyDelivered={}, ambiguous={}, deduplicated={}, failed={}, retryableFailed={}",
                        job.id,
                        job.scheduleId,
                        reminderDecision,
                        travelMinutes,
                        sendResult.requestedCount,
                        sendResult.nativeAlarmCoveredCount,
                        sendResult.attemptedCount,
                        sendResult.sentCount,
                        sendResult.alreadyDeliveredCount,
                        sendResult.ambiguousCount,
                        sendResult.deduplicatedCount,
                        sendResult.failedCount,
                        sendResult.retryableFailedCount,
                    )
                    if (sendResult.retryableFailedCount > 0) {
                        // 일부 기기가 성공했더라도 같은 generation/check event를 유지해야 다음
                        // 실행에서 SUCCESS는 건너뛰고 FAILED 기기만 다시 claim할 수 있다.
                        if (sendResult.confirmedSuccessCount > 0) {
                            job.recordConfirmedPush(
                                if (sendResult.sentCount > 0) now
                                else sendResult.alreadyDeliveredAt ?: now
                            )
                        }
                        val transition = scheduleRetryAfterPushFailure(
                            job = job,
                            now = now,
                            requestedCount = sendResult.requestedCount,
                            failedCount = sendResult.retryableFailedCount,
                            beforeTransition = { retryDueAt ->
                                if (!preserveFrozenEtaState) {
                                    job.recordRetryEtaEvaluation(
                                        trafficResult = trafficResult,
                                        travelMinutes = travelMinutes,
                                        recommendedDepartureAt = recommendedDepartureAt,
                                        predictedArrivalAt = predictedArrivalAt,
                                        etaFailureReason = etaFailureReason,
                                        travelMode = route.travelMode,
                                        routeFingerprint = routeFingerprint,
                                        etaRefreshDueAt = retryDueAt,
                                        effectiveTransitRouteJson = effectiveRouteSnapshotJson,
                                        now = now,
                                    )
                                }
                            },
                        )
                        persistMeasuredTransition(
                            job = job,
                            outcome = transition,
                            uncertainDelivery = sendResult.ambiguousCount > 0,
                            alarmIntent = alarmIntent.takeUnless { preserveFrozenEtaState },
                            liveActivityProjection = liveActivityProjection
                                .takeUnless { preserveFrozenEtaState },
                            liveActivityEndReason = "JOB_TERMINAL_FAILURE".takeIf {
                                transition == EtaJobMetricOutcome.TERMINAL_FAILURE
                            },
                            scheduleEtaEvaluationHistory = scheduleEtaEvaluationHistory
                        )
                        return
                    }
                    if (sendResult.durablyHandledCount == 0) {
                        // FCM/APNs가 한 기기에도 전달하지 못했다면 성공 이력을 기록하지 않는다.
                        // 특히 DEPART_NOW를 완료 처리하면 다시 보낼 방법이 없어 반드시 재시도해야 한다.
                        val transition = scheduleRetryAfterPushFailure(
                            job = job,
                            now = now,
                            requestedCount = sendResult.requestedCount,
                            failedCount = sendResult.failedCount,
                            beforeTransition = { retryDueAt ->
                                if (!preserveFrozenEtaState) {
                                    job.recordRetryEtaEvaluation(
                                        trafficResult = trafficResult,
                                        travelMinutes = travelMinutes,
                                        recommendedDepartureAt = recommendedDepartureAt,
                                        predictedArrivalAt = predictedArrivalAt,
                                        etaFailureReason = etaFailureReason,
                                        travelMode = route.travelMode,
                                        routeFingerprint = routeFingerprint,
                                        etaRefreshDueAt = retryDueAt,
                                        effectiveTransitRouteJson = effectiveRouteSnapshotJson,
                                        now = now,
                                    )
                                }
                            },
                        )
                        persistMeasuredTransition(
                            job = job,
                            outcome = transition,
                            alarmIntent = alarmIntent.takeUnless { preserveFrozenEtaState },
                            liveActivityProjection = liveActivityProjection
                                .takeUnless { preserveFrozenEtaState },
                            liveActivityEndReason = "JOB_TERMINAL_FAILURE".takeIf {
                                transition == EtaJobMetricOutcome.TERMINAL_FAILURE
                            },
                            scheduleEtaEvaluationHistory = scheduleEtaEvaluationHistory
                        )
                        return
                    }
                    SchedulePushOutcome(
                        notificationHandled = true,
                        nativeAlarmCovered = sendResult.nativeAlarmCoveredCount > 0,
                        confirmedSuccess = sendResult.confirmedSuccessCount > 0,
                        uncertain = sendResult.ambiguousCount > 0,
                        confirmedAt = when {
                            sendResult.sentCount > 0 -> now
                            sendResult.alreadyDeliveredCount > 0 -> sendResult.alreadyDeliveredAt
                            else -> null
                        },
                        decision = eventDecision,
                        notifiedDepartureAt = eventRecommendedDepartureAt,
                        reminderBoundaryAt = eventReminderBoundaryAt,
                        catchUpRequired = persistedMeaningChanged,
                    )
                }
            } else {
                log.info(
                    "Schedule ETA refreshed without push. jobId={}, scheduleId={}, travelMinutes={}, recommendedDepartureAt={}",
                job.id,
                job.scheduleId,
                travelMinutes,
                recommendedDepartureAt,
            )
                SchedulePushOutcome()
            }

            val transitionAt = maxOf(now, Instant.now(clock), trafficResult.fetchedAt ?: now)
            val nextCheckAt = if (pushOutcome.catchUpRequired) {
                // 이전 immutable event를 끝낸 사이 live reminder 경계를 넘었다. 같은 run에서
                // 즉시 재선점해 batch를 독점하지 않되 다음 scheduler tick에는 새 check/event로
                // 현재 의미(DEPART_NOW 등)를 처리한다.
                transitionAt.plusSeconds(1)
            } else {
                nextCheckAt(
                    job = job,
                    now = transitionAt,
                    recommendedDepartureAt = recommendedDepartureAt,
                    scheduleAt = schedule.startAt,
                    effectiveDepartureNoticeSentAt = if (
                        pushOutcome.notificationHandled &&
                        pushOutcome.decision == DepartureReminderDecision.DEPART_NOW &&
                        (job.handledDepartureNoticeAt ?: job.departureNoticeSentAt) == null
                    ) {
                        transitionAt
                    } else {
                        job.handledDepartureNoticeAt ?: job.departureNoticeSentAt
                    },
                )
            }
            val notifiedDepartureAt = pushOutcome.notifiedDepartureAt.takeIf {
                pushOutcome.notificationHandled &&
                    pushOutcome.decision != DepartureReminderDecision.NONE
            }
            val departureReminderStage = pushOutcome.decision.stage.takeIf {
                pushOutcome.notificationHandled
            }
            val handledDepartureReminderBoundaryAt = departureReminderBoundaryAt(
                decision = pushOutcome.decision,
                job = job,
                recommendedDepartureAt = pushOutcome.notifiedDepartureAt,
            ).takeIf {
                pushOutcome.notificationHandled && pushOutcome.decision.stage != null
            }
            val clearSnooze =
                pushOutcome.notificationHandled &&
                    pushOutcome.decision == DepartureReminderDecision.SNOOZE
            if (preserveFrozenEtaState) {
                job.finishCheckPreservingEta(
                    pushSent = pushOutcome.confirmedSuccess,
                    pushConfirmed = pushOutcome.confirmedSuccess,
                    pushConfirmedAt = pushOutcome.confirmedAt,
                    pushUncertain = pushOutcome.uncertain,
                    nativeAlarmCovered = pushOutcome.nativeAlarmCovered,
                    notifiedDepartureAt = notifiedDepartureAt,
                    reminderBoundaryAt = pushOutcome.reminderBoundaryAt,
                    departureReminderStage = departureReminderStage,
                    departureReminderBoundaryAt = handledDepartureReminderBoundaryAt,
                    clearSnooze = clearSnooze,
                    semanticCatchUpPending = pushOutcome.catchUpRequired,
                    nextCheckAt = nextCheckAt,
                    completeAfterCheck = nextCheckAt == null,
                    now = transitionAt,
                )
            } else {
                job.finishCheck(
                    travelMinutes = travelMinutes,
                    recommendedDepartureAt = recommendedDepartureAt,
                    pushSent = pushOutcome.confirmedSuccess,
                    pushConfirmed = pushOutcome.confirmedSuccess,
                    pushConfirmedAt = pushOutcome.confirmedAt,
                    pushUncertain = pushOutcome.uncertain,
                    nativeAlarmCovered = pushOutcome.nativeAlarmCovered,
                    notifiedDepartureAt = notifiedDepartureAt,
                    reminderBoundaryAt = pushOutcome.reminderBoundaryAt,
                    departureReminderStage = departureReminderStage,
                    departureReminderBoundaryAt = handledDepartureReminderBoundaryAt,
                    clearSnooze = clearSnooze,
                    semanticCatchUpPending = pushOutcome.catchUpRequired,
                    nextCheckAt = nextCheckAt,
                    completeAfterCheck = nextCheckAt == null,
                    etaSource = trafficResult.source,
                    liveFetchedAt = trafficResult.fetchedAt,
                    etaStale = trafficResult.stale,
                    etaFailureReason = etaFailureReason,
                    predictedArrivalAt = predictedArrivalAt,
                    etaTravelMode = route.travelMode,
                    etaRouteFingerprint = routeFingerprint,
                    effectiveTransitRouteJson = effectiveRouteSnapshotJson,
                    liveComparatorMaxAgeMinutes = liveComparatorMaxAgeMinutes,
                    now = transitionAt,
                )
            }
            persistMeasuredTransition(
                job = job,
                outcome = EtaJobMetricOutcome.PROCESSED,
                uncertainDelivery = pushOutcome.uncertain,
                alarmIntent = alarmIntent.takeUnless { preserveFrozenEtaState },
                liveActivityProjection = liveActivityProjection
                    .takeUnless { preserveFrozenEtaState },
                liveActivityEndReason = "JOB_COMPLETE".takeIf { nextCheckAt == null },
                scheduleEtaEvaluationHistory = scheduleEtaEvaluationHistory
            )
        } catch (exception: Exception) {
            operationalMetrics.recordSafely {
                // This is an execution observation, not a durable state transition. The eventual
                // RETRY_SCHEDULED or TERMINAL_FAILURE outcome is counted separately and only after
                // the independent persistence transaction commits.
                recordEtaWorkerEvent(EtaWorkerMetricEvent.PROCESSING_EXCEPTION)
            }
            log.warn(
                "Schedule push job failed. jobId={}, scheduleId={}, workerId={}, errorCode={}",
                job.id,
                job.scheduleId,
                workerId,
                exception.javaClass.simpleName,
            )
            val transition = retryOrFail(
                job = job,
                now = now,
                reason = exception.message?.take(500) ?: exception.javaClass.simpleName,
            )
            persistMeasuredTransition(
                job = job,
                outcome = transition,
                liveActivityEndReason = "JOB_TERMINAL_FAILURE".takeIf {
                    transition == EtaJobMetricOutcome.TERMINAL_FAILURE
                },
                scheduleEtaEvaluationHistory = scheduleEtaEvaluationHistory
            )
        }
    }

    /**
     * A transition counter is emitted only after the independent persistence transaction returns
     * successfully. Persistence failures are swallowed here so the outer processing catch cannot
     * mutate the same detached job and count a second retry transition.
     */
    private fun persistMeasuredTransition(
        job: SchedulePushJob,
        outcome: EtaJobMetricOutcome,
        uncertainDelivery: Boolean = false,
        alarmIntent: SchedulePushAlarmIntent? = null,
        liveActivityProjection: ScheduleLiveActivityEtaProjection? = null,
        liveActivityEndReason: String? = null,
        scheduleEtaEvaluationHistory : ScheduleEtaEvaluationHistory? = null,
    ) {
        val persisted = runCatching {
            pushJobCoordinator.persist(
                job,
                workerId,
                alarmIntent,
                liveActivityEndReason,
                scheduleEtaEvaluationHistory,
            )
        }.onFailure { failure ->
            log.error(
                "Schedule push state transition persistence failed. " +
                    "jobId={}, scheduleId={}, workerId={}, outcome={}, errorCode={}",
                job.id,
                job.scheduleId,
                workerId,
                outcome,
                failure.javaClass.simpleName,
            )
        }.getOrDefault(false)
        if (!persisted) return

        // Non-terminal ETA projection is retried by the next scheduled check. Terminal END is not
        // handled here: coordinator.persist atomically stored its durable lifecycle intent in the
        // source transition transaction above.
        runCatching {
            if (liveActivityEndReason == null && liveActivityProjection != null) {
                // 정상 처리·재시도에서 실제 저장한 ETA 갱신 경계를 사용한다.
                // 이후 실행 재예약으로 바뀔 수 있는 job.nextCheckAt과 구분한다.
                val etaRefreshDueAt = requireNotNull(job.lastEtaRefreshDueAt) {
                    "Live Activity ETA에는 저장된 갱신 예정 시각이 필요합니다."
                }
                // 출발 상태 API와 같은 유예시간을 적용하되 일정 시작을 넘기지 않는다.
                val staleAt = minOf(
                    liveActivityProjection.scheduleStartAt,
                    etaRefreshDueAt.plus(departureStatusSnapshotGraceMinutes, ChronoUnit.MINUTES),
                )
                scheduleLiveActivityService?.projectEta(
                    liveActivityProjection.copy(staleAt = staleAt),
                )
            }
            Unit
        }.onFailure { failure ->
            log.warn(
                "Live Activity projection failed after ETA state commit. " +
                    "jobId={}, scheduleId={}, errorCode={}",
                job.id,
                job.scheduleId,
                failure.javaClass.simpleName,
            )
        }

        operationalMetrics.recordSafely { recordEtaJob(outcome) }
        if (uncertainDelivery) {
            // This metric is job-scoped: multiple ambiguous device deliveries in one job still
            // represent one durably persisted uncertain ETA-job outcome.
            operationalMetrics.recordSafely {
                recordEtaJob(EtaJobMetricOutcome.UNCERTAIN_DELIVERY)
            }
        }
    }

    private fun SchedulePushJob.recordRetryEtaEvaluation(
        trafficResult: TrafficResult,
        travelMinutes: Int,
        recommendedDepartureAt: Instant,
        predictedArrivalAt: Instant?,
        etaFailureReason: String?,
        travelMode: ScheduleTravelMode?,
        routeFingerprint: String,
        etaRefreshDueAt: Instant?,
        effectiveTransitRouteJson: String?,
        now: Instant,
    ) {
        recordEtaEvaluationBeforeRetry(
            travelMinutes = travelMinutes,
            recommendedDepartureAt = recommendedDepartureAt,
            etaSource = trafficResult.source,
            liveFetchedAt = trafficResult.fetchedAt,
            etaStale = trafficResult.stale,
            etaFailureReason = etaFailureReason,
            predictedArrivalAt = predictedArrivalAt,
            etaTravelMode = travelMode,
            etaRouteFingerprint = routeFingerprint,
            etaRefreshDueAt = etaRefreshDueAt,
            effectiveTransitRouteJson = effectiveTransitRouteJson,
            liveComparatorMaxAgeMinutes = liveComparatorMaxAgeMinutes,
            now = maxOf(now, Instant.now(clock), trafficResult.fetchedAt ?: now),
        )
    }

    private fun SchedulePushJob.cancelAlarmIntent(): SchedulePushAlarmIntent? =
        departureAlarmSyncService?.let {
            SchedulePushAlarmIntent.Cancel(
                memberId = memberId,
                scheduleId = scheduleId,
            )
        }

    private fun nextCheckAt(
        job: SchedulePushJob,
        now: Instant,
        recommendedDepartureAt: Instant,
        scheduleAt: Instant,
        effectiveDepartureNoticeSentAt: Instant?,
    ): Instant? {
        if (!now.isBefore(scheduleAt)) return null

        // 출발까지 남은 시간에 따라 다음 ETA 조회시각을 계산한다.
        val etaRefreshAt = EtaRefreshCadencePolicy.nextRefreshAt(
            now = now,
            recommendedDepartureAt = recommendedDepartureAt,
        )

        val trafficCheckAt = periodicPushPolicy.nextCheckAt(
            now = now,
            recommendedDepartureAt = recommendedDepartureAt,
            intervalMinutes = EtaRefreshCadencePolicy.INTERVAL_MINUTES,
            alertLeadMinutes = departureAlertLeadMinutes,
            reminderIntervalMinutes = departureReminderIntervalMinutes,
        )
        val reminderCheckAt = departureReminderPolicy.nextReminderBoundary(
            now = now,
            recommendedDepartureAt = recommendedDepartureAt,
            scheduleAt = scheduleAt,
            lastNotifiedDepartureAt =
                job.lastHandledDepartureAt ?: job.lastNotifiedDepartureAt,
            departureNoticeSentAt = effectiveDepartureNoticeSentAt,
            lastDepartureReminderBoundaryAt =
                job.lastHandledDepartureReminderBoundaryAt
                    ?: job.lastDepartureReminderBoundaryAt,
            snoozedUntil = job.snoozedUntil,
            alertLeadMinutes = departureAlertLeadMinutes,
        )

        return listOfNotNull(etaRefreshAt, trafficCheckAt, reminderCheckAt, scheduleAt)
            .filter { it.isAfter(now) }
            .minOrNull()
    }

    private fun departureReminderBoundaryAt(
        decision: DepartureReminderDecision,
        job: SchedulePushJob,
        recommendedDepartureAt: Instant,
    ): Instant? =
        when (decision) {
            DepartureReminderDecision.DEPART_NOW -> recommendedDepartureAt
            DepartureReminderDecision.AFTER_DEPARTURE_3 ->
                requireNotNull(job.handledDepartureNoticeAt ?: job.departureNoticeSentAt)
                    .plus(3, ChronoUnit.MINUTES)
            DepartureReminderDecision.AFTER_DEPARTURE_7 ->
                requireNotNull(job.handledDepartureNoticeAt ?: job.departureNoticeSentAt)
                    .plus(7, ChronoUnit.MINUTES)
            DepartureReminderDecision.BEFORE_SCHEDULE_3 -> job.scheduleAt.minus(3, ChronoUnit.MINUTES)
            DepartureReminderDecision.BEFORE_SCHEDULE_1 -> job.scheduleAt.minus(1, ChronoUnit.MINUTES)
            DepartureReminderDecision.NONE,
            DepartureReminderDecision.ADVANCE_NOTICE,
            DepartureReminderDecision.SNOOZE -> null
        }

    private fun schedulePushInboxDeduplicationKey(job: SchedulePushJob): String {
        // 운영 worker가 조회한 엔티티에는 항상 id가 있다. 단위 테스트에서 사용하는 저장 전
        // 엔티티도 결정적인 키를 갖게 해 테스트가 재시도 의미를 그대로 검증할 수 있도록 한다.
        val jobIdentity = job.id?.toString() ?: "unsaved-${job.memberId}-${job.scheduleId}"
        return "schedule-push-job:$jobIdentity:g${job.notificationGeneration}:c${job.checkCount}"
    }

    /**
     * 새 개인 계획이 있으면 그것을 우선 사용한다. 마이그레이션 전 일정은 오너에게만 기존
     * ScheduleRoute fallback을 허용하며, 공유 사용자가 오너 경로로 알림을 받는 일은 막는다.
     */
    private fun resolveRouteSource(schedule: com.noLate.schedule.domain.Schedule, memberId: Long): PushRouteSource? {
        val personal = travelPlanRepository
            ?.findByScheduleIdAndMemberIdAndDeletedFalse(requireNotNull(schedule.id), memberId)
        if (personal != null) {
            // 업데이트 유스케이스의 즉시 취소와 별개인 최종 방어선이다. 배포 중이거나 작업이
            // 이미 선점된 경우에도 이전 목적지/시각으로 알림을 보내지 않는다.
            if (!personal.notificationEnabled || !ScheduleTravelPlanFingerprint.matches(personal, schedule)) {
                return null
            }
            val destination = schedule.route ?: return null
            return PushRouteSource(
                travelMinutes = personal.travelMinutes,
                travelMode = personal.travelMode,
                originLat = personal.originLat,
                originLng = personal.originLng,
                destinationLat = destination.destinationLat,
                destinationLng = destination.destinationLng,
                routeJson = personal.routeJson,
                destinationName = destination.destinationName ?: destination.locationName,
                alertMode = personal.alertMode,
            )
        }

        val legacy = schedule.route
            ?.takeIf { schedule.memberId == memberId && it.notificationEnabled }
            ?: return null
        return PushRouteSource(
            travelMinutes = legacy.travelMinutes,
            travelMode = legacy.travelMode,
            originLat = legacy.originLat,
            originLng = legacy.originLng,
            destinationLat = legacy.destinationLat,
            destinationLng = legacy.destinationLng,
            routeJson = legacy.routeJson,
            destinationName = legacy.destinationName ?: legacy.locationName,
            alertMode = legacy.alertMode,
        )
    }

    private fun pushPayloadType(decision: DepartureReminderDecision, showDepartureActions: Boolean): String =
        when {
            showDepartureActions -> "SCHEDULE_DEPARTURE_REMINDER"
            else -> when (decision) {
                DepartureReminderDecision.ADVANCE_NOTICE,
                DepartureReminderDecision.DEPART_NOW -> "SCHEDULE_DEPARTURE_REMINDER"
                DepartureReminderDecision.SNOOZE,
                DepartureReminderDecision.AFTER_DEPARTURE_3,
                DepartureReminderDecision.AFTER_DEPARTURE_7,
                DepartureReminderDecision.BEFORE_SCHEDULE_3,
                DepartureReminderDecision.BEFORE_SCHEDULE_1 -> "SCHEDULE_DEPARTURE_REMINDER"
                DepartureReminderDecision.NONE -> "SCHEDULE_TRAFFIC"
            }
        }

    private fun trafficChangeMinutes(previousTravelMinutes: Int?, currentTravelMinutes: Int): Int =
        previousTravelMinutes
            ?.let { currentTravelMinutes - it }
            ?: 0

    private fun String?.isTransitTransferFailure(): Boolean =
        this == TrafficFailureReasons.TRANSIT_TRANSFER_MISSED ||
            this == TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN

    private fun String?.toTransferFeasibilityPayload(): String = when (this) {
        TrafficFailureReasons.TRANSIT_TRANSFER_MISSED -> "MISSED"
        TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN -> "UNKNOWN"
        else -> ""
    }

    /**
     * The independent notification outbox may commit before the detached source job is saved. A
     * retry can dispatch that immutable event only when the source row still describes the exact
     * ETA/route snapshot from which the event was created. Values used only for presentation and
     * reminder bookkeeping are parsed as well so malformed state cannot fall back to evaluation B.
     */
    private fun AppNotificationSnapshot.matchesFrozenEtaState(
        job: SchedulePushJob,
        scheduleAt: Instant,
        effectiveRouteSnapshot: EffectiveTransitRouteSnapshot?,
    ): Boolean {
        val travelMinutes = job.lastTravelMinutes ?: return false
        val recommendedDepartureAt = job.lastRecommendedDepartureAt ?: return false
        val etaSource = job.lastEtaSource ?: return false
        val etaStale = job.lastEtaStale ?: return false
        val predictedArrivalAt = job.lastPredictedArrivalAt
        val etaFailureReason = sanitizeTrafficFailureReason(job.lastEtaFailureReason)
        val transferFailureReason = etaFailureReason.takeIf { it.isTransitTransferFailure() }
        val verifiedRoute = effectiveRouteSnapshot
            ?.takeIf(EffectiveTransitRouteSnapshot::accepted)
            ?.effectiveTransitRoute
        val expected = mapOf(
            "scheduleId" to job.scheduleId.toString(),
            "travelMinutes" to travelMinutes.toString(),
            "recommendedDepartureAt" to recommendedDepartureAt.toString(),
            "predictedArrivalAt" to (predictedArrivalAt?.toString() ?: ""),
            "onTimeArrivalPossible" to (
                predictedArrivalAt?.let { !it.isAfter(scheduleAt) }?.toString() ?: ""
            ),
            "etaSource" to etaSource.name,
            "etaStale" to etaStale.toString(),
            "etaFailureReason" to (etaFailureReason ?: ""),
            "transitTransferFeasibility" to
                transferFailureReason.toTransferFeasibilityPayload(),
            "etaRouteProvenance" to (
                effectiveRouteSnapshot?.transitRouteProvenance?.name ?: ""
            ),
            "etaTimingBasis" to (effectiveRouteSnapshot?.transitTimingBasis?.name ?: ""),
            "firstBoardingWaitMinutes" to (
                effectiveRouteSnapshot?.firstBoardingWaitMinutes?.toString() ?: ""
            ),
            "routeChanged" to (effectiveRouteSnapshot?.routeChanged == true).toString(),
            "effectiveRouteIdentity" to (verifiedRoute?.identity ?: ""),
            "schedulePushJobId" to (job.id?.toString() ?: ""),
            "schedulePushCheckCount" to job.checkCount.toString(),
            "notificationGeneration" to job.notificationGeneration.toString(),
            "notificationInputFingerprint" to job.notificationInputFingerprint,
        )
        if (expected.any { (key, value) -> data[key] != value }) return false

        val decision = data["departureReminderDecision"]
            ?.let { runCatching { DepartureReminderDecision.valueOf(it) }.getOrNull() }
            ?: return false
        val reminderBoundary = data["reminderBoundaryAt"] ?: return false
        if (decision == DepartureReminderDecision.ADVANCE_NOTICE) {
            if (runCatching { Instant.parse(reminderBoundary) }.getOrNull() == null) return false
        } else if (reminderBoundary.isNotBlank()) {
            return false
        }
        if (data["type"] !in FROZEN_ETA_EVENT_TYPES) return false
        if (data["departNow"] !in BOOLEAN_PAYLOAD_VALUES) return false
        if (data["snoozeMinutes"]?.toIntOrNull()?.let { it >= 0 } != true) return false
        if (data["trafficChangeMinutes"]?.toIntOrNull() == null) return false
        if (data["departureAdvanceMinutes"]?.toIntOrNull()?.let { it >= 0 } != true) return false
        return true
    }

    /**
     * 정상 ETA만 정시 가능으로 승격한다. 현재 확인된 차량이 마감 뒤 도착하는 경우는
     * 명시적으로 false를 유지하지만, 환승 실패/불확실 등 degraded 진단값은 null로 둔다.
     */
    private fun TrafficResult.userVisiblePredictedArrivalAt(targetArrivalAt: Instant): Instant? {
        val predicted = predictedArrivalAt ?: return null
        if (accepted) return predicted
        return predicted.takeIf {
            failureReason == TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE &&
                predicted.isAfter(targetArrivalAt)
        }
    }

    /**
     * Degraded 진단의 provider 추천시각은 native alarm/푸시를 바꾸지 못한다. 단, 현재 확인된
     * 동일 경로로 정시 도착이 불가능해 즉시 출발을 명시한 결과만 예외적으로 보존한다.
     */
    private fun TrafficResult.actionableRecommendedDepartureAt(): Instant? =
        recommendedDepartureAt?.takeIf {
            accepted || failureReason == TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE
        }

    private fun reminderMinutesBeforeDeparture(
        reminderBoundaryAt: Instant?,
        recommendedDepartureAt: Instant,
    ): Int =
        reminderBoundaryAt
            ?.let { Duration.between(it, recommendedDepartureAt).toMinutes().toInt() }
            ?.coerceAtLeast(0)
            ?: departureAlertLeadMinutes

    private fun alarmReminderOccurrence(
        alertMode: ScheduleAlertMode,
        decision: DepartureReminderDecision,
        reminderBoundaryAt: Instant?,
        recommendedDepartureAt: Instant,
    ): AlarmReminderOccurrence? {
        if (alertMode != ScheduleAlertMode.ALARM) return null
        val canonicalDepartureAt = recommendedDepartureAt.truncatedTo(ChronoUnit.MILLIS)
        if (decision == DepartureReminderDecision.DEPART_NOW) {
            return AlarmReminderOccurrence(
                occurrenceId = "M0",
                triggerAt = canonicalDepartureAt,
                minutesBeforeDeparture = 0,
            )
        }
        if (decision != DepartureReminderDecision.ADVANCE_NOTICE) return null
        val canonicalBoundary = reminderBoundaryAt?.truncatedTo(ChronoUnit.MILLIS) ?: return null
        val minutesBeforeDeparture = Duration.between(
            canonicalBoundary,
            canonicalDepartureAt,
        ).toMinutes().toInt()
        val occurrenceId = when (minutesBeforeDeparture) {
            15 -> "M15"
            10 -> "M10"
            5 -> "M5"
            else -> return null
        }
        return AlarmReminderOccurrence(
            occurrenceId = occurrenceId,
            triggerAt = canonicalBoundary,
            minutesBeforeDeparture = minutesBeforeDeparture,
        )
    }

    /**
     * 토큰 미등록과 공급자 발송 실패를 구분해 운영 로그와 작업 실패 사유에 남긴다.
     */
    private fun scheduleRetryAfterPushFailure(
        job: SchedulePushJob,
        now: Instant,
        requestedCount: Int,
        failedCount: Int,
        beforeTransition: (Instant?) -> Unit = {},
    ): EtaJobMetricOutcome {
        val reason = if (requestedCount == 0) {
            "등록된 푸시 토큰이 없습니다."
        } else {
            "푸시 공급자 발송에 실패했습니다. requested=$requestedCount, failed=$failedCount"
        }
        return retryOrFail(job, now, reason, beforeTransition)
    }

    /**
     * 일시 장애는 제한 횟수만 재시도하고, 일정 시작 이후로 재시도가 밀리면 명시적으로 실패시킨다.
     * 다음 재시도 시각도 발송 가능 시간의 끝을 넘지 않도록 제한한다.
     */
    private fun retryOrFail(
        job: SchedulePushJob,
        now: Instant,
        reason: String,
        beforeTransition: (Instant?) -> Unit = {},
    ): EtaJobMetricOutcome {
        val deliveryDeadline = job.scheduleAt.plus(deliveryGraceMinutes, ChronoUnit.MINUTES)
        val nextRetryAt = now.plus(retryDelayMinutes, ChronoUnit.MINUTES)
        val retryLimitReached = job.retryCount + 1 >= maxRetryCount
        val noRetryWindowLeft = nextRetryAt.isAfter(deliveryDeadline)

        if (retryLimitReached || noRetryWindowLeft) {
            beforeTransition(null)
            job.fail(reason)
            return EtaJobMetricOutcome.TERMINAL_FAILURE
        }

        val retryDueAt = minOf(nextRetryAt, deliveryDeadline)
        // ETA 평가와 retry 상태 전이를 같은 detached aggregate 안에서 순서대로 적용한다.
        // 평가 검증이 실패하면 retryCount를 먼저 증가시키지 않아 outer catch가 중복 전이하지 않는다.
        beforeTransition(retryDueAt)
        job.retryLater(
            reason = reason,
            nextCheckAt = retryDueAt,
        )
        return EtaJobMetricOutcome.RETRY_SCHEDULED
    }


    /**
     * 대중교통 provider의 시간 근거가 실제 시간표인지 완전히 구분되기 전에는
     * 재조회 결과만으로 이미 예약된 출발 알림을 뒤로 미루지 않는다.
     *
     * 더 이른 후보는 지각 위험을 줄이므로 그대로 적용한다.
     */
    private fun preventUnsafeTransitPostponement(
        travelMode: ScheduleTravelMode,
        previousRecommendedDepartureAt: Instant?,
        candidateRecommendedDepartureAt: Instant,
    ): Instant {
        if (
            travelMode != ScheduleTravelMode.TRANSIT ||
            previousRecommendedDepartureAt == null
        ) {
            return candidateRecommendedDepartureAt
        }

        return minOf(
            previousRecommendedDepartureAt,
            candidateRecommendedDepartureAt,
        )
    }
}

private const val MIN_ETA_EVENT_TTL_SECONDS = 30L
private const val MAX_ETA_EVENT_TTL_SECONDS = 300L
private const val DEFAULT_ETA_EVENT_TTL_SECONDS = 120L
private const val NATIVE_ALARM_REPLACEMENT_LATE_GRACE_SECONDS = 120L
private val FROZEN_ETA_EVENT_TYPES = setOf(
    "SCHEDULE_TRAFFIC",
    "SCHEDULE_DEPARTURE_REMINDER",
)
private val BOOLEAN_PAYLOAD_VALUES = setOf("true", "false")
private val ETA_PUSH_SEMANTIC_KEYS = setOf(
    "type",
    "travelMinutes",
    "recommendedDepartureAt",
    "predictedArrivalAt",
    "onTimeArrivalPossible",
    "etaSource",
    "etaStale",
    "etaFailureReason",
    "transitTransferFeasibility",
    "etaRouteProvenance",
    "etaTimingBasis",
    "firstBoardingWaitMinutes",
    "routeChanged",
    "effectiveRouteIdentity",
    "departNow",
    "departureReminderDecision",
    "reminderBoundaryAt",
    "trafficChangeMinutes",
    "departureAdvanceMinutes",
)

private data class PushRouteSource(
    val travelMinutes: Int?,
    val travelMode: ScheduleTravelMode?,
    val originLat: Double?,
    val originLng: Double?,
    val destinationLat: Double?,
    val destinationLng: Double?,
    val routeJson: String?,
    val destinationName: String?,
    val alertMode: ScheduleAlertMode,
)

private data class SchedulePushOutcome(
    val notificationHandled: Boolean = false,
    val nativeAlarmCovered: Boolean = false,
    val confirmedSuccess: Boolean = false,
    val uncertain: Boolean = false,
    val confirmedAt: Instant? = null,
    val decision: DepartureReminderDecision = DepartureReminderDecision.NONE,
    val notifiedDepartureAt: Instant = Instant.EPOCH,
    val reminderBoundaryAt: Instant? = null,
    val catchUpRequired: Boolean = false,
)

private data class AlarmReminderOccurrence(
    val occurrenceId: String,
    val triggerAt: Instant,
    val minutesBeforeDeparture: Int,
)
