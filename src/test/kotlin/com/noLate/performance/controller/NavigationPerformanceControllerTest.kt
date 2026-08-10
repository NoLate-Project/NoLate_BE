package com.noLate.performance.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.performance.application.NavigationPerformanceBatchResult
import com.noLate.performance.application.NavigationPerformanceService
import com.noLate.performance.domain.NavigationPerformanceCompletionKind
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
class NavigationPerformanceControllerTest {
    @Mock
    lateinit var service: NavigationPerformanceService

    @Test
    fun `authenticated member owns every event in the batch`() {
        whenever(service.recordBatch(eq(7), any())).thenReturn(
            NavigationPerformanceBatchResult(acceptedCount = 1, storedCount = 1),
        )
        val request = request()

        val response = NavigationPerformanceController(service).record(
            principal = MemberPrincipal(7, "member@nolate.test", "member"),
            request = request,
        )

        assertEquals(1, response.data?.storedCount)
        verify(service).recordBatch(7, listOf(request.events.single().toSample()))
    }

    @Test
    fun `anonymous telemetry submission is rejected`() {
        val error = assertThrows(BusinessException::class.java) {
            NavigationPerformanceController(service).record(null, request())
        }

        assertEquals(ErrorCode.UNAUTHORIZED, error.errorCode)
        verifyNoInteractions(service)
    }

    private fun request() = NavigationPerformanceBatchRequest(
        events = listOf(
            NavigationPerformanceEventRequest(
                eventId = "11111111-1111-4111-8111-111111111111",
                fromRoute = "/schedule",
                toRoute = "/profile",
                action = "PUSH",
                routeReadyMs = 80,
                totalMs = 220,
                completionKind = NavigationPerformanceCompletionKind.TRANSITION,
                platform = NavigationPerformancePlatform.IOS,
                occurredAt = Instant.parse("2026-08-04T02:59:59Z"),
            ),
        ),
    )
}
