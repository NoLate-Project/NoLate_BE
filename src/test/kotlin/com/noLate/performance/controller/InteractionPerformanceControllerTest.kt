package com.noLate.performance.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.performance.application.InteractionPerformanceBatchResult
import com.noLate.performance.application.InteractionPerformanceService
import com.noLate.performance.domain.InteractionPerformanceKind
import com.noLate.performance.domain.InteractionPerformanceOutcome
import com.noLate.performance.domain.NavigationPerformancePlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class InteractionPerformanceControllerTest {
    @Mock
    lateinit var service: InteractionPerformanceService

    @Test
    fun `authenticated principal owns the interaction batch`() {
        whenever(service.recordBatch(eq(11), any())).thenReturn(
            InteractionPerformanceBatchResult(acceptedCount = 1, storedCount = 1),
        )
        val request = request()

        val response = InteractionPerformanceController(service).record(
            MemberPrincipal(11, "member@nolate.test", "member"),
            InteractionPerformanceBatchRequest(listOf(request)),
        )

        assertEquals(1, response.data?.storedCount)
        verify(service).recordBatch(11, listOf(request.toSample()))
    }

    @Test
    fun `anonymous interaction batch is rejected`() {
        val error = assertThrows(BusinessException::class.java) {
            InteractionPerformanceController(service).record(
                null,
                InteractionPerformanceBatchRequest(listOf(request())),
            )
        }

        assertEquals(ErrorCode.UNAUTHORIZED, error.errorCode)
        verifyNoInteractions(service)
    }

    private fun request() = InteractionPerformanceEventRequest(
        eventId = "11111111-1111-4111-8111-111111111111",
        route = "/schedule",
        operation = "schedule.content_ready",
        kind = InteractionPerformanceKind.CONTENT_READY,
        outcome = InteractionPerformanceOutcome.SUCCESS,
        durationMs = 180,
        platform = NavigationPerformancePlatform.IOS,
        occurredAt = Instant.parse("2026-08-17T00:00:01Z"),
    )
}
