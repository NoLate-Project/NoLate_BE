package com.noLate.notification.application.service

import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.notification.application.ConfirmedPushDeliveryException
import com.noLate.notification.application.PushClient
import com.noLate.notification.application.PushSendResult
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

class PushTokenProviderLeaseMetricsTest {
    private val writer = mock<PushTokenProviderLeaseWriter>()
    private val pushClient = mock<PushClient>()
    private val registry = SimpleMeterRegistry()
    private val metrics = NoLateOperationalMetrics(registry)
    private val service = PushTokenProviderLeaseService(
        writer = writer,
        pushClient = pushClient,
        operationalMetrics = metrics,
    )
    private val claim = PushDeliveryClaim(
        outcome = PushDeliveryClaimOutcome.SEND,
        deliveryId = 3,
        tokenId = 5,
        tokenFingerprint = "opaque-fingerprint",
        tokenOwnershipVersion = 7,
    )

    @Test
    fun `acquired provider success records one lease and one timed result`() {
        stubAcquiredLease()
        whenever(pushClient.sendToToken(any(), any(), any(), any()))
            .thenReturn(PushSendResult("provider-message"))

        val result = service.sendIfOwned(
            memberId = 1,
            claim = claim,
            title = "title",
            body = "body",
            data = emptyMap(),
        )

        assertEquals(PushTokenProviderLeaseOutcome.ACQUIRED, result.outcome)
        assertEquals(
            1.0,
            registry.get("nolate.push.token.lease")
                .tag("outcome", "acquired")
                .counter()
                .count(),
        )
        assertEquals(
            1L,
            registry.get("nolate.push.provider.duration")
                .tag("outcome", "success")
                .timer()
                .count(),
        )
        verify(writer).release(
            memberId = 1,
            tokenId = 5,
            leaseId = "lease",
            tokenFingerprint = "opaque-fingerprint",
            ownershipVersion = 7,
        )
    }

    @Test
    fun `confirmed provider rejection is timed without exception or identifier tags`() {
        stubAcquiredLease()
        whenever(pushClient.sendToToken(any(), any(), any(), any()))
            .thenThrow(ConfirmedPushDeliveryException("rejected"))

        assertThrows<ConfirmedPushDeliveryException> {
            service.sendIfOwned(
                memberId = 1,
                claim = claim,
                title = "title",
                body = "body",
                data = emptyMap(),
            )
        }

        assertEquals(
            1L,
            registry.get("nolate.push.provider.duration")
                .tag("outcome", "confirmed_failure")
                .timer()
                .count(),
        )
        val tagKeys = registry.find("nolate.push.provider.duration")
            .meters()
            .flatMap { it.id.tags }
            .map { it.key }
            .toSet()
        assertEquals(setOf("outcome"), tagKeys)
    }

    @Test
    fun `metric recorder failure cannot change provider success or skip lease release`() {
        val brokenMetrics = mock<NoLateOperationalMetrics>()
        whenever(brokenMetrics.recordPushTokenLease(any(), any()))
            .thenThrow(IllegalStateException("registry unavailable"))
        whenever(brokenMetrics.recordPushProviderCall(any(), any()))
            .thenThrow(IllegalStateException("registry unavailable"))
        val serviceWithBrokenMetrics = PushTokenProviderLeaseService(
            writer = writer,
            pushClient = pushClient,
            operationalMetrics = brokenMetrics,
        )
        stubAcquiredLease()
        whenever(pushClient.sendToToken(any(), any(), any(), any()))
            .thenReturn(PushSendResult("provider-message"))

        val result = serviceWithBrokenMetrics.sendIfOwned(
            memberId = 1,
            claim = claim,
            title = "title",
            body = "body",
            data = emptyMap(),
        )

        assertEquals(PushTokenProviderLeaseOutcome.ACQUIRED, result.outcome)
        assertEquals("provider-message", result.providerResult?.messageId)
        verify(writer).release(
            memberId = 1,
            tokenId = 5,
            leaseId = "lease",
            tokenFingerprint = "opaque-fingerprint",
            ownershipVersion = 7,
        )
    }

    private fun stubAcquiredLease() {
        whenever(
            writer.acquire(
                memberId = eq(1),
                deliveryId = eq(3),
                tokenId = eq(5),
                tokenFingerprint = eq("opaque-fingerprint"),
                ownershipVersion = eq(7),
                dispatchFence = anyOrNull(),
                sessionFence = anyOrNull(),
            )
        ).thenReturn(
            PushTokenProviderLease(
                outcome = PushTokenProviderLeaseOutcome.ACQUIRED,
                tokenId = 5,
                leaseId = "lease",
                providerToken = "raw-provider-token",
            )
        )
    }
}
