package com.noLate.auth.apple

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.atMost
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        }.`when`(worker).runDue()
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

        verify(worker, timeout(2_000).atLeastOnce()).runDue()
        verify(worker, atMost(2)).runDue()
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
        }.`when`(worker).runDue()
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

    private fun properties() =
        AppleTokenLifecycleProperties(
            enabled = true,
            workerEnabled = true,
            fixedDelayMillis = 86_400_000,
        )
}
