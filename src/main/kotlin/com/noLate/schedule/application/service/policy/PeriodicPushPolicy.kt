package com.noLate.schedule.application.service.policy

import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 설정한 ETA 조회 간격과 다음 사용자 알림 경계 시각을 함께 고려한다.
 *
 * ETA 조회 간격은 교통 API 비용과 요금제 정책을 따른다. 사용자 리마인드 간격은
 * 이와 분리해 5분 같은 짧은 UX 단위로 둘 수 있으므로, 두 경계 중 더 빠른 시각에
 * Worker를 깨운다.
 */
@Component
class PeriodicPushPolicy {

    fun nextCheckAt(
        now: Instant,
        recommendedDepartureAt: Instant,
        intervalMinutes: Int,
        alertLeadMinutes: Int,
        reminderIntervalMinutes: Int,
    ): Instant {
        require(reminderIntervalMinutes > 0) {
            "reminderIntervalMinutes는 0보다 커야 합니다. reminderIntervalMinutes=$reminderIntervalMinutes"
        }

        val intervalCheckAt = now.plus(intervalMinutes.toLong(), ChronoUnit.MINUTES)
        val alertAt = recommendedDepartureAt.minus(alertLeadMinutes.toLong(), ChronoUnit.MINUTES)
        val nextNotificationBoundary = when {
            now.isBefore(alertAt) -> alertAt
            now.isBefore(recommendedDepartureAt) -> minOf(
                nextReminderBoundaryAfter(
                    now = now,
                    alertAt = alertAt,
                    reminderIntervalMinutes = reminderIntervalMinutes,
                ),
                recommendedDepartureAt,
            )
            else -> recommendedDepartureAt
        }

        return minOf(intervalCheckAt, nextNotificationBoundary)
    }

    private fun nextReminderBoundaryAfter(
        now: Instant,
        alertAt: Instant,
        reminderIntervalMinutes: Int,
    ): Instant {
        val elapsedMinutes = Duration.between(alertAt, now).toMinutes().coerceAtLeast(0)
        val currentBoundaryOffset = (elapsedMinutes / reminderIntervalMinutes) * reminderIntervalMinutes
        return alertAt.plus((currentBoundaryOffset + reminderIntervalMinutes).toLong(), ChronoUnit.MINUTES)
    }
}
