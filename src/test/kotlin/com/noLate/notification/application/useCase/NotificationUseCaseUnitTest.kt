// src/test/kotlin/com/swyp/notification/application/NotificationUseCaseUnitTest.kt
package com.noLate.notification.application

import com.noLate.notification.application.InvalidPushTokenException
import com.noLate.notification.application.PushSendResult
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.domain.PushSendStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*

@ExtendWith(MockitoExtension::class)
class NotificationUseCaseUnitTest {

    @Mock
    lateinit var notificationTokenService: NotificationTokenService

    @Mock
    lateinit var pushClient: PushClient

    @Mock
    lateinit var pushSendHistoryService: PushSendHistoryService

    private lateinit var notificationUseCase: NotificationUseCase

    @BeforeEach
    fun setUp() {
        notificationUseCase = NotificationUseCase(
            notificationTokenService = notificationTokenService,
            pushClient = pushClient,
            pushSendHistoryService = pushSendHistoryService,
        )
    }

    @Test
    fun `sendToMember는 해당 회원의 모든 토큰에 대해 PushClient를 호출한다`() {
        val memberId = 1L
        val tokens = listOf(
            NotificationDeviceToken(
                id = 1L,
                memberId = memberId,
                deviceId = "d1",
                platform = PushPlatform.ANDROID,
                token = "token-1"
            ),
            NotificationDeviceToken(
                id = 2L,
                memberId = memberId,
                deviceId = "d2",
                platform = PushPlatform.IOS,
                token = "token-2"
            )
        )

        whenever(notificationTokenService.getTokensByMember(memberId))
            .thenReturn(tokens)
        whenever(pushClient.sendToToken(any(), any(), any(), any()))
            .thenReturn(PushSendResult("message-id"))

        val title = "테스트 제목"
        val body = "테스트 내용"
        val data = mapOf("key" to "value")

        val result = notificationUseCase.sendToMember(
            memberId = memberId,
            title = title,
            body = body,
            data = data
        )

        verify(notificationTokenService, times(1))
            .getTokensByMember(memberId)

        verify(pushClient, times(1))
            .sendToToken("token-1", title, body, data)
        verify(pushClient, times(1))
            .sendToToken("token-2", title, body, data)
        verify(pushSendHistoryService).recordSuccess(
            memberId = memberId,
            token = tokens[0],
            title = title,
            body = body,
            data = data,
            fcmMessageId = "message-id",
        )
        verify(pushSendHistoryService).recordSuccess(
            memberId = memberId,
            token = tokens[1],
            title = title,
            body = body,
            data = data,
            fcmMessageId = "message-id",
        )
        assertEquals(2, result.requestedCount)
        assertEquals(2, result.sentCount)
        assertEquals(0, result.failedCount)
    }

    @Test
    fun `FCM에서 무효 토큰으로 응답하면 기존 토큰 서비스를 통해 제거한다`() {
        val memberId = 1L
        val token = NotificationDeviceToken(
            id = 1L,
            memberId = memberId,
            deviceId = "d1",
            platform = PushPlatform.ANDROID,
            token = "invalid-token",
        )
        whenever(notificationTokenService.getTokensByMember(memberId)).thenReturn(listOf(token))
        whenever(pushClient.sendToToken(eq("invalid-token"), any(), any(), any()))
            .thenThrow(InvalidPushTokenException("invalid-token"))

        val result = notificationUseCase.sendToMember(memberId, "제목", "내용")

        verify(pushSendHistoryService).recordFailure(
            memberId = memberId,
            token = token,
            title = "제목",
            body = "내용",
            data = emptyMap(),
            status = PushSendStatus.INVALID_TOKEN,
            errorCode = InvalidPushTokenException::class.java.simpleName,
            errorMessage = "유효하지 않은 푸시 토큰입니다.",
        )
        verify(notificationTokenService).removeTokenValue(memberId, "invalid-token")
        assertEquals(0, result.sentCount)
        assertEquals(1, result.failedCount)
        assertEquals(1, result.removedTokenCount)
    }

    @Test
    fun `등록된 토큰이 없으면 NO_TOKEN 이력을 남긴다`() {
        val memberId = 1L
        val data = mapOf("type" to "SCHEDULE_TRAFFIC", "scheduleId" to "10")

        whenever(notificationTokenService.getTokensByMember(memberId)).thenReturn(emptyList())

        val result = notificationUseCase.sendToMember(
            memberId = memberId,
            title = "제목",
            body = "내용",
            data = data,
        )

        verify(pushSendHistoryService).recordNoToken(
            memberId = memberId,
            title = "제목",
            body = "내용",
            data = data,
        )
        verify(pushClient, never()).sendToToken(any(), any(), any(), any())
        assertEquals(0, result.requestedCount)
        assertEquals(0, result.sentCount)
        assertEquals(0, result.failedCount)
    }

    @Test
    fun `sendToMembers는 각 memberId에 대해 sendToMember를 호출한다`() {
        val memberIds = listOf(1L, 2L)
        val title = "제목"
        val body = "내용"

        whenever(notificationTokenService.getTokensByMember(any()))
            .thenReturn(emptyList())

        notificationUseCase.sendToMembers(
            memberIds = memberIds,
            title = title,
            body = body
        )

        verify(notificationTokenService, times(1)).getTokensByMember(1L)
        verify(notificationTokenService, times(1)).getTokensByMember(2L)
    }

    @Test
    fun `여러 회원에게 서로 다른 일정 푸시를 보내도 토큰과 payload가 섞이지 않는다`() {
        val firstToken = NotificationDeviceToken(
            id = 1L,
            memberId = 1L,
            deviceId = "member-1-device",
            platform = PushPlatform.ANDROID,
            token = "member-1-token",
        )
        val secondToken = NotificationDeviceToken(
            id = 2L,
            memberId = 2L,
            deviceId = "member-2-device",
            platform = PushPlatform.IOS,
            token = "member-2-token",
        )
        val firstData = mapOf("scheduleId" to "10", "type" to "SCHEDULE_TRAFFIC")
        val secondData = mapOf("scheduleId" to "20", "type" to "SCHEDULE_TRAFFIC")

        whenever(notificationTokenService.getTokensByMember(1L)).thenReturn(listOf(firstToken))
        whenever(notificationTokenService.getTokensByMember(2L)).thenReturn(listOf(secondToken))
        whenever(pushClient.sendToToken(any(), any(), any(), any()))
            .thenReturn(PushSendResult("message-id"))

        notificationUseCase.sendToMember(1L, "회원 1 알림", "회원 1 일정", firstData)
        notificationUseCase.sendToMember(2L, "회원 2 알림", "회원 2 일정", secondData)

        verify(pushClient).sendToToken(
            "member-1-token",
            "회원 1 알림",
            "회원 1 일정",
            firstData,
        )
        verify(pushClient).sendToToken(
            "member-2-token",
            "회원 2 알림",
            "회원 2 일정",
            secondData,
        )
        verify(pushClient, never()).sendToToken(
            eq("member-1-token"),
            any(),
            any(),
            eq(secondData),
        )
        verify(pushClient, never()).sendToToken(
            eq("member-2-token"),
            any(),
            any(),
            eq(firstData),
        )
    }
}
