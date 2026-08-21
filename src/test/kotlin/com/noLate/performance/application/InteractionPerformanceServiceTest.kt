package com.noLate.performance.application

import com.noLate.global.error.BusinessException
import com.noLate.performance.domain.InteractionPerformanceKind
import com.noLate.performance.domain.InteractionPerformanceOutcome
import com.noLate.performance.domain.NavigationPerformancePlatform
import com.noLate.performance.infrastructure.InteractionPerformanceEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class InteractionPerformanceServiceTest {
    @Mock
    lateinit var repository: InteractionPerformanceEventRepository

    private val now = Instant.parse("2026-08-17T00:00:00Z")

    @Test
    fun `normalizes route and stores content-free operation telemetry`() {
        whenever(repository.findAllById(any<Iterable<String>>())).thenReturn(emptyList())
        val service = InteractionPerformanceService(repository, Clock.fixed(now, ZoneOffset.UTC))

        val result = service.recordBatch(
            memberId = 7,
            samples = listOf(sample(route = "/schedule/739?source=push")),
        )

        assertEquals(1, result.acceptedCount)
        assertEquals(1, result.storedCount)
        val events = argumentCaptor<Iterable<com.noLate.performance.domain.InteractionPerformanceEvent>>()
        verify(repository).saveAll(events.capture())
        val stored = events.firstValue.single()
        assertEquals(7, stored.memberId)
        assertEquals("/schedule/[id]", stored.route)
        assertEquals("schedule.content_ready", stored.operationName)
        assertEquals(now.plusSeconds(90L * 24 * 60 * 60), stored.expiresAt)
    }

    @Test
    fun `rejects invalid operation names and excessive durations`() {
        val service = InteractionPerformanceService(repository, Clock.fixed(now, ZoneOffset.UTC))

        assertThrows(BusinessException::class.java) {
            service.recordBatch(7, listOf(sample(operation = "User entered secret")))
        }
        assertThrows(BusinessException::class.java) {
            service.recordBatch(7, listOf(sample(operation = "customer_name")))
        }
        assertThrows(BusinessException::class.java) {
            service.recordBatch(7, listOf(sample(durationMs = 120_001)))
        }
        verifyNoInteractions(repository)
    }

    private fun sample(
        route: String = "/schedule",
        operation: String = "schedule.content_ready",
        durationMs: Int = 180,
    ) = InteractionPerformanceSample(
        eventId = "11111111-1111-4111-8111-111111111111",
        route = route,
        operation = operation,
        kind = InteractionPerformanceKind.CONTENT_READY,
        outcome = InteractionPerformanceOutcome.SUCCESS,
        durationMs = durationMs,
        platform = NavigationPerformancePlatform.IOS,
        appVersion = "1.2.0",
        buildVersion = "50",
        occurredAt = Instant.parse("2026-08-17T00:00:01Z"),
    )
}
