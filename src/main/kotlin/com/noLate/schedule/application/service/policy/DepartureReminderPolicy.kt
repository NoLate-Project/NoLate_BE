package com.noLate.schedule.application.service.policy

import com.noLate.schedule.domain.ScheduleDepartureReminderStage
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * ETA 조회 결과를 사용자 푸시로 전환할 시점을 판단한다.
 *
 * ETA는 설정된 조회 간격마다 갱신하지만 그때마다 푸시하지 않는다.
 * 추천 출발 시각의 일정 시간 전에는 준비 알림을 한 번 보내고, 추천 출발 시각이
 * 바뀌면 변경된 시각을 기준으로 다시 안내한다. 출발 시각에 도달하면 최종 알림을 보낸다.
 */
@Component
class DepartureReminderPolicy {

    fun decide(
        now: Instant,
        recommendedDepartureAt: Instant,
        scheduleAt: Instant,
        lastNotifiedDepartureAt: Instant?,
        departureNoticeSentAt: Instant?,
        lastDepartureReminderBoundaryAt: Instant?,
        snoozedUntil: Instant?,
        alertLeadMinutes: Int,
    ): DepartureReminderDecision {
        if (!now.isBefore(scheduleAt)) {
            return DepartureReminderDecision.NONE
        }

        if (snoozedUntil != null && !now.isBefore(snoozedUntil)) {
            return DepartureReminderDecision.SNOOZE
        }

        val followUpDecision = decideFollowUp(
            now = now,
            departureNoticeSentAt = departureNoticeSentAt,
            scheduleAt = scheduleAt,
            lastDepartureReminderBoundaryAt = lastDepartureReminderBoundaryAt,
        )
        if (followUpDecision != DepartureReminderDecision.NONE) {
            return followUpDecision
        }

        if (departureNoticeSentAt == null && !now.isBefore(recommendedDepartureAt)) {
            return DepartureReminderDecision.DEPART_NOW
        }

        val alertAt = recommendedDepartureAt.minusSeconds(alertLeadMinutes.toLong() * 60)
        val currentDepartureAlreadyNotified = lastNotifiedDepartureAt == recommendedDepartureAt

        return if (!now.isBefore(alertAt) && !currentDepartureAlreadyNotified) {
            DepartureReminderDecision.ADVANCE_NOTICE
        } else {
            DepartureReminderDecision.NONE
        }
    }

    fun nextReminderBoundary(
        now: Instant,
        recommendedDepartureAt: Instant,
        scheduleAt: Instant,
        lastNotifiedDepartureAt: Instant?,
        departureNoticeSentAt: Instant?,
        lastDepartureReminderBoundaryAt: Instant?,
        snoozedUntil: Instant?,
        alertLeadMinutes: Int,
    ): Instant? {
        if (!now.isBefore(scheduleAt)) {
            return null
        }

        val candidates = mutableListOf<Instant>()

        snoozedUntil
            ?.takeIf { it.isAfter(now) && it.isBefore(scheduleAt) }
            ?.let(candidates::add)

        if (departureNoticeSentAt == null) {
            val alertAt = recommendedDepartureAt.minusSeconds(alertLeadMinutes.toLong() * 60)
            val currentDepartureAlreadyNotified = lastNotifiedDepartureAt == recommendedDepartureAt

            if (!currentDepartureAlreadyNotified && alertAt.isAfter(now) && alertAt.isBefore(scheduleAt)) {
                candidates += alertAt
            }
            if (recommendedDepartureAt.isAfter(now) && recommendedDepartureAt.isBefore(scheduleAt)) {
                candidates += recommendedDepartureAt
            }
        } else {
            candidates += followUpCandidates(departureNoticeSentAt, scheduleAt)
                .map { it.boundaryAt }
                .filter { it.isAfter(now) && isAfterLastHandled(it, lastDepartureReminderBoundaryAt) }
        }

        return candidates.minOrNull()
    }

    private fun decideFollowUp(
        now: Instant,
        departureNoticeSentAt: Instant?,
        scheduleAt: Instant,
        lastDepartureReminderBoundaryAt: Instant?,
    ): DepartureReminderDecision {
        if (departureNoticeSentAt == null) return DepartureReminderDecision.NONE

        // 여러 경계가 서버 지연으로 한 번에 지난 경우, 가장 최근 경계만 보낸다.
        // 오래 지난 +3분 알림을 뒤늦게 보내면 현재 상황과 맞지 않을 수 있기 때문이다.
        return followUpCandidates(departureNoticeSentAt, scheduleAt)
            .filter { !it.boundaryAt.isAfter(now) }
            .filter { isAfterLastHandled(it.boundaryAt, lastDepartureReminderBoundaryAt) }
            .maxByOrNull { it.boundaryAt }
            ?.decision
            ?: DepartureReminderDecision.NONE
    }

    private fun followUpCandidates(
        departureNoticeSentAt: Instant,
        scheduleAt: Instant,
    ): List<DepartureReminderCandidate> {
        val candidates = listOf(
            DepartureReminderCandidate(
                decision = DepartureReminderDecision.AFTER_DEPARTURE_3,
                boundaryAt = departureNoticeSentAt.plus(3, ChronoUnit.MINUTES),
            ),
            DepartureReminderCandidate(
                decision = DepartureReminderDecision.AFTER_DEPARTURE_7,
                boundaryAt = departureNoticeSentAt.plus(7, ChronoUnit.MINUTES),
            ),
            DepartureReminderCandidate(
                decision = DepartureReminderDecision.BEFORE_SCHEDULE_3,
                boundaryAt = scheduleAt.minus(3, ChronoUnit.MINUTES),
            ),
            DepartureReminderCandidate(
                decision = DepartureReminderDecision.BEFORE_SCHEDULE_1,
                boundaryAt = scheduleAt.minus(1, ChronoUnit.MINUTES),
            ),
        )

        return candidates.filter { candidate ->
            candidate.boundaryAt.isAfter(departureNoticeSentAt) && candidate.boundaryAt.isBefore(scheduleAt)
        }
    }

    private fun isAfterLastHandled(
        boundaryAt: Instant,
        lastDepartureReminderBoundaryAt: Instant?,
    ): Boolean = lastDepartureReminderBoundaryAt == null || boundaryAt.isAfter(lastDepartureReminderBoundaryAt)
}

enum class DepartureReminderDecision {
    NONE,
    ADVANCE_NOTICE,
    DEPART_NOW,
    SNOOZE,
    AFTER_DEPARTURE_3,
    AFTER_DEPARTURE_7,
    BEFORE_SCHEDULE_3,
    BEFORE_SCHEDULE_1;

    val stage: ScheduleDepartureReminderStage?
        get() = when (this) {
            DEPART_NOW -> ScheduleDepartureReminderStage.DEPART_NOW
            AFTER_DEPARTURE_3 -> ScheduleDepartureReminderStage.AFTER_DEPARTURE_3
            AFTER_DEPARTURE_7 -> ScheduleDepartureReminderStage.AFTER_DEPARTURE_7
            BEFORE_SCHEDULE_3 -> ScheduleDepartureReminderStage.BEFORE_SCHEDULE_3
            BEFORE_SCHEDULE_1 -> ScheduleDepartureReminderStage.BEFORE_SCHEDULE_1
            NONE,
            ADVANCE_NOTICE,
            SNOOZE -> null
        }

    val departNowAction: Boolean
        get() = this == DEPART_NOW ||
            this == SNOOZE ||
            this == AFTER_DEPARTURE_3 ||
            this == AFTER_DEPARTURE_7 ||
            this == BEFORE_SCHEDULE_3 ||
            this == BEFORE_SCHEDULE_1
}

private data class DepartureReminderCandidate(
    val decision: DepartureReminderDecision,
    val boundaryAt: Instant,
)
