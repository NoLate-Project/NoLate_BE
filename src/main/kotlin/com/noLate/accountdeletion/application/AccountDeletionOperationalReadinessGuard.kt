package com.noLate.accountdeletion.application

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component

/**
 * Enabling an unauthenticated destructive surface must be an all-or-nothing deployment choice.
 * The public explanation page remains available while disabled, but a partially configured
 * automatic flow blocks startup instead of silently accepting requests it cannot complete.
 */
@Component
class AccountDeletionOperationalReadinessGuard(
    private val properties: AccountDeletionProperties,
    private val verificationPort: AccountDeletionIdentityVerificationPort,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        if (!properties.enabled) return

        check(properties.retentionPolicyConfirmed) {
            "Account deletion startup blocked: retention policy is not confirmed."
        }
        check(properties.commonMailboxProofPolicyApproved) {
            "Account deletion startup blocked: current mailbox control has not been approved " +
                "as COMMON account ownership proof."
        }
        check(properties.hmacSecret.toByteArray(Charsets.UTF_8).size >= 32) {
            "Account deletion startup blocked: a dedicated HMAC secret of at least 32 bytes is required."
        }
        check(properties.publicOriginReady()) {
            "Account deletion startup blocked: a canonical public origin is required."
        }
        check(properties.supportEmailReady()) {
            "Account deletion startup blocked: an explicit support email is required."
        }
        check(properties.operationalSettingsReady()) {
            "Account deletion startup blocked: TTL, retention, attempt, or rate-limit settings " +
                "violate the published operational policy."
        }
        check(verificationPort.isConfigured()) {
            "Account deletion startup blocked: no trusted identity-verification adapter is configured."
        }
    }
}
