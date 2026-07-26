package com.noLate.auth.apple

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.atMost
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

@ExtendWith(MockitoExtension::class)
class AppleTokenRevocationSchedulerTest {
    @Mock
    lateinit var worker: AppleTokenRevocationWorker

    @Test
    fun `wake is nonblocking coalesced and provider work stays on scheduler thread`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workerThreads = mutableListOf<String>()
        doAnswer {
            synchronized(workerThreads) { workerThreads += Thread.currentThread().name }
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            Unit
        }.`when`(worker).runDue(any<() -> Boolean>())
        val scheduler = AppleTokenRevocationScheduler(
            properties = properties(),
            worker = worker,
        )
        scheduler.start()

        try {
            val startedAt = System.nanoTime()
            scheduler.wakeUp()
            repeat(100) { scheduler.wakeUp() }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue(elapsedMillis < 250, "wake-up unexpectedly blocked for ${elapsedMillis}ms")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(
                synchronized(workerThreads) {
                    workerThreads.all { it == "apple-token-revocation" }
                }
            )
        } finally {
            release.countDown()
            scheduler.stop()
        }

        verify(worker, timeout(2_000).atLeastOnce()).runDue(any<() -> Boolean>())
        verify(worker, atMost(2)).runDue(any<() -> Boolean>())
    }

    @Test
    fun `worker exception is isolated and stopped scheduler rejects later wake signals`() {
        val firstAttempt = CountDownLatch(1)
        val recoveredAttempt = CountDownLatch(1)
        val attempts = AtomicInteger()
        doAnswer {
            if (attempts.incrementAndGet() == 1) {
                firstAttempt.countDown()
                throw IllegalStateException("synthetic scheduler failure")
            }
            recoveredAttempt.countDown()
            Unit
        }.`when`(worker).runDue(any<() -> Boolean>())
        val scheduler = AppleTokenRevocationScheduler(
            properties = properties(),
            worker = worker,
        )
        scheduler.start()

        scheduler.wakeUp()
        assertTrue(firstAttempt.await(2, TimeUnit.SECONDS))
        scheduler.wakeUp()
        assertTrue(recoveredAttempt.await(2, TimeUnit.SECONDS))
        scheduler.stop()
        repeat(100) { scheduler.wakeUp() }
        Thread.sleep(100)

        assertTrue(!scheduler.isRunning)
        assertTrue(attempts.get() == 2)
    }

    @Test
    fun `stop is bounded, serializes restart, and fences late provider completion`() {
        val now = Instant.parse("2026-07-26T06:00:00Z")
        val properties = workerProperties()
        val cipher = AppleTokenCipher(properties)
        val oldEnvelope = cipher.encrypt("old-credential-key", "old-refresh")
        val newEnvelope = cipher.encrypt("new-credential-key", "new-refresh")
        val oldLease = AppleRevocationLease(
            credentialId = 91L,
            credentialKey = "old-credential-key",
            clientId = properties.clientId,
            encryptionKeyId = oldEnvelope.keyId,
            initializationVector = oldEnvelope.initializationVector,
            encryptedRefreshToken = oldEnvelope.ciphertext,
            attemptCount = 1,
            workerId = "old-worker",
        )
        val newLease = AppleRevocationLease(
            credentialId = 92L,
            credentialKey = "new-credential-key",
            clientId = properties.clientId,
            encryptionKeyId = newEnvelope.keyId,
            initializationVector = newEnvelope.initializationVector,
            encryptedRefreshToken = newEnvelope.ciphertext,
            attemptCount = 1,
            workerId = "new-worker",
        )
        val coordinator = mock<AppleTokenRevocationCoordinator>()
        val oauthClient = mock<AppleOAuthClient>()
        whenever(coordinator.claimNextDue(eq(now), any(), isNull()))
            .thenReturn(oldLease, newLease, null)
        val oldProviderEntered = CountDownLatch(1)
        val releaseOldProvider = CountDownLatch(1)
        val oldProviderExited = CountDownLatch(1)
        val newCompletion = CountDownLatch(1)
        doAnswer {
            oldProviderEntered.countDown()
            while (true) {
                try {
                    if (releaseOldProvider.await(25, TimeUnit.MILLISECONDS)) break
                } catch (_: InterruptedException) {
                    // Deliberately ignore shutdown interruption to exercise the generation fence.
                }
            }
            oldProviderExited.countDown()
            Unit
        }.`when`(oauthClient).revokeRefreshToken("old-refresh")
        whenever(coordinator.complete(eq(newLease), eq(now))).thenAnswer {
            newCompletion.countDown()
            true
        }
        val actualWorker = AppleTokenRevocationWorker(
            properties = properties,
            coordinator = coordinator,
            oauthClient = oauthClient,
            tokenCipher = cipher,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val scheduler = AppleTokenRevocationScheduler(properties, actualWorker)
        val lifecycleThreads = Executors.newFixedThreadPool(2)

        try {
            scheduler.start()
            scheduler.wakeUp()
            assertTrue(oldProviderEntered.await(2, TimeUnit.SECONDS))

            val stopStartedAt = System.nanoTime()
            val stopFuture = lifecycleThreads.submit { scheduler.stop() }
            waitUntil(1_000) { !scheduler.isRunning }
            val restartFuture = lifecycleThreads.submit { scheduler.start() }
            assertThrows<TimeoutException> {
                restartFuture.get(200, TimeUnit.MILLISECONDS)
            }

            stopFuture.get(3, TimeUnit.SECONDS)
            val stopMillis =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartedAt)
            assertTrue(stopMillis < 2_500, "stop exceeded its bound: ${stopMillis}ms")
            restartFuture.get(3, TimeUnit.SECONDS)
            assertTrue(scheduler.isRunning)

            scheduler.wakeUp()
            assertTrue(newCompletion.await(2, TimeUnit.SECONDS))
            releaseOldProvider.countDown()
            assertTrue(oldProviderExited.await(2, TimeUnit.SECONDS))
            Thread.sleep(100)

            verify(coordinator).complete(newLease, now)
            verify(coordinator, never()).complete(eq(oldLease), any())
            verify(coordinator, times(3)).claimNextDue(eq(now), any(), isNull())
        } finally {
            releaseOldProvider.countDown()
            scheduler.stop()
            lifecycleThreads.shutdownNow()
        }
    }

    private fun properties() =
        AppleTokenLifecycleProperties(
            enabled = true,
            workerEnabled = true,
            fixedDelayMillis = 86_400_000,
        )

    private fun workerProperties() =
        AppleTokenLifecycleProperties(
            enabled = true,
            clientId = "com.nolate.test",
            teamId = "TEAM123456",
            keyId = "KEY1234567",
            privateKey = "not-read-by-scheduler-test",
            currentEncryptionKeyId = "token-v1",
            currentEncryptionKey =
                Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
            workerEnabled = true,
            fixedDelayMillis = 86_400_000,
        )

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition did not become true before timeout" }
            Thread.sleep(10)
        }
    }
}
