package com.noLate.schedule.application.service.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 기능 2: 일정에 늦지 않도록 실시간 추천 출발 시각에 최종 경고하는지 검증한다.
 */
class DepartureReminderPolicyTest {
    /*
     * 테스트 시간 설정
     *
     * 출발 알림 경계 시각을 바꾸고 싶다면 이 값만 수정한다.
     * 경계 직전과 직후는 아래 기준 시각에서 자동 계산한다.
     */
    private val recommendedDepartureAt = Instant.parse("2026-06-12T02:00:00Z")
    private val alertLeadMinutes = 15

    private val policy = DepartureReminderPolicy()

    @Test
    fun `출발 전 알림 시각 전에는 ETA만 갱신하고 푸시하지 않는다`() {
        assertEquals(
            DepartureReminderDecision.NONE,
            policy.decide(
                now = recommendedDepartureAt.minus(alertLeadMinutes + 1L, ChronoUnit.MINUTES),
                recommendedDepartureAt = recommendedDepartureAt,
                lastNotifiedDepartureAt = null,
                alertLeadMinutes = alertLeadMinutes,
            ),
        )
    }

    @Test
    fun `출발 전 알림 시각에 도달하면 준비 알림을 선택한다`() {
        assertEquals(
            DepartureReminderDecision.ADVANCE_NOTICE,
            policy.decide(
                now = recommendedDepartureAt.minus(alertLeadMinutes.toLong(), ChronoUnit.MINUTES),
                recommendedDepartureAt = recommendedDepartureAt,
                lastNotifiedDepartureAt = null,
                alertLeadMinutes = alertLeadMinutes,
            ),
        )
    }

    @Test
    fun `현재 추천 출발 시각을 이미 안내했다면 같은 알림을 반복하지 않는다`() {
        assertEquals(
            DepartureReminderDecision.NONE,
            policy.decide(
                now = recommendedDepartureAt.minus(5, ChronoUnit.MINUTES),
                recommendedDepartureAt = recommendedDepartureAt,
                lastNotifiedDepartureAt = recommendedDepartureAt,
                alertLeadMinutes = alertLeadMinutes,
            ),
        )
    }

    @Test
    fun `안내 후 추천 출발 시각이 변경되면 변경 알림을 선택한다`() {
        assertEquals(
            DepartureReminderDecision.ADVANCE_NOTICE,
            policy.decide(
                now = recommendedDepartureAt.minus(5, ChronoUnit.MINUTES),
                recommendedDepartureAt = recommendedDepartureAt,
                lastNotifiedDepartureAt = recommendedDepartureAt.plus(10, ChronoUnit.MINUTES),
                alertLeadMinutes = alertLeadMinutes,
            ),
        )
    }

    @Test
    fun `추천 출발 시각과 같거나 지난 경우 지금 출발 경고를 선택한다`() {
        assertEquals(
            DepartureReminderDecision.DEPART_NOW,
            policy.decide(
                now = recommendedDepartureAt,
                recommendedDepartureAt = recommendedDepartureAt,
                lastNotifiedDepartureAt = recommendedDepartureAt,
                alertLeadMinutes = alertLeadMinutes,
            ),
        )
    }
}
