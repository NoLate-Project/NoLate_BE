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
import org.mockito.Mockito.reset
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
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
        assertFalse(credential.authorizationCodeHash!!.contains("single-use-code"))
    }

    @Test
    fun `login holding the member fence commits credential before concurrent withdrawal queues it`() {
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
        whenever(oauthClient.exchangeAuthorizationCode("race-code")).thenAnswer {
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

            val withdrawalStarted = CountDownLatch(1)
            val withdrawal = executor.submit {
                withdrawalStarted.countDown()
                memberUseCase.withdraw(memberId, 5L, null)
            }
            assertEquals(true, withdrawalStarted.await(5, TimeUnit.SECONDS))
            releaseExchange.countDown()

            login.get(15, TimeUnit.SECONDS)
            withdrawal.get(15, TimeUnit.SECONDS)
        } finally {
            releaseExchange.countDown()
            executor.shutdownNow()
        }

        val deletedMember = memberRepository.findById(memberId).orElseThrow()
        assertEquals(true, deletedMember.deleted)
        val queued = credentialRepository.findAll().single()
        assertEquals(AppleProviderCredentialStatus.PENDING, queued.status)
        assertEquals(1, queued.attemptCount)
        assertNotNull(queued.encryptedRefreshToken)
        verify(oauthClient, times(1)).exchangeAuthorizationCode("race-code")
        verify(oauthClient, times(1)).revokeRefreshToken("race-provider-refresh")
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
                memberId = memberId,
                appleSubjectHash = "a".repeat(64),
                authorizationCodeHash = "b".repeat(64),
                refreshTokenHash = "c".repeat(64),
                clientId = "com.nolate.test",
                encryptionKeyId = envelope.keyId,
                initializationVector = envelope.initializationVector,
                encryptedRefreshToken = envelope.ciphertext,
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
}
