package com.noLate.notification.controller

import com.noLate.global.security.MemberPrincipal
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.domain.PushPlatform
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class NotificationControllerTokenRegistrationUnitTest {
    @Mock
    lateinit var tokenService: NotificationTokenService

    @Mock
    lateinit var notificationUseCase: NotificationUseCase

    @Mock
    lateinit var historyService: PushSendHistoryService

    @Test
    fun `register forwards verified access issuedAt without raw JWT`() {
        val issuedAt = Instant.parse("2026-07-24T03:00:00Z")
        val principal = MemberPrincipal(
            id = 91L,
            email = "member@example.com",
            name = "member",
            accessTokenIssuedAt = issuedAt,
            accessTokenSessionGeneration = 7,
        )
        val controller = NotificationController(tokenService, notificationUseCase, historyService)

        controller.registerToken(
            principal,
            RegisterPushTokenRequest(
                deviceId = "device-91",
                platform = PushPlatform.ANDROID,
                token = "opaque-token-not-logged",
            ),
        )

        verify(tokenService).registerToken(
            memberId = 91L,
            deviceId = "device-91",
            platform = PushPlatform.ANDROID,
            token = "opaque-token-not-logged",
            accessTokenIssuedAt = issuedAt,
            accessTokenSessionGeneration = 7,
        )
    }

    @Test
    fun `public test send uses the durable recipient fenced outbox`() {
        val principal = MemberPrincipal(
            id = 92L,
            email = "member@example.com",
            name = "member",
            accessTokenIssuedAt = Instant.parse("2026-07-24T03:00:00Z"),
            accessTokenSessionGeneration = 8,
        )
        val request = SendTestNotificationRequest(
            title = "private title",
            body = "private body",
            data = mapOf("type" to "TEST"),
        )
        val expected = NotificationSendResult(
            requestedCount = 1,
            sentCount = 1,
        )
        whenever(
            notificationUseCase.sendToMember(
                memberId = 92L,
                title = request.title,
                body = request.body,
                data = request.data.orEmpty(),
                persistInInbox = true,
            )
        ).thenReturn(expected)
        val controller = NotificationController(tokenService, notificationUseCase, historyService)

        val response = controller.sendTest(principal, request)

        assertSame(expected, response.data)
        verify(notificationUseCase).sendToMember(
            memberId = 92L,
            title = request.title,
            body = request.body,
            data = request.data.orEmpty(),
            persistInInbox = true,
        )
    }
}
