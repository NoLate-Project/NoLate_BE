package com.noLate.schedule.application.service.policy

import com.noLate.schedule.application.TrafficFailureReasons
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
        departureAdvanceMinutes: Int = 0,
        onTimeArrivalPossible: Boolean? = null,
        predictedArrivalAt: Instant? = null,
        transferFailureReason: String? = null,
    ): SchedulePushMessage {
        val titleText = scheduleTitle.trim().ifBlank { "일정" }
        val departureText = timeFormatter.format(recommendedDepartureAt)
        val delta = previousTravelMinutes?.let { currentTravelMinutes - it }

        when (transferFailureReason) {
            TrafficFailureReasons.TRANSIT_TRANSFER_MISSED ->
                return SchedulePushMessage(
                    title = "선택한 환승을 놓칠 수 있어요",
                    body = "'$titleText' 선택 경로의 환승 차량을 현재 ETA로는 탈 수 없어요. " +
                        "기존 출발 알람을 신뢰하지 말고 기기 알람 상태와 앱 경로를 다시 확인해 주세요.",
                    trafficChangeMinutes = delta,
                )
            TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN ->
                return SchedulePushMessage(
                    title = "환승 가능 여부를 확인할 수 없어요",
                    body = "'$titleText' 선택 경로의 환승 시간표나 안전 여유를 확정하지 못했어요. " +
                        "기존 출발 알람을 신뢰하지 말고 기기 알람 상태와 앱 경로를 다시 확인해 주세요.",
                    trafficChangeMinutes = delta,
                )
        }

        if (onTimeArrivalPossible == false) {
            val arrivalText = predictedArrivalAt?.let(timeFormatter::format)
            val prediction = arrivalText
                ?.let { "현재 확인한 가장 빠른 예상 도착은 ${it}예요. " }
                .orEmpty()
            return SchedulePushMessage(
                title = "정시 도착이 어려워요",
                body = "'$titleText'에 제시간 도착하기 어려워요. ${prediction}아직 출발 전이면 지금 출발하세요.",
                trafficChangeMinutes = delta,
            )
        }

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
            departureAdvanceMinutes > 0 && (delta == null || delta <= 0) ->
                "대중교통 운행시각이 바뀌어 ${departureAdvanceMinutes}분 일찍 출발해야 해요. " +
                    "'$titleText' 권장 출발 $departureText."
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
                departureAdvanceMinutes > 0 && (delta == null || delta <= 0) ->
                    "출발 시간이 앞당겨졌어요"
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
