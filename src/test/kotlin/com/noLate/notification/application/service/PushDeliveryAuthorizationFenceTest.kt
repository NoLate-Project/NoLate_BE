package com.noLate.notification.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.AppNotification
import com.noLate.notification.domain.PushDelivery
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class PushDeliveryAuthorizationFenceTest {

    @Mock lateinit var deliveryRepository: PushDeliveryRepository
    @Mock lateinit var tokenRepository: NotificationDeviceTokenRepository
    @Mock lateinit var notificationRepository: AppNotificationRepository
    @Mock lateinit var memberRepository: MemberRepository
    @Mock lateinit var authorizationValidator: PushRecipientAuthorizationValidator

    @Test
    fun `revoked immutable source is terminal before token or provider boundary`() {
        val delivery = PushDelivery(
            id = 11L,
            memberId = 2L,
            eventKey = "logical:revoked",
            deviceKey = "device-sha256:test",
            deviceTokenId = 22L,
            tokenFingerprint = "a".repeat(64),
            tokenOwnershipVersion = 1L,
            platform = PushPlatform.ANDROID,
            scheduleId = 10L,
        )
        val source = AppNotification(
            id = 33L,
            memberId = 2L,
            logicalEventKey = delivery.eventKey,
            type = "SCHEDULE_DEPARTURE_REMINDER",
            scheduleId = 10L,
            title = "private title",
            body = "private body",
            dataJson = jacksonObjectMapper().writeValueAsString(emptyMap<String, String>()),
            createdAt = Instant.parse("2026-07-24T00:00:00Z"),
        )
        whenever(memberRepository.findActiveNotificationRecipientForUpdate(2L)).thenReturn(
            Member(id = 2L, email = "recipient@example.com", password = "Password1!", name = "recipient")
        )
        whenever(deliveryRepository.findByIdAndMemberIdAndEventKey(11L, 2L, delivery.eventKey))
            .thenReturn(delivery)
        whenever(notificationRepository.findByMemberIdAndLogicalEventKey(2L, delivery.eventKey))
            .thenReturn(source)
        whenever(
            authorizationValidator.canDispatch(
                memberId = 2L,
                scheduleId = 10L,
                categoryId = null,
                payloadType = source.type,
                calendarId = null,
            )
        ).thenReturn(false)

        val result = writer().claim(
            memberId = 2L,
            eventKey = delivery.eventKey,
            deliveryId = 11L,
        )

        assertEquals(PushDeliveryClaimOutcome.SUPERSEDED, result.outcome)
        assertEquals(PushDeliveryStatus.SUPERSEDED, delivery.status)
        verify(tokenRepository, never()).findByIdForUpdate(22L)
    }

    @Test
    fun `calendar source passes its frozen calendar identity to the final authorization fence`() {
        val delivery = PushDelivery(
            id = 44L,
            memberId = 2L,
            eventKey = "logical:calendar-revoked",
            deviceKey = "device-sha256:calendar",
            deviceTokenId = 55L,
            tokenFingerprint = "b".repeat(64),
            tokenOwnershipVersion = 1L,
            platform = PushPlatform.ANDROID,
            calendarId = 77L,
            payloadType = "CALENDAR_SHARE_RECEIVED",
        )
        val source = AppNotification(
            id = 66L,
            memberId = 2L,
            logicalEventKey = delivery.eventKey,
            type = "CALENDAR_SHARE_RECEIVED",
            calendarId = 77L,
            title = "private calendar title",
            body = "private calendar body",
            dataJson = jacksonObjectMapper().writeValueAsString(
                mapOf("calendarId" to "77")
            ),
            createdAt = Instant.parse("2026-07-24T00:00:00Z"),
        )
        whenever(memberRepository.findActiveNotificationRecipientForUpdate(2L)).thenReturn(
            Member(id = 2L, email = "recipient@example.com", password = "Password1!", name = "recipient")
        )
        whenever(deliveryRepository.findByIdAndMemberIdAndEventKey(44L, 2L, delivery.eventKey))
            .thenReturn(delivery)
        whenever(notificationRepository.findByMemberIdAndLogicalEventKey(2L, delivery.eventKey))
            .thenReturn(source)
        whenever(
            authorizationValidator.canDispatch(
                memberId = 2L,
                scheduleId = null,
                categoryId = null,
                payloadType = source.type,
                calendarId = 77L,
            )
        ).thenReturn(false)

        val result = writer().claim(
            memberId = 2L,
            eventKey = delivery.eventKey,
            deliveryId = 44L,
        )

        assertEquals(PushDeliveryClaimOutcome.SUPERSEDED, result.outcome)
        assertEquals(PushDeliveryStatus.SUPERSEDED, delivery.status)
        verify(authorizationValidator).canDispatch(
            memberId = 2L,
            scheduleId = null,
            categoryId = null,
            payloadType = source.type,
            calendarId = 77L,
        )
        verify(tokenRepository, never()).findByIdForUpdate(55L)
    }

    private fun writer() = PushDeliveryWriter(
        repository = deliveryRepository,
        tokenRepository = tokenRepository,
        appNotificationRepository = notificationRepository,
        memberRepository = memberRepository,
        clock = Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC),
        recipientAuthorizationValidator = authorizationValidator,
    )
}
