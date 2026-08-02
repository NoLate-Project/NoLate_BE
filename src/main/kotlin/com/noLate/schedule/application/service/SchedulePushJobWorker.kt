package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.observability.EtaJobMetricOutcome
import com.noLate.global.observability.EtaWorkerMetricEvent
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.global.observability.recordSafely
import com.noLate.notification.application.service.PushDispatchFence
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.schedule.application.EtaTravelTimePolicy
import com.noLate.schedule.application.SelectedRouteMetadata
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.application.sanitizeTrafficFailureReason
import com.noLate.schedule.application.service.policy.DepartureReminderPolicy
import com.noLate.schedule.application.service.policy.DepartureReminderDecision
import com.noLate.schedule.application.service.policy.PeriodicPushPolicy
import com.noLate.schedule.application.service.policy.TrafficChangePolicy
import com.noLate.schedule.domain.ScheduleEtaRouteFingerprint
import com.noLate.schedule.domain.ScheduleAlertMode
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
        try {
            // 일정이 시작된 뒤에는 "출발" 알림의 의미가 사라지므로 남은 후속 푸시를 종료한다.
            if (job.isPastDeliveryWindow(now, deliveryGraceMinutes)) {
                job.complete()
                persistMeasuredTransition(
                    job = job,
                    outcome = EtaJobMetricOutcome.PROCESSED,
                    alarmIntent = job.cancelAlarmIntent(),
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
                    )
                    return
                }
            // 공유 범위 축소와 이미 선점된 worker가 경합해도 발송 직전 유효 이동 권한을 다시
            // 확인한다. 일정 조회 권한만 남은 사용자는 기존 개인 계획이 있어도 알림을 받지 않는다.
            if (
                job.memberId != schedule.memberId &&
                scheduleAccessPolicy?.resolve(job.memberId, schedule)?.travelEnabled == false
            ) {
                job.cancel()
                persistMeasuredTransition(
                    job = job,
                    outcome = EtaJobMetricOutcome.PROCESSED,
                    alarmIntent = job.cancelAlarmIntent(),
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
                    )
                    return
                }
            // A previous check that intentionally scheduled exactly +1 second is a durable
            // semantic catch-up marker. It forces a new cN payload even when the latest ETA change
            // would otherwise fall outside a normal reminder/traffic-increase boundary.
            val semanticCatchUpDue = job.lastCheckedAt
                ?.plusSeconds(1) == job.nextCheckAt

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
            val trafficResult = trafficClient.getTravelMinutes(request)
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
            val recommendedDepartureAt = trafficResult.actionableRecommendedDepartureAt()
                ?.takeUnless { it.isAfter(schedule.startAt) }
                // Provider timeout/degraded 결과는 직전 fresh 알람을 늦추거나 앞당기지 않는다.
                // 첫 조회 실패에서도 사용자가 저장한 기준 출발시각을 그대로 유지한다.
                ?: (job.lastRecommendedDepartureAt ?: job.departureAt)
                    .takeIf { !trafficResult.accepted }
                    ?.takeUnless { it.isAfter(schedule.startAt) }
                ?: schedule.startAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES)
            val alarmIntent = departureAlarmSyncService?.let {
                SchedulePushAlarmIntent.AutomaticEta(
                    memberId = job.memberId,
                    scheduleId = job.scheduleId,
                    notificationEnabled = transferFailureReason == null,
                    alertMode = route.alertMode,
                    recommendedDepartureAt = recommendedDepartureAt,
                    scheduleTitle = schedule.title,
                    resumeCanceledAfterTransitTransferFailure =
                        etaFailureReason == null && previousTransferFailureReason != null,
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
            val eventExpiresAt = persistedEventExpiresAt
                ?: dispatchCheckedAt.plusSeconds(etaEventTtlSeconds)
            val shouldPush =
                !persistedEventExpired && (
                    persistedEvent != null ||
                    reminderDecision != DepartureReminderDecision.NONE ||
                    trafficChangeMinutes > 0 ||
                    departureAdvanceMinutes > 0 ||
                    semanticCatchUpDue ||
                    transferFailureTransition
                    )

            val pushOutcome = if (persistedEventExpired) {
                // 같은 check의 immutable payload가 안전 TTL을 넘겼다. 외부 provider 호출 없이
                // 현재 check를 닫아 cN outbox를 stale로 만들고, 다음 tick에서 새 ETA/cN+1을 만든다.
                SchedulePushOutcome(catchUpRequired = true)
            } else if (shouldPush) {
                val onTimeArrivalPossible = predictedArrivalAt?.let {
                    !it.isAfter(schedule.startAt)
                }
                val liveMessage = trafficChangePolicy.createMessage(
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
                )
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
                val sendResult = if (dispatchFence == null) {
                    notificationUseCase.sendToMember(
                        memberId = job.memberId,
                        title = persistedEvent?.title ?: liveMessage.title,
                        body = persistedEvent?.body ?: liveMessage.body,
                        data = persistedEvent?.data ?: liveData,
                        inboxDeduplicationKey = deduplicationKey,
                    )
                } else {
                    notificationUseCase.sendToMemberFenced(
                        memberId = job.memberId,
                        title = persistedEvent?.title ?: liveMessage.title,
                        body = persistedEvent?.body ?: liveMessage.body,
                        data = persistedEvent?.data ?: liveData,
                        // push 실패 재시도 중에는 checkCount가 증가하지 않는다. 같은 회차는 한 알림으로
                        // 합치되, 다음 ETA 확인 회차는 별도 알림이 되도록 job과 checkCount를 함께 사용한다.
                        inboxDeduplicationKey = deduplicationKey,
                        dispatchFence = dispatchFence,
                    )
                }
                val eventSnapshot = sendResult.eventSnapshot ?: persistedEvent
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
                        "Schedule push handled. jobId={}, scheduleId={}, decision={}, travelMinutes={}, requested={}, attempted={}, sent={}, alreadyDelivered={}, ambiguous={}, deduplicated={}, failed={}, retryableFailed={}",
                        job.id,
                        job.scheduleId,
                        reminderDecision,
                        travelMinutes,
                        sendResult.requestedCount,
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
                        job.recordRetryEtaEvaluation(
                            trafficResult = trafficResult,
                            travelMinutes = travelMinutes,
                            recommendedDepartureAt = recommendedDepartureAt,
                            predictedArrivalAt = predictedArrivalAt,
                            etaFailureReason = etaFailureReason,
                            travelMode = route.travelMode,
                            routeFingerprint = routeFingerprint,
                            now = now,
                        )
                        val transition = scheduleRetryAfterPushFailure(
                            job = job,
                            now = now,
                            requestedCount = sendResult.requestedCount,
                            failedCount = sendResult.retryableFailedCount,
                        )
                        persistMeasuredTransition(
                            job = job,
                            outcome = transition,
                            uncertainDelivery = sendResult.ambiguousCount > 0,
                            alarmIntent = alarmIntent,
                        )
                        return
                    }
                    if (sendResult.durablyHandledCount == 0) {
                        // FCM/APNs가 한 기기에도 전달하지 못했다면 성공 이력을 기록하지 않는다.
                        // 특히 DEPART_NOW를 완료 처리하면 다시 보낼 방법이 없어 반드시 재시도해야 한다.
                        job.recordRetryEtaEvaluation(
                            trafficResult = trafficResult,
                            travelMinutes = travelMinutes,
                            recommendedDepartureAt = recommendedDepartureAt,
                            predictedArrivalAt = predictedArrivalAt,
                            etaFailureReason = etaFailureReason,
                            travelMode = route.travelMode,
                            routeFingerprint = routeFingerprint,
                            now = now,
                        )
                        val transition = scheduleRetryAfterPushFailure(
                            job = job,
                            now = now,
                            requestedCount = sendResult.requestedCount,
                            failedCount = sendResult.failedCount,
                        )
                        persistMeasuredTransition(
                            job = job,
                            outcome = transition,
                            alarmIntent = alarmIntent,
                        )
                        return
                    }
                    SchedulePushOutcome(
                        notificationHandled = true,
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
            job.finishCheck(
                travelMinutes = travelMinutes,
                recommendedDepartureAt = recommendedDepartureAt,
                pushSent = pushOutcome.confirmedSuccess,
                pushConfirmed = pushOutcome.confirmedSuccess,
                pushConfirmedAt = pushOutcome.confirmedAt,
                pushUncertain = pushOutcome.uncertain,
                notifiedDepartureAt = pushOutcome.notifiedDepartureAt.takeIf {
                    pushOutcome.notificationHandled &&
                        pushOutcome.decision != DepartureReminderDecision.NONE
                },
                reminderBoundaryAt = pushOutcome.reminderBoundaryAt,
                departureReminderStage = pushOutcome.decision.stage.takeIf {
                    pushOutcome.notificationHandled
                },
                departureReminderBoundaryAt = departureReminderBoundaryAt(
                    decision = pushOutcome.decision,
                    job = job,
                    recommendedDepartureAt = pushOutcome.notifiedDepartureAt,
                ).takeIf {
                    pushOutcome.notificationHandled && pushOutcome.decision.stage != null
                },
                clearSnooze =
                    pushOutcome.notificationHandled &&
                        pushOutcome.decision == DepartureReminderDecision.SNOOZE,
                nextCheckAt = nextCheckAt,
                completeAfterCheck = nextCheckAt == null,
                etaSource = trafficResult.source,
                liveFetchedAt = trafficResult.fetchedAt,
                etaStale = trafficResult.stale,
                etaFailureReason = etaFailureReason,
                predictedArrivalAt = predictedArrivalAt,
                etaTravelMode = route.travelMode,
                etaRouteFingerprint = routeFingerprint,
                liveComparatorMaxAgeMinutes = liveComparatorMaxAgeMinutes,
                now = transitionAt,
            )
            persistMeasuredTransition(
                job = job,
                outcome = EtaJobMetricOutcome.PROCESSED,
                uncertainDelivery = pushOutcome.uncertain,
                alarmIntent = alarmIntent,
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
            persistMeasuredTransition(job, transition)
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
    ) {
        val persisted = runCatching {
            pushJobCoordinator.persist(job, workerId, alarmIntent)
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

        val trafficCheckAt = periodicPushPolicy.nextCheckAt(
            now = now,
            recommendedDepartureAt = recommendedDepartureAt,
            intervalMinutes = job.intervalMinutes,
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

        return listOfNotNull(trafficCheckAt, reminderCheckAt, scheduleAt)
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

    /**
     * 토큰 미등록과 공급자 발송 실패를 구분해 운영 로그와 작업 실패 사유에 남긴다.
     */
    private fun scheduleRetryAfterPushFailure(
        job: SchedulePushJob,
        now: Instant,
        requestedCount: Int,
        failedCount: Int,
    ): EtaJobMetricOutcome {
        val reason = if (requestedCount == 0) {
            "등록된 푸시 토큰이 없습니다."
        } else {
            "푸시 공급자 발송에 실패했습니다. requested=$requestedCount, failed=$failedCount"
        }
        return retryOrFail(job, now, reason)
    }

    /**
     * 일시 장애는 제한 횟수만 재시도하고, 일정 시작 이후로 재시도가 밀리면 명시적으로 실패시킨다.
     * 다음 재시도 시각도 발송 가능 시간의 끝을 넘지 않도록 제한한다.
     */
    private fun retryOrFail(
        job: SchedulePushJob,
        now: Instant,
        reason: String,
    ): EtaJobMetricOutcome {
        val deliveryDeadline = job.scheduleAt.plus(deliveryGraceMinutes, ChronoUnit.MINUTES)
        val nextRetryAt = now.plus(retryDelayMinutes, ChronoUnit.MINUTES)
        val retryLimitReached = job.retryCount + 1 >= maxRetryCount
        val noRetryWindowLeft = nextRetryAt.isAfter(deliveryDeadline)

        if (retryLimitReached || noRetryWindowLeft) {
            job.fail(reason)
            return EtaJobMetricOutcome.TERMINAL_FAILURE
        }

        job.retryLater(
            reason = reason,
            nextCheckAt = minOf(nextRetryAt, deliveryDeadline),
        )
        return EtaJobMetricOutcome.RETRY_SCHEDULED
    }
}

private const val MIN_ETA_EVENT_TTL_SECONDS = 30L
private const val MAX_ETA_EVENT_TTL_SECONDS = 300L
private const val DEFAULT_ETA_EVENT_TTL_SECONDS = 120L
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
    val alertMode: ScheduleAlertMode,
)

private data class SchedulePushOutcome(
    val notificationHandled: Boolean = false,
    val confirmedSuccess: Boolean = false,
    val uncertain: Boolean = false,
    val confirmedAt: Instant? = null,
    val decision: DepartureReminderDecision = DepartureReminderDecision.NONE,
    val notifiedDepartureAt: Instant = Instant.EPOCH,
    val reminderBoundaryAt: Instant? = null,
    val catchUpRequired: Boolean = false,
)
