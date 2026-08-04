package com.noLate.schedule.application.service

import com.noLate.schedule.application.service.policy.DepartureReminderDecision
import com.noLate.schedule.application.service.policy.TrafficChangePolicy
import com.noLate.schedule.domain.DepartureAlarmOccurrence
import com.noLate.schedule.domain.DepartureAlarmPlan
import com.noLate.schedule.domain.departureAlarmActionEventKey
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Builds the complete M15/M10/M5/M0 plan before the app may enter the background. */
@Component
class DepartureAlarmPlanFactory(
    private val trafficChangePolicy: TrafficChangePolicy = TrafficChangePolicy(),
    @Value("\${schedule.push.departure-alert-lead-minutes:15}")
    private val alertLeadMinutes: Int = 15,
    @Value("\${schedule.push.departure-reminder-interval-minutes:5}")
    private val reminderIntervalMinutes: Int = 5,
) {
    init {
        require(alertLeadMinutes == 15 && reminderIntervalMinutes == 5) {
            "Departure alarm plan v2 requires the same M15/M10/M5/M0 15/5 reminder policy."
        }
    }

    fun create(
        memberId: Long,
        scheduleId: Long,
        recommendedDepartureAt: Instant,
        scheduleTitle: String?,
    ): DepartureAlarmPlan {
        val canonicalDepartureAt = recommendedDepartureAt.truncatedTo(ChronoUnit.MILLIS)
        val normalizedTitle = scheduleTitle?.trim().orEmpty().ifBlank { "일정" }
        val occurrences = minuteBoundaries().map { minutesBeforeDeparture ->
            val triggerAt = canonicalDepartureAt.minus(minutesBeforeDeparture.toLong(), ChronoUnit.MINUTES)
            val decision = if (minutesBeforeDeparture == 0) {
                DepartureReminderDecision.DEPART_NOW
            } else {
                DepartureReminderDecision.ADVANCE_NOTICE
            }
            val message = trafficChangePolicy.createCanonicalDepartureReminderMessage(
                scheduleTitle = normalizedTitle,
                recommendedDepartureAt = canonicalDepartureAt,
                decision = decision,
                minutesBeforeDeparture = minutesBeforeDeparture,
            )
            DepartureAlarmOccurrence(
                occurrenceId = "M$minutesBeforeDeparture",
                triggerAt = triggerAt.toString(),
                title = message.title.take(100),
                body = message.body.take(500),
                decision = decision.name,
                minutesBeforeDeparture = minutesBeforeDeparture,
                actionEventKey = departureAlarmActionEventKey(
                    memberId = memberId,
                    scheduleId = scheduleId,
                    occurrenceId = "M$minutesBeforeDeparture",
                    triggerAt = triggerAt,
                ),
            )
        }
        return DepartureAlarmPlan(occurrences)
    }

    private fun minuteBoundaries(): List<Int> =
        generateSequence(alertLeadMinutes) { previous -> previous - reminderIntervalMinutes }
            .takeWhile { it > 0 }
            .toList() + 0
}
