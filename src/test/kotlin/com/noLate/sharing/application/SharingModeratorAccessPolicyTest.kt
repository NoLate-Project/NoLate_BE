package com.noLate.sharing.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SharingModeratorAccessPolicyTest {
    @Test
    fun `configured member IDs are allowed and every other member is denied`() {
        val policy = SharingModeratorAccessPolicy(" 7,11 ")

        policy.requireModerator(7L)
        val denied = assertThrows(BusinessException::class.java) {
            policy.requireModerator(8L)
        }

        assertEquals(ErrorCode.FORBIDDEN, denied.errorCode)
    }

    @Test
    fun `empty allowlist denies everyone`() {
        val denied = assertThrows(BusinessException::class.java) {
            SharingModeratorAccessPolicy("").requireModerator(1L)
        }

        assertEquals(ErrorCode.FORBIDDEN, denied.errorCode)
    }

    @Test
    fun `malformed operator configuration fails startup`() {
        assertThrows(IllegalStateException::class.java) {
            SharingModeratorAccessPolicy("1,not-a-member")
        }
    }
}
