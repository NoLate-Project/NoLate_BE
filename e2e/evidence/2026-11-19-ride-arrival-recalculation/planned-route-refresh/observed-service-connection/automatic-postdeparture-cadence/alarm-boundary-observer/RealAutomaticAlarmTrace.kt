package com.noLate.eta.simulator

import com.noLate.schedule.application.service.DepartureAlarmSyncExpiryScheduler
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.framework.ProxyFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Instant

/** Passive test-only boundaries: distinguish ETA mutations from the real alarm-expiry writer. */
internal class RealAutomaticAlarmTrace(
    private val scheduler: DepartureAlarmSyncExpiryScheduler,
    private val jdbc: JdbcTemplate,
    private val graceMinutes: Long,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val original = requireNotNull(ReflectionTestUtils.getField(scheduler, "writer"))
    private val events = mutableListOf<Map<String, Any?>>()
    private val beforeWorker = ThreadLocal<Map<String, Any?>>()

    private fun states(): List<Map<String, Any>> = jdbc.queryForList(
        "SELECT * FROM departure_alarm_sync_state WHERE member_id=296 AND schedule_id=363",
    )

    init {
        val proxy = ProxyFactory(original).apply {
            isProxyTargetClass = true
            addAdvice(MethodInterceptor { invocation ->
                if (invocation.method.name != "cancelExpired" || invocation.arguments.size != 2)
                    return@MethodInterceptor invocation.proceed()
                val stateId = invocation.arguments[0] as Long
                val before = states()
                val relevant = before.any { (it["id"] as? Number)?.toLong() == stateId }
                val startedAt = Instant.now(clock)
                // Delegate exactly once, preserving the real transactional writer and its cutoff.
                invocation.proceed().also {
                    if (relevant) synchronized(events) {
                        check(events.size < 8) { "Bounded alarm lifecycle trace exceeded" }
                        events += mapOf(
                            "kind" to "GRACE_EXPIRY_WRITER", "method" to "DepartureAlarmSyncExpiryWriter.cancelExpired",
                            "stateId" to stateId, "cutoffAt" to invocation.arguments[1],
                            "configuredGraceMinutes" to graceMinutes.coerceAtLeast(1),
                            "startedAt" to startedAt, "finishedAt" to Instant.now(clock),
                            "executionThread" to Thread.currentThread().name,
                            "before" to before, "after" to states(),
                        )
                    }
                }
            })
        }.proxy
        ReflectionTestUtils.setField(scheduler, "writer", proxy)
    }

    fun beforeTick(thread: String) {
        check(thread == Thread.currentThread().name && thread.startsWith("nolate-scheduler-"))
        beforeWorker.set(mapOf("observedAt" to Instant.now(clock), "executionThread" to thread, "states" to states()))
    }

    fun tickBoundary(thread: String?): Map<String, Any?> {
        val before = requireNotNull(beforeWorker.get()) { "Actual ETA invocation boundary missing" }
        check(thread == before["executionThread"] && thread == Thread.currentThread().name)
        return mapOf("before" to before, "after" to mapOf(
            "observedAt" to Instant.now(clock), "executionThread" to thread, "states" to states(),
        ))
    }

    fun snapshot(): List<Map<String, Any?>> = synchronized(events) { events.toList() }

    override fun close() {
        ReflectionTestUtils.setField(scheduler, "writer", original)
        beforeWorker.remove()
    }
}
