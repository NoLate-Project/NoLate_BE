// src/test/kotlin/com/swyp/notification/application/NotificationUseCaseUnitTest.kt
package com.noLate.notification.application

import com.noLate.notification.application.InvalidPushTokenException
import com.noLate.notification.application.PushSendResult
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.AppNotificationRecordResult
import com.noLate.notification.application.service.AppNotificationService
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.domain.AppNotification
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.domain.PushLogicalEventKey
import com.noLate.notification.domain.PushSendStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class NotificationUseCaseUnitTest {

    @Mock
    lateinit var notificationTokenService: NotificationTokenService

    @Mock
    lateinit var pushClient: PushClient

    @Mock
    lateinit var pushSendHistoryService: PushSendHistoryService

    @Mock
    lateinit var appNotificationService: AppNotificationService

    private lateinit var notificationUseCase: NotificationUseCase

    @BeforeEach
    fun setUp() {
        notificationUseCase = NotificationUseCase(
            notificationTokenService = notificationTokenService,
            pushClient = pushClient,
            pushSendHistoryService = pushSendHistoryService,
            appNotificationService = appNotificationService,
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
            .sendToToken(
                eq("token-1"),
                eq(title),
                eq(body),
                argThat { canonicalFor(memberId, data) },
            )
        verify(pushClient, times(1))
            .sendToToken(
                eq("token-2"),
                eq(title),
                eq(body),
                argThat { canonicalFor(memberId, data) },
            )
        verify(pushSendHistoryService).recordSuccess(
            memberId = eq(memberId),
            token = eq(tokens[0]),
            title = eq(title),
            body = eq(body),
            data = argThat { canonicalFor(memberId, data) },
            fcmMessageId = eq("message-id"),
        )
        verify(appNotificationService, times(1)).recordWithResult(
            memberId = memberId,
            title = title,
            body = body,
            data = data,
            deduplicationKey = null,
        )
        verify(pushSendHistoryService).recordSuccess(
            memberId = eq(memberId),
            token = eq(tokens[1]),
            title = eq(title),
            body = eq(body),
            data = argThat { canonicalFor(memberId, data) },
            fcmMessageId = eq("message-id"),
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
        whenever(
            notificationTokenService.removeTokenByOwnership(
                memberId,
                1L,
                token.tokenFingerprint,
                token.ownershipVersion,
            )
        ).thenReturn(true)

        val result = notificationUseCase.sendToMember(memberId, "제목", "내용")

        verify(pushSendHistoryService).recordFailure(
            memberId = eq(memberId),
            token = eq(token),
            title = eq("제목"),
            body = eq("내용"),
            data = argThat { canonicalFor(memberId, emptyMap()) },
            status = eq(PushSendStatus.INVALID_TOKEN),
            errorCode = eq(InvalidPushTokenException::class.java.simpleName),
            errorMessage = eq("유효하지 않은 푸시 토큰입니다."),
        )
        verify(notificationTokenService).removeTokenByOwnership(
            memberId,
            1L,
            token.tokenFingerprint,
            token.ownershipVersion,
        )
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
            memberId = eq(memberId),
            title = eq("제목"),
            body = eq("내용"),
            data = argThat { canonicalFor(memberId, data) },
        )
        verify(appNotificationService).recordWithResult(
            memberId = memberId,
            title = "제목",
            body = "내용",
            data = data,
            deduplicationKey = null,
        )
        verify(pushClient, never()).sendToToken(any(), any(), any(), any())
        assertEquals(0, result.requestedCount)
        assertEquals(0, result.sentCount)
        assertEquals(0, result.failedCount)
    }

    @Test
    fun `공급자 점검용 푸시는 사용자 알림함에 저장하지 않을 수 있다`() {
        whenever(notificationTokenService.getTokensByMember(1L)).thenReturn(emptyList())

        notificationUseCase.sendToMember(
            memberId = 1L,
            title = "테스트",
            body = "공급자 점검",
            data = mapOf("type" to "PUSH_SCENARIO_TOKEN_CHECK"),
            persistInInbox = false,
        )

        verifyNoInteractions(appNotificationService)
    }

    @Test
    fun `기존 inbox 이벤트이면 외부 전송도 중복 실행하지 않는다`() {
        val memberId = 1L
        val token = NotificationDeviceToken(
            id = 1L,
            memberId = memberId,
            deviceId = "d1",
            platform = PushPlatform.ANDROID,
            token = "must-not-send",
        )
        whenever(notificationTokenService.getTokensByMember(memberId)).thenReturn(listOf(token))
        whenever(
            appNotificationService.recordWithResult(
                memberId = memberId,
                title = "제목",
                body = "내용",
                data = emptyMap(),
                deduplicationKey = "same-event",
            )
        ).thenReturn(
            AppNotificationRecordResult(
                notification = AppNotification(
                    id = 10L,
                    memberId = memberId,
                    deduplicationKey = "same-event",
                    type = "GENERAL",
                    title = "제목",
                    body = "내용",
                    dataJson = "{}",
                    createdAt = Instant.parse("2026-07-24T03:00:00Z"),
                ),
                created = false,
            )
        )

        val result = notificationUseCase.sendToMember(
            memberId = memberId,
            title = "제목",
            body = "내용",
            inboxDeduplicationKey = "same-event",
        )

        verify(pushClient, never()).sendToToken(any(), any(), any(), any())
        assertEquals(0, result.attemptedCount)
        assertEquals(1, result.deduplicatedCount)
        assertEquals(true, result.inboxDeduplicated)
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
            eq("member-1-token"),
            eq("회원 1 알림"),
            eq("회원 1 일정"),
            argThat { canonicalFor(1L, firstData) },
        )
        verify(pushClient).sendToToken(
            eq("member-2-token"),
            eq("회원 2 알림"),
            eq("회원 2 일정"),
            argThat { canonicalFor(2L, secondData) },
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

    @Test
    fun `route action payload는 durable logical event와 recipient account binding을 포함한다`() {
        val memberId = 77L
        val deduplicationKey = "route-setup:77:marker-digest"
        val expectedEventKey = PushLogicalEventKey.deterministic(memberId, deduplicationKey)
        val token = NotificationDeviceToken(
            id = 77L,
            memberId = memberId,
            deviceId = "route-device",
            platform = PushPlatform.ANDROID,
            token = "route-token",
        )
        whenever(notificationTokenService.getTokensByMember(memberId)).thenReturn(listOf(token))
        whenever(pushClient.sendToToken(any(), any(), any(), any()))
            .thenReturn(PushSendResult("route-message"))

        notificationUseCase.sendToMember(
            memberId = memberId,
            title = "경로를 설정해주세요",
            body = "일정의 출발 경로를 확인해주세요.",
            data = mapOf(
                "type" to "ROUTE_SETUP_REMINDER",
                "scheduleId" to "700",
            ),
            inboxDeduplicationKey = deduplicationKey,
        )

        verify(pushClient).sendToToken(
            eq("route-token"),
            any(),
            any(),
            argThat {
                this["type"] == "ROUTE_SETUP_REMINDER" &&
                    this["logicalEventKey"] == expectedEventKey &&
                    this["recipientMemberId"] == memberId.toString()
            },
        )
    }

    private fun Map<String, String>.canonicalFor(
        memberId: Long,
        original: Map<String, String>,
    ): Boolean =
        entries.containsAll(original.entries) &&
            this["recipientMemberId"] == memberId.toString() &&
            this["logicalEventKey"]?.startsWith("event:") == true
}
