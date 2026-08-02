package com.noLate.global.error

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class GlobalExceptionHandlerSharingSafetyTest {
    private val handler = GlobalExceptionHandler()

    @Test
    fun `blocked interaction and invalid report relationship are forbidden`() {
        val blocked = handler.handleBusinessException(
            BusinessException(ErrorCode.SHARING_INTERACTION_BLOCKED)
        )
        val invalidReport = handler.handleBusinessException(
            BusinessException(ErrorCode.SHARING_REPORT_NOT_ALLOWED)
        )

        assertEquals(HttpStatus.FORBIDDEN, blocked.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, invalidReport.statusCode)
    }

    @Test
    fun `report abuse limit returns too many requests`() {
        val response = handler.handleBusinessException(
            BusinessException(ErrorCode.SHARING_REPORT_RATE_LIMITED)
        )

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)
        assertEquals("S013", response.body?.errorCode)
    }
}
