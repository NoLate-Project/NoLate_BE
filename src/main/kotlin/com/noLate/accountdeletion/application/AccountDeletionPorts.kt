package com.noLate.accountdeletion.application

import com.noLate.member.domain.member.LoginType
import java.time.Instant

/**
 * Infrastructure adapters may deliver an email code or replace this with a provider-auth flow.
 * The core never treats possession of an email string as verification; only a delivered secret
 * followed by the single-use deletion grant can reach account cleanup.
 */
interface AccountDeletionIdentityVerificationPort {
    fun isConfigured(): Boolean

    fun supports(loginType: LoginType, accountEmail: String): Boolean

    fun deliver(command: AccountDeletionVerificationDelivery)
}

data class AccountDeletionVerificationDelivery(
    val requestId: String,
    val destination: String,
    val verificationCode: String,
    val expiresAt: Instant,
)

interface AccountDeletionRateLimitPort {
    /**
     * Returns false on exhaustion or infrastructure failure. A public caller receives the same
     * generic receipt either way, while the core performs no persistence or delivery.
     */
    fun allow(identifierHash: String, requesterHash: String): Boolean
}
