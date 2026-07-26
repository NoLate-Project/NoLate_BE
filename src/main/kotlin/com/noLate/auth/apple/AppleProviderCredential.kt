package com.noLate.auth.apple

import com.noLate.global.common.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

enum class AppleProviderCredentialStatus {
    ACTIVE,
    PENDING,
    PROCESSING,
    BLOCKED,
    REVOKED,
}

/**
 * An encrypted Apple refresh-token envelope and its durable revocation lease.
 *
 * There is no member foreign key by design: account cleanup may commit while provider revocation
 * is temporarily unavailable. The row is anonymized only after Apple's idempotent revoke endpoint
 * confirms success.
 */
@Entity
@Table(
    name = "apple_provider_credentials",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_apple_provider_credentials_authorization_code_hash",
            columnNames = ["authorization_code_hash"],
        ),
        UniqueConstraint(
            name = "uk_apple_provider_credentials_refresh_token_hash",
            columnNames = ["refresh_token_hash"],
        ),
        UniqueConstraint(
            name = "uk_apple_provider_credentials_credential_key",
            columnNames = ["credential_key"],
        ),
    ],
    indexes = [
        Index(
            name = "idx_apple_provider_credentials_member_status",
            columnList = "member_id,status,id",
        ),
        Index(
            name = "idx_apple_provider_credentials_due",
            columnList = "status,next_attempt_at,id",
        ),
        Index(
            name = "idx_apple_provider_credentials_stale",
            columnList = "status,locked_at,id",
        ),
    ],
)
class AppleProviderCredential(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "credential_key", nullable = false, length = 36, updatable = false)
    var credentialKey: String = UUID.randomUUID().toString(),

    @Column(name = "member_id")
    var memberId: Long? = null,

    @Column(name = "apple_subject_hash", length = 64)
    var appleSubjectHash: String? = null,

    @Column(name = "authorization_code_hash", length = 64)
    var authorizationCodeHash: String? = null,

    @Column(name = "refresh_token_hash", length = 64)
    var refreshTokenHash: String? = null,

    @Column(name = "client_id", nullable = false, length = 255)
    var clientId: String = "",

    @Column(name = "encryption_key_id", length = 40)
    var encryptionKeyId: String? = null,

    @Column(name = "initialization_vector", length = 64)
    var initializationVector: String? = null,

    @Column(name = "encrypted_refresh_token", length = 16384)
    var encryptedRefreshToken: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AppleProviderCredentialStatus = AppleProviderCredentialStatus.ACTIVE,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = null,

    @Column(name = "locked_at")
    var lockedAt: Instant? = null,

    @Column(name = "locked_by", length = 80)
    var lockedBy: String? = null,

    @Column(name = "last_failure_code", length = 120)
    var lastFailureCode: String? = null,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0,
) : BaseEntity() {

    fun queueForRevocation(now: Instant) {
        if (status == AppleProviderCredentialStatus.REVOKED) return
        if (status != AppleProviderCredentialStatus.PROCESSING) {
            status = AppleProviderCredentialStatus.PENDING
            nextAttemptAt = now
            lockedAt = null
            lockedBy = null
        }
    }

    fun claim(workerId: String, now: Instant): Boolean {
        if (
            status != AppleProviderCredentialStatus.PENDING ||
            nextAttemptAt?.isAfter(now) == true ||
            encryptedRefreshToken.isNullOrBlank()
        ) {
            return false
        }
        status = AppleProviderCredentialStatus.PROCESSING
        attemptCount = Math.addExact(attemptCount, 1)
        lockedAt = now
        lockedBy = workerId
        return true
    }

    fun recoverStale(staleBefore: Instant, now: Instant): Boolean {
        if (
            status != AppleProviderCredentialStatus.PROCESSING ||
            lockedAt?.isAfter(staleBefore) != false
        ) {
            return false
        }
        status = AppleProviderCredentialStatus.PENDING
        nextAttemptAt = now
        lockedAt = null
        lockedBy = null
        lastFailureCode = "STALE_LEASE_RECOVERED"
        return true
    }

    fun retry(workerId: String, nextAt: Instant, safeCode: String): Boolean {
        if (!owns(workerId)) return false
        status = AppleProviderCredentialStatus.PENDING
        nextAttemptAt = nextAt
        lockedAt = null
        lockedBy = null
        lastFailureCode = safeCode.sanitizedFailureCode()
        return true
    }

    fun block(workerId: String, safeCode: String): Boolean {
        if (!owns(workerId)) return false
        status = AppleProviderCredentialStatus.BLOCKED
        nextAttemptAt = null
        lockedAt = null
        lockedBy = null
        lastFailureCode = safeCode.sanitizedFailureCode()
        return true
    }

    fun markRevoked(workerId: String, at: Instant): Boolean {
        if (!owns(workerId)) return false
        status = AppleProviderCredentialStatus.REVOKED
        revokedAt = at
        nextAttemptAt = null
        lockedAt = null
        lockedBy = null
        lastFailureCode = null

        // Apple requires deleting the credential after revocation. Keep only a non-user tombstone
        // proving that a provider-confirmed cleanup occurred.
        memberId = null
        appleSubjectHash = null
        authorizationCodeHash = null
        refreshTokenHash = null
        encryptionKeyId = null
        initializationVector = null
        encryptedRefreshToken = null
        return true
    }

    private fun owns(workerId: String): Boolean =
        status == AppleProviderCredentialStatus.PROCESSING && lockedBy == workerId
}

internal fun String.sanitizedFailureCode(): String =
    uppercase()
        .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
        .joinToString("")
        .take(120)
