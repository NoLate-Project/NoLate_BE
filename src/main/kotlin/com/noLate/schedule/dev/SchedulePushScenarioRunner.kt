package com.noLate.schedule.dev

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.application.service.SchedulePushJobWorker
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
@ConditionalOnProperty(
    prefix = "notification.push-schedule-scenario",
    name = ["enabled"],
    havingValue = "true",
)
class SchedulePushScenarioRunner(
    private val scheduleRepository: ScheduleRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val objectMapper: ObjectMapper,
    private val worker: SchedulePushJobWorker,
    @Value("\${schedule.push.departure-alert-lead-minutes:15}")
    private val departureAlertLeadMinutes: Int,
) {

    @Transactional
    fun run(memberId: Long, request: SchedulePushScenarioRunRequest): SchedulePushScenarioRunResponse {
        val scenarios = request.scenarios.ifEmpty {
            listOf(
                SchedulePushScenario.TRAFFIC_CHANGED,
                SchedulePushScenario.DEPARTURE_SOON,
                SchedulePushScenario.DEPART_NOW,
            )
        }

        val results = scenarios.map { scenario ->
            runScenario(
                memberId = memberId,
                request = request,
                scenario = scenario,
            )
        }

        return SchedulePushScenarioRunResponse(
            memberId = memberId,
            scheduleId = request.scheduleId,
            results = results,
        )
    }

    private fun runScenario(
        memberId: Long,
        request: SchedulePushScenarioRunRequest,
        scenario: SchedulePushScenario,
    ): SchedulePushScenarioResult {
        val schedule = scheduleRepository.findScheduleDetail(request.scheduleId, memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        val job = pushJobRepository.findByScheduleIdAndMemberId(request.scheduleId, memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        val currentTravelMinutes = request.currentTravelMinutes
            ?: selectedRouteTravelMinutes(schedule)
            ?: schedule.route?.travelMinutes
            ?: throw BusinessException(ErrorCode.INVALID_INPUT)
        val recommendedDepartureAt = schedule.startAt.minus(
            currentTravelMinutes.toLong(),
            ChronoUnit.MINUTES,
        )
        val now = scenario.nowFor(recommendedDepartureAt, departureAlertLeadMinutes)

        resetJobForScenario(
            job = job,
            schedule = schedule,
            currentTravelMinutes = currentTravelMinutes,
            now = now,
            scenario = scenario,
            trafficChangeMinutes = request.trafficChangeMinutes,
        )

        val processedCount = worker.runDueJobs(now)

        return SchedulePushScenarioResult(
            scenario = scenario,
            workerNow = now,
            processedCount = processedCount,
            jobStatus = job.status.name,
            currentTravelMinutes = currentTravelMinutes,
            recommendedDepartureAt = recommendedDepartureAt,
            lastTravelMinutes = job.lastTravelMinutes,
            lastPushedAt = job.lastPushedAt,
            lastNotifiedDepartureAt = job.lastNotifiedDepartureAt,
            failureReason = job.failureReason,
            expectedPayloadType = scenario.expectedPayloadType,
            expectedDepartNow = scenario == SchedulePushScenario.DEPART_NOW,
        )
    }

    private fun resetJobForScenario(
        job: SchedulePushJob,
        schedule: Schedule,
        currentTravelMinutes: Int,
        now: Instant,
        scenario: SchedulePushScenario,
        trafficChangeMinutes: Int,
    ) {
        val recommendedDepartureAt = schedule.startAt.minus(
            currentTravelMinutes.toLong(),
            ChronoUnit.MINUTES,
        )
        val monitorStartAt = recommendedDepartureAt.minus(60, ChronoUnit.MINUTES)
        job.changeSchedule(
            scheduleAt = schedule.startAt,
            departureAt = recommendedDepartureAt,
            monitorStartAt = monitorStartAt,
            intervalMinutes = job.intervalMinutes,
        )

        if (scenario == SchedulePushScenario.TRAFFIC_CHANGED) {
            val previousTravelMinutes = (currentTravelMinutes - trafficChangeMinutes)
                .coerceAtLeast(1)
            val previousRecommendedDepartureAt = schedule.startAt.minus(
                previousTravelMinutes.toLong(),
                ChronoUnit.MINUTES,
            )
            job.startProcessing("schedule-push-scenario")
            job.finishCheck(
                travelMinutes = previousTravelMinutes,
                recommendedDepartureAt = previousRecommendedDepartureAt,
                pushSent = false,
                notifiedDepartureAt = null,
                nextCheckAt = now,
                completeAfterCheck = false,
                now = now.minus(1, ChronoUnit.MINUTES),
            )
        }
    }

    private fun selectedRouteTravelMinutes(schedule: Schedule): Int? {
        val routeJson = schedule.route?.routeJson?.takeIf { it.isNotBlank() } ?: return null

        return runCatching {
            val root = objectMapper.readTree(routeJson)
            sequenceOf(
                root.path("minutes"),
                root.path("travelMinutes"),
                root.path("durationMinutes"),
            )
                .firstOrNull { it.isNumber }
                ?.asInt()
                ?.takeIf { it > 0 }
        }.getOrNull()
    }
}

data class SchedulePushScenarioRunRequest(
    val scheduleId: Long,
    val scenarios: List<SchedulePushScenario> = emptyList(),
    val currentTravelMinutes: Int? = null,
    val trafficChangeMinutes: Int = 15,
)

data class SchedulePushScenarioRunResponse(
    val memberId: Long,
    val scheduleId: Long,
    val results: List<SchedulePushScenarioResult>,
)

data class SchedulePushScenarioResult(
    val scenario: SchedulePushScenario,
    val workerNow: Instant,
    val processedCount: Int,
    val jobStatus: String,
    val currentTravelMinutes: Int,
    val recommendedDepartureAt: Instant,
    val lastTravelMinutes: Int?,
    val lastPushedAt: Instant?,
    val lastNotifiedDepartureAt: Instant?,
    val failureReason: String?,
    val expectedPayloadType: String,
    val expectedDepartNow: Boolean,
)

enum class SchedulePushScenario(
    val expectedPayloadType: String,
) {
    TRAFFIC_CHANGED("SCHEDULE_TRAFFIC"),
    DEPARTURE_SOON("SCHEDULE_DEPARTURE_REMINDER"),
    DEPART_NOW("SCHEDULE_DEPARTURE_REMINDER"),
    ;

    fun nowFor(recommendedDepartureAt: Instant, alertLeadMinutes: Int): Instant =
        when (this) {
            TRAFFIC_CHANGED -> recommendedDepartureAt
                .minus(alertLeadMinutes.toLong(), ChronoUnit.MINUTES)
                .minus(1, ChronoUnit.MINUTES)
            DEPARTURE_SOON -> recommendedDepartureAt
                .minus(alertLeadMinutes.toLong(), ChronoUnit.MINUTES)
            DEPART_NOW -> recommendedDepartureAt
        }
}
