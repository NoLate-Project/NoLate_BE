// src/test/kotlin/com/swyp/member/application/MemberUseCaseUnitTest.kt
package com.swyp.member.application

import com.swyp.global.security.JwtTokenProvider
import com.swyp.member.domain.Member.LoginType
import com.swyp.member.domain.Member.Member
import com.swyp.member.domain.Member.MemberDto
import com.swyp.member.domain.MemberSetting.MemberSettingDto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.security.crypto.password.PasswordEncoder

@ExtendWith(MockitoExtension::class)
class MemberUseCaseUnitTest {

    @Mock
    lateinit var memberService: MemberService

    @Mock
    lateinit var memberSettingService: MemberSettingService

    @Mock
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Mock
    lateinit var passwordEncoder: PasswordEncoder

    @Mock
    lateinit var memberValidator: MemberValidator

    private lateinit var memberUseCase: MemberUseCase

    @BeforeEach
    fun setUp() {
        memberUseCase = MemberUseCase(
            memberService = memberService,
            memberSettingService = memberSettingService,
            jwtTokenProvider = jwtTokenProvider,
            passwordEncoder = passwordEncoder,
            memberValidator = memberValidator
        )
    }

    @Test
    fun `COMMON 회원가입 성공 시 비밀번호 암호화, 회원 저장, 기본 설정 생성이 호출되고 저장된 DTO를 반환한다`() {
        // given
        val requestDto = MemberDto(
            id = null,
            email = "user@test.com",
            password = "raw-password",
            name = "테스트유저",
            loginType = LoginType.COMMON,
            snsId = null
        )

        whenever(passwordEncoder.encode("raw-password"))
            .thenReturn("encoded-password")

        val savedDto = MemberDto(
            id = 1L,
            email = "user@test.com",
            password = "encoded-password",
            name = "테스트유저",
            loginType = LoginType.COMMON,
            snsId = null
        )

        whenever(memberService.addMember(any<Member>()))
            .thenReturn(savedDto)

        // when
        val result = memberUseCase.signUp(requestDto)

        // then
        verify(memberValidator, times(1)).validateCommonSignUp(requestDto)
        verify(passwordEncoder, times(1)).encode("raw-password")
        verify(memberService, times(1)).addMember(any<Member>())
        verify(memberSettingService, times(1))
            .createDefaultSetting(check<MemberSettingDto> {
                assertEquals(1L, it.memberId)
            })

        assertEquals(1L, result.id)
        assertEquals("user@test.com", result.email)
        assertEquals("테스트유저", result.name)
        assertEquals(LoginType.COMMON, result.loginType)
        assertEquals("encoded-password", result.password)
    }

    @Test
    fun `COMMON 로그인 성공 시 validator가 돌려준 DTO 기준으로 토큰을 발급하고 세팅한다`() {
        // given
        val loginRequestDto = MemberDto(
            id = null,
            email = "user@test.com",
            password = "raw-password",
            name = null,
            loginType = LoginType.COMMON,
            snsId = null
        )

        val validatedDto = MemberDto(
            id = 1L,
            email = "user@test.com",
            password = "encoded-password",
            name = "테스트유저",
            loginType = LoginType.COMMON,
            snsId = null
        )

        whenever(memberValidator.validateAndGetMemberForCommonLogin(loginRequestDto))
            .thenReturn(validatedDto)

        whenever(jwtTokenProvider.createAccessToken(1L, "테스트유저"))
            .thenReturn("access-token")
        whenever(jwtTokenProvider.createRefreshToken(1L, "테스트유저"))
            .thenReturn("refresh-token")

        // when
        val result = memberUseCase.login(loginRequestDto)

        // then
        verify(memberValidator, times(1))
            .validateAndGetMemberForCommonLogin(loginRequestDto)

        verify(jwtTokenProvider, times(1))
            .createAccessToken(1L, "테스트유저")
        verify(jwtTokenProvider, times(1))
            .createRefreshToken(1L, "테스트유저")

        assertEquals(1L, result.id)
        assertEquals("user@test.com", result.email)
        assertEquals("테스트유저", result.name)
        assertEquals("access-token", result.accessToken)
        assertEquals("refresh-token", result.refreshToken)
    }

    @Test
    fun `SNS 로그인 시 기존 회원이 없으면 새로 생성하고 설정 생성 후 토큰을 발급한다`() {
        // given
        val snsLoginRequest = MemberDto(
            id = null,
            email = "sns@test.com",
            password = null,
            name = "SNS유저",
            loginType = LoginType.KAKAO,
            snsId = "kakao-123"
        )

        whenever(memberValidator.requireSnsId(snsLoginRequest))
            .thenReturn("kakao-123")

        whenever(memberService.findByLoginTypeAndSnsId(LoginType.KAKAO, "kakao-123"))
            .thenReturn(null)

        val savedSnsDto = MemberDto(
            id = 10L,
            email = "sns@test.com",
            password = null,
            name = "SNS유저",
            loginType = LoginType.KAKAO,
            snsId = "kakao-123"
        )

        whenever(memberService.addMember(any<Member>()))
            .thenReturn(savedSnsDto)

        whenever(jwtTokenProvider.createAccessToken(10L, "SNS유저"))
            .thenReturn("access-token-sns")
        whenever(jwtTokenProvider.createRefreshToken(10L, "SNS유저"))
            .thenReturn("refresh-token-sns")

        // when
        val result = memberUseCase.login(snsLoginRequest)

        // then
        verify(memberValidator, times(1))
            .requireSnsId(snsLoginRequest)
        verify(memberService, times(1))
            .findByLoginTypeAndSnsId(LoginType.KAKAO, "kakao-123")
        verify(memberService, times(1))
            .addMember(any<Member>())
        verify(memberSettingService, times(1))
            .createDefaultSetting(check<MemberSettingDto> {
                assertEquals(10L, it.memberId)
            })
        verify(jwtTokenProvider, times(1))
            .createAccessToken(10L, "SNS유저")
        verify(jwtTokenProvider, times(1))
            .createRefreshToken(10L, "SNS유저")

        assertEquals(10L, result.id)
        assertEquals("sns@test.com", result.email)
        assertEquals("SNS유저", result.name)
        assertEquals("access-token-sns", result.accessToken)
        assertEquals("refresh-token-sns", result.refreshToken)
    }
}