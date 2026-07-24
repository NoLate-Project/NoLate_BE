package com.noLate.member.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.JwtTokenProvider
import com.noLate.member.application.service.MemberService
import com.noLate.member.application.service.SocialIdentityVerifier
import com.noLate.member.application.useCase.MemberUseCase
import com.noLate.member.domain.consent.SignupConsentCommand
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.MemberDto
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.domain.PushPlatform
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MemberSessionGenerationIntegrationTest @Autowired constructor(
    private val memberUseCase: MemberUseCase,
    private val memberService: MemberService,
    private val memberRepository: MemberRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val notificationTokenService: NotificationTokenService,
) {
    @MockBean
    lateinit var socialIdentityVerifier: SocialIdentityVerifier

    private val signupConsents = SignupConsentCommand(
        termsVersion = "2026.07.16",
        privacyCollectionVersion = "2026.07.16",
        termsAgreed = true,
        privacyCollectionAgreed = true,
    )

    @Test
    fun `delayed g1 logout after explicit g2 login preserves g2 access refresh and push registration`() {
        val password = "SessionFence1!"
        val credentials = createAccount(password)
        val sessionA = memberUseCase.login(credentials)
        val refreshA = requireNotNull(sessionA.refreshToken)
        assertEquals(1L, jwtTokenProvider.getSessionGeneration(refreshA))

        val logoutParsed = CountDownLatch(1)
        val releaseLogout = CountDownLatch(1)
        val logoutDone = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            runCatching {
                // 실제 요청이 JWT 검증을 끝낸 뒤 service write 전에 지연된 상태를 만든다.
                requireNotNull(jwtTokenProvider.resolveRefreshSessionForLogout(refreshA))
                logoutParsed.countDown()
                check(releaseLogout.await(10, TimeUnit.SECONDS))
                memberUseCase.logout(refreshA)
            }.onFailure(failures::add)
            logoutDone.countDown()
        }

        try {
            assertTrue(logoutParsed.await(10, TimeUnit.SECONDS))

            // A가 아직 write fence에 도달하기 전에 explicit B login이 g2로 선형화된다.
            val sessionB = memberUseCase.login(credentials)
            val memberId = requireNotNull(sessionB.id)
            val accessB = requireNotNull(sessionB.accessToken)
            val refreshB = requireNotNull(sessionB.refreshToken)
            val issuedAtB = jwtTokenProvider.getIssuedAt(accessB)
            assertEquals(2L, jwtTokenProvider.getSessionGeneration(accessB))
            assertEquals(2L, jwtTokenProvider.getSessionGeneration(refreshB))

            assertNotNull(memberService.getPrincipalById(memberId, issuedAtB, 2L))
            notificationTokenService.registerToken(
                memberId = memberId,
                deviceId = "session-generation-device",
                platform = PushPlatform.ANDROID,
                token = "session-generation-push-token",
                accessTokenIssuedAt = issuedAtB,
                accessTokenSessionGeneration = 2L,
            )

            releaseLogout.countDown()
            assertTrue(logoutDone.await(10, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })

            // 늦은 A와 반복 replay는 g2 generation/refresh/push ownership을 변경하지 않는다.
            memberUseCase.logout(refreshA)
            assertEquals(2L, memberRepository.findById(memberId).orElseThrow().sessionGeneration)
            assertEquals(
                "session-generation-push-token",
                notificationTokenService.getTokensByMember(memberId).single().token,
            )
            assertNotNull(memberService.getPrincipalById(memberId, issuedAtB, 2L))

            val rotatedB = memberUseCase.refresh(refreshB)
            assertEquals(
                2L,
                jwtTokenProvider.getSessionGeneration(requireNotNull(rotatedB.refreshToken)),
            )
        } finally {
            releaseLogout.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `stale g1 withdrawal after g2 login cannot delete the account or g2 session`() {
        val password = "StaleDraw1!"
        val credentials = createAccount(password)
        val sessionA = memberUseCase.login(credentials)
        val memberId = requireNotNull(sessionA.id)
        val staleGeneration = jwtTokenProvider.getSessionGeneration(
            requireNotNull(sessionA.accessToken),
        )
        assertEquals(1L, staleGeneration)

        // 네트워크에서 A withdrawal 요청이 지연된 사이 명시적 B login이 g2를 연다.
        val sessionB = memberUseCase.login(credentials)
        val accessB = requireNotNull(sessionB.accessToken)
        val refreshB = requireNotNull(sessionB.refreshToken)
        val issuedAtB = jwtTokenProvider.getIssuedAt(accessB)
        assertEquals(2L, jwtTokenProvider.getSessionGeneration(accessB))
        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = "stale-withdrawal-device",
            platform = PushPlatform.IOS,
            token = "stale-withdrawal-g2-push-token",
            accessTokenIssuedAt = issuedAtB,
            accessTokenSessionGeneration = 2L,
        )

        val failure = assertThrows<BusinessException> {
            memberUseCase.withdraw(
                memberId = memberId,
                presentedSessionGeneration = staleGeneration,
                passwordForCheck = password,
            )
        }

        assertEquals(ErrorCode.INVALID_TOKEN, failure.errorCode)
        val member = memberRepository.findById(memberId).orElseThrow()
        assertFalse(member.deleted)
        assertEquals(2L, member.sessionGeneration)
        assertNotNull(memberService.getPrincipalById(memberId, issuedAtB, 2L))
        assertEquals(
            "stale-withdrawal-g2-push-token",
            notificationTokenService.getTokensByMember(memberId).single().token,
        )
        val rotatedB = memberUseCase.refresh(refreshB)
        assertEquals(
            2L,
            jwtTokenProvider.getSessionGeneration(requireNotNull(rotatedB.refreshToken)),
        )
    }

    private fun createAccount(password: String): MemberDto {
        val email = "session-${UUID.randomUUID()}@test.com"
        memberUseCase.signUp(
            MemberDto(
                email = email,
                password = password,
                name = "세션 통합 유저",
                loginType = LoginType.COMMON,
            ),
            signupConsents,
        )
        return MemberDto(
            email = email,
            password = password,
            loginType = LoginType.COMMON,
        )
    }
}
