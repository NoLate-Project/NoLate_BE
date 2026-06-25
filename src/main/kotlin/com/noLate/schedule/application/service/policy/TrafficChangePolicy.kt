package com.noLate.schedule.application.service.policy

import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 이전 교통 조회 결과와 현재 결과를 비교해 사용자가 이해하기 쉬운 알림을 만든다.
 *
 * 매 주기마다 푸시는 발송하지만, 이동 시간이 늘어난 경우에는 증가한 분량과
 * 새 추천 출발 시각을 명시한다. 이동 시간이 동일하거나 감소한 경우에도 현재
 * 이동 시간과 추천 출발 시각을 제공해 반복 알림 자체의 의미를 유지한다.
 */
@Component
class TrafficChangePolicy {
    private val timeFormatter = DateTimeFormatter
        .ofPattern("HH:mm")
        .withZone(ZoneId.of("Asia/Seoul"))

    fun createMessage(
        scheduleTitle: String,
        previousTravelMinutes: Int?,
        currentTravelMinutes: Int,
        recommendedDepartureAt: Instant,
        decision: DepartureReminderDecision,
        alertLeadMinutes: Int,
    ): SchedulePushMessage {
        val titleText = scheduleTitle.trim().ifBlank { "일정" }
        val departureText = timeFormatter.format(recommendedDepartureAt)
        val delta = previousTravelMinutes?.let { currentTravelMinutes - it }

        if (decision == DepartureReminderDecision.DEPART_NOW) {
            val trafficChange = delta
                ?.takeIf { it > 0 }
                ?.let { " 이동 시간이 ${it}분 늘었어요." }
                .orEmpty()
            return SchedulePushMessage(
                title = "지금 출발하세요",
                body = "'$titleText'에 늦지 않으려면 지금 출발하세요.$trafficChange",
                trafficChangeMinutes = delta,
            )
        }

        val body = when {
            delta == null ->
                "'$titleText' 권장 출발 $departureText. 약 ${alertLeadMinutes}분 남았어요."
            delta > 0 ->
                "이전보다 ${delta}분 더 걸려요. '$titleText' 권장 출발 $departureText."
            delta < 0 ->
                "이전보다 ${-delta}분 덜 걸려요. '$titleText' 권장 출발 $departureText."
            else ->
                "'$titleText' 권장 출발 $departureText. 약 ${alertLeadMinutes}분 남았어요."
        }

        return SchedulePushMessage(
            title = when {
                delta == null -> "출발 준비하세요"
                delta > 0 -> "이동 시간이 늘었어요"
                delta < 0 -> "이동 시간이 줄었어요"
                else -> "출발 시간 안내"
            },
            body = body,
            trafficChangeMinutes = delta,
        )
    }
}

data class SchedulePushMessage(
    val title: String,
    val body: String,
    val trafficChangeMinutes: Int?,
)
