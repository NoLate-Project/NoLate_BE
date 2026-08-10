package com.noLate.notification.liveactivity.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryOperation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LiveActivityDispatchWorkerTest {
    @Test
    fun `missing provider performs no durable outbox query`() {
        val provider = mock<ObjectProvider<LiveActivityPushClient>>()
        val coordinator = mock<LiveActivityDispatchCoordinator>()
        whenever(provider.getIfAvailable()).thenReturn(null)
        val worker = worker(provider, coordinator)

        assertThat(worker.dispatchDue()).isEqualTo(LiveActivityDispatchRunResult(0, false))
        assertThat(worker.recoverStaleDispatches()).isEqualTo(LiveActivityStaleRecoveryResult(0))
        verifyNoInteractions(coordinator)
    }

    @Test
    fun `one batch retains every independently persisted retry time`() {
        val provider = mock<ObjectProvider<LiveActivityPushClient>>()
        val client = mock<LiveActivityPushClient>()
        val coordinator = mock<LiveActivityDispatchCoordinator>()
        val first = lease(1)
        val second = lease(2)
        val firstRetryAt = NOW.plusSeconds(30)
        val secondRetryAt = NOW.plusSeconds(60)
        whenever(provider.getIfAvailable()).thenReturn(client)
        whenever(coordinator.claimNext(any())).thenReturn(first, second, null)
        whenever(client.send(any())).thenThrow(RetryableLiveActivityDeliveryException("retry"))
        whenever(
            coordinator.completeRetryableFailure(eq(first), eq("PROVIDER_THROTTLED"), any()),
        ).thenReturn(firstRetryAt)
        whenever(
            coordinator.completeRetryableFailure(eq(second), eq("PROVIDER_THROTTLED"), any()),
        ).thenReturn(secondRetryAt)

        val result = worker(provider, coordinator).dispatchDue()

        assertThat(result.processedCount).isEqualTo(2)
        assertThat(result.batchExhausted).isFalse()
        assertThat(result.retryTimes).containsExactlyInAnyOrder(firstRetryAt, secondRetryAt)
    }

    private fun worker(
        provider: ObjectProvider<LiveActivityPushClient>,
        coordinator: LiveActivityDispatchCoordinator,
    ) = LiveActivityDispatchWorker(
        clientProvider = provider,
        coordinator = coordinator,
        notificationTokenService = mock<NotificationTokenService>(),
        objectMapper = jacksonObjectMapper(),
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        enabled = true,
        batchSize = 50,
    )

    private fun lease(id: Long) = LiveActivityDispatchLease(
        deliveryId = id,
        leaseId = "lease-$id",
        memberId = 7,
        fcmTokenId = 11,
        fcmTokenFingerprint = "a".repeat(64),
        fcmOwnershipVersion = 0,
        fcmRegistrationToken = "fcm-$id",
        activityToken = "activity-token-$id",
        operation = LiveActivityDeliveryOperation.UPDATE,
        timestamp = NOW.epochSecond,
        activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
        attributesJson = null,
        contentStateJson = "{}",
        alertTitle = null,
        alertBody = null,
        staleAt = null,
        dismissalAt = null,
        expirationAt = NOW.plusSeconds(3_600),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-11T00:00:00Z")
    }
}
