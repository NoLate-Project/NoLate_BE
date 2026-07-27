package com.noLate.auth.apple

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.noLate.auth.infrastructure.RefreshTokenRepository
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.application.service.SocialIdentityVerifier
import com.noLate.member.application.service.VerifiedSocialIdentity
import com.noLate.member.application.useCase.MemberUseCase
import com.noLate.member.application.useCase.MemberWithdrawalResult
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.reset
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:apple-account-lifecycle;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.push.enabled=false",
        "auth.social.apple.audiences=com.nolate.test",
        "auth.social.apple.token-lifecycle.enabled=true",
        "auth.social.apple.token-lifecycle.client-id=com.nolate.test",
        "auth.social.apple.token-lifecycle.team-id=TEAM123456",
        "auth.social.apple.token-lifecycle.key-id=KEY1234567",
        "auth.social.apple.token-lifecycle.private-key=test-not-used-by-mocked-client",
        "auth.social.apple.token-lifecycle.encryption.current-key-id=token-v1",
        "auth.social.apple.token-lifecycle.encryption.current-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "auth.social.apple.token-lifecycle.revocation.worker-enabled=true",
        "auth.social.apple.token-lifecycle.revocation.fixed-delay-ms=86400000",
        "auth.social.apple.token-lifecycle.revocation.retry-delay-seconds=60",
    ]
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AppleAccountLifecycleIntegrationTest @Autowired constructor(
    private val memberUseCase: MemberUseCase,
    private val memberRepository: MemberRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val credentialRepository: AppleProviderCredentialRepository,
    private val receiptRepository: AppleAuthorizationCodeReceiptRepository,
    private val tokenCipher: AppleTokenCipher,
) {
    @MockBean
    lateinit var oauthClient: AppleOAuthClient

    @MockBean
    lateinit var identityVerifier: SocialIdentityVerifier

    @BeforeEach
    fun cleanDatabase() {
        refreshTokenRepository.deleteAll()
        credentialRepository.deleteAll()
        receiptRepository.deleteAll()
        memberRepository.deleteAll()
        reset(oauthClient, identityVerifier)
    }

    @Test
    fun `concurrent duplicate Apple login exchanges a single-use code exactly once`() {
        val member = memberRepository.saveAndFlush(
            appleMember(
                email = "apple-concurrent@example.com",
                subject = "apple-concurrent-subject",
            )
        )
        val identity = VerifiedSocialIdentity(
            subject = "apple-concurrent-subject",
            email = member.email,
            name = null,
            audience = "com.nolate.test",
        )
        whenever(
            identityVerifier.verify(eq(LoginType.APPLE), any(), eq("nonce"))
        ).thenReturn(identity)
        whenever(oauthClient.exchangeAuthorizationCode("single-use-code")).thenReturn(
            AppleTokenExchangeResult(
                refreshToken = "provider-refresh-token",
                accessToken = "provider-access-token",
                identityToken = "exchanged-identity-token",
            )
        )

        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val outcomes = try {
            val futures = (1..2).map {
                executor.submit {
                    start.await(5, TimeUnit.SECONDS)
                    memberUseCase.loginSns(
                        loginType = LoginType.APPLE,
                        providerToken = "initial-identity-token",
                        nonce = "nonce",
                        authorizationCode = "single-use-code",
                    )
                }
            }
            start.countDown()
            futures.map { future ->
                runCatching { future.get(15, TimeUnit.SECONDS) }
            }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.isFailure })
        val replayFailure = outcomes.single { it.isFailure }.exceptionOrNull()
            as java.util.concurrent.ExecutionException
        val businessFailure = replayFailure.cause as BusinessException
        assertEquals(ErrorCode.INVALID_CREDENTIALS, businessFailure.errorCode)
        verify(oauthClient, times(1)).exchangeAuthorizationCode("single-use-code")
        val credential = credentialRepository.findAll().single()
        assertEquals(member.id, credential.memberId)
        assertEquals(AppleProviderCredentialStatus.ACTIVE, credential.status)
        assertFalse(credential.encryptedRefreshToken!!.contains("provider-refresh-token"))
        assertFalse(credential.encryptedRefreshToken!!.contains("provider-access-token"))
        assertEquals(1, receiptRepository.count())
        assertFalse(
            receiptRepository.findAll().single().authorizationCodeHash.contains("single-use-code")
        )
    }

    @Test
    fun `fresh codes reusing one refresh token retain every immutable replay receipt`() {
        memberRepository.saveAndFlush(
            appleMember(
                email = "apple-repeat@example.com",
                subject = "apple-repeat-subject",
            )
        )
        val identity = VerifiedSocialIdentity(
            subject = "apple-repeat-subject",
            email = "apple-repeat@example.com",
            name = null,
            audience = "com.nolate.test",
        )
        whenever(identityVerifier.verify(eq(LoginType.APPLE), any(), eq("nonce")))
            .thenReturn(identity)
        whenever(oauthClient.exchangeAuthorizationCode(any())).thenReturn(
            AppleTokenExchangeResult(
                refreshToken = "same-long-lived-refresh",
                accessToken = "ephemeral-access",
                identityToken = "exchanged-identity",
            )
        )

        memberUseCase.loginSns(
            LoginType.APPLE,
            "initial-one",
            "nonce",
            "fresh-code-one",
        )
        memberUseCase.loginSns(
            LoginType.APPLE,
            "initial-two",
            "nonce",
            "fresh-code-two",
        )

        assertEquals(2, receiptRepository.count())
        assertEquals(1, credentialRepository.count())
        val hashes = receiptRepository.findAll().map { it.authorizationCodeHash }.toSet()
        assertEquals(setOf(sha256("fresh-code-one"), sha256("fresh-code-two")), hashes)
        val replay = assertThrows<BusinessException> {
            memberUseCase.loginSns(
                LoginType.APPLE,
                "initial-replay",
                "nonce",
                "fresh-code-one",
            )
        }
        assertEquals(ErrorCode.INVALID_CREDENTIALS, replay.errorCode)
        verify(oauthClient, times(2)).exchangeAuthorizationCode(any())
    }

    @Test
    fun `Apple provider call holds no transaction or member lock and withdrawal may win`() {
        val member = memberRepository.saveAndFlush(
            appleMember(
                email = "apple-login-withdraw@example.com",
                subject = "apple-login-withdraw-subject",
                sessionGeneration = 5,
            )
        )
        val memberId = requireNotNull(member.id)
        val identity = VerifiedSocialIdentity(
            subject = "apple-login-withdraw-subject",
            email = member.email,
            name = null,
            audience = "com.nolate.test",
        )
        whenever(
            identityVerifier.verify(eq(LoginType.APPLE), any(), eq("nonce"))
        ).thenReturn(identity)
        val exchangeEntered = CountDownLatch(1)
        val releaseExchange = CountDownLatch(1)
        val providerSawTransaction = AtomicBoolean(true)
        whenever(oauthClient.exchangeAuthorizationCode("race-code")).thenAnswer {
            providerSawTransaction.set(TransactionSynchronizationManager.isActualTransactionActive())
            exchangeEntered.countDown()
            check(releaseExchange.await(10, TimeUnit.SECONDS))
            AppleTokenExchangeResult(
                refreshToken = "race-provider-refresh",
                accessToken = "race-provider-access",
                identityToken = "race-exchanged-identity",
            )
        }
        whenever(oauthClient.revokeRefreshToken("race-provider-refresh")).thenThrow(
            AppleProviderCallException("APPLE_AUTH_REVOKE_IO", retryable = true)
        )

        val executor = Executors.newFixedThreadPool(2)
        try {
            val login = executor.submit {
                memberUseCase.loginSns(
                    loginType = LoginType.APPLE,
                    providerToken = "race-initial-identity",
                    nonce = "nonce",
                    authorizationCode = "race-code",
                )
            }
            assertEquals(true, exchangeEntered.await(10, TimeUnit.SECONDS))

            val withdrawal = executor.submit<MemberWithdrawalResult> {
                memberUseCase.withdraw(memberId, 5L, null)
            }
            // This completes while the Apple call is still blocked. A surrounding login
            // transaction/member lock would make this timeout.
            val withdrawalResult = withdrawal.get(5, TimeUnit.SECONDS)
            assertEquals(true, withdrawalResult.manualAppleRevocationRequired)
            releaseExchange.countDown()

            val loginFailure = runCatching { login.get(15, TimeUnit.SECONDS) }.exceptionOrNull()
            assertNotNull(loginFailure)
        } finally {
            releaseExchange.countDown()
            executor.shutdownNow()
        }

        val deletedMember = memberRepository.findById(memberId).orElseThrow()
        assertEquals(true, deletedMember.deleted)
        assertFalse(providerSawTransaction.get())
        waitUntil(Duration.ofSeconds(5)) {
            credentialRepository.findAll()
                .any {
                    it.status == AppleProviderCredentialStatus.PENDING &&
                        it.attemptCount >= 1 &&
                        it.encryptedRefreshToken != null
                }
        }
        val queued = credentialRepository.findAll().single {
            it.status != AppleProviderCredentialStatus.MANUAL_ACTION
        }
        assertEquals(AppleProviderCredentialStatus.PENDING, queued.status)
        assertNotNull(queued.encryptedRefreshToken)
        val manual = credentialRepository.findAll()
            .single { it.status == AppleProviderCredentialStatus.MANUAL_ACTION }
        assertNull(manual.memberId)
        assertNull(manual.appleSubjectHash)
        assertNull(manual.encryptedRefreshToken)
        verify(oauthClient, times(1)).exchangeAuthorizationCode("race-code")
    }

    @Test
    fun `revoke network failure does not roll back local cleanup or leak provider values`() {
        val member = memberRepository.saveAndFlush(
            appleMember(
                email = "apple-withdraw@example.com",
                subject = "apple-withdraw-subject",
                sessionGeneration = 5,
            )
        )
        val memberId = requireNotNull(member.id)
        val envelope = tokenCipher.encrypt("credential-withdraw", "refresh-secret-never-log")
        val credential = credentialRepository.saveAndFlush(
            AppleProviderCredential(
                credentialKey = "credential-withdraw",
                sourceReceiptKey = "receipt-withdraw",
                memberId = memberId,
                appleSubjectHash = sha256("apple-withdraw-subject"),
                refreshTokenHash = "c".repeat(64),
                clientId = "com.nolate.test",
                encryptionKeyId = envelope.keyId,
                initializationVector = envelope.initializationVector,
                encryptedRefreshToken = envelope.ciphertext,
                status = AppleProviderCredentialStatus.ACTIVE,
            )
        )
        whenever(oauthClient.revokeRefreshToken("refresh-secret-never-log")).thenThrow(
            IllegalStateException("provider-error-body-never-log")
        )
        val logger = LoggerFactory.getLogger(AppleTokenRevocationWorker::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            assertDoesNotThrow {
                memberUseCase.withdraw(memberId, 5L, null)
            }
            waitUntil(Duration.ofSeconds(5)) {
                credentialRepository.findById(requireNotNull(credential.id))
                    .orElseThrow()
                    .let {
                        it.attemptCount >= 1 &&
                            it.status == AppleProviderCredentialStatus.PENDING
                    }
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        val deletedMember = memberRepository.findById(memberId).orElseThrow()
        assertEquals(true, deletedMember.deleted)
        assertNull(deletedMember.snsId)
        assertEquals("", deletedMember.password)

        val retry = credentialRepository.findById(requireNotNull(credential.id)).orElseThrow()
        assertEquals(AppleProviderCredentialStatus.PENDING, retry.status)
        assertEquals(1, retry.attemptCount)
        assertNotNull(retry.nextAttemptAt)
        assertNotNull(retry.encryptedRefreshToken)
        assertEquals("APPLE_REVOKE_ILLEGALSTATEEXCEPTION", retry.lastFailureCode)

        val renderedLogs = appender.list.joinToString("\n") { it.formattedMessage }
        assertFalse(renderedLogs.contains("refresh-secret-never-log"))
        assertFalse(renderedLogs.contains("provider-error-body-never-log"))
        assertFalse(renderedLogs.contains(envelope.ciphertext))
    }

    @Test
    fun `externally verified Apple withdrawal commits cleanup with durable revoke retry`() {
        val member = memberRepository.saveAndFlush(
            appleMember(
                email = "external-apple-withdraw@example.com",
                subject = "external-apple-withdraw-subject",
                sessionGeneration = 14,
            )
        )
        val memberId = requireNotNull(member.id)
        val envelope = tokenCipher.encrypt(
            "credential-external-withdraw",
            "external-refresh-token",
        )
        val credential = credentialRepository.saveAndFlush(
            AppleProviderCredential(
                credentialKey = "credential-external-withdraw",
                sourceReceiptKey = "receipt-external-withdraw",
                memberId = memberId,
                appleSubjectHash = sha256("external-apple-withdraw-subject"),
                refreshTokenHash = sha256("external-refresh-token"),
                clientId = "com.nolate.test",
                encryptionKeyId = envelope.keyId,
                initializationVector = envelope.initializationVector,
                encryptedRefreshToken = envelope.ciphertext,
                status = AppleProviderCredentialStatus.ACTIVE,
            )
        )
        whenever(oauthClient.revokeRefreshToken("external-refresh-token")).thenThrow(
            IllegalStateException("synthetic-external-provider-failure")
        )

        assertDoesNotThrow {
            memberUseCase.withdrawAfterExternalIdentityVerification(memberId, 14L)
        }

        waitUntil(Duration.ofSeconds(5)) {
            credentialRepository.findById(requireNotNull(credential.id))
                .orElseThrow()
                .let {
                    it.status == AppleProviderCredentialStatus.PENDING &&
                        it.attemptCount >= 1
                }
        }
        val deletedMember = memberRepository.findById(memberId).orElseThrow()
        assertEquals(true, deletedMember.deleted)
        assertNull(deletedMember.snsId)

        val retry = credentialRepository.findById(requireNotNull(credential.id)).orElseThrow()
        assertEquals(AppleProviderCredentialStatus.PENDING, retry.status)
        assertNotNull(retry.encryptedRefreshToken)
        assertEquals("APPLE_REVOKE_ILLEGALSTATEEXCEPTION", retry.lastFailureCode)
    }

    @Test
    fun `slow provider revoke never extends authenticated withdrawal response latency`() {
        val member = memberRepository.saveAndFlush(
            appleMember(
                email = "apple-slow-revoke@example.com",
                subject = "apple-slow-revoke-subject",
                sessionGeneration = 9,
            )
        )
        val memberId = requireNotNull(member.id)
        val envelope = tokenCipher.encrypt("credential-slow-revoke", "slow-refresh-token")
        credentialRepository.saveAndFlush(
            AppleProviderCredential(
                credentialKey = "credential-slow-revoke",
                sourceReceiptKey = "receipt-slow-revoke",
                memberId = memberId,
                appleSubjectHash = sha256("apple-slow-revoke-subject"),
                refreshTokenHash = sha256("slow-refresh-token"),
                clientId = "com.nolate.test",
                encryptionKeyId = envelope.keyId,
                initializationVector = envelope.initializationVector,
                encryptedRefreshToken = envelope.ciphertext,
                status = AppleProviderCredentialStatus.ACTIVE,
            )
        )
        val providerEntered = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        whenever(oauthClient.revokeRefreshToken("slow-refresh-token")).thenAnswer {
            providerEntered.countDown()
            check(releaseProvider.await(10, TimeUnit.SECONDS))
            Unit
        }

        val executor = Executors.newSingleThreadExecutor()
        try {
            val withdrawal = executor.submit<MemberWithdrawalResult> {
                memberUseCase.withdraw(memberId, 9L, null)
            }
            val response = withdrawal.get(3, TimeUnit.SECONDS)
            assertFalse(response.manualAppleRevocationRequired)
            assertEquals(true, providerEntered.await(5, TimeUnit.SECONDS))
        } finally {
            releaseProvider.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `legacy Apple member without credential deletes locally and leaves value-free manual state`() {
        val member = memberRepository.saveAndFlush(
            appleMember(
                email = "apple-legacy@example.com",
                subject = "apple-legacy-subject",
                sessionGeneration = 11,
            )
        )

        val result = memberUseCase.withdraw(requireNotNull(member.id), 11L, null)

        assertEquals(true, result.manualAppleRevocationRequired)
        val tombstone = credentialRepository.findAll().single()
        assertEquals(AppleProviderCredentialStatus.MANUAL_ACTION, tombstone.status)
        assertNull(tombstone.memberId)
        assertNull(tombstone.appleSubjectHash)
        assertNull(tombstone.refreshTokenHash)
        assertNull(tombstone.encryptedRefreshToken)
        assertEquals(true, memberRepository.findById(member.id!!).orElseThrow().deleted)
        verify(oauthClient, never()).revokeRefreshToken(any())
    }

    @Test
    fun `withdrawal blocks mismatched Apple ownership and requires manual disconnect`() {
        val member = memberRepository.saveAndFlush(
            appleMember(
                email = "apple-mismatch@example.com",
                subject = "apple-member-subject",
                sessionGeneration = 12,
            )
        )
        val envelope = tokenCipher.encrypt("credential-mismatch", "mismatch-refresh")
        val credential = credentialRepository.saveAndFlush(
            AppleProviderCredential(
                credentialKey = "credential-mismatch",
                sourceReceiptKey = "receipt-mismatch",
                memberId = requireNotNull(member.id),
                appleSubjectHash = sha256("different-apple-subject"),
                refreshTokenHash = sha256("mismatch-refresh"),
                clientId = "com.nolate.test",
                encryptionKeyId = envelope.keyId,
                initializationVector = envelope.initializationVector,
                encryptedRefreshToken = envelope.ciphertext,
                status = AppleProviderCredentialStatus.ACTIVE,
            )
        )

        val result = memberUseCase.withdraw(requireNotNull(member.id), 12L, null)

        assertEquals(true, result.manualAppleRevocationRequired)
        val blocked = credentialRepository.findById(credential.id!!).orElseThrow()
        assertEquals(AppleProviderCredentialStatus.BLOCKED, blocked.status)
        assertEquals("APPLE_MEMBER_CREDENTIAL_MISMATCH", blocked.lastFailureCode)
        assertEquals(
            1,
            credentialRepository.countByStatus(AppleProviderCredentialStatus.MANUAL_ACTION),
        )
        verify(oauthClient, never()).revokeRefreshToken(any())
    }

    private fun waitUntil(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition did not become true before timeout" }
            Thread.sleep(20)
        }
    }

    private fun appleMember(
        email: String,
        subject: String,
        sessionGeneration: Long = 0,
    ): Member =
        Member(
            name = "Apple 사용자",
            password = "",
            email = email,
            loginType = LoginType.APPLE,
            snsId = subject,
            sessionGeneration = sessionGeneration,
        )

    private fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
