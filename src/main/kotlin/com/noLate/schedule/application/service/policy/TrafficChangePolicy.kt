package com.noLate.schedule.application.service.policy

import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 이전 교통 조회 결과와 현재 결과를 비교해 사용자가 이해하기 쉬운 알림을 만든다.
 *
 * 리마인드 경계마다 현재 추천 출발 시각을 안내한다. 이동 시간이 늘어난 경우에는
 * 증가한 분량을 명시하고, 일반 리마인드는 실제 남은 분을 표시한다.
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
        reminderMinutesBeforeDeparture: Int = alertLeadMinutes,
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
                "'$titleText' 권장 출발 $departureText. 약 ${reminderMinutesBeforeDeparture}분 남았어요."
            delta > 0 ->
                "이전보다 ${delta}분 더 걸려요. '$titleText' 권장 출발 $departureText."
            delta < 0 ->
                "이전보다 ${-delta}분 덜 걸려요. '$titleText' 권장 출발 $departureText."
            else ->
                "'$titleText' 권장 출발 $departureText. 약 ${reminderMinutesBeforeDeparture}분 남았어요."
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
