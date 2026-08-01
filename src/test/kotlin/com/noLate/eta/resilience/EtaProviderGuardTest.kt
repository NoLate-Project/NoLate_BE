package com.noLate.eta.resilience

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class EtaProviderGuardTest {
    @Test
    fun `provider마다 bulkhead가 격리되어 ODsay 포화가 TAGO를 막지 않는다`() {
        val harness = harness(policy(maxConcurrentCalls = 1, maxQueuedCalls = 0))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val occupied = executor.submit<String> {
                harness.guard.execute("odsay") {
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    "odsay"
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            assertThrows(EtaProviderBulkheadRejectedException::class.java) {
                harness.guard.execute("ODSay") { "must not run" }
            }
            assertEquals("tago", harness.guard.execute("tago") { "tago" })

            release.countDown()
            assertEquals("odsay", occupied.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `동시 실행과 대기열 모두 설정한 상한을 넘지 않는다`() {
        val harness = harness(
            policy(
                maxConcurrentCalls = 1,
                maxQueuedCalls = 1,
                maxQueueWait = Duration.ofSeconds(2),
            )
        )
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val invocations = AtomicInteger()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val guardedOperation = {
            val invocation = invocations.incrementAndGet()
            val current = active.incrementAndGet()
            maximumActive.accumulateAndGet(current, ::maxOf)
            try {
                if (invocation == 1) {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                }
                invocation
            } finally {
                active.decrementAndGet()
            }
        }

        try {
            val first = executor.submit<Int> { harness.guard.execute("odsay", guardedOperation) }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val queued = executor.submit<Int> { harness.guard.execute("odsay", guardedOperation) }
            awaitCondition { harness.guard.snapshot("odsay").queuedCalls == 1 }

            assertThrows(EtaProviderBulkheadRejectedException::class.java) {
                harness.guard.execute("odsay", guardedOperation)
            }
            assertEquals(1, harness.guard.snapshot("odsay").activeCalls)
            assertEquals(1, harness.guard.snapshot("odsay").queuedCalls)

            releaseFirst.countDown()
            assertEquals(1, first.get(2, TimeUnit.SECONDS))
            assertEquals(2, queued.get(2, TimeUnit.SECONDS))
            assertEquals(1, maximumActive.get())
            assertEquals(0, harness.guard.snapshot("odsay").activeCalls)
            assertEquals(0, harness.guard.snapshot("odsay").queuedCalls)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `연속 실패 임계치에서 circuit을 열고 open 경계 시각에 단 하나의 probe를 허용한다`() {
        val harness = harness(policy(failureThreshold = 2, openDuration = Duration.ofSeconds(30)))
        val calls = AtomicInteger()

        repeat(2) {
            assertThrows(IllegalStateException::class.java) {
                harness.guard.execute("odsay") {
                    calls.incrementAndGet()
                    error("provider down")
                }
            }
        }
        val opened = harness.guard.snapshot("odsay")
        assertEquals(EtaProviderCircuitState.OPEN, opened.circuitState)
        assertEquals(2, opened.consecutiveFailures)

        assertThrows(EtaProviderCircuitOpenException::class.java) {
            harness.guard.execute("odsay") { calls.incrementAndGet() }
        }
        assertEquals(2, calls.get())

        harness.clock.advance(Duration.ofSeconds(30))
        val probeEntered = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val probe = executor.submit<String> {
                harness.guard.execute("odsay") {
                    probeEntered.countDown()
                    assertTrue(releaseProbe.await(2, TimeUnit.SECONDS))
                    "recovered"
                }
            }
            assertTrue(probeEntered.await(2, TimeUnit.SECONDS))
            assertEquals(EtaProviderCircuitState.HALF_OPEN, harness.guard.snapshot("odsay").circuitState)
            assertThrows(EtaProviderCircuitOpenException::class.java) {
                harness.guard.execute("odsay") { "second probe" }
            }
            releaseProbe.countDown()
            assertEquals("recovered", probe.get(2, TimeUnit.SECONDS))
            assertEquals(EtaProviderCircuitState.CLOSED, harness.guard.snapshot("odsay").circuitState)
            assertEquals(0, harness.guard.snapshot("odsay").consecutiveFailures)
        } finally {
            releaseProbe.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `half open probe 실패는 현재 clock을 기준으로 circuit을 다시 연다`() {
        val harness = harness(policy(failureThreshold = 1, openDuration = Duration.ofSeconds(10)))
        assertThrows(IllegalStateException::class.java) {
            harness.guard.execute("odsay") { error("first") }
        }
        harness.clock.advance(Duration.ofSeconds(10))

        assertThrows(IllegalArgumentException::class.java) {
            harness.guard.execute("odsay") { throw IllegalArgumentException("probe") }
        }

        val reopened = harness.guard.snapshot("odsay")
        assertEquals(EtaProviderCircuitState.OPEN, reopened.circuitState)
        assertEquals(harness.clock.instant().plusSeconds(10), reopened.openUntil)
    }

    @Test
    fun `half open probe가 provider와 무관하게 취소되어도 circuit이 고착되지 않는다`() {
        val harness = harness(policy(failureThreshold = 1, openDuration = Duration.ofSeconds(10)))
        assertThrows(IllegalStateException::class.java) {
            harness.guard.execute("odsay") { error("first") }
        }
        harness.clock.advance(Duration.ofSeconds(10))

        assertThrows(CancellationException::class.java) {
            harness.guard.execute("odsay") { throw CancellationException("worker stopped") }
        }
        assertEquals(EtaProviderCircuitState.OPEN, harness.guard.snapshot("odsay").circuitState)
        assertEquals("replacement probe", harness.guard.execute("odsay") { "replacement probe" })
        assertEquals(EtaProviderCircuitState.CLOSED, harness.guard.snapshot("odsay").circuitState)
    }

    @Test
    fun `성공은 closed circuit의 연속 실패 횟수를 초기화한다`() {
        val harness = harness(policy(failureThreshold = 2))
        assertThrows(IllegalStateException::class.java) {
            harness.guard.execute("odsay") { error("one") }
        }
        assertEquals(1, harness.guard.snapshot("odsay").consecutiveFailures)

        assertEquals("ok", harness.guard.execute("odsay") { "ok" })
        assertThrows(IllegalStateException::class.java) {
            harness.guard.execute("odsay") { error("one again") }
        }

        val snapshot = harness.guard.snapshot("odsay")
        assertEquals(EtaProviderCircuitState.CLOSED, snapshot.circuitState)
        assertEquals(1, snapshot.consecutiveFailures)
    }

    @Test
    fun `bulkhead 거절은 provider 장애가 아니므로 circuit 실패로 집계하지 않는다`() {
        val harness = harness(policy(maxConcurrentCalls = 1, maxQueuedCalls = 0, failureThreshold = 1))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val occupied = executor.submit<Unit> {
                harness.guard.execute("odsay") {
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertThrows(EtaProviderBulkheadRejectedException::class.java) {
                harness.guard.execute("odsay") { Unit }
            }
            val snapshot = harness.guard.snapshot("odsay")
            assertEquals(EtaProviderCircuitState.CLOSED, snapshot.circuitState)
            assertEquals(0, snapshot.consecutiveFailures)
            release.countDown()
            occupied.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `bulkhead 대기는 provider 설정값보다 현재 calculation 남은 budget을 우선한다`() {
        val harness = harness(
            policy(
                maxConcurrentCalls = 1,
                maxQueuedCalls = 1,
                maxQueueWait = Duration.ofSeconds(2),
            )
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val occupied = executor.submit<Unit> {
                harness.guard.execute("odsay") {
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            val startedAt = System.nanoTime()
            harness.deadline.within(Duration.ofNanos(1)) {
                assertThrows(EtaProviderBulkheadRejectedException::class.java) {
                    harness.guard.execute("odsay") { error("must not run") }
                }
            }
            val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
            assertTrue(elapsed < Duration.ofSeconds(1), "elapsed=$elapsed")
            assertEquals(0, harness.guard.snapshot("odsay").queuedCalls)

            release.countDown()
            occupied.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `deadline이 지난 뒤에는 provider operation을 시작하지 않는다`() {
        val harness = harness(policy())
        var invoked = false

        assertThrows(EtaSoftDeadlineExceededException::class.java) {
            harness.deadline.within(Duration.ofMillis(50)) {
                harness.ticker.advance(Duration.ofMillis(50))
                harness.guard.execute("odsay") {
                    invoked = true
                }
            }
        }

        assertFalse(invoked)
        assertEquals(EtaProviderCircuitState.CLOSED, harness.guard.snapshot("odsay").circuitState)
    }

    @Test
    fun `provider가 deadline 뒤 성공하면 늦은 값만 버리고 circuit은 정상으로 유지한다`() {
        val events = Collections.synchronizedList(mutableListOf<EtaProviderGuardEvent>())
        val harness = harness(
            policy = policy(failureThreshold = 1),
            observers = listOf(EtaProviderGuardObserver(events::add)),
        )

        assertThrows(EtaSoftDeadlineExceededException::class.java) {
            harness.deadline.within(Duration.ofMillis(50)) {
                harness.guard.execute("odsay") {
                    harness.ticker.advance(Duration.ofMillis(51))
                    "late but provider-successful"
                }
            }
        }

        val snapshot = harness.guard.snapshot("odsay")
        assertEquals(EtaProviderCircuitState.CLOSED, snapshot.circuitState)
        assertEquals(0, snapshot.consecutiveFailures)
        assertEquals(listOf(EtaProviderGuardOutcome.DEADLINE_EXCEEDED), events.map { it.outcome })
    }

    @Test
    fun `observer 장애가 실제 provider 결과를 바꾸지 않는다`() {
        val harness = harness(
            policy(),
            observers = listOf(EtaProviderGuardObserver { error("metrics unavailable") }),
        )

        assertEquals(42, harness.guard.execute("odsay") { 42 })
    }

    @Test
    fun `provider id와 policy 설정값을 유한한 범위로 제한한다`() {
        val harness = harness(policy())
        assertThrows(IllegalArgumentException::class.java) {
            harness.guard.execute("bad/provider") { Unit }
        }
        assertThrows(IllegalArgumentException::class.java) {
            EtaProviderResiliencePolicy(
                maxConcurrentCalls = 1,
                maxQueuedCalls = 0,
                maxQueueWait = Duration.ofSeconds(6),
                failureThreshold = 1,
                openDuration = Duration.ofSeconds(1),
            )
        }
    }

    private fun harness(
        policy: EtaProviderResiliencePolicy,
        observers: List<EtaProviderGuardObserver> = emptyList(),
    ): GuardHarness {
        val ticker = MutableEtaTicker()
        val deadline = EtaCalculationDeadline(Duration.ofSeconds(5), ticker)
        val clock = MutableEtaClock(Instant.parse("2026-08-01T00:00:00Z"))
        return GuardHarness(
            ticker = ticker,
            deadline = deadline,
            clock = clock,
            guard = EtaProviderGuard(
                policyResolver = StaticEtaProviderResiliencePolicyResolver(policy),
                calculationDeadline = deadline,
                clock = clock,
                ticker = ticker,
                observers = observers,
            ),
        )
    }

    private fun policy(
        maxConcurrentCalls: Int = 2,
        maxQueuedCalls: Int = 2,
        maxQueueWait: Duration = Duration.ZERO,
        failureThreshold: Int = 3,
        openDuration: Duration = Duration.ofSeconds(30),
    ) = EtaProviderResiliencePolicy(
        maxConcurrentCalls = maxConcurrentCalls,
        maxQueuedCalls = maxQueuedCalls,
        maxQueueWait = maxQueueWait,
        failureThreshold = failureThreshold,
        openDuration = openDuration,
    )

    private fun awaitCondition(condition: () -> Boolean) {
        val realDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition()) {
            check(System.nanoTime() < realDeadline) { "condition did not become true" }
            Thread.onSpinWait()
        }
    }
}

private data class GuardHarness(
    val ticker: MutableEtaTicker,
    val deadline: EtaCalculationDeadline,
    val clock: MutableEtaClock,
    val guard: EtaProviderGuard,
)

private class MutableEtaClock(
    initial: Instant,
) : Clock() {
    private val now = AtomicReference(initial)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now.get()

    fun advance(duration: Duration) {
        now.updateAndGet { it.plus(duration) }
    }
}
