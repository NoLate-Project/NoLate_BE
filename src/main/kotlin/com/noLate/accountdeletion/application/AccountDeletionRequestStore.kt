package com.noLate.accountdeletion.application

import com.noLate.accountdeletion.domain.AccountDeletionRequest
import com.noLate.accountdeletion.domain.AccountDeletionRequestStatus
import com.noLate.accountdeletion.infrastructure.AccountDeletionRequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class ActionableDeletionAccount(
    val memberId: Long,
    val observedSessionGeneration: Long,
)

data class ClaimedAccountDeletion(
    val requestId: String,
    val memberId: Long?,
    val observedSessionGeneration: Long?,
    val manualReviewRequired: Boolean,
)

@Service
class AccountDeletionRequestStore(
    private val repository: AccountDeletionRequestRepository,
    private val secrets: AccountDeletionSecrets,
    private val properties: AccountDeletionProperties,
    private val clock: Clock,
) {
    @Transactional
    fun purgeExpired(): Int {
        val now = Instant.now(clock)
        repository.rejectStaleProcessing(
            cutoff = now.minus(properties.processingTimeout.coerceAtLeast(Duration.ofMinutes(5))),
            now = now,
        )
        return repository.deleteExpiredBefore(now)
    }

    @Transactional
    fun create(
        requestId: String,
        identifierHash: String,
        requesterHash: String,
        verificationCode: String,
        account: ActionableDeletionAccount?,
        deliveryEnabled: Boolean,
        manualReviewRequired: Boolean = false,
        verificationExpiresAt: Instant,
    ): AccountDeletionRequest {
        val now = Instant.now(clock)
        repository.rejectStaleProcessing(
            cutoff = now.minus(properties.processingTimeout.coerceAtLeast(Duration.ofMinutes(5))),
            now = now,
        )
        repository.deleteExpiredBefore(now)
        return repository.saveAndFlush(
            AccountDeletionRequest(
                id = requestId,
                identifierHash = identifierHash,
                requesterHash = requesterHash,
                memberId = account?.memberId,
                observedSessionGeneration = account?.observedSessionGeneration,
                manualReviewRequired = manualReviewRequired,
                status = if (deliveryEnabled) {
                    AccountDeletionRequestStatus.READY_TO_DELIVER
                } else {
                    AccountDeletionRequestStatus.VERIFICATION_UNAVAILABLE
                },
                verificationTokenHash = if (deliveryEnabled) {
                    secrets.verificationHash(requestId, verificationCode)
                } else {
                    null
                },
                verificationExpiresAt = verificationExpiresAt.takeIf { deliveryEnabled },
                createdAt = now,
                updatedAt = now,
                retentionExpiresAt = now.plus(properties.requestRecordRetention),
            )
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markVerificationSent(requestId: String) {
        val request = repository.findByIdForUpdate(requestId) ?: return
        if (request.status != AccountDeletionRequestStatus.READY_TO_DELIVER) return
        request.status = AccountDeletionRequestStatus.VERIFICATION_SENT
        request.updatedAt = Instant.now(clock)
        repository.saveAndFlush(request)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markVerificationUnavailable(requestId: String) {
        val request = repository.findByIdForUpdate(requestId) ?: return
        if (request.status != AccountDeletionRequestStatus.READY_TO_DELIVER) return
        request.status = AccountDeletionRequestStatus.VERIFICATION_UNAVAILABLE
        request.verificationTokenHash = null
        request.verificationExpiresAt = null
        request.updatedAt = Instant.now(clock)
        repository.saveAndFlush(request)
    }

    /**
     * Verification and attempt consumption share one row lock. Two correct-code requests cannot
     * mint two grants, and a replay after VERIFIED never receives the already-issued secret again.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun verifyAndMintGrant(
        requestId: String,
        presentedCode: String,
        deletionGrant: String,
    ): Boolean {
        val request = repository.findByIdForUpdate(requestId) ?: return false
        val now = Instant.now(clock)
        if (
            request.status != AccountDeletionRequestStatus.VERIFICATION_SENT ||
            request.verificationExpiresAt?.let { !now.isBefore(it) } != false
        ) {
            return false
        }

        request.verificationAttemptCount += 1
        val matches = secrets.matches(
            request.verificationTokenHash,
            secrets.verificationHash(requestId, presentedCode),
        )
        if (!matches) {
            if (request.verificationAttemptCount >= properties.maxVerificationAttempts.coerceIn(1, 20)) {
                request.status = AccountDeletionRequestStatus.REJECTED
                request.verificationTokenHash = null
                request.verificationExpiresAt = null
            }
            request.updatedAt = now
            repository.saveAndFlush(request)
            return false
        }

        request.status = AccountDeletionRequestStatus.VERIFIED
        request.verificationTokenHash = null
        request.verificationExpiresAt = null
        request.deletionGrantHash = secrets.deletionGrantHash(requestId, deletionGrant)
        request.deletionGrantExpiresAt = now.plus(properties.deletionGrantTtl)
        request.updatedAt = now
        repository.saveAndFlush(request)
        return true
    }

    /**
     * Claim commits in its own transaction before account cleanup starts. A provider/browser retry
     * therefore cannot reuse the grant even when the later destructive transaction fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimDeletion(
        requestId: String,
        presentedGrant: String,
    ): ClaimedAccountDeletion? {
        val request = repository.findByIdForUpdate(requestId) ?: return null
        val now = Instant.now(clock)
        if (
            request.status != AccountDeletionRequestStatus.VERIFIED ||
            request.deletionGrantExpiresAt?.let { !now.isBefore(it) } != false ||
            !secrets.matches(
                request.deletionGrantHash,
                secrets.deletionGrantHash(requestId, presentedGrant),
            )
        ) {
            return null
        }

        request.status = AccountDeletionRequestStatus.PROCESSING
        request.deletionGrantHash = null
        request.deletionGrantExpiresAt = null
        request.processingStartedAt = now
        request.updatedAt = now
        repository.saveAndFlush(request)
        return ClaimedAccountDeletion(
            requestId = request.id,
            memberId = request.memberId,
            observedSessionGeneration = request.observedSessionGeneration,
            manualReviewRequired = request.manualReviewRequired,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markCompleted(requestId: String) {
        val request = repository.findByIdForUpdate(requestId) ?: return
        if (request.status != AccountDeletionRequestStatus.PROCESSING) return
        val now = Instant.now(clock)
        request.status = AccountDeletionRequestStatus.COMPLETED
        request.completedAt = now
        request.updatedAt = now
        request.memberId = null
        request.observedSessionGeneration = null
        repository.saveAndFlush(request)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailed(requestId: String, failureCode: AccountDeletionFailureCode) {
        val request = repository.findByIdForUpdate(requestId) ?: return
        if (request.status != AccountDeletionRequestStatus.PROCESSING) return
        request.status = AccountDeletionRequestStatus.REJECTED
        request.failureCode = failureCode.name
        request.memberId = null
        request.observedSessionGeneration = null
        request.updatedAt = Instant.now(clock)
        repository.saveAndFlush(request)
    }
}

enum class AccountDeletionFailureCode {
    SESSION_CHANGED,
    PROVIDER_VERIFICATION_REQUIRED,
    OWNER_ACTION_REQUIRED,
    CLEANUP_FAILED,
    OUTCOME_UNKNOWN,
}
