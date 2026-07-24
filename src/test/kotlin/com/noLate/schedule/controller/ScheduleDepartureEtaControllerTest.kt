package com.noLate.schedule.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.service.ScheduleDepartureEtaService
import com.noLate.schedule.domain.ScheduleDepartureEtaStatusDto
import com.noLate.schedule.domain.ScheduleEtaConfidence
import com.noLate.schedule.domain.TrafficSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class ScheduleDepartureEtaControllerTest {
    @Mock
    lateinit var service: ScheduleDepartureEtaService

    @Test
    fun `인증 회원 id로 departure status를 조회한다`() {
        val status = status()
        whenever(service.getDepartureStatus(7L, 10L)).thenReturn(status)
        val controller = ScheduleDepartureEtaController(service)

        val response = controller.getDepartureStatus(
            principal = MemberPrincipal(7L, "member@example.com", "member"),
            scheduleId = 10L,
        )

        assertTrue(response.success)
        assertEquals(status, response.data)
        verify(service).getDepartureStatus(7L, 10L)
    }

    @Test
    fun `익명 departure status 요청은 service 호출 전에 차단한다`() {
        val controller = ScheduleDepartureEtaController(service)

        val exception = assertThrows(BusinessException::class.java) {
            controller.getDepartureStatus(null, 10L)
        }

        assertEquals(ErrorCode.UNAUTHORIZED, exception.errorCode)
        verifyNoInteractions(service)
    }

    private fun status() = ScheduleDepartureEtaStatusDto(
        scheduleId = 10L,
        travelMinutes = 35,
        recommendedDepartureAt = Instant.parse("2026-07-24T04:25:00Z"),
        evaluatedAt = Instant.parse("2026-07-24T03:00:00Z"),
        liveFetchedAt = Instant.parse("2026-07-24T02:59:58Z"),
        source = TrafficSource.LIVE_PROVIDER,
        stale = false,
        confidence = ScheduleEtaConfidence.HIGH,
        failureReason = null,
        lastTrafficChangeMinutes = null,
        lastChangedAt = null,
        nextCheckAt = Instant.parse("2026-07-24T03:20:00Z"),
        preparationMinutes = null,
        preparationStartAt = null,
        safetyBufferMinutes = null,
        timeZone = "Asia/Seoul",
    )
}
