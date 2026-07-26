package com.noLate.accountdeletion.application

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AccountDeletionOperationalReadinessGuardTest {
    private val verificationPort = mock<AccountDeletionIdentityVerificationPort>()

    @Test
    fun `disabled external deletion keeps the public page deployable without an adapter`() {
        val properties = AccountDeletionProperties().apply { enabled = false }

        assertDoesNotThrow {
            AccountDeletionOperationalReadinessGuard(properties, verificationPort)
                .afterSingletonsInstantiated()
        }
    }

    @Test
    fun `enabled external deletion refuses startup without a trusted verifier`() {
        val properties = readyProperties()
        whenever(verificationPort.isConfigured()).thenReturn(false)

        val error = assertThrows(IllegalStateException::class.java) {
            AccountDeletionOperationalReadinessGuard(properties, verificationPort)
                .afterSingletonsInstantiated()
        }

        assertTrue(error.message!!.contains("identity-verification adapter"))
    }

    @Test
    fun `enabled external deletion accepts only a complete operational configuration`() {
        val properties = readyProperties()
        whenever(verificationPort.isConfigured()).thenReturn(true)

        assertDoesNotThrow {
            AccountDeletionOperationalReadinessGuard(properties, verificationPort)
                .afterSingletonsInstantiated()
        }
    }

    private fun readyProperties() =
        AccountDeletionProperties().apply {
            enabled = true
            retentionPolicyConfirmed = true
            hmacSecret = "account-deletion-test-hmac-secret-at-least-32-bytes"
            publicOrigin = "https://delete.example"
            supportEmail = "privacy@example.com"
        }
}
