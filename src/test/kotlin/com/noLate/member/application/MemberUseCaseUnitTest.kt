// src/test/kotlin/com/swyp/member/application/MemberUseCaseUnitTest.kt
package com.noLate.member.application

import com.noLate.auth.application.RefreshTokenService
import com.noLate.auth.domain.RefreshToken
import com.noLate.global.error.BusinessException
import com.noLate.global.security.JwtTokenProvider
import com.noLate.member.application.service.MemberProfileService
import com.noLate.member.application.service.MemberService
import com.noLate.member.application.service.MemberSettingService
import com.noLate.member.application.service.MemberValidator
import com.noLate.member.application.useCase.MemberUseCase
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.domain.member.MemberDto
import com.noLate.member.domain.memberSetting.MemberSetting
import com.noLate.member.domain.memberSetting.MemberSettingDto
import com.noLate.member.domain.memberSetting.ThemeType
import com.noLate.member.domain.profile.MemberProfileDto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
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
    lateinit var memberProfileService: MemberProfileService

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
            memberProfileService = memberProfileService,
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

        // refreshTokenService는 회원가입에선 사용 안 되고, 기본 프로필은 생성한다
        verifyNoInteractions(refreshTokenService)
        verify(memberProfileService, times(1)).createDefaultProfile(1L)

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
        verify(refreshTokenService, never()).validateAndGet(any())
        verify(refreshTokenService, times(1)).revokeToken(refreshToken)
    }

    @Test
    fun `비밀번호 변경 성공 시 기존 비밀번호가 일치하면 새 비밀번호로 변경된다`() {
        val memberId = 10L
        val currentPassword = "old-pw"
        val newPassword = "new-password"

        val member = Member(
            id = memberId,
            email = "pw@test.com",
            password = "encoded-old",
            name = "유저",
            loginType = LoginType.COMMON,
            snsId = null
        )

        whenever(memberService.getFindMemberId(memberId))
            .thenReturn(Optional.of(member))
        whenever(passwordEncoder.matches(currentPassword, "encoded-old"))
            .thenReturn(true)
        whenever(passwordEncoder.encode(newPassword))
            .thenReturn("encoded-new")

        // when
        memberUseCase.changePassword(memberId, currentPassword, newPassword)

        // then
        verify(memberService, times(1)).updateMember(check {
            assertEquals("encoded-new", it.password)
        })
    }

    @Test
    fun `SNS 계정은 비밀번호 변경을 할 수 없다`() {
        val memberId = 11L
        val member = Member(
            id = memberId,
            email = "sns@test.com",
            password = null,
            name = "SNS유저",
            loginType = LoginType.KAKAO,
            snsId = "kakao-xyz"
        )

        whenever(memberService.getFindMemberId(memberId))
            .thenReturn(Optional.of(member))

        val ex = assertThrows<BusinessException> {
            memberUseCase.changePassword(memberId, "any", "new-password")
        }

        assertTrue(ex.message?.contains("SNS") == true)
        verify(memberService, never()).updateMember(any())
    }

    @Test
    fun `withdraw는 COMMON 계정에서 비밀번호가 일치하면 refresh, setting, member에 대해 정리 작업을 수행한다`() {
        val memberId = 20L
        val member = Member(
            id = memberId,
            email = "withdraw@test.com",
            password = "encoded-pw",
            name = "탈퇴유저",
            loginType = LoginType.COMMON,
            snsId = null
        )

        val settingDto = MemberSettingDto(
            id = 1L,
            memberId = memberId,
            pushEnabled = false,
            emailEnabled = false,
            marketingConsent = false,
            theme = ThemeType.LIGTH
        )

        whenever(memberService.getFindMemberId(memberId))
            .thenReturn(Optional.of(member))
        whenever(passwordEncoder.matches("pw", "encoded-pw"))
            .thenReturn(true)
        whenever(memberSettingService.getByMemberId(memberId))
            .thenReturn(settingDto)

        // when
        memberUseCase.withdraw(memberId, "pw")

        // then
        verify(refreshTokenService, times(1)).deleteAllByMemberId(memberId)
        verify(memberSettingService, times(1)).softDelete(any<MemberSetting>())
        verify(memberService, times(1)).softDelete(member)
    }

    @Test
    fun `getMyProfile은 회원이 존재하고 프로필이 있으면 그대로 반환하고 없으면 기본 프로필을 생성한다`() {
        val memberId = 30L
        val member = Member(
            id = memberId,
            email = "profile@test.com",
            password = "pw",
            name = "프로필유저",
            loginType = LoginType.COMMON,
            snsId = null
        )
        whenever(memberService.getFindMemberId(memberId))
            .thenReturn(Optional.of(member))

        val existingProfile = MemberProfileDto(
            id = 10L,
            memberId = memberId,
            nickname = "닉",
            imgId = 10,
            intro = "소개"
        )

        // 1) 프로필이 있는 경우
        whenever(memberProfileService.getByMemberId(memberId))
            .thenReturn(existingProfile)

        val result1 = memberUseCase.getMyProfile(memberId)
        assertEquals("닉", result1.nickname)

        // 2) 프로필이 없는 경우 → 기본 생성
        whenever(memberProfileService.getByMemberId(memberId))
            .thenReturn(null)
        val defaultProfile = MemberProfileDto(existingProfile.id, memberId, null, null, null)
        whenever(memberProfileService.createDefaultProfile(memberId))
            .thenReturn(defaultProfile)

        val result2 = memberUseCase.getMyProfile(memberId)
        assertEquals(memberId, result2.memberId)
    }

    @Test
    fun `updateMyProfile은 회원 존재 여부를 확인한 후 profileService로 위임한다`() {
        val memberId = 40L
        val member = Member(
            id = memberId,
            email = "profile2@test.com",
            password = "pw",
            name = "유저2",
            loginType = LoginType.COMMON,
            snsId = null
        )
        whenever(memberService.getFindMemberId(memberId))
            .thenReturn(Optional.of(member))

        val reqDto = MemberProfileDto(
            memberId = memberId,
            nickname = "새닉",
            imgId = null,
            intro = "자기소개"
        )

        val updatedDto = reqDto.apply { this.intro = "저는 수정된 유저입니다." }
        whenever(memberProfileService.updateProfile(memberId, reqDto))
            .thenReturn(updatedDto)

        val result = memberUseCase.updateMyProfile(memberId, reqDto)

        assertEquals("새닉", result.nickname)
        assertEquals("저는 수정된 유저입니다.", result.intro)
    }
}
