package com.noLate.notification.dev

import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 개발/검증 환경에서 FE 앱의 푸시 수신 상태를 한 번에 확인하기 위한 Runner.
 *
 * 필수 조건:
 * - 서버 실행 시 notification.push-scenario.enabled=true 설정
 * - 앱 로그인 및 푸시 알림 권한 허용
 * - 앱에서 /api/notifications/token 호출로 FCM 토큰 등록
 * - 실제 FCM 전송 확인 시 firebase.enabled=true, firebase.project-id, 인증 정보 설정
 *
 * 역할:
 * - 일정/교통 알림에서 사용하는 대표 payload를 같은 회원에게 순차 전송한다.
 * - BE의 NotificationUseCase, PushClient, 토큰 정리 흐름을 실제 경로로 검증한다.
 * - FE가 알림 수신, 상세 이동, 긴급 알림 표현을 처리하는지 수동 E2E로 확인하게 한다.
 */
@Component
@ConditionalOnProperty(
    prefix = "notification.push-scenario",
    name = ["enabled"],
    havingValue = "true",
)
class PushScenarioRunner(
    private val notificationUseCase: NotificationUseCase,
) {

    fun run(memberId: Long, request: PushScenarioRunRequest): PushScenarioRunResponse {
        val scenarios = buildScenarios(request)
        val results = scenarios.map { scenario ->
            val result = notificationUseCase.sendToMember(
                memberId = memberId,
                title = scenario.title,
                body = scenario.body,
                data = scenario.data,
            )
            PushScenarioResult(
                scenario = scenario.name,
                title = scenario.title,
                data = scenario.data,
                sendResult = result,
            )
        }

        val total = results.fold(NotificationSendResult()) { acc, scenarioResult ->
            acc + scenarioResult.sendResult
        }

        return PushScenarioRunResponse(
            memberId = memberId,
            scheduleId = request.scheduleId,
            scenarioCount = results.size,
            total = total,
            results = results,
            requiredConditions = REQUIRED_CONDITIONS,
            runnerRole = RUNNER_ROLE,
        )
    }

    private fun buildScenarios(request: PushScenarioRunRequest): List<PushScenarioMessage> {
        val scheduleId = request.scheduleId?.toString() ?: "0"
        val prefix = request.titlePrefix?.takeIf { it.isNotBlank() } ?: "NoLate 푸시 검증"

        return listOf(
            PushScenarioMessage(
                name = "TOKEN_CHECK",
                title = "$prefix - 토큰 수신 확인",
                body = "현재 로그인한 앱에서 푸시를 받을 수 있는지 확인합니다.",
                data = mapOf(
                    "type" to "PUSH_SCENARIO_TOKEN_CHECK",
                    "scenario" to "TOKEN_CHECK",
                ),
            ),
            PushScenarioMessage(
                name = "TRAFFIC_CHANGED",
                title = "$prefix - 이동 시간 변경",
                body = "선택한 경로의 예상 이동 시간이 15분 늘어났습니다.",
                data = mapOf(
                    "type" to "SCHEDULE_TRAFFIC",
                    "scenario" to "TRAFFIC_CHANGED",
                    "scheduleId" to scheduleId,
                    "travelMinutes" to request.changedTravelMinutes.toString(),
                    "trafficChangeMinutes" to request.trafficChangeMinutes.toString(),
                    "departNow" to "false",
                ),
            ),
            PushScenarioMessage(
                name = "DEPARTURE_SOON",
                title = "$prefix - 출발 준비",
                body = "예상 출발 시각이 가까워지고 있습니다.",
                data = mapOf(
                    "type" to "SCHEDULE_DEPARTURE_REMINDER",
                    "scenario" to "DEPARTURE_SOON",
                    "scheduleId" to scheduleId,
                    "travelMinutes" to request.changedTravelMinutes.toString(),
                    "departNow" to "false",
                ),
            ),
            PushScenarioMessage(
                name = "DEPART_NOW",
                title = "$prefix - 바로 출발 필요",
                body = "교통 상황이 나빠져 지금 출발해야 늦지 않습니다.",
                data = mapOf(
                    "type" to "SCHEDULE_TRAFFIC",
                    "scenario" to "DEPART_NOW",
                    "scheduleId" to scheduleId,
                    "travelMinutes" to request.changedTravelMinutes.toString(),
                    "trafficChangeMinutes" to request.trafficChangeMinutes.toString(),
                    "departNow" to "true",
                ),
            ),
            PushScenarioMessage(
                name = "DETAIL_NAVIGATION",
                title = "$prefix - 일정 상세 이동",
                body = "알림을 눌렀을 때 일정 상세 화면으로 이동하는지 확인합니다.",
                data = mapOf(
                    "type" to "SCHEDULE_DETAIL",
                    "scenario" to "DETAIL_NAVIGATION",
                    "scheduleId" to scheduleId,
                ),
            ),
        )
    }

    companion object {
        val REQUIRED_CONDITIONS = listOf(
            "notification.push-scenario.enabled=true로 Runner 활성화",
            "로그인된 앱에서 푸시 알림 권한 허용",
            "앱이 /api/notifications/token으로 FCM 토큰 등록",
            "실제 단말 푸시 확인 시 firebase.enabled=true 및 Firebase 인증 정보 설정",
        )

        const val RUNNER_ROLE =
            "일정/교통 푸시 대표 payload를 현재 회원에게 순차 발송해 FE 수신, 상세 이동, 긴급 알림 표현을 검증한다."
    }
}

data class PushScenarioRunRequest(
    val scheduleId: Long? = null,
    val titlePrefix: String? = null,
    val changedTravelMinutes: Int = 45,
    val trafficChangeMinutes: Int = 15,
)

data class PushScenarioRunResponse(
    val memberId: Long,
    val scheduleId: Long?,
    val scenarioCount: Int,
    val total: NotificationSendResult,
    val results: List<PushScenarioResult>,
    val requiredConditions: List<String>,
    val runnerRole: String,
)

data class PushScenarioResult(
    val scenario: String,
    val title: String,
    val data: Map<String, String>,
    val sendResult: NotificationSendResult,
)

private data class PushScenarioMessage(
    val name: String,
    val title: String,
    val body: String,
    val data: Map<String, String>,
)
