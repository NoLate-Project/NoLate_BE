package com.noLate.schedule.application.service.policy

import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * ETA 조회 결과를 사용자 푸시로 전환할 시점을 판단한다.
 *
 * ETA는 설정된 조회 간격마다 갱신하지만 그때마다 푸시하지 않는다.
 * 추천 출발 시각의 일정 시간 전부터는 사용자 리마인드 간격마다 준비 알림을 보내고,
 * 추천 출발 시각이 바뀌면 변경된 시각을 기준으로 다시 안내한다.
 * 출발 시각에 도달하면 최종 알림을 보낸다.
 */
@Component
class DepartureReminderPolicy {

    fun decide(
        now: Instant,
        recommendedDepartureAt: Instant,
        lastNotifiedDepartureAt: Instant?,
        lastReminderBoundaryAt: Instant?,
        alertLeadMinutes: Int,
        reminderIntervalMinutes: Int,
    ): DepartureReminderDecision {
        if (!now.isBefore(recommendedDepartureAt)) {
            return DepartureReminderDecision.DEPART_NOW
        }

        val reminderBoundaryAt = reminderBoundaryAt(
            now = now,
            recommendedDepartureAt = recommendedDepartureAt,
            alertLeadMinutes = alertLeadMinutes,
            reminderIntervalMinutes = reminderIntervalMinutes,
        ) ?: return DepartureReminderDecision.NONE
        val sameDeparture = lastNotifiedDepartureAt == recommendedDepartureAt
        val currentBoundaryAlreadyNotified = sameDeparture &&
            lastReminderBoundaryAt?.let { !it.isBefore(reminderBoundaryAt) } == true

        return if (!currentBoundaryAlreadyNotified) {
            DepartureReminderDecision.ADVANCE_NOTICE
        } else {
            DepartureReminderDecision.NONE
        }
    }

    fun reminderBoundaryAt(
        now: Instant,
        recommendedDepartureAt: Instant,
        alertLeadMinutes: Int,
        reminderIntervalMinutes: Int,
    ): Instant? {
        require(reminderIntervalMinutes > 0) {
            "reminderIntervalMinutes는 0보다 커야 합니다. reminderIntervalMinutes=$reminderIntervalMinutes"
        }

        if (!now.isBefore(recommendedDepartureAt)) return null

        val alertAt = recommendedDepartureAt.minusSeconds(alertLeadMinutes.toLong() * 60)
        if (now.isBefore(alertAt)) return null

        val elapsedMinutes = Duration.between(alertAt, now).toMinutes().coerceAtLeast(0)
        val boundaryOffset = (elapsedMinutes / reminderIntervalMinutes) * reminderIntervalMinutes
        val boundaryAt = alertAt.plusSeconds(boundaryOffset * 60)

        return boundaryAt.takeIf { it.isBefore(recommendedDepartureAt) }
    }
}

enum class DepartureReminderDecision {
    NONE,
    ADVANCE_NOTICE,
    DEPART_NOW,
}
