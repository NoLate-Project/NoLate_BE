package com.noLate.auth.apple

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.application.service.SocialIdentityVerifier
import com.noLate.member.application.service.VerifiedSocialIdentity
import com.noLate.member.domain.member.LoginType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.capture
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

@ExtendWith(MockitoExtension::class)
class AppleTokenLifecycleServiceTest {
    @Mock
    lateinit var oauthClient: AppleOAuthClient

    @Mock
    lateinit var repository: AppleProviderCredentialRepository

    @Mock
    lateinit var verifier: SocialIdentityVerifier

    @Mock
    lateinit var publisher: ApplicationEventPublisher

    private val properties = AppleTokenLifecycleProperties(
        enabled = true,
        clientId = "com.nolate.test",
        teamId = "TEAM123456",
        keyId = "KEY1234567",
        privateKey = "not-read-by-this-test",
        currentEncryptionKeyId = "token-v1",
        currentEncryptionKey = Base64.getEncoder().encodeToString(ByteArray(32) { 3 }),
    )
    private val clock = Clock.fixed(Instant.parse("2026-07-26T01:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `unmatched initial Apple audience rejects before consuming authorization code`() {
        val failure = assertThrows<BusinessException> {
            service().exchangeAuthorizationCode(
                memberId = 41L,
                identity = VerifiedSocialIdentity(
                    "apple-subject",
                    null,
                    null,
                    "com.attacker.client",
                ),
                authorizationCode = "single-use-code",
                nonce = null,
            )
        }

        assertEquals(ErrorCode.INVALID_CREDENTIALS, failure.errorCode)
        verify(oauthClient, never()).exchangeAuthorizationCode(any())
    }

    @Test
    fun `expired or already consumed authorization code is rejected as invalid credentials`() {
        val identity = VerifiedSocialIdentity(
            "apple-subject",
            null,
            null,
            properties.clientId,
        )
        whenever(repository.findByAuthorizationCodeHash(any())).thenReturn(null)
        whenever(oauthClient.exchangeAuthorizationCode("expired-code")).thenThrow(
            AppleProviderCallException("APPLE_AUTH_TOKEN_HTTP_400", retryable = false)
        )

        val failure = assertThrows<BusinessException> {
            service().exchangeAuthorizationCode(41L, identity, "expired-code", null)
        }

        assertEquals(ErrorCode.INVALID_CREDENTIALS, failure.errorCode)
        verify(repository, never()).saveAndFlush(any())
    }

    @Test
    fun `verified authorization code stores only encrypted refresh credential`() {
        val service = service()
        val initial = VerifiedSocialIdentity(
            subject = "apple-subject",
            email = "relay@example.com",
            name = null,
            audience = properties.clientId,
        )
        val exchanged = AppleTokenExchangeResult(
            refreshToken = "provider-refresh-secret",
            accessToken = "ephemeral-access-secret",
            identityToken = "signed-exchanged-identity",
        )
        whenever(repository.findByAuthorizationCodeHash(any())).thenReturn(null)
        whenever(repository.findByRefreshTokenHash(any())).thenReturn(null)
        whenever(oauthClient.exchangeAuthorizationCode("single-use-code")).thenReturn(exchanged)
        whenever(
            verifier.verify(LoginType.APPLE, "signed-exchanged-identity", "nonce"),
        ).thenReturn(initial)
        whenever(repository.saveAndFlush(any<AppleProviderCredential>()))
            .thenAnswer { it.getArgument(0) }

        val grant = service.exchangeAuthorizationCode(
            memberId = 41L,
            identity = initial,
            authorizationCode = "single-use-code",
            nonce = "nonce",
        )
        service.storeGrant(41L, grant)

        val captor = ArgumentCaptor.forClass(AppleProviderCredential::class.java)
        verify(repository).saveAndFlush(capture(captor))
        val stored = captor.value
        assertEquals(41L, stored.memberId)
        assertEquals(AppleProviderCredentialStatus.ACTIVE, stored.status)
        assertNotEquals("provider-refresh-secret", stored.encryptedRefreshToken)
        assertFalse(stored.encryptedRefreshToken!!.contains("provider-refresh-secret"))
        assertEquals(
            "provider-refresh-secret",
            AppleTokenCipher(properties).decrypt(
                credentialKey = stored.credentialKey,
                keyId = requireNotNull(stored.encryptionKeyId),
                initializationVector = requireNotNull(stored.initializationVector),
                ciphertext = requireNotNull(stored.encryptedRefreshToken),
            ),
        )
    }

    @Test
    fun `authorization code replay fails closed without another provider call`() {
        val service = service()
        val initial = VerifiedSocialIdentity(
            "apple-subject",
            null,
            null,
            properties.clientId,
        )
        val existing = AppleProviderCredential(
            id = 9L,
            memberId = 41L,
            appleSubjectHash =
                "19b1be29b70d56ed6f1a3f018c4410c50c352b78ec82da494290e3813aec64ac",
            authorizationCodeHash =
                "8b8c1d8c325f6fb296365c34adbb58e33c6675e19e2dacc70446d37c7f7ab900",
            clientId = properties.clientId,
            status = AppleProviderCredentialStatus.ACTIVE,
        )
        // Use runtime hashes so the fixture remains coupled to the real replay comparison.
        whenever(repository.findByAuthorizationCodeHash(any())).thenReturn(existing.apply {
            appleSubjectHash = sha256("apple-subject")
            authorizationCodeHash = sha256("single-use-code")
        })

        val failure = assertThrows<BusinessException> {
            service.exchangeAuthorizationCode(
                memberId = 41L,
                identity = initial,
                authorizationCode = "single-use-code",
                nonce = null,
            )
        }

        assertEquals(ErrorCode.INVALID_CREDENTIALS, failure.errorCode)
        verify(oauthClient, never()).exchangeAuthorizationCode(any())
        verify(repository, never()).saveAndFlush(any())
    }

    @Test
    fun `new code may reuse refresh token but displaced old code still requires Apple and fails`() {
        val service = service()
        val identity = VerifiedSocialIdentity(
            "apple-subject",
            null,
            null,
            properties.clientId,
        )
        val existing = AppleProviderCredential(
            id = 9L,
            memberId = 41L,
            appleSubjectHash = sha256("apple-subject"),
            authorizationCodeHash = sha256("old-code"),
            refreshTokenHash = sha256("same-refresh"),
            clientId = properties.clientId,
            status = AppleProviderCredentialStatus.ACTIVE,
        )
        whenever(repository.findByAuthorizationCodeHash(any())).thenAnswer { invocation ->
            val requestedHash = invocation.getArgument<String>(0)
            existing.takeIf { it.authorizationCodeHash == requestedHash }
        }
        whenever(repository.findByRefreshTokenHash(any())).thenReturn(existing)
        whenever(repository.findByIdForUpdate(9L)).thenReturn(existing)
        whenever(oauthClient.exchangeAuthorizationCode("new-code")).thenReturn(
            AppleTokenExchangeResult("same-refresh", "access", "new-identity")
        )
        whenever(verifier.verify(LoginType.APPLE, "new-identity", null)).thenReturn(identity)
        whenever(oauthClient.exchangeAuthorizationCode("old-code")).thenThrow(
            AppleProviderCallException("APPLE_AUTH_TOKEN_HTTP_400", retryable = false)
        )

        val newGrant = service.exchangeAuthorizationCode(41L, identity, "new-code", null)
        service.storeGrant(41L, newGrant)
        assertEquals(sha256("new-code"), existing.authorizationCodeHash)

        val oldCodeFailure = assertThrows<BusinessException> {
            service.exchangeAuthorizationCode(41L, identity, "old-code", null)
        }

        assertEquals(ErrorCode.INVALID_CREDENTIALS, oldCodeFailure.errorCode)
        verify(oauthClient).exchangeAuthorizationCode("new-code")
        verify(oauthClient).exchangeAuthorizationCode("old-code")
        verify(repository, never()).saveAndFlush(any())
    }

    @Test
    fun `exchanged identity subject mismatch rejects credential`() {
        val service = service()
        val initial = VerifiedSocialIdentity(
            "apple-subject-a",
            null,
            null,
            properties.clientId,
        )
        whenever(repository.findByAuthorizationCodeHash(any())).thenReturn(null)
        whenever(oauthClient.exchangeAuthorizationCode("single-use-code")).thenReturn(
            AppleTokenExchangeResult("refresh", "access", "identity")
        )
        whenever(verifier.verify(LoginType.APPLE, "identity", null)).thenReturn(
            VerifiedSocialIdentity("apple-subject-b", null, null, properties.clientId)
        )

        val failure = assertThrows<BusinessException> {
            service.exchangeAuthorizationCode(41L, initial, "single-use-code", null)
        }

        assertEquals(ErrorCode.INVALID_CREDENTIALS, failure.errorCode)
        verify(repository, never()).saveAndFlush(any())
    }

    @Test
    fun `exchanged identity must retain the exact configured client audience`() {
        val service = service()
        val initial = VerifiedSocialIdentity(
            "apple-subject",
            null,
            null,
            properties.clientId,
        )
        whenever(repository.findByAuthorizationCodeHash(any())).thenReturn(null)
        whenever(oauthClient.exchangeAuthorizationCode("single-use-code")).thenReturn(
            AppleTokenExchangeResult("refresh", "access", "identity")
        )
        whenever(verifier.verify(LoginType.APPLE, "identity", null)).thenReturn(
            VerifiedSocialIdentity("apple-subject", null, null, "com.attacker.client")
        )

        val failure = assertThrows<BusinessException> {
            service.exchangeAuthorizationCode(41L, initial, "single-use-code", null)
        }

        assertEquals(ErrorCode.INVALID_CREDENTIALS, failure.errorCode)
        verify(repository, never()).saveAndFlush(any())
    }

    private fun service(): AppleTokenLifecycleService =
        AppleTokenLifecycleService(
            properties = properties,
            oauthClient = oauthClient,
            tokenCipher = AppleTokenCipher(properties),
            credentialRepository = repository,
            socialIdentityVerifier = verifier,
            eventPublisher = publisher,
            clock = clock,
        )

    private fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
