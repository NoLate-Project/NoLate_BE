package com.noLate.notification.dev

import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class PushScenarioRunnerTest {

    @Mock
    lateinit var notificationUseCase: NotificationUseCase

    private lateinit var runner: PushScenarioRunner

    @BeforeEach
    fun setUp() {
        runner = PushScenarioRunner(notificationUseCase)
    }

    @Test
    fun `푸시 검증 Runner를 실행하면 앱에서 확인해야 할 대표 시나리오를 순서대로 발송한다`() {
        // 토큰 확인, 교통 변경, 출발 임박, 즉시 출발, 상세 이동 payload가 한 번에 발송되는지 확인한다.
        whenever(notificationUseCase.sendToMember(eq(1L), any(), any(), any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        val response = runner.run(
            memberId = 1L,
            request = PushScenarioRunRequest(
                scheduleId = 10L,
                titlePrefix = "로컬 검증",
                changedTravelMinutes = 55,
                trafficChangeMinutes = 20,
            ),
        )

        assertEquals(5, response.scenarioCount)
        assertEquals(
            listOf(
                "TOKEN_CHECK",
                "TRAFFIC_CHANGED",
                "DEPARTURE_SOON",
                "DEPART_NOW",
                "DETAIL_NAVIGATION",
            ),
            response.results.map { it.scenario },
        )
        assertEquals(5, response.total.requestedCount)
        assertEquals(5, response.total.sentCount)

        verify(notificationUseCase, times(5)).sendToMember(eq(1L), any(), any(), any())
        verify(notificationUseCase).sendToMember(
            eq(1L),
            eq("로컬 검증 - 바로 출발 필요"),
            any(),
            check { data ->
                assertEquals("SCHEDULE_TRAFFIC", data["type"])
                assertEquals("DEPART_NOW", data["scenario"])
                assertEquals("10", data["scheduleId"])
                assertEquals("55", data["travelMinutes"])
                assertEquals("20", data["trafficChangeMinutes"])
                assertEquals("true", data["departNow"])
            },
        )
    }

    @Test
    fun `일정 번호가 없어도 토큰 수신 확인과 payload 형태를 검증할 수 있게 기본값으로 발송한다`() {
        // 신규 일정 없이도 앱 푸시 연결 상태를 확인할 수 있도록 scheduleId는 문자열 0으로 채운다.
        whenever(notificationUseCase.sendToMember(eq(7L), any(), any(), any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        val response = runner.run(memberId = 7L, request = PushScenarioRunRequest())

        assertEquals(5, response.scenarioCount)
        assertTrue(response.requiredConditions.any { it.contains("notification.push-scenario.enabled=true") })
        assertTrue(response.runnerRole.contains("FE 수신"))

        verify(notificationUseCase).sendToMember(
            eq(7L),
            eq("NoLate 푸시 검증 - 일정 상세 이동"),
            any(),
            check { data ->
                assertEquals("SCHEDULE_DETAIL", data["type"])
                assertEquals("DETAIL_NAVIGATION", data["scenario"])
                assertEquals("0", data["scheduleId"])
            },
        )
    }
}
