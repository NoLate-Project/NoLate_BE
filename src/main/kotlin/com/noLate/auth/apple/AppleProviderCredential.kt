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
import org.hibernate.annotations.Check
import java.time.Instant
import java.util.UUID

enum class AppleProviderCredentialStatus {
    CAPTURED,
    ACTIVE,
    PENDING,
    PROCESSING,
    BLOCKED,
    MANUAL_ACTION,
    REVOKED,
}

/**
 * An encrypted Apple refresh-token envelope and its durable compensation/revocation state.
 *
 * There is intentionally no member foreign key: account cleanup may commit while provider
 * revocation is unavailable. CAPTURED is also deliberately unbound. Only the final local login
 * transaction attaches it to a member and makes it ACTIVE.
 */
@Entity
@Table(
    name = "apple_provider_credentials",
    uniqueConstraints = [
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
            name = "idx_apple_provider_credentials_capture",
            columnList = "status,capture_expires_at,id",
        ),
        Index(
            name = "idx_apple_provider_credentials_stale",
            columnList = "status,locked_at,id",
        ),
    ],
)
@Check(
    name = "ck_apple_provider_credentials_status",
    constraints =
        """
        status in ('CAPTURED','ACTIVE','PENDING','PROCESSING','BLOCKED','MANUAL_ACTION','REVOKED')
        and attempt_count >= 0
        and (
          (
            status = 'CAPTURED'
            and source_receipt_key is not null
            and member_id is null
            and apple_subject_hash is not null
            and refresh_token_hash is not null
            and encryption_key_id is not null
            and initialization_vector is not null
            and encrypted_refresh_token is not null
            and capture_expires_at is not null
            and next_attempt_at is null
            and locked_at is null
            and locked_by is null
            and revoked_at is null
          )
          or (
            status = 'ACTIVE'
            and source_receipt_key is not null
            and member_id is not null
            and apple_subject_hash is not null
            and refresh_token_hash is not null
            and encryption_key_id is not null
            and initialization_vector is not null
            and encrypted_refresh_token is not null
            and capture_expires_at is null
            and next_attempt_at is null
            and locked_at is null
            and locked_by is null
            and revoked_at is null
          )
          or (
            status = 'PENDING'
            and source_receipt_key is not null
            and apple_subject_hash is not null
            and refresh_token_hash is not null
            and encryption_key_id is not null
            and initialization_vector is not null
            and encrypted_refresh_token is not null
            and capture_expires_at is null
            and next_attempt_at is not null
            and locked_at is null
            and locked_by is null
            and revoked_at is null
          )
          or (
            status = 'PROCESSING'
            and source_receipt_key is not null
            and apple_subject_hash is not null
            and refresh_token_hash is not null
            and encryption_key_id is not null
            and initialization_vector is not null
            and encrypted_refresh_token is not null
            and capture_expires_at is null
            and next_attempt_at is not null
            and locked_at is not null
            and locked_by is not null
            and revoked_at is null
          )
          or (
            status = 'BLOCKED'
            and capture_expires_at is null
            and next_attempt_at is null
            and locked_at is null
            and locked_by is null
            and revoked_at is null
          )
          or (
            status = 'MANUAL_ACTION'
            and source_receipt_key is null
            and member_id is null
            and apple_subject_hash is null
            and refresh_token_hash is null
            and encryption_key_id is null
            and initialization_vector is null
            and encrypted_refresh_token is null
            and capture_expires_at is null
            and next_attempt_at is null
            and locked_at is null
            and locked_by is null
            and last_failure_code is not null
            and revoked_at is null
          )
          or (
            status = 'REVOKED'
            and source_receipt_key is null
            and member_id is null
            and apple_subject_hash is null
            and refresh_token_hash is null
            and encryption_key_id is null
            and initialization_vector is null
            and encrypted_refresh_token is null
            and capture_expires_at is null
            and next_attempt_at is null
            and locked_at is null
            and locked_by is null
            and revoked_at is not null
          )
        )
        """,
)
class AppleProviderCredential(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "credential_key", nullable = false, length = 36, updatable = false)
    var credentialKey: String = UUID.randomUUID().toString(),

    @Column(name = "source_receipt_key", length = 36)
    var sourceReceiptKey: String? = null,

    @Column(name = "member_id")
    var memberId: Long? = null,

    @Column(name = "apple_subject_hash", length = 64)
    var appleSubjectHash: String? = null,

    @Column(name = "refresh_token_hash", length = 64)
    var refreshTokenHash: String? = null,

    @Column(name = "client_id", nullable = false, length = 255)
    var clientId: String = "",

    @Column(name = "encryption_key_id", length = 40)
    var encryptionKeyId: String? = null,

    @Column(name = "initialization_vector", length = 64)
    var initializationVector: String? = null,

    // AES-GCM ciphertext is Base64 ASCII. The MySQL executable comment pins its charset so a
    // 16 KiB value is not budgeted as utf8mb4, while H2 can ignore the vendor-specific clause.
    @Column(
        name = "encrypted_refresh_token",
        length = 16384,
        columnDefinition =
            "VARCHAR(16384) /*!40100 CHARACTER SET ascii COLLATE ascii_bin */",
    )
    var encryptedRefreshToken: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AppleProviderCredentialStatus = AppleProviderCredentialStatus.CAPTURED,

    @Column(name = "capture_expires_at")
    var captureExpiresAt: Instant? = null,

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

    fun hasCompleteEnvelope(): Boolean =
        !appleSubjectHash.isNullOrBlank() &&
            !refreshTokenHash.isNullOrBlank() &&
            !encryptionKeyId.isNullOrBlank() &&
            !initializationVector.isNullOrBlank() &&
            !encryptedRefreshToken.isNullOrBlank()

    fun bind(memberId: Long, expectedSubjectHash: String, expectedClientId: String): Boolean {
        if (
            appleSubjectHash != expectedSubjectHash ||
            clientId != expectedClientId ||
            !hasCompleteEnvelope()
        ) {
            return false
        }
        if (status == AppleProviderCredentialStatus.ACTIVE) {
            return this.memberId == memberId
        }
        if (status != AppleProviderCredentialStatus.CAPTURED) return false
        this.memberId = memberId
        status = AppleProviderCredentialStatus.ACTIVE
        captureExpiresAt = null
        lastFailureCode = null
        return true
    }

    fun abandonCapture(now: Instant, safeCode: String): Boolean {
        if (status != AppleProviderCredentialStatus.CAPTURED || !hasCompleteEnvelope()) return false
        status = AppleProviderCredentialStatus.PENDING
        captureExpiresAt = null
        nextAttemptAt = now
        lastFailureCode = safeCode.sanitizedFailureCode()
        return true
    }

    fun expireCapture(now: Instant): Boolean {
        if (
            status != AppleProviderCredentialStatus.CAPTURED ||
            captureExpiresAt?.isAfter(now) != false ||
            !hasCompleteEnvelope()
        ) {
            return false
        }
        return abandonCapture(now, "CAPTURE_BIND_DEADLINE_EXPIRED")
    }

    fun queueForRevocation(now: Instant) {
        if (
            status == AppleProviderCredentialStatus.REVOKED ||
            status == AppleProviderCredentialStatus.MANUAL_ACTION ||
            status == AppleProviderCredentialStatus.PROCESSING
        ) {
            return
        }
        if (!hasCompleteEnvelope()) {
            quarantineMalformed("MALFORMED_ENVELOPE")
            return
        }
        status = AppleProviderCredentialStatus.PENDING
        captureExpiresAt = null
        nextAttemptAt = now
        lockedAt = null
        lockedBy = null
    }

    fun claim(workerId: String, now: Instant): Boolean {
        if (
            status != AppleProviderCredentialStatus.PENDING ||
            nextAttemptAt?.isAfter(now) == true ||
            !hasCompleteEnvelope()
        ) {
            return false
        }
        status = AppleProviderCredentialStatus.PROCESSING
        attemptCount = Math.addExact(attemptCount, 1)
        lockedAt = now
        lockedBy = workerId
        return true
    }

    fun quarantineMalformed(safeCode: String): Boolean {
        if (
            status != AppleProviderCredentialStatus.CAPTURED &&
            status != AppleProviderCredentialStatus.PENDING &&
            status != AppleProviderCredentialStatus.PROCESSING
        ) {
            return false
        }
        status = AppleProviderCredentialStatus.BLOCKED
        captureExpiresAt = null
        nextAttemptAt = null
        lockedAt = null
        lockedBy = null
        lastFailureCode = safeCode.sanitizedFailureCode()
        return true
    }

    fun blockForManualReview(safeCode: String): Boolean {
        if (
            status == AppleProviderCredentialStatus.REVOKED ||
            status == AppleProviderCredentialStatus.MANUAL_ACTION
        ) {
            return false
        }
        status = AppleProviderCredentialStatus.BLOCKED
        captureExpiresAt = null
        nextAttemptAt = null
        lockedAt = null
        lockedBy = null
        lastFailureCode = safeCode.sanitizedFailureCode()
        return true
    }

    fun recoverStale(staleBefore: Instant, now: Instant): Boolean {
        if (
            status != AppleProviderCredentialStatus.PROCESSING ||
            lockedAt?.isAfter(staleBefore) != false
        ) {
            return false
        }
        if (!hasCompleteEnvelope()) {
            return quarantineMalformed("MALFORMED_STALE_ENVELOPE")
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
        captureExpiresAt = null
        nextAttemptAt = null
        lockedAt = null
        lockedBy = null
        lastFailureCode = null

        // Keep only a value-free tombstone after Apple's idempotent revoke confirms deletion.
        sourceReceiptKey = null
        memberId = null
        appleSubjectHash = null
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
