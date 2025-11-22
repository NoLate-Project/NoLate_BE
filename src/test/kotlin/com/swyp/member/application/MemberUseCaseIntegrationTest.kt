// src/test/kotlin/com/swyp/member/application/MemberUseCaseIntegrationTest.kt
package com.swyp.member.application

import com.swyp.global.error.BusinessException
import com.swyp.global.security.JwtTokenProvider
import com.swyp.member.domain.Member.LoginType
import com.swyp.member.domain.Member.MemberDto
import com.swyp.member.infrastructure.MemberRepository
import com.swyp.member.infrastructure.MemberSettingRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ExtendWith(SpringExtension::class)
@Transactional   // 각 테스트마다 롤백
class MemberUseCaseIntegrationTest @Autowired constructor(
    private val memberUseCase: MemberUseCase,
    private val memberRepository: MemberRepository,
    private val memberSettingRepository: MemberSettingRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val passwordEncoder: PasswordEncoder
) {

    @Test
    fun `COMMON 회원가입 후 로그인하면 JWT 토큰이 발급되고 유효하다`() {
        val rawPassword = "raw-password-1"

        // 1) 회원가입
        val signUpDto = MemberDto(
            email = "it1@test.com",
            password = rawPassword,
            name = "통합테스트유저1",
            loginType = LoginType.COMMON
        )

        val signedUpDto = memberUseCase.signUp(signUpDto)

        assertNotNull(signedUpDto.id)
        assertEquals("it1@test.com", signedUpDto.email)

        val saved = memberRepository.findAll().first()
        assertTrue(passwordEncoder.matches(rawPassword, saved.password))

        // 2) 로그인
        val loginDto = MemberDto(
            email = "it1@test.com",
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
    }

    @Test
    fun `같은 이메일로 두 번 COMMON 회원가입을 시도하면 중복 예외를 던진다`() {
        val rawPassword = "raw-password-2"

        val dto = MemberDto(
            email = "dup@test.com",
            password = rawPassword,
            name = "중복테스트",
            loginType = LoginType.COMMON
        )

        // 첫 번째는 정상 가입
        memberUseCase.signUp(dto)

        // 두 번째는 중복 예외
        val ex = assertThrows<BusinessException> {
            memberUseCase.signUp(dto)
        }

        // ErrorCode까지 체크해도 되고, 일단 메시지만 확인
        assertTrue(ex.message?.contains("COMMON 방식으로 가입된 이메일") == true)
    }

    @Test
    fun `COMMON 로그인 시 비밀번호가 틀리면 예외를 던진다`() {
        val rawPassword = "raw-password-3"

        // 먼저 회원가입
        val signUpDto = MemberDto(
            email = "wrongpw@test.com",
            password = rawPassword,
            name = "비번틀림",
            loginType = LoginType.COMMON
        )
        memberUseCase.signUp(signUpDto)

        // 잘못된 비밀번호로 로그인
        val wrongLoginDto = MemberDto(
            email = "wrongpw@test.com",
            password = "WRONG-PASSWORD",
            loginType = LoginType.COMMON
        )

        val ex = assertThrows<BusinessException> {
            memberUseCase.login(wrongLoginDto)
        }

        // INVALID_CREDENTIALS 관련 메시지일 것
        assertTrue(ex.message?.contains("비밀번호") == true || ex.message?.contains("일치하지") == true)
    }

    @Test
    fun `존재하지 않는 이메일로 COMMON 로그인을 시도하면 예외를 던진다`() {
        val loginDto = MemberDto(
            email = "not-exist@test.com",
            password = "any-password",
            loginType = LoginType.COMMON
        )

        val ex = assertThrows<BusinessException> {
            memberUseCase.login(loginDto)
        }

        // MEMBER_NOT_FOUND 관련 메시지일 것
        assertTrue(ex.message?.contains("존재하지 않는") == true || ex.message?.contains("회원") == true)
    }

    @Test
    fun `SNS 첫 로그인 시 자동 회원 생성과 설정 생성 및 토큰 발급이 된다`() {
        val snsLoginDto = MemberDto(
            email = "sns1@test.com",
            password = null,
            name = "SNS유저1",
            loginType = LoginType.KAKAO,
            snsId = "kakao-111"
        )

        assertEquals(0, memberRepository.count())
        assertEquals(0, memberSettingRepository.count())

        val result = memberUseCase.login(snsLoginDto)

        // 회원이 새로 하나 생성되어야 함
        assertEquals(1, memberRepository.count())
        assertEquals(1, memberSettingRepository.count())

        assertNotNull(result.id)
        assertEquals("sns1@test.com", result.email)
        assertEquals("SNS유저1", result.name)
        assertEquals(LoginType.KAKAO, result.loginType)
        assertNotNull(result.accessToken)
        assertNotNull(result.refreshToken)

        val accessToken = result.accessToken!!
        assertTrue(jwtTokenProvider.validateToken(accessToken))
    }

    @Test
    fun `SNS 두 번째 로그인 시에는 기존 회원을 재사용하고 회원 수가 늘어나지 않는다`() {
        val snsLoginDto = MemberDto(
            email = "sns2@test.com",
            password = null,
            name = "SNS유저2",
            loginType = LoginType.KAKAO,
            snsId = "kakao-222"
        )

        // 첫 번째 SNS 로그인 → 자동 가입
        val first = memberUseCase.login(snsLoginDto)

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
        assertEquals("sns2@test.com", second.email)
        assertEquals("SNS유저2", second.name)

        assertNotNull(second.accessToken)
        assertTrue(jwtTokenProvider.validateToken(second.accessToken!!))
    }
}