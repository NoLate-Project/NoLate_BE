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

        if (decision.departNowAction) {
            val trafficChange = delta
                ?.takeIf { it > 0 }
                ?.let { " 이동 시간이 ${it}분 늘었어요." }
                .orEmpty()

            val titleBody = when (decision) {
                DepartureReminderDecision.SNOOZE ->
                    "다시 알려드려요" to "'$titleText' 출발 시간이 지났어요. 아직 출발 전이면 지금 출발하세요.$trafficChange"
                DepartureReminderDecision.AFTER_DEPARTURE_3 ->
                    "출발 확인이 필요해요" to "'$titleText'에 늦지 않으려면 지금 출발해야 해요.$trafficChange"
                DepartureReminderDecision.AFTER_DEPARTURE_7 ->
                    "늦을 수 있어요" to "'$titleText' 출발 확인이 없어요. 아직 출발 전이면 바로 출발하세요.$trafficChange"
                DepartureReminderDecision.BEFORE_SCHEDULE_3 ->
                    "곧 일정 시작이에요" to "'$titleText' 시작까지 3분 남았어요. 이동 중인지 확인해 주세요.$trafficChange"
                DepartureReminderDecision.BEFORE_SCHEDULE_1 ->
                    "곧 일정 시작이에요" to "'$titleText' 시작까지 1분 남았어요. 이동 중인지 확인해 주세요.$trafficChange"
                else ->
                    "지금 출발하세요" to "'$titleText'에 늦지 않으려면 지금 출발하세요.$trafficChange"
            }

            return SchedulePushMessage(
                title = titleBody.first,
                body = titleBody.second,
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
