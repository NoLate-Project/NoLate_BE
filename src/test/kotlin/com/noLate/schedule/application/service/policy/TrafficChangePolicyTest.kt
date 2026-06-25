package com.noLate.schedule.application.service.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 기능 3: 매 검사 시 교통시간을 비교하고 변경된 출발 시각을 안내하는지 검증한다.
 */
class TrafficChangePolicyTest {
    /*
     * 테스트 시간 설정
     *
     * 알림 문구에 표시할 추천 출발 시각을 이곳에서 한 번만 관리한다.
     * 정책은 Asia/Seoul 기준으로 표시하므로 아래 UTC 01:30은 화면에서 10:30으로 보인다.
     */
    private val recommendedDepartureAt = Instant.parse("2026-06-12T01:30:00Z")
    private val expectedDepartureText = "10:30"
    private val alertLeadMinutes = 15

    private val policy = TrafficChangePolicy()

    @Test
    fun `첫 조회는 현재 이동시간과 추천 출발 시각을 안내한다`() {
        val message = policy.createMessage(
            scheduleTitle = "회의",
            previousTravelMinutes = null,
            currentTravelMinutes = 30,
            recommendedDepartureAt = recommendedDepartureAt,
            decision = DepartureReminderDecision.ADVANCE_NOTICE,
            alertLeadMinutes = alertLeadMinutes,
        )

        assertEquals("출발 준비하세요", message.title)
        assertEquals(
            "'회의' 권장 출발 $expectedDepartureText. 약 15분 남았어요.",
            message.body,
        )
    }

    @Test
    fun `교통시간이 증가하면 증가한 분량과 앞당겨진 출발 시각을 안내한다`() {
        val message = policy.createMessage(
            scheduleTitle = "회의",
            previousTravelMinutes = 30,
            currentTravelMinutes = 45,
            recommendedDepartureAt = recommendedDepartureAt,
            decision = DepartureReminderDecision.ADVANCE_NOTICE,
            alertLeadMinutes = alertLeadMinutes,
        )

        assertEquals("이동 시간이 늘었어요", message.title)
        assertEquals(15, message.trafficChangeMinutes)
        assertTrue(message.body.contains("15분 더 걸려요"))
        assertTrue(message.body.contains("권장 출발 $expectedDepartureText"))
    }

    @Test
    fun `교통시간이 동일하면 현재 이동시간과 추천 출발 시각을 다시 안내한다`() {
        val message = policy.createMessage(
            scheduleTitle = "회의",
            previousTravelMinutes = 30,
            currentTravelMinutes = 30,
            recommendedDepartureAt = recommendedDepartureAt,
            decision = DepartureReminderDecision.ADVANCE_NOTICE,
            alertLeadMinutes = alertLeadMinutes,
        )

        assertEquals(0, message.trafficChangeMinutes)
        assertEquals(
            "'회의' 권장 출발 $expectedDepartureText. 약 15분 남았어요.",
            message.body,
        )
    }

    @Test
    fun `교통시간이 감소하면 감소한 분량과 늦춰진 출발 시각을 안내한다`() {
        val message = policy.createMessage(
            scheduleTitle = "회의",
            previousTravelMinutes = 45,
            currentTravelMinutes = 30,
            recommendedDepartureAt = recommendedDepartureAt,
            decision = DepartureReminderDecision.ADVANCE_NOTICE,
            alertLeadMinutes = alertLeadMinutes,
        )

        assertEquals("이동 시간이 줄었어요", message.title)
        assertEquals(-15, message.trafficChangeMinutes)
        assertTrue(message.body.contains("15분 덜 걸려요"))
        assertTrue(message.body.contains("권장 출발 $expectedDepartureText"))
    }

    @Test
    fun `추천 출발 시각이 지나면 교통 증가 여부와 함께 지금 출발을 안내한다`() {
        val message = policy.createMessage(
            scheduleTitle = "회의",
            previousTravelMinutes = 30,
            currentTravelMinutes = 50,
            recommendedDepartureAt = recommendedDepartureAt,
            decision = DepartureReminderDecision.DEPART_NOW,
            alertLeadMinutes = alertLeadMinutes,
        )

        assertEquals("지금 출발하세요", message.title)
        assertTrue(message.body.contains("지금 출발"))
        assertTrue(message.body.contains("20분 늘었어요"))
    }
}
