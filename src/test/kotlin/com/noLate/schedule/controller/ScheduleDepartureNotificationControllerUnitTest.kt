package com.noLate.schedule.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.schedule.application.service.ScheduleDepartureNotificationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ScheduleDepartureNotificationControllerUnitTest {

    @Mock
    lateinit var service: ScheduleDepartureNotificationService

    @Test
    fun `authenticated owner can request a nudge for one participant`() {
        val controller = ScheduleDepartureNotificationController(service)
        val principal = MemberPrincipal(id = 1L, email = "owner@example.com", name = "Owner")
        val sendResult = NotificationSendResult(requestedCount = 1, sentCount = 1)
        whenever(service.sendDepartureNudge(1L, 10L, 2L)).thenReturn(sendResult)

        val response = controller.sendDepartureNudge(principal, scheduleId = 10L, targetMemberId = 2L)

        assertTrue(response.success)
        assertEquals(sendResult, response.data)
        verify(service).sendDepartureNudge(1L, 10L, 2L)
    }

    @Test
    fun `anonymous request is rejected before notification service call`() {
        val controller = ScheduleDepartureNotificationController(service)

        val error = assertThrows(BusinessException::class.java) {
            controller.sendDepartureNudge(null, scheduleId = 10L, targetMemberId = 2L)
        }

        assertEquals(ErrorCode.UNAUTHORIZED, error.errorCode)
    }
}
