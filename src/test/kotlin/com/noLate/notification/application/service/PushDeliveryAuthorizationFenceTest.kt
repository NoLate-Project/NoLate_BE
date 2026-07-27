package com.noLate.notification.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.PushClient
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
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class PushDeliveryAuthorizationFenceTest {

    @Mock lateinit var deliveryRepository: PushDeliveryRepository
    @Mock lateinit var tokenRepository: NotificationDeviceTokenRepository
    @Mock lateinit var notificationRepository: AppNotificationRepository
    @Mock lateinit var memberRepository: MemberRepository
    @Mock lateinit var authorizationValidator: PushRecipientAuthorizationValidator
    @Mock lateinit var freshnessValidator: PushSourceFreshnessValidator
    @Mock lateinit var dispatchFenceValidator: PushDispatchFenceValidator
    @Mock lateinit var pushClient: PushClient

    @Test
    fun `global off after delivery claim terminalizes at provider fence with zero provider calls`() {
        val delivery = PushDelivery(
            id = 121L,
            memberId = 2L,
            eventKey = "logical:sharing-disabled",
            deviceKey = "device-sha256:sharing-disabled",
            deviceTokenId = 122L,
            tokenFingerprint = "f".repeat(64),
            tokenOwnershipVersion = 1L,
            platform = PushPlatform.ANDROID,
            scheduleId = 10L,
            payloadType = "SCHEDULE_SHARE_RECEIVED",
        ).apply {
            beginDispatch(NOW)
        }
        val source = AppNotification(
            id = 123L,
            memberId = 2L,
            logicalEventKey = delivery.eventKey,
            type = "SCHEDULE_SHARE_RECEIVED",
            scheduleId = 10L,
            title = "private dormant share",
            body = "must not reach provider",
            dataJson = "{}",
            createdAt = NOW,
        )
        whenever(memberRepository.findActiveNotificationRecipientForUpdate(2L)).thenReturn(
            Member(id = 2L, email = "recipient@example.com", password = "Password1!", name = "recipient")
        )
        whenever(deliveryRepository.findById(121L)).thenReturn(Optional.of(delivery))
        whenever(
            notificationRepository.findByMemberIdAndLogicalEventKeyForUpdate(
                2L,
                delivery.eventKey,
            )
        ).thenReturn(source)
        whenever(deliveryRepository.findByIdForUpdate(121L)).thenReturn(delivery)
        whenever(
            authorizationValidator.canDispatch(
                memberId = 2L,
                scheduleId = 10L,
                categoryId = null,
                payloadType = "SCHEDULE_SHARE_RECEIVED",
                calendarId = null,
            )
        ).thenReturn(false)
        val service = PushTokenProviderLeaseService(
            writer = providerLeaseWriter(),
            pushClient = pushClient,
        )

        val result = service.sendIfOwned(
            memberId = 2L,
            claim = PushDeliveryClaim(
                outcome = PushDeliveryClaimOutcome.SEND,
                deliveryId = 121L,
                tokenId = 122L,
                tokenFingerprint = "f".repeat(64),
                tokenOwnershipVersion = 1L,
            ),
            title = source.title,
            body = source.body,
            data = emptyMap(),
        )

        assertEquals(PushTokenProviderLeaseOutcome.SUPERSEDED, result.outcome)
        assertEquals(PushDeliveryStatus.SUPERSEDED, delivery.status)
        assertEquals("RECIPIENT_ACCESS_REVOKED", delivery.errorCode)
        verify(tokenRepository, never()).findByIdForUpdate(122L)
        verify(pushClient, never()).sendToToken(any(), any(), any(), any())
    }

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
        assertEquals("RECIPIENT_ACCESS_REVOKED", delivery.errorCode)
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
        assertEquals("RECIPIENT_ACCESS_REVOKED", delivery.errorCode)
        verify(authorizationValidator).canDispatch(
            memberId = 2L,
            scheduleId = null,
            categoryId = null,
            payloadType = source.type,
            calendarId = 77L,
        )
        verify(tokenRepository, never()).findByIdForUpdate(55L)
    }

    @Test
    fun `stale immutable source records freshness supersede reason`() {
        val delivery = PushDelivery(
            id = 77L,
            memberId = 2L,
            eventKey = "logical:stale",
            deviceKey = "device-sha256:stale",
            deviceTokenId = 88L,
            tokenFingerprint = "c".repeat(64),
            tokenOwnershipVersion = 1L,
            platform = PushPlatform.ANDROID,
            scheduleId = 10L,
        )
        val source = AppNotification(
            id = 99L,
            memberId = 2L,
            logicalEventKey = delivery.eventKey,
            type = "ROUTE_SETUP_REMINDER",
            scheduleId = 10L,
            title = "stale",
            body = "stale",
            dataJson = "{}",
            createdAt = Instant.parse("2026-07-24T00:00:00Z"),
        )
        whenever(memberRepository.findActiveNotificationRecipientForUpdate(2L)).thenReturn(
            Member(id = 2L, email = "recipient@example.com", password = "Password1!", name = "recipient")
        )
        whenever(deliveryRepository.findByIdAndMemberIdAndEventKey(77L, 2L, delivery.eventKey))
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
        ).thenReturn(true)
        whenever(freshnessValidator.isFresh(source.toFrozenPushSource())).thenReturn(false)

        val result = writer().claim(
            memberId = 2L,
            eventKey = delivery.eventKey,
            deliveryId = 77L,
        )

        assertEquals(PushDeliveryClaimOutcome.SUPERSEDED, result.outcome)
        assertEquals("PUSH_SOURCE_STALE", delivery.errorCode)
        verify(tokenRepository, never()).findByIdForUpdate(88L)
    }

    @Test
    fun `stale authenticated session records generation supersede reason`() {
        val delivery = PushDelivery(
            id = 101L,
            memberId = 2L,
            eventKey = "logical:old-session",
            deviceKey = "device-sha256:old-session",
            tokenFingerprint = "d".repeat(64),
            tokenOwnershipVersion = 1L,
            platform = PushPlatform.ANDROID,
        )
        whenever(memberRepository.findActiveNotificationRecipientForUpdate(2L)).thenReturn(
            Member(
                id = 2L,
                email = "recipient@example.com",
                password = "Password1!",
                name = "recipient",
                sessionGeneration = 2L,
            )
        )
        whenever(deliveryRepository.findByIdAndMemberIdAndEventKey(101L, 2L, delivery.eventKey))
            .thenReturn(delivery)

        val result = writer().claim(
            memberId = 2L,
            eventKey = delivery.eventKey,
            deliveryId = 101L,
            sessionFence = AuthenticatedPushSessionFence(
                memberId = 2L,
                sessionGeneration = 1L,
            ),
        )

        assertEquals(PushDeliveryClaimOutcome.SUPERSEDED, result.outcome)
        assertEquals("AUTHENTICATED_SESSION_GENERATION_CHANGED", delivery.errorCode)
    }

    @Test
    fun `terminal persisted schedule fence records source fence reason`() {
        val delivery = PushDelivery(
            id = 111L,
            memberId = 2L,
            eventKey = "logical:old-generation",
            deviceKey = "device-sha256:old-generation",
            tokenFingerprint = "e".repeat(64),
            tokenOwnershipVersion = 1L,
            platform = PushPlatform.ANDROID,
            scheduleId = 10L,
        )
        val fence = PushDispatchFence(
            jobId = 12L,
            workerId = "safety",
            jobVersion = 3L,
            notificationGeneration = 4L,
            notificationInputFingerprint = "old",
            requireWorkerLease = false,
        )
        whenever(memberRepository.findActiveNotificationRecipientForUpdate(2L)).thenReturn(
            Member(id = 2L, email = "recipient@example.com", password = "Password1!", name = "recipient")
        )
        whenever(dispatchFenceValidator.evaluate(fence))
            .thenReturn(PushDispatchFenceDecision.REJECT_TERMINAL)
        whenever(deliveryRepository.findByIdAndMemberIdAndEventKey(111L, 2L, delivery.eventKey))
            .thenReturn(delivery)

        val result = writer().claim(
            memberId = 2L,
            eventKey = delivery.eventKey,
            deliveryId = 111L,
            fence = fence,
        )

        assertEquals(PushDeliveryClaimOutcome.SUPERSEDED, result.outcome)
        assertEquals("SCHEDULE_SOURCE_FENCE_CHANGED", delivery.errorCode)
    }

    private fun writer() = PushDeliveryWriter(
        repository = deliveryRepository,
        tokenRepository = tokenRepository,
        appNotificationRepository = notificationRepository,
        memberRepository = memberRepository,
        clock = Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC),
        fenceValidator = dispatchFenceValidator,
        recipientAuthorizationValidator = authorizationValidator,
        sourceFreshnessValidator = freshnessValidator,
    )

    private fun providerLeaseWriter() = PushTokenProviderLeaseWriter(
        tokenRepository = tokenRepository,
        deliveryRepository = deliveryRepository,
        appNotificationRepository = notificationRepository,
        memberRepository = memberRepository,
        clock = CLOCK,
        recipientAuthorizationValidator = authorizationValidator,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-24T00:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
