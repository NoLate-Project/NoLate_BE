package com.noLate.notification.controller

import com.noLate.global.security.MemberPrincipal
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.domain.PushPlatform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
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
}
