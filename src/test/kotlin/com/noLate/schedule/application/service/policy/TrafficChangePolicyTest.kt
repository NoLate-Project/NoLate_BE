package com.noLate.schedule.application.service.policy

import com.noLate.schedule.application.TrafficFailureReasons
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

    @Test
    fun `정시 도착 불가 결과는 늦지 않는다고 오인시키지 않고 예상 도착시각을 안내한다`() {
        val predictedArrivalAt = Instant.parse("2026-06-12T02:20:00Z")

        val message = policy.createMessage(
            scheduleTitle = "회의",
            previousTravelMinutes = 30,
            currentTravelMinutes = 50,
            recommendedDepartureAt = recommendedDepartureAt,
            decision = DepartureReminderDecision.DEPART_NOW,
            alertLeadMinutes = alertLeadMinutes,
            onTimeArrivalPossible = false,
            predictedArrivalAt = predictedArrivalAt,
        )

        assertEquals("정시 도착이 어려워요", message.title)
        assertTrue(message.body.contains("제시간 도착하기 어려워요"))
        assertTrue(message.body.contains("가장 빠른 예상 도착은 11:20"))
        assertTrue(message.body.contains("지금 출발"))
    }

    @Test
    fun `환승 실패는 지금 출발하면 도착한다는 문구 대신 경로 재확인을 안내한다`() {
        val message = policy.createMessage(
            scheduleTitle = "회의",
            previousTravelMinutes = 30,
            currentTravelMinutes = 45,
            recommendedDepartureAt = recommendedDepartureAt,
            decision = DepartureReminderDecision.DEPART_NOW,
            alertLeadMinutes = alertLeadMinutes,
            transferFailureReason = TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
        )

        assertEquals("선택한 환승을 놓칠 수 있어요", message.title)
        assertTrue(message.body.contains("환승 차량을 현재 ETA로는 탈 수 없어요"))
        assertTrue(message.body.contains("경로를 다시 확인"))
        assertTrue(!message.body.contains("지금 출발"))
    }

    @Test
    fun `환승 시간표 불확실은 알람을 신뢰하지 말고 상태를 확인하라고 안내한다`() {
        val message = policy.createMessage(
            scheduleTitle = "회의",
            previousTravelMinutes = 30,
            currentTravelMinutes = 30,
            recommendedDepartureAt = recommendedDepartureAt,
            decision = DepartureReminderDecision.NONE,
            alertLeadMinutes = alertLeadMinutes,
            transferFailureReason = TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN,
        )

        assertEquals("환승 가능 여부를 확인할 수 없어요", message.title)
        assertTrue(message.body.contains("환승 시간표나 안전 여유를 확정하지 못했어요"))
        assertTrue(message.body.contains("기존 출발 알람을 신뢰하지 말고"))
        assertTrue(message.body.contains("기기 알람 상태"))
        assertTrue(message.body.contains("경로를 다시 확인"))
    }
}
