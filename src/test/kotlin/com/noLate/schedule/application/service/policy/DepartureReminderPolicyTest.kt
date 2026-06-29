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
    private val scheduleAt = Instant.parse("2026-06-12T03:00:00Z")
    private val alertLeadMinutes = 15

    private val policy = DepartureReminderPolicy()

    @Test
    fun `출발 전 알림 시각 전에는 ETA만 갱신하고 푸시하지 않는다`() {
        assertEquals(
            DepartureReminderDecision.NONE,
            decide(
                now = recommendedDepartureAt.minus(alertLeadMinutes + 1L, ChronoUnit.MINUTES),
            ),
        )
    }

    @Test
    fun `출발 전 알림 시각에 도달하면 준비 알림을 선택한다`() {
        assertEquals(
            DepartureReminderDecision.ADVANCE_NOTICE,
            decide(
                now = recommendedDepartureAt.minus(alertLeadMinutes.toLong(), ChronoUnit.MINUTES),
            ),
        )
    }

    @Test
    fun `현재 추천 출발 시각을 이미 안내했다면 같은 알림을 반복하지 않는다`() {
        assertEquals(
            DepartureReminderDecision.NONE,
            decide(
                now = recommendedDepartureAt.minus(5, ChronoUnit.MINUTES),
                lastNotifiedDepartureAt = recommendedDepartureAt,
            ),
        )
    }

    @Test
    fun `안내 후 추천 출발 시각이 변경되면 변경 알림을 선택한다`() {
        assertEquals(
            DepartureReminderDecision.ADVANCE_NOTICE,
            decide(
                now = recommendedDepartureAt.minus(5, ChronoUnit.MINUTES),
                lastNotifiedDepartureAt = recommendedDepartureAt.plus(10, ChronoUnit.MINUTES),
            ),
        )
    }

    @Test
    fun `추천 출발 시각과 같거나 지난 경우 지금 출발 경고를 선택한다`() {
        assertEquals(
            DepartureReminderDecision.DEPART_NOW,
            decide(
                now = recommendedDepartureAt,
                lastNotifiedDepartureAt = recommendedDepartureAt,
            ),
        )
    }

    @Test
    fun `지금 출발 알림 이후 3분 경계에 후속 알림을 선택한다`() {
        val departureNoticeSentAt = recommendedDepartureAt

        assertEquals(
            DepartureReminderDecision.AFTER_DEPARTURE_3,
            decide(
                now = departureNoticeSentAt.plus(3, ChronoUnit.MINUTES),
                departureNoticeSentAt = departureNoticeSentAt,
                lastDepartureReminderBoundaryAt = recommendedDepartureAt,
            ),
        )
    }

    @Test
    fun `여러 후속 알림 경계가 지나면 가장 최근 경계만 선택한다`() {
        val departureNoticeSentAt = recommendedDepartureAt

        assertEquals(
            DepartureReminderDecision.BEFORE_SCHEDULE_1,
            decide(
                now = scheduleAt.minus(1, ChronoUnit.MINUTES),
                departureNoticeSentAt = departureNoticeSentAt,
                lastDepartureReminderBoundaryAt = recommendedDepartureAt,
            ),
        )
    }

    @Test
    fun `일정 시작 이후에는 출발 알림을 선택하지 않는다`() {
        assertEquals(
            DepartureReminderDecision.NONE,
            decide(
                now = scheduleAt,
                departureNoticeSentAt = recommendedDepartureAt,
                lastDepartureReminderBoundaryAt = recommendedDepartureAt,
            ),
        )
    }

    private fun decide(
        now: Instant,
        recommendedDepartureAt: Instant = this.recommendedDepartureAt,
        scheduleAt: Instant = this.scheduleAt,
        lastNotifiedDepartureAt: Instant? = null,
        departureNoticeSentAt: Instant? = null,
        lastDepartureReminderBoundaryAt: Instant? = null,
        snoozedUntil: Instant? = null,
    ): DepartureReminderDecision =
        policy.decide(
            now = now,
            recommendedDepartureAt = recommendedDepartureAt,
            scheduleAt = scheduleAt,
            lastNotifiedDepartureAt = lastNotifiedDepartureAt,
            departureNoticeSentAt = departureNoticeSentAt,
            lastDepartureReminderBoundaryAt = lastDepartureReminderBoundaryAt,
            snoozedUntil = snoozedUntil,
            alertLeadMinutes = alertLeadMinutes,
        )
}
