package com.noLate.auth.apple

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.application.service.SocialIdentityVerifier
import com.noLate.member.application.service.VerifiedSocialIdentity
import com.noLate.member.domain.member.LoginType
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

interface AppleTokenLifecycle {
    /**
     * Reserves the one-shot code and captures the encrypted provider credential before returning.
     * This method must be called without an ambient member transaction.
     */
    fun exchangeAndCapture(
        identity: VerifiedSocialIdentity,
        authorizationCode: String?,
        nonce: String?,
    ): AppleCredentialCapture

    /** Binds a captured credential inside the final member/session transaction. */
    fun bindCapture(memberId: Long, capture: AppleCredentialCapture)

    /** Makes an unbound capture immediately eligible for durable compensation. */
    fun abandonCapture(capture: AppleCredentialCapture)

    /** Queues deletion-time revocation in the same transaction as local account cleanup. */
    fun queueRevocation(memberId: Long, appleSubject: String?): AppleRevocationQueueResult
}

/**
 * Non-secret handle to an encrypted capture. Diagnostic rendering cannot expose provider values.
 */
class AppleCredentialCapture internal constructor(
    internal val credentialId: Long,
    internal val credentialKey: String,
    internal val appleSubjectHash: String,
    internal val compensationOwner: Boolean,
)

data class AppleRevocationQueueResult(
    val manualAppleRevocationRequired: Boolean,
)

class AppleRevocationRequested

private class AppleCaptureInsertConflict : RuntimeException()

@Service
class AppleCredentialPersistenceCoordinator(
    private val receiptRepository: AppleAuthorizationCodeReceiptRepository,
    private val credentialRepository: AppleProviderCredentialRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val properties: AppleTokenLifecycleProperties,
    private val clock: Clock,
) {
    /**
     * A committed immutable receipt is the local consume fence. REQUIRES_NEW is essential:
     * provider I/O happens only after this transaction has ended, so a later member rollback can
     * never make the same authorization code look unused.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reserveCode(codeHash: String, subjectHash: String): String {
        if (receiptRepository.existsByAuthorizationCodeHash(codeHash)) {
            throw replayFailure()
        }
        val receiptKey = UUID.randomUUID().toString()
        try {
            receiptRepository.saveAndFlush(
                AppleAuthorizationCodeReceipt(
                    receiptKey = receiptKey,
                    authorizationCodeHash = codeHash,
                    expectedSubjectHash = subjectHash,
                    clientId = properties.clientId,
                    reservedAt = Instant.now(clock),
                )
            )
        } catch (_: DataIntegrityViolationException) {
            // A concurrent reservation won the unique code-hash fence. Never retry at Apple.
            throw replayFailure()
        }
        return receiptKey
    }

    /**
     * Commits ciphertext independently of the eventual login/member transaction. The only
     * irreducible crash window is Apple's successful response before this small transaction
     * commits; avoiding it would require distributed 2PC that Apple does not offer.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun captureEncrypted(
        receiptKey: String,
        subjectHash: String,
        refreshTokenHash: String,
        credentialKey: String,
        encrypted: EncryptedAppleToken,
    ): AppleCredentialCapture {
        reusableCapture(refreshTokenHash, subjectHash)?.let { return it }
        val now = Instant.now(clock)
        val credential = AppleProviderCredential(
            credentialKey = credentialKey,
            sourceReceiptKey = receiptKey,
            memberId = null,
            appleSubjectHash = subjectHash,
            refreshTokenHash = refreshTokenHash,
            clientId = properties.clientId,
            encryptionKeyId = encrypted.keyId,
            initializationVector = encrypted.initializationVector,
            encryptedRefreshToken = encrypted.ciphertext,
            status = AppleProviderCredentialStatus.CAPTURED,
            captureExpiresAt =
                now.plusSeconds(properties.captureBindingDeadlineSeconds.coerceAtLeast(10)),
        )
        try {
            credentialRepository.saveAndFlush(credential)
        } catch (_: DataIntegrityViolationException) {
            // A concurrent fresh code can legitimately receive the same refresh token.
            throw AppleCaptureInsertConflict()
        }
        return credential.toCapture(compensationOwner = true)
    }

    /**
     * Resolves the unique-refresh race in a fresh transaction after the losing insert rolled back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun findReusableCapture(
        refreshTokenHash: String,
        subjectHash: String,
    ): AppleCredentialCapture =
        reusableCapture(refreshTokenHash, subjectHash)
            ?: throw BusinessException(ErrorCode.INVALID_CREDENTIALS)

    @Transactional(propagation = Propagation.MANDATORY)
    fun bind(memberId: Long, capture: AppleCredentialCapture) {
        val credential = credentialRepository.findByIdForUpdate(capture.credentialId)
            ?.takeIf { it.credentialKey == capture.credentialKey }
            ?: throw BusinessException(ErrorCode.INVALID_CREDENTIALS)
        if (!credential.bind(memberId, capture.appleSubjectHash, properties.clientId)) {
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS)
        }
        credentialRepository.flush()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun abandon(capture: AppleCredentialCapture, safeCode: String): Boolean {
        if (!capture.compensationOwner) return false
        val credential = credentialRepository.findByIdForUpdate(capture.credentialId)
            ?.takeIf { it.credentialKey == capture.credentialKey }
            ?: return false
        val changed = credential.abandonCapture(Instant.now(clock), safeCode)
        if (changed) {
            credentialRepository.flush()
            // Published before this REQUIRES_NEW commit; the listener only enqueues a wake signal
            // in AFTER_COMMIT and therefore cannot perform provider I/O on this thread.
            eventPublisher.publishEvent(AppleRevocationRequested())
        }
        return changed
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun queueForMember(memberId: Long, appleSubject: String?): AppleRevocationQueueResult {
        val now = Instant.now(clock)
        val credentials = credentialRepository.findAllRevocableByMemberIdForUpdate(memberId)
        val expectedSubjectHash = appleSubject
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::sha256)
        var queuedCredential = false
        var manualRequired = credentials.isEmpty() || expectedSubjectHash == null
        credentials.forEach { credential ->
            if (credential.status == AppleProviderCredentialStatus.MANUAL_ACTION) {
                manualRequired = true
                return@forEach
            }
            if (
                expectedSubjectHash == null ||
                credential.appleSubjectHash != expectedSubjectHash ||
                !credential.hasCompleteEnvelope()
            ) {
                credential.blockForManualReview("APPLE_MEMBER_CREDENTIAL_MISMATCH")
                manualRequired = true
                return@forEach
            }
            when (credential.status) {
                AppleProviderCredentialStatus.ACTIVE,
                AppleProviderCredentialStatus.CAPTURED,
                -> {
                    credential.queueForRevocation(now)
                    queuedCredential = true
                }
                AppleProviderCredentialStatus.PENDING,
                AppleProviderCredentialStatus.PROCESSING,
                -> queuedCredential = true
                AppleProviderCredentialStatus.BLOCKED -> manualRequired = true
                AppleProviderCredentialStatus.MANUAL_ACTION,
                AppleProviderCredentialStatus.REVOKED,
                -> Unit
            }
        }
        if (credentials.isNotEmpty()) {
            credentialRepository.flush()
        }
        if (queuedCredential) {
            eventPublisher.publishEvent(AppleRevocationRequested())
        }

        if (manualRequired) {
            // This tombstone intentionally has no member/subject/provider value. The authenticated
            // response carries the one-shot user instruction while operations need only a durable
            // aggregate count; retaining a deleted account identifier adds no recovery value.
            credentialRepository.saveAndFlush(
                AppleProviderCredential(
                    memberId = null,
                    appleSubjectHash = null,
                    clientId = properties.clientId.ifBlank { "UNCONFIGURED" },
                    status = AppleProviderCredentialStatus.MANUAL_ACTION,
                    lastFailureCode = "APPLE_MANUAL_DISCONNECT_REQUIRED",
                )
            )
        }
        return AppleRevocationQueueResult(
            manualAppleRevocationRequired = manualRequired,
        )
    }

    private fun reusableCapture(
        refreshTokenHash: String,
        subjectHash: String,
    ): AppleCredentialCapture? {
        val snapshot = credentialRepository.findByRefreshTokenHash(refreshTokenHash) ?: return null
        val existing = snapshot.id
            ?.let(credentialRepository::findByIdForUpdate)
            ?: throw BusinessException(ErrorCode.INVALID_CREDENTIALS)
        if (
            existing.appleSubjectHash != subjectHash ||
            existing.clientId != properties.clientId ||
            !existing.hasCompleteEnvelope() ||
            existing.status !in setOf(
                AppleProviderCredentialStatus.CAPTURED,
                AppleProviderCredentialStatus.ACTIVE,
            )
        ) {
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS)
        }
        if (existing.status == AppleProviderCredentialStatus.CAPTURED) {
            // A new receipt using the same long-lived token gets a full bind window, but the
            // original receipt and source receipt pointer are never replaced.
            existing.captureExpiresAt = Instant.now(clock)
                .plusSeconds(properties.captureBindingDeadlineSeconds.coerceAtLeast(10))
            credentialRepository.flush()
        }
        return existing.toCapture(compensationOwner = false)
    }

    private fun AppleProviderCredential.toCapture(compensationOwner: Boolean) =
        AppleCredentialCapture(
            credentialId = requireNotNull(id),
            credentialKey = credentialKey,
            appleSubjectHash = requireNotNull(appleSubjectHash),
            compensationOwner = compensationOwner,
        )

    private fun replayFailure() =
        BusinessException(ErrorCode.INVALID_CREDENTIALS, "이미 사용된 Apple 인증 정보입니다.")
}

@Service
class AppleTokenLifecycleService(
    private val properties: AppleTokenLifecycleProperties,
    private val oauthClient: AppleOAuthClient,
    private val tokenCipher: AppleTokenCipher,
    private val persistence: AppleCredentialPersistenceCoordinator,
    private val socialIdentityVerifier: SocialIdentityVerifier,
) : AppleTokenLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun exchangeAndCapture(
        identity: VerifiedSocialIdentity,
        authorizationCode: String?,
        nonce: String?,
    ): AppleCredentialCapture {
        requireEnabled()
        if (identity.audience != properties.clientId) {
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS, "Apple 인증 대상이 일치하지 않습니다.")
        }
        val code = authorizationCode
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.takeIf { it.isPrintableAsciiToken(MAX_AUTHORIZATION_CODE_LENGTH) }
            ?: throw BusinessException(
                ErrorCode.INVALID_CREDENTIALS,
                "Apple authorization code가 필요합니다.",
            )
        val subjectHash = sha256(identity.subject)
        val receiptKey = persistence.reserveCode(sha256(code), subjectHash)

        // reserveCode's REQUIRES_NEW transaction is complete here. Do not add @Transactional to
        // this method: provider latency must never retain a member lock or any JDBC transaction.
        val response = try {
            oauthClient.exchangeAuthorizationCode(code)
        } catch (failure: AppleProviderCallException) {
            log.warn(
                "Apple authorization code exchange failed. reason={}, retryable={}",
                failure.safeCode,
                failure.retryable,
            )
            if (failure.providerError == AppleProviderError.INVALID_GRANT) {
                throw BusinessException(ErrorCode.INVALID_CREDENTIALS, "유효하지 않은 Apple 인증 정보입니다.")
            }
            throw BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE)
        }
        if (!response.refreshToken.isPrintableAsciiToken(MAX_PROVIDER_TOKEN_LENGTH)) {
            throw BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE)
        }

        val credentialKey = UUID.randomUUID().toString()
        val encrypted = tokenCipher.encrypt(credentialKey, response.refreshToken)
        val refreshTokenHash = sha256(response.refreshToken)
        val capture = try {
            persistence.captureEncrypted(
                receiptKey = receiptKey,
                subjectHash = subjectHash,
                refreshTokenHash = refreshTokenHash,
                credentialKey = credentialKey,
                encrypted = encrypted,
            )
        } catch (_: AppleCaptureInsertConflict) {
            persistence.findReusableCapture(refreshTokenHash, subjectHash)
        }

        try {
            if (
                !response.accessToken.isPrintableAsciiToken(MAX_PROVIDER_TOKEN_LENGTH) ||
                !response.identityToken.isPrintableAsciiToken(MAX_PROVIDER_TOKEN_LENGTH)
            ) {
                throw BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE)
            }
            val exchangedIdentity = socialIdentityVerifier.verify(
                loginType = LoginType.APPLE,
                providerToken = response.identityToken,
                nonce = nonce,
            )
            if (
                exchangedIdentity.subject != identity.subject ||
                exchangedIdentity.audience != properties.clientId
            ) {
                throw BusinessException(
                    ErrorCode.INVALID_CREDENTIALS,
                    "Apple 인증 주체가 일치하지 않습니다.",
                )
            }
            return capture
        } catch (failure: Exception) {
            // The refresh credential is already durable. A local verification failure converts
            // only a newly-created capture to compensation; an ACTIVE reused token is untouched.
            runCatching { persistence.abandon(capture, "POST_EXCHANGE_LOCAL_FAILURE") }
            throw failure
        }
    }

    override fun bindCapture(memberId: Long, capture: AppleCredentialCapture) {
        persistence.bind(memberId, capture)
    }

    override fun abandonCapture(capture: AppleCredentialCapture) {
        persistence.abandon(capture, "MEMBER_TRANSACTION_FAILED")
    }

    override fun queueRevocation(
        memberId: Long,
        appleSubject: String?,
    ): AppleRevocationQueueResult =
        persistence.queueForMember(memberId, appleSubject)

    private fun requireEnabled() {
        if (!properties.enabled) {
            throw BusinessException(
                ErrorCode.INVALID_STATE,
                "Apple 로그인 서버 token lifecycle 설정이 완료되지 않았습니다.",
            )
        }
        try {
            properties.requireReady()
        } catch (_: IllegalStateException) {
            throw BusinessException(
                ErrorCode.INVALID_STATE,
                "Apple 로그인 서버 token lifecycle 설정이 완료되지 않았습니다.",
            )
        }
    }

    private fun String.isPrintableAsciiToken(maxLength: Int): Boolean =
        length in 1..maxLength && all { it.code in 0x21..0x7E }

    private companion object {
        const val MAX_AUTHORIZATION_CODE_LENGTH = 2_048
        const val MAX_PROVIDER_TOKEN_LENGTH = 8_192
    }
}

internal fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
