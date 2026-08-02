package com.noLate.notification.application.service

import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class PushOutboxDispatchWorkerUnitTest {

    @Mock
    lateinit var notificationUseCase: NotificationUseCase

    @Mock
    lateinit var coordinator: PushOutboxDispatchCoordinator

    private val now = Instant.parse("2026-07-24T04:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `disabled outbox worker does not recover or claim events`() {
        assertEquals(0, worker(enabled = false).runDueEvents(now))

        verify(coordinator, never()).recoverStale(any(), any(), any())
        verify(coordinator, never()).claimNextDue(any(), any())
        verify(notificationUseCase, never()).redrivePersistedEvent(any(), any(), any())
    }

    @Test
    fun `confirmed provider failure is retried with the same event before terminal success`() {
        val firstLease = lease(attempt = 1)
        val secondLease = lease(attempt = 2)
        whenever(coordinator.recoverStale(any(), any(), any())).thenReturn(0)
        whenever(coordinator.claimNextDue(any(), any()))
            .thenReturn(firstLease, null, secondLease, null)
        whenever(notificationUseCase.redrivePersistedEvent(31L, "event:durable", firstLease))
            .thenReturn(
                NotificationSendResult(
                    requestedCount = 1,
                    failedCount = 1,
                    retryableFailedCount = 1,
                )
            )
        whenever(notificationUseCase.redrivePersistedEvent(31L, "event:durable", secondLease))
            .thenReturn(
                NotificationSendResult(
                    requestedCount = 1,
                    sentCount = 1,
                ),
            )
        whenever(coordinator.retry(any(), any(), any())).thenReturn(true)
        whenever(coordinator.complete(any(), any())).thenReturn(true)
        val worker = worker()

        assertEquals(1, worker.runDueEvents(now))
        assertEquals(1, worker.runDueEvents(now.plusSeconds(60)))

        verify(coordinator).retry(
            eq(firstLease),
            eq(now.plusSeconds(60)),
            eq("CONFIRMED_PROVIDER_FAILURE"),
        )
        verify(coordinator).complete(secondLease, now.plusSeconds(60))
        verify(coordinator, never()).fail(any(), any(), any())
        verify(notificationUseCase)
            .redrivePersistedEvent(31L, "event:durable", firstLease)
        verify(notificationUseCase)
            .redrivePersistedEvent(31L, "event:durable", secondLease)
    }

    @Test
    fun `permanent invalid token is a terminal manifest outcome and is not retried`() {
        val lease = lease(attempt = 1)
        whenever(coordinator.recoverStale(any(), any(), any())).thenReturn(0)
        whenever(coordinator.claimNextDue(any(), any())).thenReturn(lease, null)
        whenever(notificationUseCase.redrivePersistedEvent(31L, "event:durable", lease))
            .thenReturn(
                NotificationSendResult(
                    requestedCount = 1,
                    failedCount = 1,
                    invalidTokenCount = 1,
                )
            )
        whenever(coordinator.complete(any(), any())).thenReturn(true)

        assertEquals(1, worker().runDueEvents(now))

        verify(coordinator).complete(lease, now)
        verify(coordinator, never()).retry(any(), any(), any())
        verify(coordinator, never()).fail(any(), any(), any())
    }

    @Test
    fun `authorization superseded sharing manifest completes instead of reopening the outbox`() {
        val lease = lease(attempt = 1)
        whenever(coordinator.recoverStale(any(), any(), any())).thenReturn(0)
        whenever(coordinator.claimNextDue(any(), any())).thenReturn(lease, null)
        whenever(notificationUseCase.redrivePersistedEvent(31L, "event:durable", lease))
            .thenReturn(
                NotificationSendResult(
                    requestedCount = 1,
                    failedCount = 1,
                    supersededCount = 1,
                )
            )
        whenever(coordinator.complete(any(), any())).thenReturn(true)

        assertEquals(1, worker().runDueEvents(now))

        verify(coordinator).complete(lease, now)
        verify(coordinator, never()).retry(any(), any(), any())
        verify(coordinator, never()).fail(any(), any(), any())
    }

    @Test
    fun `zero-recipient frozen manifest completes without inventing a future recipient`() {
        val lease = lease(attempt = 1, recipientCount = 0)
        whenever(coordinator.recoverStale(any(), any(), any())).thenReturn(0)
        whenever(coordinator.claimNextDue(any(), any())).thenReturn(lease, null)
        whenever(notificationUseCase.redrivePersistedEvent(31L, "event:durable", lease))
            .thenReturn(NotificationSendResult(requestedCount = 0))
        whenever(coordinator.complete(any(), any())).thenReturn(true)

        assertEquals(1, worker().runDueEvents(now))

        verify(coordinator).complete(lease, now)
        verify(coordinator, never()).retry(any(), any(), any())
    }

    @Test
    fun `redrive exception reaches bounded max attempts and becomes failed`() {
        val lease = lease(attempt = 2, failureCount = 1)
        whenever(coordinator.recoverStale(any(), any(), any())).thenReturn(0)
        whenever(coordinator.claimNextDue(any(), any())).thenReturn(lease, null)
        whenever(notificationUseCase.redrivePersistedEvent(any(), any(), any()))
            .thenThrow(IllegalStateException("opaque provider token must not be persisted"))
        whenever(coordinator.fail(any(), any(), any())).thenReturn(true)

        assertEquals(1, worker(maxAttempts = 2).runDueEvents(now))

        verify(coordinator).fail(
            eq(lease),
            eq(now),
            eq("REDRIVE_IllegalStateException"),
        )
        verify(coordinator, never()).retry(any(), any(), any())
    }

    @Test
    fun `one run claims at most configured batch size and never leases the tail early`() {
        val first = lease(notificationId = 1, eventKey = "event:1")
        val second = lease(notificationId = 2, eventKey = "event:2")
        val third = lease(notificationId = 3, eventKey = "event:3")
        whenever(coordinator.recoverStale(any(), any(), any())).thenReturn(0)
        whenever(coordinator.claimNextDue(any(), any())).thenReturn(first, second, third)
        whenever(notificationUseCase.redrivePersistedEvent(any(), any(), any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))
        whenever(coordinator.complete(any(), any())).thenReturn(true)

        assertEquals(2, worker(batchSize = 2).runDueEvents(now))

        verify(coordinator, times(2)).claimNextDue(eq(now), any())
        verify(notificationUseCase, times(2)).redrivePersistedEvent(any(), any(), any())
    }

    @Test
    fun `slow provider does not backdate the next tail lease`() {
        val later = now.plusSeconds(601)
        val advancingClock = AdvancingPushOutboxClock(now)
        val first = lease(notificationId = 1, eventKey = "event:slow")
        val second = lease(notificationId = 2, eventKey = "event:tail")
        whenever(coordinator.recoverStale(eq(now), any(), any())).thenReturn(0)
        whenever(coordinator.claimNextDue(eq(now), any())).thenReturn(first)
        whenever(coordinator.claimNextDue(eq(later), any())).thenReturn(second, null)
        whenever(notificationUseCase.redrivePersistedEvent(31L, "event:slow", first))
            .thenAnswer {
                advancingClock.advanceTo(later)
                NotificationSendResult(requestedCount = 1, sentCount = 1)
            }
        whenever(notificationUseCase.redrivePersistedEvent(31L, "event:tail", second))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))
        whenever(coordinator.complete(any(), any())).thenReturn(true)

        val worker = PushOutboxDispatchWorker(
            notificationUseCase = notificationUseCase,
            coordinator = coordinator,
            clock = advancingClock,
            enabled = true,
            batchSize = 2,
            maxAttempts = 3,
            retryDelaySeconds = 60,
            processingTimeoutSeconds = 600,
        )

        assertEquals(2, worker.runDueEvents(now))

        verify(coordinator).claimNextDue(eq(now), any())
        verify(coordinator).claimNextDue(eq(later), any())
        verify(coordinator).complete(first, later)
        verify(coordinator).complete(second, later)
    }

    private fun worker(
        enabled: Boolean = true,
        batchSize: Int = 10,
        maxAttempts: Int = 3,
    ): PushOutboxDispatchWorker =
        PushOutboxDispatchWorker(
            notificationUseCase = notificationUseCase,
            coordinator = coordinator,
            clock = clock,
            enabled = enabled,
            batchSize = batchSize,
            maxAttempts = maxAttempts,
            retryDelaySeconds = 60,
            processingTimeoutSeconds = 600,
        )

    private fun lease(
        notificationId: Long = 10,
        eventKey: String = "event:durable",
        attempt: Int = 1,
        failureCount: Int = 0,
        recipientCount: Int = 1,
    ): PushOutboxDispatchLease =
        PushOutboxDispatchLease(
            notificationId = notificationId,
            memberId = 31,
            logicalEventKey = eventKey,
            manifestRecipientCount = recipientCount,
            attemptCount = attempt,
            failureCount = failureCount,
            workerId = "test-worker",
        )
}

private class AdvancingPushOutboxClock(
    private var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current

    fun advanceTo(next: Instant) {
        current = next
    }
}
