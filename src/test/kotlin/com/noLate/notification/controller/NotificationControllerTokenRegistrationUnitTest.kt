package com.noLate.notification.controller

import com.noLate.global.security.MemberPrincipal
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.domain.PushSendHistory
import com.noLate.notification.domain.PushSendStatus
import org.junit.jupiter.api.Assertions.assertEquals
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
            notificationUseCase.sendAuthenticatedToMember(
                memberId = 92L,
                presentedSessionGeneration = 8L,
                title = request.title,
                body = request.body,
                data = request.data.orEmpty(),
            )
        ).thenReturn(expected)
        val controller = NotificationController(tokenService, notificationUseCase, historyService)

        val response = controller.sendTest(principal, request)

        assertSame(expected, response.data)
        verify(notificationUseCase).sendAuthenticatedToMember(
            memberId = 92L,
            presentedSessionGeneration = 8L,
            title = request.title,
            body = request.body,
            data = request.data.orEmpty(),
        )
    }

    @Test
    fun `public send histories expose durable event and typed resource identity`() {
        val principal = MemberPrincipal(
            id = 93L,
            email = "member@example.com",
            name = "member",
        )
        val history = PushSendHistory(
            id = 501L,
            memberId = 93L,
            scheduleId = null,
            logicalEventKey = "logical:calendar-share-history",
            categoryId = 31L,
            calendarId = 41L,
            payloadType = "CALENDAR_SHARE_RECEIVED",
            title = "shared calendar",
            body = "calendar invitation",
            dataJson = """{"calendarId":"41"}""",
            status = PushSendStatus.SUCCESS,
            sentAt = Instant.parse("2026-07-24T03:01:00Z"),
        )
        whenever(historyService.getRecentByMember(93L, 25)).thenReturn(listOf(history))
        val controller = NotificationController(tokenService, notificationUseCase, historyService)

        val response = requireNotNull(controller.getSendHistories(principal, 25).data).single()

        assertEquals("logical:calendar-share-history", response.logicalEventKey)
        assertEquals(31L, response.categoryId)
        assertEquals(41L, response.calendarId)
        assertEquals("CALENDAR_SHARE_RECEIVED", response.payloadType)
    }
}
