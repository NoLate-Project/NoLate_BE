package com.noLate.notification.liveactivity.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LiveActivityDispatchSchedulerTest {
    @Test
    fun `concurrent commit signals coalesce without dropping a trailing dispatch pass`() {
        val worker = mock<LiveActivityDispatchWorker>()
        val firstPassEntered = CountDownLatch(1)
        val releaseFirstPass = CountDownLatch(1)
        val secondPassFinished = CountDownLatch(1)
        val calls = AtomicInteger()
        whenever(worker.dispatchDue()).thenAnswer {
            when (calls.incrementAndGet()) {
                1 -> {
                    firstPassEntered.countDown()
                    check(releaseFirstPass.await(2, TimeUnit.SECONDS))
                }
                2 -> secondPassFinished.countDown()
            }
            LiveActivityDispatchRunResult(0, false)
        }
        whenever(worker.recoverStaleDispatches()).thenReturn(LiveActivityStaleRecoveryResult(0))
        val scheduler = scheduler(worker)

        try {
            scheduler.start()
            assertThat(firstPassEntered.await(2, TimeUnit.SECONDS)).isTrue()

            repeat(100) { scheduler.wakeUp() }
            releaseFirstPass.countDown()

            assertThat(secondPassFinished.await(2, TimeUnit.SECONDS)).isTrue()
            Thread.sleep(100)
            assertThat(calls.get()).isEqualTo(2)
        } finally {
            releaseFirstPass.countDown()
            scheduler.stop()
        }
    }

    @Test
    fun `different retry times all wake at their persisted availability without fallback polling`() {
        val worker = mock<LiveActivityDispatchWorker>()
        val calls = AtomicInteger()
        val firstPassNanos = AtomicLong()
        val firstRetryPassNanos = AtomicLong()
        val secondRetryPassNanos = AtomicLong()
        val retryPassesFinished = CountDownLatch(2)
        whenever(worker.dispatchDue()).thenAnswer {
            when (calls.incrementAndGet()) {
                1 -> {
                    firstPassNanos.set(System.nanoTime())
                    val now = Instant.now()
                    LiveActivityDispatchRunResult(
                        processedCount = 2,
                        batchExhausted = false,
                        retryTimes = setOf(
                            now.plusMillis(RETRY_DELAY_MILLIS),
                            now.plusMillis(RETRY_DELAY_MILLIS * 2),
                        ),
                    )
                }
                2 -> {
                    firstRetryPassNanos.set(System.nanoTime())
                    retryPassesFinished.countDown()
                    LiveActivityDispatchRunResult(0, false)
                }
                else -> {
                    secondRetryPassNanos.set(System.nanoTime())
                    retryPassesFinished.countDown()
                    LiveActivityDispatchRunResult(0, false)
                }
            }
        }
        whenever(worker.recoverStaleDispatches()).thenReturn(LiveActivityStaleRecoveryResult(0))
        val scheduler = scheduler(worker)

        try {
            scheduler.start()

            assertThat(retryPassesFinished.await(2, TimeUnit.SECONDS)).isTrue()
            val firstElapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                firstRetryPassNanos.get() - firstPassNanos.get(),
            )
            val secondElapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                secondRetryPassNanos.get() - firstPassNanos.get(),
            )
            assertThat(firstElapsedMillis).isGreaterThanOrEqualTo(RETRY_DELAY_MILLIS - 30)
            assertThat(secondElapsedMillis).isGreaterThanOrEqualTo(RETRY_DELAY_MILLIS * 2 - 30)
            assertThat(calls.get()).isEqualTo(3)
        } finally {
            scheduler.stop()
        }
    }

    @Test
    fun `a full batch schedules another immediate pass`() {
        val worker = mock<LiveActivityDispatchWorker>()
        val calls = AtomicInteger()
        val secondPassFinished = CountDownLatch(1)
        whenever(worker.dispatchDue()).thenAnswer {
            if (calls.incrementAndGet() == 1) {
                LiveActivityDispatchRunResult(processedCount = 50, batchExhausted = true)
            } else {
                secondPassFinished.countDown()
                LiveActivityDispatchRunResult(processedCount = 0, batchExhausted = false)
            }
        }
        whenever(worker.recoverStaleDispatches()).thenReturn(LiveActivityStaleRecoveryResult(0))
        val scheduler = scheduler(worker)

        try {
            scheduler.start()

            assertThat(secondPassFinished.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(calls.get()).isEqualTo(2)
        } finally {
            scheduler.stop()
        }
    }

    private fun scheduler(worker: LiveActivityDispatchWorker) = LiveActivityDispatchScheduler(
        worker = worker,
        clock = Clock.systemUTC(),
        enabled = true,
        fallbackScanMillis = 60_000,
        staleRecoveryMillis = 60_000,
    )

    private companion object {
        const val RETRY_DELAY_MILLIS = 150L
    }
}
