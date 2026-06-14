package com.noLate.schedule.application.service.policy

import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 설정한 ETA 조회 간격과 다음 사용자 알림 경계 시각을 함께 고려한다.
 *
 * 평소에는 ETA API만 일정 간격으로 조회한다. 다만 추천 출발 전 알림 시각이나
 * 추천 출발 시각이 조회 주기보다 먼저 도달하면 해당 경계 시각에 Worker를 깨워
 * 푸시가 최대 1분 단위 스케줄러 지연 안에서 발송되도록 한다.
 */
@Component
class PeriodicPushPolicy {

    fun nextCheckAt(
        now: Instant,
        recommendedDepartureAt: Instant,
        intervalMinutes: Int,
        alertLeadMinutes: Int,
    ): Instant {
        val intervalCheckAt = now.plus(intervalMinutes.toLong(), ChronoUnit.MINUTES)
        val alertAt = recommendedDepartureAt.minus(alertLeadMinutes.toLong(), ChronoUnit.MINUTES)
        val nextNotificationBoundary = if (now.isBefore(alertAt)) {
            alertAt
        } else {
            recommendedDepartureAt
        }

        return minOf(intervalCheckAt, nextNotificationBoundary)
    }
}
