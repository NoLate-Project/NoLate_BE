package com.noLate.auth.apple

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.application.service.SocialIdentityVerifier
import com.noLate.member.application.service.VerifiedSocialIdentity
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

interface AppleTokenLifecycle {
    fun exchangeAuthorizationCode(
        memberId: Long,
        identity: VerifiedSocialIdentity,
        authorizationCode: String?,
        nonce: String?,
    ): AppleAuthorizationGrant

    fun storeGrant(memberId: Long, grant: AppleAuthorizationGrant)

    fun queueRevocation(memberId: Long)
}

/**
 * Contains an exchanged refresh token only until the surrounding login transaction encrypts it.
 * Deliberately not a data class: diagnostic rendering must never include provider credentials.
 */
class AppleAuthorizationGrant internal constructor(
    internal val credentialKey: String,
    internal val appleSubjectHash: String,
    internal val authorizationCodeHash: String,
    internal val refreshTokenHash: String,
    internal val refreshToken: String,
) {
    companion object {
        internal fun exchanged(
            credentialKey: String,
            subjectHash: String,
            authorizationCodeHash: String,
            refreshTokenHash: String,
            refreshToken: String,
        ): AppleAuthorizationGrant =
            AppleAuthorizationGrant(
                credentialKey = credentialKey,
                appleSubjectHash = subjectHash,
                authorizationCodeHash = authorizationCodeHash,
                refreshTokenHash = refreshTokenHash,
                refreshToken = refreshToken,
            )
    }
}

class AppleRevocationRequested(val memberId: Long)

@Service
class AppleTokenLifecycleService(
    private val properties: AppleTokenLifecycleProperties,
    private val oauthClient: AppleOAuthClient,
    private val tokenCipher: AppleTokenCipher,
    private val credentialRepository: AppleProviderCredentialRepository,
    private val socialIdentityVerifier: SocialIdentityVerifier,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) : AppleTokenLifecycle {

    @Transactional(propagation = Propagation.MANDATORY)
    override fun exchangeAuthorizationCode(
        memberId: Long,
        identity: VerifiedSocialIdentity,
        authorizationCode: String?,
        nonce: String?,
    ): AppleAuthorizationGrant {
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
        val codeHash = sha256(code)
        credentialRepository.findByAuthorizationCodeHash(codeHash)?.let {
            // Without a separate one-shot idempotency receipt, an HTTP response retry and a
            // credential replay are indistinguishable. Apple codes are five-minute/single-use,
            // so a locally consumed fingerprint must always fail closed.
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS, "이미 사용된 Apple 인증 정보입니다.")
        }

        val response = try {
            oauthClient.exchangeAuthorizationCode(code)
        } catch (failure: AppleProviderCallException) {
            if (!failure.retryable && failure.safeCode.endsWith("HTTP_400")) {
                throw BusinessException(ErrorCode.INVALID_CREDENTIALS, "유효하지 않은 Apple 인증 정보입니다.")
            }
            throw BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE)
        }
        if (
            !response.refreshToken.isPrintableAsciiToken(MAX_PROVIDER_TOKEN_LENGTH) ||
            !response.accessToken.isPrintableAsciiToken(MAX_PROVIDER_TOKEN_LENGTH) ||
            !response.identityToken.isPrintableAsciiToken(MAX_PROVIDER_TOKEN_LENGTH)
        ) {
            throw BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE)
        }
        val exchangedIdentity = socialIdentityVerifier.verify(
            loginType = com.noLate.member.domain.member.LoginType.APPLE,
            providerToken = response.identityToken,
            nonce = nonce,
        )
        if (
            exchangedIdentity.subject != identity.subject ||
            exchangedIdentity.audience != properties.clientId
        ) {
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS, "Apple 인증 주체가 일치하지 않습니다.")
        }

        return AppleAuthorizationGrant.exchanged(
            credentialKey = java.util.UUID.randomUUID().toString(),
            subjectHash = subjectHash,
            authorizationCodeHash = codeHash,
            refreshTokenHash = sha256(response.refreshToken),
            refreshToken = response.refreshToken,
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun storeGrant(memberId: Long, grant: AppleAuthorizationGrant) {
        requireEnabled()
        val refreshToken = grant.refreshToken
        val refreshTokenHash = grant.refreshTokenHash
        val credentialKey = grant.credentialKey
        credentialRepository.findByRefreshTokenHash(refreshTokenHash)?.let { snapshot ->
            val existing = snapshot.id
                ?.let(credentialRepository::findByIdForUpdate)
                ?: throw BusinessException(ErrorCode.INVALID_CREDENTIALS)
            if (
                existing.memberId == memberId &&
                existing.appleSubjectHash == grant.appleSubjectHash &&
                existing.clientId == properties.clientId &&
                existing.status == AppleProviderCredentialStatus.ACTIVE
            ) {
                // Apple may return the same long-lived refresh token for a newer authorization
                // code. Move the replay receipt to the newest consumed code without duplicating
                // credential material.
                existing.authorizationCodeHash = grant.authorizationCodeHash
                credentialRepository.flush()
                return
            }
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS)
        }
        val encrypted = tokenCipher.encrypt(credentialKey, refreshToken)
        credentialRepository.saveAndFlush(
            AppleProviderCredential(
                credentialKey = credentialKey,
                memberId = memberId,
                appleSubjectHash = grant.appleSubjectHash,
                authorizationCodeHash = grant.authorizationCodeHash,
                refreshTokenHash = refreshTokenHash,
                clientId = properties.clientId,
                encryptionKeyId = encrypted.keyId,
                initializationVector = encrypted.initializationVector,
                encryptedRefreshToken = encrypted.ciphertext,
                status = AppleProviderCredentialStatus.ACTIVE,
            )
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun queueRevocation(memberId: Long) {
        if (!properties.enabled) return
        val now = Instant.now(clock)
        val credentials = credentialRepository.findAllRevocableByMemberIdForUpdate(memberId)
        if (credentials.isEmpty()) return
        credentials.forEach { it.queueForRevocation(now) }
        credentialRepository.flush()
        eventPublisher.publishEvent(AppleRevocationRequested(memberId))
    }

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

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun String.isPrintableAsciiToken(maxLength: Int): Boolean =
        length in 1..maxLength && all { it.code in 0x21..0x7E }

    private companion object {
        const val MAX_AUTHORIZATION_CODE_LENGTH = 2_048
        const val MAX_PROVIDER_TOKEN_LENGTH = 8_192
    }
}
