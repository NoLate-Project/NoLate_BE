// src/test/kotlin/com/swyp/notification/application/NotificationUseCaseUnitTest.kt
package com.noLate.notification.application

import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushPlatform
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

    private lateinit var notificationUseCase: NotificationUseCase

    @BeforeEach
    fun setUp() {
        notificationUseCase = NotificationUseCase(
            notificationTokenService = notificationTokenService,
            pushClient = pushClient
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

        val title = "테스트 제목"
        val body = "테스트 내용"
        val data = mapOf("key" to "value")

        notificationUseCase.sendToMember(
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
}
