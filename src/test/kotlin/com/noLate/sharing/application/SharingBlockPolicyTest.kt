package com.noLate.sharing.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.sharing.infrastructure.SharingMemberBlockRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.mock

class SharingBlockPolicyTest {
    private val repository = mock<SharingMemberBlockRepository>()
    private val policy = SharingBlockPolicy(repository)

    @Test
    fun `same member is never treated as blocked`() {
        assertFalse(policy.isInteractionBlocked(3L, 3L))
        verify(repository, never()).existsActiveEitherDirection(3L, 3L)
    }

    @Test
    fun `an active block in either direction rejects sharing interaction`() {
        whenever(repository.existsActiveEitherDirection(1L, 2L)).thenReturn(true)

        assertTrue(policy.isInteractionBlocked(1L, 2L))
        val error = assertThrows(BusinessException::class.java) {
            policy.requireInteractionAllowed(1L, 2L)
        }

        assertEquals(ErrorCode.SHARING_INTERACTION_BLOCKED, error.errorCode)
    }

    @Test
    fun `bulk lookup removes duplicates and the actor before querying`() {
        whenever(repository.findBlockedCounterpartIds(1L, listOf(2L, 3L)))
            .thenReturn(listOf(3L))

        assertEquals(setOf(3L), policy.blockedCounterpartIds(1L, listOf(1L, 2L, 2L, 3L)))
    }
}
