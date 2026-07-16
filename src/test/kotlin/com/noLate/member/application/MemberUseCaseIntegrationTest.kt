// src/test/kotlin/com/swyp/member/application/MemberUseCaseIntegrationTest.kt
package com.noLate.member.application

import com.noLate.auth.infrastructure.RefreshTokenRepository
import com.noLate.global.error.BusinessException
import com.noLate.global.security.JwtTokenProvider
import com.noLate.member.application.useCase.MemberUseCase
import com.noLate.member.domain.consent.SignupConsentCommand
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.MemberDto
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.member.infrastructure.MemberProfileRepository
import com.noLate.member.infrastructure.MemberSettingRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest
@ExtendWith(SpringExtension::class)
@Transactional   // 각 테스트마다 롤백
class MemberUseCaseIntegrationTest @Autowired constructor(
    private val memberUseCase: MemberUseCase,
    private val memberRepository: MemberRepository,
    private val memberSettingRepository: MemberSettingRepository,
    private val memberProfileRepository: MemberProfileRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val passwordEncoder: PasswordEncoder
) {
    private val signupConsents = SignupConsentCommand(
        termsVersion = "2026.07.16",
        privacyCollectionVersion = "2026.07.16",
        termsAgreed = true,
        privacyCollectionAgreed = true,
    )

    @Test
    fun `COMMON 회원가입 후 로그인하면 JWT 토큰이 발급되고 유효하다`() {
        val rawPassword = "raw-password-1"

        // 1) 회원가입
        val signUpDto = MemberDto(
            email = uniqueEmail("it1"),
            password = rawPassword,
            name = "통합테스트유저1",
            loginType = LoginType.COMMON
        )

        val signedUpDto = memberUseCase.signUp(signUpDto, signupConsents)

        assertNotNull(signedUpDto.id)
        assertEquals(signUpDto.email, signedUpDto.email)

        val saved = memberRepository.findById(signedUpDto.id!!).orElseThrow()
        assertTrue(passwordEncoder.matches(rawPassword, saved.password))

        // 2) 로그인
        val loginDto = MemberDto(
            email = signUpDto.email,
            password = rawPassword,
            loginType = LoginType.COMMON
        )

        val loginResult = memberUseCase.login(loginDto)

        assertEquals(saved.id, loginResult.id)
        assertNotNull(loginResult.accessToken)
        assertNotNull(loginResult.refreshToken)

        val accessToken = loginResult.accessToken!!
        val refreshToken = loginResult.refreshToken!!

        // 3) JWT 검증
        assertTrue(jwtTokenProvider.validateToken(accessToken))
        assertTrue(jwtTokenProvider.validateToken(refreshToken))

        val memberIdFromToken = jwtTokenProvider.getMemberIdFromToken(accessToken)
        val memberNameFromToken = jwtTokenProvider.getMemberNameFromToken(accessToken)

        assertEquals(saved.id, memberIdFromToken)
        assertEquals(saved.name, memberNameFromToken)

        // refreshToken 이 DB에 저장되어 있는지 확인
        assertEquals(1, activeRefreshTokenCountFor(loginResult.id!!))
    }

    @Test
    fun `같은 이메일로 두 번 COMMON 회원가입을 시도하면 중복 예외를 던진다`() {
        val rawPassword = "raw-password-2"

        val dto = MemberDto(
            email = uniqueEmail("dup"),
            password = rawPassword,
            name = "중복테스트",
            loginType = LoginType.COMMON
        )

        // 첫 번째는 정상 가입
        memberUseCase.signUp(dto, signupConsents)

        // 두 번째는 중복 예외
        val ex = assertThrows<BusinessException> {
            memberUseCase.signUp(dto, signupConsents)
        }

        assertTrue(ex.message?.contains("COMMON 방식으로 가입된 이메일") == true)
    }

    @Test
    fun `COMMON 로그인 시 비밀번호가 틀리면 예외를 던진다`() {
        val rawPassword = "raw-password-3"

        // 먼저 회원가입
        val signUpDto = MemberDto(
            email = uniqueEmail("wrongpw"),
            password = rawPassword,
            name = "비번틀림",
            loginType = LoginType.COMMON
        )
        memberUseCase.signUp(signUpDto, signupConsents)

        // 잘못된 비밀번호로 로그인
        val wrongLoginDto = MemberDto(
            email = signUpDto.email,
            password = "WRONG-PASSWORD",
            loginType = LoginType.COMMON
        )

        val ex = assertThrows<BusinessException> {
            memberUseCase.login(wrongLoginDto)
        }

        assertTrue(ex.message?.contains("비밀번호") == true || ex.message?.contains("일치하지") == true)
    }

    @Test
    fun `존재하지 않는 이메일로 COMMON 로그인을 시도하면 예외를 던진다`() {
        val loginDto = MemberDto(
            email = uniqueEmail("not-exist"),
            password = "any-password",
            loginType = LoginType.COMMON
        )

        val ex = assertThrows<BusinessException> {
            memberUseCase.login(loginDto)
        }

        assertTrue(ex.message?.contains("존재하지 않는") == true || ex.message?.contains("회원") == true)
    }

    @Test
    fun `SNS 가입 동의 후 회원 생성과 설정 생성 및 토큰 발급이 된다`() {
        val snsLoginDto = MemberDto(
            email = uniqueEmail("sns1"),
            password = null,
            name = "SNS유저1",
            loginType = LoginType.KAKAO,
            snsId = uniqueSnsId("kakao-111")
        )

        val memberCountBefore = memberRepository.count()
        val settingCountBefore = memberSettingRepository.count()
        val profileCountBefore = memberProfileRepository.count()
        val refreshCountBefore = refreshTokenRepository.count()

        val result = memberUseCase.signUpSns(snsLoginDto, signupConsents)

        // 회원이 새로 하나 생성되어야 함
        assertEquals(memberCountBefore + 1, memberRepository.count())
        assertEquals(settingCountBefore + 1, memberSettingRepository.count())
        assertEquals(profileCountBefore + 1, memberProfileRepository.count())

        assertNotNull(result.id)
        assertNotNull(memberProfileRepository.findByMemberId(result.id!!))
        assertEquals(snsLoginDto.email, result.email)
        assertEquals("SNS유저1", result.name)
        assertEquals(LoginType.KAKAO, result.loginType)
        assertNotNull(result.accessToken)
        assertNotNull(result.refreshToken)

        val accessToken = result.accessToken!!
        assertTrue(jwtTokenProvider.validateToken(accessToken))

        assertEquals(refreshCountBefore + 1, refreshTokenRepository.count())
    }

    @Test
    fun `SNS 두 번째 로그인 시에는 기존 회원을 재사용하고 회원 수가 늘어나지 않는다`() {
        val snsLoginDto = MemberDto(
            email = uniqueEmail("sns2"),
            password = null,
            name = "SNS유저2",
            loginType = LoginType.KAKAO,
            snsId = uniqueSnsId("kakao-222")
        )

        // 가입 동의를 거쳐 SNS 회원 생성
        val first = memberUseCase.signUpSns(snsLoginDto, signupConsents)

        val memberCountAfterFirst = memberRepository.count()
        val settingCountAfterFirst = memberSettingRepository.count()
        val firstId = first.id

        // 두 번째 SNS 로그인 (같은 snsId, loginType)
        val second = memberUseCase.login(snsLoginDto)

        val memberCountAfterSecond = memberRepository.count()
        val settingCountAfterSecond = memberSettingRepository.count()

        // 회원 수, 설정 수는 그대로여야 한다
        assertEquals(memberCountAfterFirst, memberCountAfterSecond)
        assertEquals(settingCountAfterFirst, settingCountAfterSecond)

        // 같은 회원 기준으로 로그인된 것인지 확인
        assertEquals(firstId, second.id)
        assertEquals(snsLoginDto.email, second.email)
        assertEquals("SNS유저2", second.name)

        assertNotNull(second.accessToken)
        assertTrue(jwtTokenProvider.validateToken(second.accessToken!!))
    }

    @Test
    fun `tokenLogin은 refreshToken만으로 새 토큰 세트를 발급하고 DB의 refreshToken을 갱신한다`() {
        val signUpDto = MemberDto(
            email = uniqueEmail("tokenlogin"),
            password = "pw-token",
            name = "토큰로그인유저",
            loginType = LoginType.COMMON
        )
        memberUseCase.signUp(signUpDto, signupConsents)

        val loginResult = memberUseCase.login(
            MemberDto(
                email = signUpDto.email,
                password = "pw-token",
                loginType = LoginType.COMMON
            )
        )

        val firstRefreshToken = loginResult.refreshToken!!
        assertEquals(1, activeRefreshTokenCountFor(loginResult.id!!))

        // when - tokenLogin 호출
        val reLoginResult = memberUseCase.tokenLogin(firstRefreshToken)

        // then
        assertEquals(loginResult.id, reLoginResult.id)
        assertNotNull(reLoginResult.accessToken)
        assertNotNull(reLoginResult.refreshToken)

        val newRefreshToken = reLoginResult.refreshToken!!
        assertTrue(jwtTokenProvider.validateToken(newRefreshToken))

        // 정책상 한 회원당 refreshToken 한 개만 유지한다고 가정
        assertEquals(1, activeRefreshTokenCountFor(loginResult.id!!))
    }

    @Test
    fun `refresh는 tokenLogin과 동일하게 refreshToken만으로 새 토큰 세트를 발급한다`() {
        val signUpDto = MemberDto(
            email = uniqueEmail("refresh"),
            password = "pw-refresh",
            name = "리프레시유저",
            loginType = LoginType.COMMON
        )
        memberUseCase.signUp(signUpDto, signupConsents)

        val loginResult = memberUseCase.login(
            MemberDto(
                email = signUpDto.email,
                password = "pw-refresh",
                loginType = LoginType.COMMON
            )
        )

        val oldRefreshToken = loginResult.refreshToken!!
        val countBefore = refreshTokenRepository.count()

        val refreshed = memberUseCase.refresh(oldRefreshToken)

        assertEquals(loginResult.id, refreshed.id)
        assertNotNull(refreshed.accessToken)
        assertNotNull(refreshed.refreshToken)

        val newRefreshToken = refreshed.refreshToken!!
        assertTrue(jwtTokenProvider.validateToken(newRefreshToken))

        assertEquals(countBefore, refreshTokenRepository.count())
    }

    @Test
    fun `logout 이후에는 해당 refreshToken으로 tokenLogin을 시도하면 예외를 던진다`() {
        val signUpDto = MemberDto(
            email = uniqueEmail("logout"),
            password = "pw-logout",
            name = "로그아웃유저",
            loginType = LoginType.COMMON
        )
        memberUseCase.signUp(signUpDto, signupConsents)

        val loginResult = memberUseCase.login(
            MemberDto(
                email = signUpDto.email,
                password = "pw-logout",
                loginType = LoginType.COMMON
            )
        )

        val refreshToken = loginResult.refreshToken!!
        assertEquals(1, activeRefreshTokenCountFor(loginResult.id!!))

        // 로그아웃
        memberUseCase.logout(refreshToken)

        val ex = assertThrows<BusinessException> {
            memberUseCase.tokenLogin(refreshToken)
        }

        assertTrue(ex.message?.contains("리프레시 토큰") == true ||
                ex.message?.contains("유효하지") == true ||
                ex.message?.contains("회원 정보가 일치하지") == true)
    }

    @Test
    fun `비밀번호 변경 후에는 새 비밀번호로는 로그인 가능하고 기존 비밀번호로는 로그인할 수 없다`() {
        val signUpDto = MemberDto(
            email = uniqueEmail("changepw"),
            password = "old-pw",
            name = "패스워드유저",
            loginType = LoginType.COMMON
        )
        val signed = memberUseCase.signUp(signUpDto, signupConsents)
        val memberId = signed.id!!

        // 비밀번호 변경
        memberUseCase.changePassword(memberId, "old-pw", "new-pw-1234")

        // 새 비밀번호로 로그인 → 성공
        val loginNew = memberUseCase.login(
            MemberDto(
                email = signUpDto.email,
                password = "new-pw-1234",
                loginType = LoginType.COMMON
            )
        )
        assertEquals(memberId, loginNew.id)

        // 옛 비밀번호로 로그인 → 예외
        val ex = assertThrows<BusinessException> {
            memberUseCase.login(
                MemberDto(
                    email = signUpDto.email,
                    password = "old-pw",
                    loginType = LoginType.COMMON
                )
            )
        }
        assertTrue(ex.message?.contains("비밀번호") == true || ex.message?.contains("일치하지") == true)
    }

    @Test
    fun `회원 탈퇴 이후에는 같은 계정으로 다시 로그인할 수 없다`() {
        val signUpDto = MemberDto(
            email = uniqueEmail("withdraw-it"),
            password = "pw-withdraw",
            name = "탈퇴통합유저",
            loginType = LoginType.COMMON
        )
        val signed = memberUseCase.signUp(signUpDto, signupConsents)
        val memberId = signed.id!!

        // 로그인 한 번 해서 refreshToken 생성
        memberUseCase.login(
            MemberDto(
                email = signUpDto.email,
                password = "pw-withdraw",
                loginType = LoginType.COMMON
            )
        )
        assertEquals(1, activeRefreshTokenCountFor(memberId))

        // 탈퇴
        memberUseCase.withdraw(memberId, "pw-withdraw")

        // refreshToken 전부 삭제되었는지(혹은 soft delete 처리되었는지) 확인
        assertEquals(0, activeRefreshTokenCountFor(memberId))

        // 같은 계정으로 다시 로그인 시도 → 예외
        val ex = assertThrows<BusinessException> {
            memberUseCase.login(
                MemberDto(
                    email = signUpDto.email,
                    password = "pw-withdraw",
                    loginType = LoginType.COMMON
                )
            )
        }
        assertTrue(ex.message?.contains("회원") == true || ex.message?.contains("존재") == true)
    }

    private fun activeRefreshTokenCountFor(memberId: Long): Int =
        refreshTokenRepository.findAllByMemberIdAndRevokedIsFalseAndExpiresAtAfter(
            memberId,
            LocalDateTime.now(),
        ).size

    private fun uniqueEmail(prefix: String): String =
        "$prefix-${UUID.randomUUID()}@test.com"

    private fun uniqueSnsId(prefix: String): String =
        "$prefix-${UUID.randomUUID()}"
}
