package com.noLate.schedule.application.service

import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.service.policy.DepartureReminderPolicy
import com.noLate.schedule.application.service.policy.DepartureReminderDecision
import com.noLate.schedule.application.service.policy.PeriodicPushPolicy
import com.noLate.schedule.application.service.policy.TrafficChangePolicy
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Component
class SchedulePushJobWorker(
    private val pushJobRepository: SchedulePushJobRepository,
    private val scheduleRepository: ScheduleRepository,
    private val trafficClient: TrafficClient,
    private val notificationUseCase: NotificationUseCase,
    private val periodicPushPolicy: PeriodicPushPolicy,
    private val departureReminderPolicy: DepartureReminderPolicy,
    private val trafficChangePolicy: TrafficChangePolicy,
    @Value("\${schedule.push.retry-delay-minutes:5}") private val retryDelayMinutes: Long,
    @Value("\${schedule.push.departure-alert-lead-minutes:15}") private val departureAlertLeadMinutes: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val workerId = "schedule-push-${UUID.randomUUID()}"

    @Scheduled(fixedDelayString = "\${schedule.push.fixed-delay-ms:60000}")
    @Transactional
    fun runDueJobs() {
        runDueJobs(Instant.now())
    }

    fun runDueJobs(now: Instant): Int {
        val dueJobs = pushJobRepository
            .findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                now,
            )

        if (dueJobs.isNotEmpty()) {
            log.info("Detected schedule push jobs. count={}, checkedAt={}", dueJobs.size, now)
        }
        dueJobs.forEach { process(it, now) }
        return dueJobs.size
    }

    private fun process(job: SchedulePushJob, now: Instant) {
        job.startProcessing(workerId)

        try {
            if (job.isExpired(now)) {
                job.complete()
                return
            }

            val schedule = scheduleRepository.findScheduleDetail(job.scheduleId, job.memberId)
                ?: run {
                    job.cancel()
                    return
                }
            val route = schedule.route
                ?.takeIf { it.notificationEnabled }
                ?: run {
                    job.cancel()
                    return
                }

            val fallbackMinutes = requireNotNull(route.travelMinutes) {
                "교통 조회 fallback 이동 시간이 없습니다."
            }
            val request = TrafficRequest(
                originLat = requireNotNull(route.originLat) { "출발지 위도가 없습니다." },
                originLng = requireNotNull(route.originLng) { "출발지 경도가 없습니다." },
                destinationLat = requireNotNull(route.destinationLat) { "도착지 위도가 없습니다." },
                destinationLng = requireNotNull(route.destinationLng) { "도착지 경도가 없습니다." },
                travelMode = requireNotNull(route.travelMode) { "이동 수단이 없습니다." },
                fallbackTravelMinutes = fallbackMinutes,
            )
            val travelMinutes = trafficClient.getTravelMinutes(request)
            val recommendedDepartureAt = schedule.startAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES)
            val reminderDecision = departureReminderPolicy.decide(
                now = now,
                recommendedDepartureAt = recommendedDepartureAt,
                lastNotifiedDepartureAt = job.lastNotifiedDepartureAt,
                alertLeadMinutes = departureAlertLeadMinutes,
            )
            val shouldPush = reminderDecision != DepartureReminderDecision.NONE

            val pushSent = if (shouldPush) {
                val message = trafficChangePolicy.createMessage(
                    scheduleTitle = schedule.title,
                    previousTravelMinutes = job.lastTravelMinutes,
                    currentTravelMinutes = travelMinutes,
                    recommendedDepartureAt = recommendedDepartureAt,
                    decision = reminderDecision,
                    alertLeadMinutes = departureAlertLeadMinutes,
                )
                val sendResult = notificationUseCase.sendToMember(
                    memberId = job.memberId,
                    title = message.title,
                    body = message.body,
                    data = mapOf(
                        "type" to "SCHEDULE_TRAFFIC",
                        "scheduleId" to job.scheduleId.toString(),
                        "travelMinutes" to travelMinutes.toString(),
                        "recommendedDepartureAt" to recommendedDepartureAt.toString(),
                        "departNow" to
                            (reminderDecision == DepartureReminderDecision.DEPART_NOW).toString(),
                        "trafficChangeMinutes" to (message.trafficChangeMinutes?.toString() ?: "0"),
                    ),
                )
                log.info(
                    "Schedule push sent. jobId={}, scheduleId={}, decision={}, travelMinutes={}, requested={}, sent={}, failed={}",
                    job.id,
                    job.scheduleId,
                    reminderDecision,
                    travelMinutes,
                    sendResult.requestedCount,
                    sendResult.sentCount,
                    sendResult.failedCount,
                )
                sendResult.sentCount > 0
            } else {
                log.info(
                    "Schedule ETA refreshed without push. jobId={}, scheduleId={}, travelMinutes={}, recommendedDepartureAt={}",
                    job.id,
                    job.scheduleId,
                    travelMinutes,
                    recommendedDepartureAt,
                )
                false
            }

            val departNow = reminderDecision == DepartureReminderDecision.DEPART_NOW
            job.finishCheck(
                travelMinutes = travelMinutes,
                recommendedDepartureAt = recommendedDepartureAt,
                pushSent = pushSent,
                notifiedDepartureAt = recommendedDepartureAt.takeIf { pushSent },
                nextCheckAt = if (departNow) {
                    null
                } else {
                    periodicPushPolicy.nextCheckAt(
                        now = now,
                        recommendedDepartureAt = recommendedDepartureAt,
                        intervalMinutes = job.intervalMinutes,
                        alertLeadMinutes = departureAlertLeadMinutes,
                    )
                },
                completeAfterCheck = departNow,
                now = now,
            )
        } catch (exception: Exception) {
            log.warn("Schedule push job failed. jobId={}", job.id, exception)
            job.retryLater(
                reason = exception.message?.take(500) ?: exception.javaClass.simpleName,
                nextCheckAt = now.plus(retryDelayMinutes, ChronoUnit.MINUTES),
            )
        }
    }
}
