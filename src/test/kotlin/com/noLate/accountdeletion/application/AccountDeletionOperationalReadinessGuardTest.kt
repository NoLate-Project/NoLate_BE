package com.noLate.accountdeletion.application

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration

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
    fun `enabled external deletion refuses startup without exact COMMON mailbox proof approval`() {
        val properties = readyProperties().apply {
            commonMailboxProofPolicyApproved = false
        }

        val error = assertThrows(IllegalStateException::class.java) {
            AccountDeletionOperationalReadinessGuard(properties, verificationPort)
                .afterSingletonsInstantiated()
        }

        assertTrue(error.message!!.contains("COMMON account ownership proof"))
    }

    @Test
    fun `enabled external deletion rejects unsafe lifecycle and rate configurations`() {
        val invalidConfigurations = listOf<(AccountDeletionProperties) -> Unit>(
            { it.verificationCodeTtl = Duration.ZERO },
            { it.deletionGrantTtl = Duration.ofSeconds(-1) },
            { it.processingTimeout = Duration.ofMinutes(4) },
            { it.requestRecordRetention = Duration.ofDays(29) },
            {
                it.verificationCodeTtl = Duration.ofDays(20)
                it.deletionGrantTtl = Duration.ofDays(10)
            },
            { it.retentionCleanupFixedDelay = Duration.ZERO },
            { it.maxVerificationAttempts = 0 },
            { it.identityRateLimit = 0 },
            { it.requesterRateWindow = Duration.ZERO },
        )
        whenever(verificationPort.isConfigured()).thenReturn(true)

        invalidConfigurations.forEach { invalidate ->
            val properties = readyProperties().also(invalidate)
            val error = assertThrows(IllegalStateException::class.java) {
                AccountDeletionOperationalReadinessGuard(properties, verificationPort)
                    .afterSingletonsInstantiated()
            }
            assertTrue(error.message!!.contains("TTL, retention, attempt, or rate-limit"))
        }
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
            commonMailboxProofPolicyApproved = true
            hmacSecret = "account-deletion-test-hmac-secret-at-least-32-bytes"
            publicOrigin = "https://delete.example"
            supportEmail = "privacy@example.com"
        }
}
