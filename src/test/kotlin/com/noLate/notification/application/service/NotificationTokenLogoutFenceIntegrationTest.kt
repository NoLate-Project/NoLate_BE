package com.noLate.notification.application.service

import com.noLate.auth.application.RefreshTokenService
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.application.service.MemberSessionFenceService
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@Import(
    NotificationTokenService::class,
    NotificationTokenWriter::class,
    MemberSessionFenceService::class,
    NotificationTokenLogoutFenceTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:notification-token-logout-fence;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationTokenLogoutFenceIntegrationTest @Autowired constructor(
    private val tokenService: NotificationTokenService,
    private val sessionFenceService: MemberSessionFenceService,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val memberRepository: MemberRepository,
    private val observer: BlockingNotificationTokenRegistrationObserver,
) {
    private val logoutAt = Instant.parse("2026-07-24T03:00:00Z")
    private val oldIssuedAt = logoutAt.minusSeconds(60)
    private val newIssuedAt = logoutAt.plusSeconds(60)

    @BeforeEach
    fun clean() {
        observer.reset()
        tokenRepository.deleteAll()
        memberRepository.deleteAll()
    }

    @Test
    fun `register linearized before logout is deleted by the later logout`() {
        val memberId = createMember("register-first@example.com")
        val registerLockedMember = CountDownLatch(1)
        val allowRegister = CountDownLatch(1)
        observer.block = {
            registerLockedMember.countDown()
            assertTrue(allowRegister.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newFixedThreadPool(2)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val logoutDone = CountDownLatch(1)

        executor.submit {
            runCatching {
                tokenService.registerToken(
                    memberId = memberId,
                    deviceId = "device-register-first",
                    platform = PushPlatform.ANDROID,
                    token = "token-register-first",
                    accessTokenIssuedAt = oldIssuedAt,
                )
            }.onFailure(failures::add)
        }
        assertTrue(registerLockedMember.await(5, TimeUnit.SECONDS))

        executor.submit {
            runCatching {
                sessionFenceService.invalidateSessionsAndLogout(memberId)
            }.onFailure(failures::add)
            logoutDone.countDown()
        }
        // logout은 같은 member row lock 뒤에서 기다려야 한다.
        assertEquals(1L, logoutDone.count)
        allowRegister.countDown()
        assertTrue(logoutDone.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertTrue(tokenRepository.findAllByMemberId(memberId).isEmpty())
        assertTrue(requireNotNull(memberRepository.findById(memberId).orElseThrow().tokensValidAfter)
            .compareTo(oldIssuedAt) > 0)
    }

    @Test
    fun `logout linearized before old register rejects stale access session`() {
        val memberId = createMember("logout-first@example.com")
        sessionFenceService.invalidateSessionsAndLogout(memberId)

        val failure = assertThrows<BusinessException> {
            tokenService.registerToken(
                memberId = memberId,
                deviceId = "device-logout-first",
                platform = PushPlatform.ANDROID,
                token = "token-logout-first",
                accessTokenIssuedAt = oldIssuedAt,
            )
        }

        assertEquals(ErrorCode.INVALID_TOKEN, failure.errorCode)
        assertTrue(tokenRepository.findAllByMemberId(memberId).isEmpty())
    }

    @Test
    fun `request delayed after security filter cannot commit after logout`() {
        val memberId = createMember("filter-passed@example.com")
        val filterPassed = CountDownLatch(1)
        val continueToWrite = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            filterPassed.countDown()
            continueToWrite.await()
            runCatching {
                tokenService.registerToken(
                    memberId = memberId,
                    deviceId = "device-filter-passed",
                    platform = PushPlatform.ANDROID,
                    token = "token-filter-passed",
                    accessTokenIssuedAt = oldIssuedAt,
                )
            }.onFailure(failure::add)
            done.countDown()
        }

        assertTrue(filterPassed.await(5, TimeUnit.SECONDS))
        sessionFenceService.invalidateSessionsAndLogout(memberId)
        continueToWrite.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(1, failure.size)
        assertEquals(ErrorCode.INVALID_TOKEN, (failure.single() as BusinessException).errorCode)
        assertTrue(tokenRepository.findAllByMemberId(memberId).isEmpty())
    }

    @Test
    fun `new account session wins while old logged-out session is rejected`() {
        val oldMemberId = createMember("old-session@example.com")
        val newMemberId = createMember("new-session@example.com")
        sessionFenceService.invalidateSessionsAndLogout(oldMemberId)

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newFixedThreadPool(2)

        executor.submit {
            ready.countDown()
            start.await()
            runCatching {
                tokenService.registerToken(
                    memberId = oldMemberId,
                    deviceId = "shared-device",
                    platform = PushPlatform.ANDROID,
                    token = "shared-case-sensitive-token",
                    accessTokenIssuedAt = oldIssuedAt,
                )
            }.onFailure(failures::add)
            done.countDown()
        }
        executor.submit {
            ready.countDown()
            start.await()
            runCatching {
                tokenService.registerToken(
                    memberId = newMemberId,
                    deviceId = "shared-device",
                    platform = PushPlatform.ANDROID,
                    token = "shared-case-sensitive-token",
                    accessTokenIssuedAt = newIssuedAt,
                )
            }.onFailure(failures::add)
            done.countDown()
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(1, failures.size)
        assertEquals(ErrorCode.INVALID_TOKEN, (failures.single() as BusinessException).errorCode)
        assertTrue(tokenRepository.findAllByMemberId(oldMemberId).isEmpty())
        val current = tokenRepository.findAllByMemberId(newMemberId).single()
        assertEquals("shared-device", current.deviceId)
        assertEquals("shared-case-sensitive-token", current.token)
    }

    private fun createMember(email: String): Long =
        requireNotNull(
            memberRepository.saveAndFlush(
                Member(
                    name = "member",
                    password = "Password1!",
                    email = email,
                    loginType = LoginType.COMMON,
                )
            ).id
        )
}

@TestConfiguration
class NotificationTokenLogoutFenceTestConfig {
    @Bean
    fun tokenLogoutFenceClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-24T03:00:00Z"), ZoneOffset.UTC)

    @Bean
    fun tokenLogoutRefreshTokenService(): RefreshTokenService = mock()

    @Bean
    fun blockingTokenRegistrationObserver(): BlockingNotificationTokenRegistrationObserver =
        BlockingNotificationTokenRegistrationObserver()
}

class BlockingNotificationTokenRegistrationObserver : NotificationTokenRegistrationObserver {
    @Volatile
    var block: (() -> Unit)? = null

    override fun afterMemberSessionFence(memberId: Long) {
        block?.invoke()
    }

    fun reset() {
        block = null
    }
}
