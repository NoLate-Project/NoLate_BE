package com.noLate.performance.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.performance.domain.NavigationPerformanceCompletionKind
import com.noLate.performance.domain.NavigationPerformanceEvent
import com.noLate.performance.domain.NavigationPerformancePlatform
import com.noLate.performance.infrastructure.NavigationPerformanceEventRepository
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
class NavigationPerformanceServiceTest {
    @Mock
    lateinit var repository: NavigationPerformanceEventRepository

    private val now = Instant.parse("2026-08-04T03:00:00Z")

    @Test
    fun `batch storage is idempotent and strips dynamic identifiers`() {
        whenever(repository.findAllById(any<Iterable<String>>())).thenReturn(emptyList())
        val service = NavigationPerformanceService(
            repository,
            Clock.fixed(now, ZoneOffset.UTC),
        )

        val result = service.recordBatch(
            memberId = 7,
            samples = listOf(sample(toRoute = "/schedule/739?source=push")),
        )

        assertEquals(1, result.acceptedCount)
        assertEquals(1, result.storedCount)
        val captor = argumentCaptor<Iterable<NavigationPerformanceEvent>>()
        verify(repository).saveAll(captor.capture())
        val stored = captor.firstValue.single()
        assertEquals("/schedule/[id]", stored.toRoute)
        assertEquals(7, stored.memberId)
        assertEquals(now.plusSeconds(90L * 24 * 60 * 60), stored.expiresAt)
    }

    @Test
    fun `invalid timing is rejected before database access`() {
        val service = NavigationPerformanceService(repository, Clock.fixed(now, ZoneOffset.UTC))

        val error = assertThrows(BusinessException::class.java) {
            service.recordBatch(
                memberId = 7,
                samples = listOf(sample(routeReadyMs = 301, totalMs = 300)),
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
        verifyNoInteractions(repository)
    }

    private fun sample(
        toRoute: String = "/profile",
        routeReadyMs: Int = 80,
        totalMs: Int = 220,
    ) = NavigationPerformanceSample(
        eventId = "11111111-1111-4111-8111-111111111111",
        fromRoute = "/schedule",
        toRoute = toRoute,
        action = "PUSH",
        routeReadyMs = routeReadyMs,
        totalMs = totalMs,
        completionKind = NavigationPerformanceCompletionKind.TRANSITION,
        platform = NavigationPerformancePlatform.IOS,
        appVersion = "1.2.0",
        buildVersion = "42",
        occurredAt = Instant.parse("2026-08-04T02:59:59Z"),
    )
}
