package com.noLate.global.error

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class GlobalExceptionHandlerFeatureDisabledTest {

    @Test
    fun `feature disabled is a stable forbidden response`() {
        val response = GlobalExceptionHandler().handleBusinessException(
            BusinessException(ErrorCode.FEATURE_DISABLED),
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals(ErrorCode.FEATURE_DISABLED.code, response.body?.errorCode)
        assertEquals(ErrorCode.FEATURE_DISABLED.message, response.body?.errorMessage)
    }
}
