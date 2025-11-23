// src/test/kotlin/com/swyp/member/application/MemberUseCaseUnitTest.kt
package com.swyp.member.application

import com.swyp.auth.application.RefreshTokenService
import com.swyp.auth.domain.RefreshToken
import com.swyp.global.error.BusinessException
import com.swyp.global.security.JwtTokenProvider
import com.swyp.member.domain.Member.LoginType
import com.swyp.member.domain.Member.Member
import com.swyp.member.domain.Member.MemberDto
import com.swyp.member.domain.MemberSetting.MemberSettingDto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.mockito.stubbing.OngoingStubbing
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.LocalDateTime
import java.util.*

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

    @Mock
    lateinit var refreshTokenService: RefreshTokenService

    private lateinit var memberUseCase: MemberUseCase

    @BeforeEach
    fun setUp() {
        memberUseCase = MemberUseCase(
            memberService = memberService,
            memberSettingService = memberSettingService,
            jwtTokenProvider = jwtTokenProvider,
            passwordEncoder = passwordEncoder,
            memberValidator = memberValidator,
            refreshTokenService = refreshTokenService
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

        // refreshTokenService 는 회원가입에선 사용 안 됨
        verifyNoInteractions(refreshTokenService)

        assertEquals(1L, result.id)
        assertEquals("user@test.com", result.email)
        assertEquals("테스트유저", result.name)
        assertEquals(LoginType.COMMON, result.loginType)
        assertEquals("encoded-password", result.password)
    }

    @Test
    fun `COMMON 로그인 성공 시 validator가 돌려준 DTO 기준으로 토큰을 발급하고 refreshToken을 저장한다`() {
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
        val expiry = LocalDateTime.now().plusDays(7)
        whenever(jwtTokenProvider.getRefreshTokenExpiryLocalDateTime())
            .thenReturn(expiry)

        // when
        val result = memberUseCase.login(loginRequestDto)

        // then
        verify(memberValidator, times(1))
            .validateAndGetMemberForCommonLogin(loginRequestDto)

        verify(jwtTokenProvider, times(1))
            .createAccessToken(1L, "테스트유저")
        verify(jwtTokenProvider, times(1))
            .createRefreshToken(1L, "테스트유저")

        verify(refreshTokenService, times(1))
            .saveNewToken(eq(1L), eq("refresh-token"), eq(expiry))

        assertEquals(1L, result.id)
        assertEquals("user@test.com", result.email)
        assertEquals("테스트유저", result.name)
        assertEquals("access-token", result.accessToken)
        assertEquals("refresh-token", result.refreshToken)
    }

    @Test
    fun `SNS 로그인 시 기존 회원이 없으면 새로 생성하고 설정 생성 후 토큰을 발급하고 refreshToken을 저장한다`() {
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
        val expiry = LocalDateTime.now().plusDays(7)
        whenever(jwtTokenProvider.getRefreshTokenExpiryLocalDateTime())
            .thenReturn(expiry)

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
        verify(refreshTokenService, times(1))
            .saveNewToken(eq(10L), eq("refresh-token-sns"), eq(expiry))

        assertEquals(10L, result.id)
        assertEquals("sns@test.com", result.email)
        assertEquals("SNS유저", result.name)
        assertEquals("access-token-sns", result.accessToken)
        assertEquals("refresh-token-sns", result.refreshToken)
    }

    @Test
    fun `tokenLogin은 refreshToken을 검증하고 새 토큰 세트를 발급한다`() {
        // given
        val oldRefreshToken = "old-refresh"
        val memberId = 1L

        whenever(jwtTokenProvider.validateToken(oldRefreshToken))
            .thenReturn(true)
        whenever(jwtTokenProvider.getMemberIdFromToken(oldRefreshToken))
            .thenReturn(memberId)

        val stored = RefreshToken(
            id = 100L,
            memberId = memberId,
            token = oldRefreshToken,
            expiresAt = LocalDateTime.now().plusDays(1),
            revoked = false
        )
        whenever(refreshTokenService.validateAndGet(oldRefreshToken))
            .thenReturn(stored)

        val member = Member(
            id = memberId,
            email = "user@test.com",
            password = "encoded",
            name = "테스트유저",
            loginType = LoginType.COMMON,
            snsId = null
        )
        whenever(memberService.getFindMemberId(memberId))
            .thenReturn(Optional.of(member))

        whenever(jwtTokenProvider.createAccessToken(memberId, "테스트유저"))
            .thenReturn("new-access")
        whenever(jwtTokenProvider.createRefreshToken(memberId, "테스트유저"))
            .thenReturn("new-refresh")
        val newExpiry = LocalDateTime.now().plusDays(7)
        whenever(jwtTokenProvider.getRefreshTokenExpiryLocalDateTime())
            .thenReturn(newExpiry)

        // when
        val result = memberUseCase.tokenLogin(oldRefreshToken)

        // then
        verify(jwtTokenProvider, times(1)).validateToken(oldRefreshToken)
        verify(refreshTokenService, times(1)).validateAndGet(oldRefreshToken)
        verify(refreshTokenService, times(1)).revokeToken(oldRefreshToken)
        verify(refreshTokenService, times(1))
            .saveNewToken(eq(memberId), eq("new-refresh"), eq(newExpiry))

        assertEquals(memberId, result.id)
        assertEquals("new-access", result.accessToken)
        assertEquals("new-refresh", result.refreshToken)
    }

    @Test
    fun `refresh도 tokenLogin과 동일하게 토큰 세트를 재발급한다`() {
        // given
        val oldRefreshToken = "old-refresh-2"
        val memberId = 2L

        whenever(jwtTokenProvider.validateToken(oldRefreshToken))
            .thenReturn(true)
        whenever(jwtTokenProvider.getMemberIdFromToken(oldRefreshToken))
            .thenReturn(memberId)

        val stored = RefreshToken(
            id = 101L,
            memberId = memberId,
            token = oldRefreshToken,
            expiresAt = LocalDateTime.now().plusDays(1),
            revoked = false
        )
        whenever(refreshTokenService.validateAndGet(oldRefreshToken))
            .thenReturn(stored)

        val member = Member(
            id = memberId,
            email = "user2@test.com",
            password = "encoded",
            name = "두번째유저",
            loginType = LoginType.COMMON,
            snsId = null
        )
        whenever(memberService.getFindMemberId(memberId))
            .thenReturn(Optional.of(member))

        whenever(jwtTokenProvider.createAccessToken(memberId, "두번째유저"))
            .thenReturn("new-access-2")
        whenever(jwtTokenProvider.createRefreshToken(memberId, "두번째유저"))
            .thenReturn("new-refresh-2")
        val newExpiry = LocalDateTime.now().plusDays(7)
        whenever(jwtTokenProvider.getRefreshTokenExpiryLocalDateTime())
            .thenReturn(newExpiry)

        // when
        val result = memberUseCase.refresh(oldRefreshToken)

        // then
        verify(jwtTokenProvider, times(1)).validateToken(oldRefreshToken)
        verify(refreshTokenService, times(1)).validateAndGet(oldRefreshToken)
        verify(refreshTokenService, times(1)).revokeToken(oldRefreshToken)
        verify(refreshTokenService, times(1))
            .saveNewToken(eq(memberId), eq("new-refresh-2"), eq(newExpiry))

        assertEquals(memberId, result.id)
        assertEquals("new-access-2", result.accessToken)
        assertEquals("new-refresh-2", result.refreshToken)
    }

    @Test
    fun `logout은 토큰이 유효하면 validateAndGet으로 소유자 검증 후 revoke를 호출한다`() {
        // given
        val refreshToken = "logout-refresh"
        val memberId = 3L

        whenever(jwtTokenProvider.validateToken(refreshToken))
            .thenReturn(true)
        whenever(jwtTokenProvider.getMemberIdFromToken(refreshToken))
            .thenReturn(memberId)

        val stored = RefreshToken(
            id = 200L,
            memberId = memberId,
            token = refreshToken,
            expiresAt = LocalDateTime.now().plusDays(1),
            revoked = false
        )
        whenever(refreshTokenService.validateAndGet(refreshToken))
            .thenReturn(stored)

        // when
        memberUseCase.logout(refreshToken)

        // then
        verify(refreshTokenService, times(1))
            .validateAndGet(refreshToken)
        verify(refreshTokenService, times(1))
            .revokeToken(refreshToken)
    }

    @Test
    fun `logout은 토큰이 유효하지 않아도 revoke를 시도한다`() {
        // given
        val refreshToken = "invalid-refresh"

        whenever(jwtTokenProvider.validateToken(refreshToken))
            .thenReturn(false)

        // when
        memberUseCase.logout(refreshToken)

        // then
        // validateAndGet은 호출되지 않고 바로 revoke만 호출
        verify(refreshTokenService, never()).validateAndGet(any())
        verify(refreshTokenService, times(1)).revokeToken(refreshToken)
    }
}

