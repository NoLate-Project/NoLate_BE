package com.noLate.schedule.application.service.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 기능 1: 알림 시작 이후 설정한 재알림 간격으로 계속 검사되는지 검증한다.
 */
class PeriodicPushPolicyTest {
    /*
     * 테스트 시간 설정
     *
     * 반복 알림 시나리오의 시간을 바꾸려면 아래 값만 수정한다.
     * 각 테스트의 예상 시각도 이 값을 기준으로 계산하므로 테스트 본문을 함께 고칠 필요가 없다.
     */
    private val testNow = Instant.parse("2026-06-12T01:00:00Z")
    private val intervalMinutes = 20
    private val alertLeadMinutes = 15

    private val policy = PeriodicPushPolicy()

    @Test
    fun `추천 출발 시각이 충분히 남으면 설정한 재알림 간격을 사용한다`() {
        val recommendedDepartureAt = testNow.plus(60, ChronoUnit.MINUTES)
        val next = policy.nextCheckAt(
            now = testNow,
            recommendedDepartureAt = recommendedDepartureAt,
            intervalMinutes = intervalMinutes,
            alertLeadMinutes = alertLeadMinutes,
        )

        assertEquals(testNow.plus(intervalMinutes.toLong(), ChronoUnit.MINUTES), next)
    }

    @Test
    fun `조회 주기보다 출발 전 알림 시각이 빠르면 알림 시각에 다시 검사한다`() {
        val recommendedDepartureAt = testNow.plus(25, ChronoUnit.MINUTES)
        val alertAt = recommendedDepartureAt.minus(alertLeadMinutes.toLong(), ChronoUnit.MINUTES)
        val next = policy.nextCheckAt(
            now = testNow,
            recommendedDepartureAt = recommendedDepartureAt,
            intervalMinutes = intervalMinutes,
            alertLeadMinutes = alertLeadMinutes,
        )

        assertEquals(alertAt, next)
    }

    @Test
    fun `출발 전 알림 시각이 지난 뒤에는 추천 출발 시각을 다음 경계로 사용한다`() {
        val recommendedDepartureAt = testNow.plus(8, ChronoUnit.MINUTES)
        val next = policy.nextCheckAt(
            now = testNow,
            recommendedDepartureAt = recommendedDepartureAt,
            intervalMinutes = intervalMinutes,
            alertLeadMinutes = alertLeadMinutes,
        )

        assertEquals(recommendedDepartureAt, next)
    }
}
