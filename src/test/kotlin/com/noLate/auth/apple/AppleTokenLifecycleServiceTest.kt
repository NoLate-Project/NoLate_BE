package com.noLate.auth.apple

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.application.service.SocialIdentityVerifier
import com.noLate.member.application.service.VerifiedSocialIdentity
import com.noLate.member.domain.member.LoginType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Base64

@ExtendWith(MockitoExtension::class)
class AppleTokenLifecycleServiceTest {
    @Mock
    lateinit var oauthClient: AppleOAuthClient

    @Mock
    lateinit var persistence: AppleCredentialPersistenceCoordinator

    @Mock
    lateinit var verifier: SocialIdentityVerifier

    private val properties = AppleTokenLifecycleProperties(
        enabled = true,
        clientId = "com.nolate.test",
        teamId = "TEAM123456",
        keyId = "KEY1234567",
        privateKey = "not-read-by-this-test",
        currentEncryptionKeyId = "token-v1",
        currentEncryptionKey = Base64.getEncoder().encodeToString(ByteArray(32) { 3 }),
    )
    private val identity = VerifiedSocialIdentity(
        subject = "apple-subject",
        email = "relay@example.com",
        name = null,
        audience = properties.clientId,
    )

    @Test
    fun `unmatched initial Apple audience rejects before reserving or calling provider`() {
        val failure = assertThrows<BusinessException> {
            service().exchangeAndCapture(
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
        verify(persistence, never()).reserveCode(any(), any())
        verify(oauthClient, never()).exchangeAuthorizationCode(any())
    }

    @Test
    fun `only token invalid_grant is exposed as invalid credentials`() {
        whenever(persistence.reserveCode(any(), any())).thenReturn("receipt-key")
        whenever(oauthClient.exchangeAuthorizationCode("expired-code")).thenThrow(
            AppleProviderCallException(
                safeCode = "APPLE_AUTH_TOKEN_HTTP_400_INVALID_GRANT",
                retryable = false,
                providerError = AppleProviderError.INVALID_GRANT,
            )
        )

        val failure = assertThrows<BusinessException> {
            service().exchangeAndCapture(identity, "expired-code", null)
        }

        assertEquals(ErrorCode.INVALID_CREDENTIALS, failure.errorCode)
    }

    @Test
    fun `token invalid_client remains an operational failure`() {
        whenever(persistence.reserveCode(any(), any())).thenReturn("receipt-key")
        whenever(oauthClient.exchangeAuthorizationCode("new-code")).thenThrow(
            AppleProviderCallException(
                safeCode = "APPLE_AUTH_TOKEN_HTTP_400_INVALID_CLIENT",
                retryable = false,
                providerError = AppleProviderError.INVALID_CLIENT,
            )
        )

        val failure = assertThrows<BusinessException> {
            service().exchangeAndCapture(identity, "new-code", null)
        }

        assertEquals(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, failure.errorCode)
    }

    @Test
    fun `encrypted capture commits before exchanged identity verification`() {
        val capture = mockCapture()
        whenever(persistence.reserveCode(any(), any())).thenReturn("receipt-key")
        whenever(oauthClient.exchangeAuthorizationCode("single-use-code")).thenReturn(
            AppleTokenExchangeResult(
                refreshToken = "provider-refresh-secret",
                accessToken = "ephemeral-access",
                identityToken = "exchanged-identity",
            )
        )
        whenever(
            persistence.captureEncrypted(
                receiptKey = eq("receipt-key"),
                subjectHash = any(),
                refreshTokenHash = any(),
                credentialKey = any(),
                encrypted = any(),
            )
        ).thenReturn(capture)
        whenever(verifier.verify(LoginType.APPLE, "exchanged-identity", "nonce"))
            .thenReturn(identity)

        val result = service().exchangeAndCapture(identity, "single-use-code", "nonce")

        assertEquals(capture, result)
        val order = org.mockito.kotlin.inOrder(persistence, oauthClient, verifier)
        order.verify(persistence).reserveCode(any(), any())
        order.verify(oauthClient).exchangeAuthorizationCode("single-use-code")
        order.verify(persistence).captureEncrypted(
            receiptKey = eq("receipt-key"),
            subjectHash = any(),
            refreshTokenHash = any(),
            credentialKey = any(),
            encrypted = any(),
        )
        order.verify(verifier).verify(LoginType.APPLE, "exchanged-identity", "nonce")
    }

    @Test
    fun `post-exchange subject mismatch queues captured token for compensation`() {
        val capture = mockCapture()
        whenever(persistence.reserveCode(any(), any())).thenReturn("receipt-key")
        whenever(oauthClient.exchangeAuthorizationCode("single-use-code")).thenReturn(
            AppleTokenExchangeResult("provider-refresh", "access", "exchanged-identity")
        )
        whenever(persistence.captureEncrypted(any(), any(), any(), any(), any()))
            .thenReturn(capture)
        whenever(verifier.verify(LoginType.APPLE, "exchanged-identity", null)).thenReturn(
            VerifiedSocialIdentity("another-subject", null, null, properties.clientId)
        )

        val failure = assertThrows<BusinessException> {
            service().exchangeAndCapture(identity, "single-use-code", null)
        }

        assertEquals(ErrorCode.INVALID_CREDENTIALS, failure.errorCode)
        verify(persistence).abandon(capture, "POST_EXCHANGE_LOCAL_FAILURE")
    }

    @Test
    fun `missing identity fields are rejected only after encrypted refresh capture`() {
        val capture = mockCapture()
        whenever(persistence.reserveCode(any(), any())).thenReturn("receipt-key")
        whenever(oauthClient.exchangeAuthorizationCode("single-use-code")).thenReturn(
            AppleTokenExchangeResult(
                refreshToken = "provider-refresh",
                accessToken = "",
                identityToken = "",
            )
        )
        whenever(persistence.captureEncrypted(any(), any(), any(), any(), any()))
            .thenReturn(capture)

        val failure = assertThrows<BusinessException> {
            service().exchangeAndCapture(identity, "single-use-code", null)
        }

        assertEquals(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, failure.errorCode)
        verify(persistence).captureEncrypted(any(), any(), any(), any(), any())
        verify(persistence).abandon(capture, "POST_EXCHANGE_LOCAL_FAILURE")
        verify(verifier, never()).verify(any(), any(), any())
    }

    private fun service(): AppleTokenLifecycleService =
        AppleTokenLifecycleService(
            properties = properties,
            oauthClient = oauthClient,
            tokenCipher = AppleTokenCipher(properties),
            persistence = persistence,
            socialIdentityVerifier = verifier,
        )

    private fun mockCapture() =
        AppleCredentialCapture(
            credentialId = 7L,
            credentialKey = "credential-key",
            appleSubjectHash = sha256("apple-subject"),
            compensationOwner = true,
        )
}
