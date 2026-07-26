package com.noLate.accountdeletion.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "account_deletion_requests",
    indexes = [
        Index(
            name = "idx_account_deletion_requests_status_expiry",
            columnList = "status, verification_expires_at",
        ),
        Index(
            name = "idx_account_deletion_requests_retention",
            columnList = "retention_expires_at",
        ),
        Index(
            name = "idx_account_deletion_requests_processing",
            columnList = "status, processing_started_at",
        ),
    ],
)
class AccountDeletionRequest(
    @Id
    @Column(length = 36, nullable = false, updatable = false)
    var id: String,

    /** Submitted identifiers are never persisted; only a keyed, domain-separated digest is kept. */
    @Column(name = "identifier_hash", length = 64, nullable = false, updatable = false)
    var identifierHash: String,

    @Column(name = "requester_hash", length = 64, nullable = false, updatable = false)
    var requesterHash: String,

    /**
     * Null is deliberate for decoy and unsupported requests. Public responses never reveal whether
     * this binding exists, so the same request/verification pages cannot be used for enumeration.
     */
    @Column(name = "member_id")
    var memberId: Long? = null,

    @Column(name = "observed_session_generation")
    var observedSessionGeneration: Long? = null,

    /**
     * True for an existing account whose login provider cannot be proven by the configured
     * verification adapter. It never authorizes cleanup; after email-channel verification the
     * requester is directed to a provider-aware support path instead of receiving a false success.
     */
    @Column(name = "manual_review_required", nullable = false)
    var manualReviewRequired: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    var status: AccountDeletionRequestStatus,

    @Column(name = "verification_token_hash", length = 64)
    var verificationTokenHash: String? = null,

    @Column(name = "verification_attempt_count", nullable = false)
    var verificationAttemptCount: Int = 0,

    @Column(name = "verification_expires_at")
    var verificationExpiresAt: Instant? = null,

    @Column(name = "deletion_grant_hash", length = 64)
    var deletionGrantHash: String? = null,

    @Column(name = "deletion_grant_expires_at")
    var deletionGrantExpiresAt: Instant? = null,

    @Column(name = "processing_started_at")
    var processingStartedAt: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "failure_code", length = 40)
    var failureCode: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Column(name = "retention_expires_at", nullable = false)
    var retentionExpiresAt: Instant,
) {
    protected constructor() : this(
        id = "",
        identifierHash = "",
        requesterHash = "",
        status = AccountDeletionRequestStatus.VERIFICATION_UNAVAILABLE,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        retentionExpiresAt = Instant.EPOCH,
    )
}

enum class AccountDeletionRequestStatus {
    READY_TO_DELIVER,
    VERIFICATION_SENT,
    VERIFICATION_UNAVAILABLE,
    VERIFIED,
    PROCESSING,
    COMPLETED,
    REJECTED,
}
