package com.noLate.global.error

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException

class GlobalExceptionHandlerConcurrencyTest {

    @Test
    fun `optimistic locking conflicts return a retryable conflict response`() {
        val response = GlobalExceptionHandler().handleException(
            ObjectOptimisticLockingFailureException("ScheduleCalendar", 1L)
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertFalse(requireNotNull(response.body).success)
        assertEquals(ErrorCode.CONCURRENT_MODIFICATION.code, response.body?.errorCode)
    }
}
