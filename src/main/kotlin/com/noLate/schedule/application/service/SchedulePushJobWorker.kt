package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.service.policy.DepartureReminderPolicy
import com.noLate.schedule.application.service.policy.DepartureReminderDecision
import com.noLate.schedule.application.service.policy.PeriodicPushPolicy
import com.noLate.schedule.application.service.policy.TrafficChangePolicy
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.Duration
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
    @Value("\${schedule.push.batch-size:50}") private val batchSize: Int,
    @Value("\${schedule.push.retry-delay-minutes:5}") private val retryDelayMinutes: Long,
    @Value("\${schedule.push.max-retry-count:3}") private val maxRetryCount: Int,
    @Value("\${schedule.push.delivery-grace-minutes:10}") private val deliveryGraceMinutes: Long = 10,
    @Value("\${schedule.push.departure-alert-lead-minutes:15}") private val departureAlertLeadMinutes: Int,
    @Value("\${schedule.push.departure-reminder-interval-minutes:5}") private val departureReminderIntervalMinutes: Int,
    @Value("\${schedule.push.departure-snooze-minutes:5}") private val departureSnoozeMinutes: Int = 5,
    @Value("\${schedule.push.processing-timeout-minutes:10}") private val processingTimeoutMinutes: Long,
    private val travelPlanRepository: ScheduleTravelPlanRepository? = null,
    private val scheduleAccessPolicy: ScheduleAccessPolicy? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val workerId = "schedule-push-${UUID.randomUUID()}"

    @Scheduled(fixedDelayString = "\${schedule.push.fixed-delay-ms:60000}")
    fun runDueJobs() {
        runDueJobs(Instant.now())
    }

    fun runDueJobs(now: Instant): Int {
        val recoveredCount = pushJobCoordinator.recoverStaleProcessingJobs(
            now = now,
            processingTimeoutMinutes = processingTimeoutMinutes,
            deliveryGraceMinutes = deliveryGraceMinutes,
        )
        if (recoveredCount > 0) {
            log.warn(
                "Recovered stale schedule push jobs. count={}, timeoutMinutes={}, checkedAt={}",
                recoveredCount,
                processingTimeoutMinutes,
                now,
            )
        }

        val dueJobs = pushJobCoordinator.claimDueJobs(now, workerId, batchSize)

        if (dueJobs.isNotEmpty()) {
            log.info(
                "Claimed schedule push jobs. count={}, workerId={}, checkedAt={}",
                dueJobs.size,
                workerId,
                now,
            )
        }
        dueJobs.forEach { job ->
            pushJobCoordinator.execute { process(job, now) }
        }
        return dueJobs.size
    }

    private fun process(job: SchedulePushJob, now: Instant) {
        try {
            // 일정이 시작된 뒤에는 "출발" 알림의 의미가 사라지므로 남은 후속 푸시를 종료한다.
            if (job.isPastDeliveryWindow(now, deliveryGraceMinutes)) {
                job.complete()
                pushJobCoordinator.persist(job, workerId)
                return
            }

            val schedule = scheduleRepository.findScheduleDetail(job.scheduleId, job.memberId)
                ?: run {
                    job.cancel()
                    pushJobCoordinator.persist(job, workerId)
                    return
                }
            // 공유 범위 축소와 이미 선점된 worker가 경합해도 발송 직전 유효 이동 권한을 다시
            // 확인한다. 일정 조회 권한만 남은 사용자는 기존 개인 계획이 있어도 알림을 받지 않는다.
            if (
                job.memberId != schedule.memberId &&
                scheduleAccessPolicy?.resolve(job.memberId, schedule)?.travelEnabled == false
            ) {
                job.cancel()
                pushJobCoordinator.persist(job, workerId)
                return
            }
            val route = resolveRouteSource(schedule, job.memberId)
                ?: run {
                    job.cancel()
                    pushJobCoordinator.persist(job, workerId)
                    return
                }

            val fallbackMinutes = requireNotNull(route.travelMinutes) {
                "교통 조회 fallback 이동 시간이 없습니다."
            }
            val selectedRouteTravelMinutes = parseSelectedRouteTravelMinutes(route.routeJson)
            val request = TrafficRequest(
                originLat = requireNotNull(route.originLat) { "출발지 위도가 없습니다." },
                originLng = requireNotNull(route.originLng) { "출발지 경도가 없습니다." },
                destinationLat = requireNotNull(route.destinationLat) { "도착지 위도가 없습니다." },
                destinationLng = requireNotNull(route.destinationLng) { "도착지 경도가 없습니다." },
                travelMode = requireNotNull(route.travelMode) { "이동 수단이 없습니다." },
                fallbackTravelMinutes = fallbackMinutes,
                selectedRouteJson = route.routeJson,
                selectedRouteTravelMinutes = selectedRouteTravelMinutes,
            )
            val travelMinutes = trafficClient.getTravelMinutes(request)
            val recommendedDepartureAt = schedule.startAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES)
            val reminderDecision = departureReminderPolicy.decide(
                now = now,
                recommendedDepartureAt = recommendedDepartureAt,
                scheduleAt = schedule.startAt,
                lastNotifiedDepartureAt = job.lastNotifiedDepartureAt,
                lastReminderBoundaryAt = job.lastReminderBoundaryAt,
                departureNoticeSentAt = job.departureNoticeSentAt,
                lastDepartureReminderBoundaryAt = job.lastDepartureReminderBoundaryAt,
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
                previousTravelMinutes = job.lastTravelMinutes,
                currentTravelMinutes = travelMinutes,
            )
            val showDepartureActions = reminderDecision.departNowAction ||
                (job.departureNoticeSentAt != null && !now.isBefore(recommendedDepartureAt))
            // 이동 시간이 늘어난 경우만 즉시 보낸다. 줄어든 경우는 다음 경계 시각만 재계산해 불필요한 알림을 줄인다.
            val shouldPush = reminderDecision != DepartureReminderDecision.NONE ||
                trafficChangeMinutes > 0

            val pushOutcome = if (shouldPush) {
                val message = trafficChangePolicy.createMessage(
                    scheduleTitle = schedule.title,
                    previousTravelMinutes = job.lastTravelMinutes,
                    currentTravelMinutes = travelMinutes,
                    recommendedDepartureAt = recommendedDepartureAt,
                    decision = reminderDecision,
                    alertLeadMinutes = departureAlertLeadMinutes,
                    reminderMinutesBeforeDeparture = reminderMinutesBeforeDeparture(
                        reminderBoundaryAt = reminderBoundaryAt,
                        recommendedDepartureAt = recommendedDepartureAt,
                    ),
                )
                val sendResult = notificationUseCase.sendToMember(
                    memberId = job.memberId,
                    title = message.title,
                    body = message.body,
                    data = mapOf(
                        "type" to pushPayloadType(reminderDecision, showDepartureActions),
                        "scheduleId" to job.scheduleId.toString(),
                        "travelMinutes" to travelMinutes.toString(),
                        "recommendedDepartureAt" to recommendedDepartureAt.toString(),
                        "departNow" to showDepartureActions.toString(),
                        "departureReminderDecision" to reminderDecision.name,
                        "snoozeMinutes" to departureSnoozeMinutes.toString(),
                        "trafficChangeMinutes" to (message.trafficChangeMinutes?.toString() ?: "0"),
                    ),
                    // push 실패 재시도 중에는 checkCount가 증가하지 않는다. 같은 회차는 한 알림으로
                    // 합치되, 다음 ETA 확인 회차는 별도 알림이 되도록 job과 checkCount를 함께 사용한다.
                    inboxDeduplicationKey = schedulePushInboxDeduplicationKey(job),
                )
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
                        job.recordConfirmedPush(now)
                    }
                    scheduleRetryAfterPushFailure(
                        job = job,
                        now = now,
                        requestedCount = sendResult.requestedCount,
                        failedCount = sendResult.retryableFailedCount,
                    )
                    pushJobCoordinator.persist(job, workerId)
                    return
                }
                if (sendResult.durablyHandledCount == 0) {
                    // FCM/APNs가 한 기기에도 전달하지 못했다면 성공 이력을 기록하지 않는다.
                    // 특히 DEPART_NOW를 완료 처리하면 다시 보낼 방법이 없어 반드시 재시도해야 한다.
                    scheduleRetryAfterPushFailure(
                        job = job,
                        now = now,
                        requestedCount = sendResult.requestedCount,
                        failedCount = sendResult.failedCount,
                    )
                    pushJobCoordinator.persist(job, workerId)
                    return
                }
                SchedulePushOutcome(
                    notificationHandled = true,
                    confirmedSuccess = sendResult.confirmedSuccessCount > 0,
                )
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

            val nextCheckAt = nextCheckAt(
                job = job,
                now = now,
                recommendedDepartureAt = recommendedDepartureAt,
                scheduleAt = schedule.startAt,
                effectiveDepartureNoticeSentAt = if (
                    pushOutcome.notificationHandled &&
                    reminderDecision == DepartureReminderDecision.DEPART_NOW &&
                    job.departureNoticeSentAt == null
                ) {
                    now
                } else {
                    job.departureNoticeSentAt
                },
            )
            job.finishCheck(
                travelMinutes = travelMinutes,
                recommendedDepartureAt = recommendedDepartureAt,
                pushSent = pushOutcome.notificationHandled,
                pushConfirmed = pushOutcome.confirmedSuccess,
                notifiedDepartureAt = recommendedDepartureAt.takeIf {
                    pushOutcome.notificationHandled && reminderDecision != DepartureReminderDecision.NONE
                },
                reminderBoundaryAt = reminderBoundaryAt,
                departureReminderStage = reminderDecision.stage.takeIf {
                    pushOutcome.notificationHandled
                },
                departureReminderBoundaryAt = departureReminderBoundaryAt(
                    decision = reminderDecision,
                    job = job,
                    recommendedDepartureAt = recommendedDepartureAt,
                ).takeIf {
                    pushOutcome.notificationHandled && reminderDecision.stage != null
                },
                clearSnooze =
                    pushOutcome.notificationHandled && reminderDecision == DepartureReminderDecision.SNOOZE,
                nextCheckAt = nextCheckAt,
                completeAfterCheck = nextCheckAt == null,
                now = now,
            )
            pushJobCoordinator.persist(job, workerId)
        } catch (exception: Exception) {
            log.warn(
                "Schedule push job failed. jobId={}, scheduleId={}, workerId={}, errorCode={}",
                job.id,
                job.scheduleId,
                workerId,
                exception.javaClass.simpleName,
            )
            retryOrFail(
                job = job,
                now = now,
                reason = exception.message?.take(500) ?: exception.javaClass.simpleName,
            )
            runCatching { pushJobCoordinator.persist(job, workerId) }
                .onFailure {
                    log.error(
                        "Schedule push failure transition persistence failed. jobId={}, scheduleId={}, workerId={}, errorCode={}",
                        job.id,
                        job.scheduleId,
                        workerId,
                        it.javaClass.simpleName,
                    )
                }
        }
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
            lastNotifiedDepartureAt = job.lastNotifiedDepartureAt,
            departureNoticeSentAt = effectiveDepartureNoticeSentAt,
            lastDepartureReminderBoundaryAt = job.lastDepartureReminderBoundaryAt,
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
                requireNotNull(job.departureNoticeSentAt).plus(3, ChronoUnit.MINUTES)
            DepartureReminderDecision.AFTER_DEPARTURE_7 ->
                requireNotNull(job.departureNoticeSentAt).plus(7, ChronoUnit.MINUTES)
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
        )
    }

    private fun parseSelectedRouteTravelMinutes(routeJson: String?): Int? {
        if (routeJson.isNullOrBlank()) return null

        // FE 경로 후보 payload가 버전별로 다른 필드명을 썼던 이력을 흡수한다.
        return runCatching {
            val root = objectMapper.readTree(routeJson)
            sequenceOf(
                root.path("minutes"),
                root.path("travelMinutes"),
                root.path("durationMinutes"),
            )
                .firstOrNull { it.isNumber }
                ?.asDouble()
                ?.let { ceilToPositiveMinutes(it) }
        }.getOrNull()
    }

    private fun ceilToPositiveMinutes(value: Double): Int? {
        if (!value.isFinite() || value <= 0) return null
        return kotlin.math.ceil(value).toInt().coerceAtLeast(1)
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
    ) {
        val reason = if (requestedCount == 0) {
            "등록된 푸시 토큰이 없습니다."
        } else {
            "푸시 공급자 발송에 실패했습니다. requested=$requestedCount, failed=$failedCount"
        }
        retryOrFail(job, now, reason)
    }

    /**
     * 일시 장애는 제한 횟수만 재시도하고, 일정 시작 이후로 재시도가 밀리면 명시적으로 실패시킨다.
     * 다음 재시도 시각도 발송 가능 시간의 끝을 넘지 않도록 제한한다.
     */
    private fun retryOrFail(job: SchedulePushJob, now: Instant, reason: String) {
        val deliveryDeadline = job.scheduleAt.plus(deliveryGraceMinutes, ChronoUnit.MINUTES)
        val nextRetryAt = now.plus(retryDelayMinutes, ChronoUnit.MINUTES)
        val retryLimitReached = job.retryCount + 1 >= maxRetryCount
        val noRetryWindowLeft = nextRetryAt.isAfter(deliveryDeadline)

        if (retryLimitReached || noRetryWindowLeft) {
            job.fail(reason)
            return
        }

        job.retryLater(
            reason = reason,
            nextCheckAt = minOf(nextRetryAt, deliveryDeadline),
        )
    }
}

private data class PushRouteSource(
    val travelMinutes: Int?,
    val travelMode: ScheduleTravelMode?,
    val originLat: Double?,
    val originLng: Double?,
    val destinationLat: Double?,
    val destinationLng: Double?,
    val routeJson: String?,
)

private data class SchedulePushOutcome(
    val notificationHandled: Boolean = false,
    val confirmedSuccess: Boolean = false,
)
