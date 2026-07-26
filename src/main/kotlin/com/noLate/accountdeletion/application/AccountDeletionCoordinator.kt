package com.noLate.accountdeletion.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.application.useCase.MemberUseCase
import com.noLate.member.infrastructure.MemberRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class PublicAccountDeletionReceipt(
    val requestId: String,
)

data class PublicAccountDeletionVerification(
    val requestId: String,
    val deletionGrant: String?,
)

enum class PublicAccountDeletionConfirmation {
    ACCEPTED,
    NEEDS_REVERIFICATION,
    NEEDS_SUPPORT,
}

@Service
class AccountDeletionCoordinator(
    private val properties: AccountDeletionProperties,
    private val secrets: AccountDeletionSecrets,
    private val rateLimitPort: AccountDeletionRateLimitPort,
    private val verificationPort: AccountDeletionIdentityVerificationPort,
    private val memberRepository: MemberRepository,
    private val store: AccountDeletionRequestStore,
    private val memberUseCase: MemberUseCase,
    private val clock: Clock,
) {
    fun requestDeletion(
        submittedEmail: String?,
        requesterAddress: String?,
    ): PublicAccountDeletionReceipt {
        val publicRequestId = UUID.randomUUID().toString()
        val normalizedEmail = secrets.normalizeEmail(submittedEmail)
            ?: return PublicAccountDeletionReceipt(publicRequestId)

        val identifierHash = runCatching { secrets.identifierHash(normalizedEmail) }.getOrNull()
            ?: return PublicAccountDeletionReceipt(publicRequestId)
        val requesterHash = runCatching { secrets.requesterHash(requesterAddress) }.getOrNull()
            ?: return PublicAccountDeletionReceipt(publicRequestId)
        if (!rateLimitPort.allow(identifierHash, requesterHash)) {
            return PublicAccountDeletionReceipt(publicRequestId)
        }

        val verificationCode = secrets.newVerificationCode()
        val deliveryEnabled =
            properties.corePolicyReady() && verificationPort.isConfigured()
        val member = if (deliveryEnabled) {
            memberRepository.findByEmailAndDeletedFalse(normalizedEmail)
        } else {
            null
        }
        val actionableAccount = member
            ?.takeIf { account ->
                val loginType = account.loginType ?: return@takeIf false
                verificationPort.supports(loginType, normalizedEmail)
            }
            ?.let { account ->
                ActionableDeletionAccount(
                    memberId = requireNotNull(account.id),
                    observedSessionGeneration = account.sessionGeneration,
                )
            }
        val manualReviewRequired =
            member != null && actionableAccount == null
        val verificationExpiresAt =
            Instant.now(clock).plus(properties.verificationCodeTtl)

        store.create(
            requestId = publicRequestId,
            identifierHash = identifierHash,
            requesterHash = requesterHash,
            verificationCode = verificationCode,
            account = actionableAccount,
            deliveryEnabled = deliveryEnabled,
            manualReviewRequired = manualReviewRequired,
            verificationExpiresAt = verificationExpiresAt,
        )

        if (deliveryEnabled) {
            try {
                // Delivery is intentionally attempted for every syntactically valid address,
                // including an unregistered one, so provider latency cannot enumerate accounts.
                verificationPort.deliver(
                    AccountDeletionVerificationDelivery(
                        requestId = publicRequestId,
                        destination = normalizedEmail,
                        verificationCode = verificationCode,
                        expiresAt = verificationExpiresAt,
                    )
                )
                store.markVerificationSent(publicRequestId)
            } catch (_: Exception) {
                store.markVerificationUnavailable(publicRequestId)
            }
        }
        return PublicAccountDeletionReceipt(publicRequestId)
    }

    fun verify(
        requestId: String?,
        verificationCode: String?,
    ): PublicAccountDeletionVerification {
        val normalizedRequestId = canonicalUuid(requestId)
            ?: return PublicAccountDeletionVerification("", null)
        if (!operationallyReady()) {
            return PublicAccountDeletionVerification(normalizedRequestId, null)
        }
        val code = verificationCode?.trim()?.takeIf { it.length in 8..32 }
            ?: return PublicAccountDeletionVerification(normalizedRequestId, null)
        val deletionGrant = secrets.newDeletionGrant()
        val verified = runCatching {
            store.verifyAndMintGrant(normalizedRequestId, code, deletionGrant)
        }.getOrDefault(false)
        return PublicAccountDeletionVerification(
            requestId = normalizedRequestId,
            deletionGrant = deletionGrant.takeIf { verified },
        )
    }

    fun confirm(
        requestId: String?,
        deletionGrant: String?,
    ): PublicAccountDeletionConfirmation {
        if (!operationallyReady()) {
            return PublicAccountDeletionConfirmation.NEEDS_SUPPORT
        }
        val normalizedRequestId = canonicalUuid(requestId)
            ?: return PublicAccountDeletionConfirmation.NEEDS_REVERIFICATION
        val grant = deletionGrant?.trim()?.takeIf { it.length in 32..128 }
            ?: return PublicAccountDeletionConfirmation.NEEDS_REVERIFICATION
        val claimed = runCatching {
            store.claimDeletion(normalizedRequestId, grant)
        }.getOrNull() ?: return PublicAccountDeletionConfirmation.NEEDS_REVERIFICATION

        if (claimed.manualReviewRequired) {
            store.markFailed(
                claimed.requestId,
                AccountDeletionFailureCode.PROVIDER_VERIFICATION_REQUIRED,
            )
            return PublicAccountDeletionConfirmation.NEEDS_SUPPORT
        }

        val memberId = claimed.memberId
        val generation = claimed.observedSessionGeneration
        if (memberId == null || generation == null) {
            // A verified decoy follows the same public terminal response but never reaches cleanup.
            store.markCompleted(claimed.requestId)
            return PublicAccountDeletionConfirmation.ACCEPTED
        }

        return try {
            memberUseCase.withdrawAfterExternalIdentityVerification(
                memberId = memberId,
                presentedSessionGeneration = generation,
            )
            store.markCompleted(claimed.requestId)
            PublicAccountDeletionConfirmation.ACCEPTED
        } catch (error: BusinessException) {
            when (error.errorCode) {
                ErrorCode.INVALID_TOKEN,
                ErrorCode.UNAUTHORIZED -> {
                    store.markFailed(claimed.requestId, AccountDeletionFailureCode.SESSION_CHANGED)
                    PublicAccountDeletionConfirmation.NEEDS_REVERIFICATION
                }

                ErrorCode.INVALID_STATE -> {
                    store.markFailed(claimed.requestId, AccountDeletionFailureCode.OWNER_ACTION_REQUIRED)
                    PublicAccountDeletionConfirmation.NEEDS_SUPPORT
                }

                else -> {
                    store.markFailed(claimed.requestId, AccountDeletionFailureCode.CLEANUP_FAILED)
                    PublicAccountDeletionConfirmation.NEEDS_SUPPORT
                }
            }
        } catch (_: Exception) {
            store.markFailed(claimed.requestId, AccountDeletionFailureCode.CLEANUP_FAILED)
            PublicAccountDeletionConfirmation.NEEDS_SUPPORT
        }
    }

    private fun canonicalUuid(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        val canonical = runCatching { UUID.fromString(trimmed).toString() }.getOrNull()
            ?: return null
        return canonical.takeIf { it == trimmed.lowercase(Locale.ROOT) }
    }

    private fun operationallyReady(): Boolean =
        properties.corePolicyReady() && verificationPort.isConfigured()
}
