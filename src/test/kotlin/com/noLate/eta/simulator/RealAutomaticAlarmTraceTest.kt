package com.noLate.eta.simulator

import com.noLate.schedule.application.service.DepartureAlarmSyncExpiryScheduler
import com.noLate.schedule.application.service.DepartureAlarmSyncExpiryWriter
import com.noLate.schedule.infrastructure.DepartureAlarmSyncStateRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RealAutomaticAlarmTraceTest {
    @Test
    fun `실제 만료 writer를 한번 호출하고 ETA 경계와 별도 기록한다`() {
        val now = Instant.parse("2026-11-20T12:18:11Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val writer = mock<DepartureAlarmSyncExpiryWriter>()
        val scheduler = DepartureAlarmSyncExpiryScheduler(mock<DepartureAlarmSyncStateRepository>(), writer, clock = clock)
        val jdbc = mock<JdbcTemplate>()
        val before = listOf(mapOf<String, Any>("id" to 1L, "operation" to "UPSERT", "generation" to 1L))
        val after = listOf(mapOf<String, Any>("id" to 1L, "operation" to "CANCEL", "generation" to 2L))
        whenever(jdbc.queryForList(any<String>())).thenReturn(before, after, after, after)
        val originalThread = Thread.currentThread().name
        val trace = RealAutomaticAlarmTrace(scheduler, jdbc, 10, clock)
        try {
            val observed = ReflectionTestUtils.getField(scheduler, "writer") as DepartureAlarmSyncExpiryWriter
            observed.cancelExpired(1L, now.minusSeconds(600))
            verify(writer).cancelExpired(1L, now.minusSeconds(600))
            val event = trace.snapshot().single()
            assertEquals(before, event["before"])
            assertEquals(after, event["after"])
            assertEquals("GRACE_EXPIRY_WRITER", event["kind"])
            assertEquals(now.minusSeconds(600), event["cutoffAt"])
            Thread.currentThread().name = "nolate-scheduler-controlled-test"
            trace.beforeTick(Thread.currentThread().name)
            val boundary = trace.tickBoundary(Thread.currentThread().name)
            assertEquals(after, (boundary["before"] as Map<*, *>)["states"])
            assertEquals(after, (boundary["after"] as Map<*, *>)["states"])
        } finally {
            Thread.currentThread().name = originalThread
            trace.close()
        }
        assertSame(writer, ReflectionTestUtils.getField(scheduler, "writer"))
    }
}
