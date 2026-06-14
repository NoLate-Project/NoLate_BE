package com.noLate.schedule.application.service.policy

import org.springframework.stereotype.Component
import java.time.Instant

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
        lastNotifiedDepartureAt: Instant?,
        alertLeadMinutes: Int,
    ): DepartureReminderDecision {
        if (!now.isBefore(recommendedDepartureAt)) {
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
}

enum class DepartureReminderDecision {
    NONE,
    ADVANCE_NOTICE,
    DEPART_NOW,
}
