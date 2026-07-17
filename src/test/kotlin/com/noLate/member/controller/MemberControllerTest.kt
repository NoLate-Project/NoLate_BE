package com.noLate.member.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.member.application.useCase.MemberUseCase
import com.noLate.member.domain.consent.SignupConsentCommand
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.MemberDto
import com.noLate.member.domain.profile.MemberProfileDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.check
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.isNull

@ExtendWith(MockitoExtension::class)
class MemberControllerTest {

    @Mock
    lateinit var memberUseCase: MemberUseCase

    private val principal = MemberPrincipal(
        id = 1L,
        email = "user@test.com",
        name = "tester",
    )

    private val consentRequest = SignupConsentRequest(
        termsVersion = "2026.07.16",
        privacyCollectionVersion = "2026.07.16",
        termsAgreed = true,
        privacyCollectionAgreed = true,
    )

    @Test
    fun `common signup passes versioned consent to use case`() {
        val controller = controller()
        val saved = MemberDto(id = 3L, email = "new@test.com", name = "new")
        whenever(memberUseCase.signUp(any(), any())).thenReturn(saved)

        val response = controller.signUp(
            SignUpRequest(
                email = "new@test.com",
                password = "password1!",
                name = "new",
                consents = consentRequest,
            )
        )

        assertSame(saved, response.data)
        verify(memberUseCase).signUp(
            memberDto = check { assertEquals(LoginType.COMMON, it.loginType) },
            consents = eq(consentRequest.toCommand()),
        )
    }

    @Test
    fun `sns registration status does not create an account`() {
        val controller = controller()
        whenever(memberUseCase.isSnsMemberRegistered(LoginType.KAKAO, "provider-token", null))
            .thenReturn(false)

        val response = controller.getSnsRegistrationStatus(
            SnsRegistrationRequest(LoginType.KAKAO, "provider-token")
        )

        assertEquals(false, response.data?.registered)
        verify(memberUseCase).isSnsMemberRegistered(LoginType.KAKAO, "provider-token", null)
    }

    @Test
    fun `sns signup passes profile and the same required consent`() {
        val controller = controller()
        val saved = MemberDto(id = 4L, loginType = LoginType.NAVER, snsId = "naver-1")
        whenever(
            memberUseCase.signUpSns(
                eq(LoginType.NAVER),
                eq("provider-token"),
                isNull(),
                any(),
            )
        ).thenReturn(saved)

        val response = controller.snsSignUp(
            SnsSignUpRequest(
                loginType = LoginType.NAVER,
                providerToken = "provider-token",
                consents = consentRequest,
            )
        )

        assertSame(saved, response.data)
        verify(memberUseCase).signUpSns(
            loginType = eq(LoginType.NAVER),
            providerToken = eq("provider-token"),
            nonce = eq(null),
            consents = eq(consentRequest.toCommand()),
        )
    }

    @Test
    // Verifies that the new refresh endpoint exposes the existing token reissue use case.
    // The controller should not create tokens itself; it only delegates and wraps the response.
    fun `refresh delegates to use case and returns token response`() {
        val controller = controller()
        val memberDto = MemberDto(id = 1L, name = "tester").apply {
            accessToken = "new-access"
            refreshToken = "new-refresh"
        }
        whenever(memberUseCase.refresh("refresh-token")).thenReturn(memberDto)

        val response = controller.refresh(TokenLoginRequest("refresh-token"))

        assertTrue(response.success)
        assertSame(memberDto, response.data)
        verify(memberUseCase).refresh("refresh-token")
    }

    @Test
    // Verifies that logout revokes the provided refresh token through the use case.
    // Access-token blacklisting is intentionally not handled at the controller layer.
    fun `logout delegates refresh token revocation`() {
        val controller = controller()

        val response = controller.logout(TokenLoginRequest("refresh-token"))

        assertTrue(response.success)
        verify(memberUseCase).logout("refresh-token")
    }

    @Test
    fun `getCurationStatus uses authenticated member state`() {
        val controller = controller()
        whenever(memberUseCase.getCurationStatus(1L)).thenReturn(false)

        val response = controller.getCurationStatus(principal)

        assertTrue(response.success)
        assertEquals(false, response.data?.curationCompleted)
        verify(memberUseCase).getCurationStatus(1L)
    }

    @Test
    fun `completeCuration updates only the authenticated member`() {
        val controller = controller()
        whenever(memberUseCase.completeCuration(1L)).thenReturn(true)

        val response = controller.completeCuration(principal)

        assertTrue(response.success)
        assertEquals(true, response.data?.curationCompleted)
        verify(memberUseCase).completeCuration(1L)
    }

    @Test
    // Verifies that profile reads use the authenticated principal, not a client-supplied member id.
    // This protects the "my profile" endpoint from cross-member lookup.
    fun `getMyProfile uses authenticated member id`() {
        val controller = controller()
        val profile = MemberProfileDto(
            memberId = 1L,
            nickname = "late-no-more",
            intro = "hello",
        )
        whenever(memberUseCase.getMyProfile(1L)).thenReturn(profile)

        val response = controller.getMyProfile(principal)

        assertSame(profile, response.data)
        verify(memberUseCase).getMyProfile(1L)
    }

    @Test
    // Verifies that profile updates also derive memberId from authentication.
    // The request body intentionally has no memberId field, so clients cannot update another profile.
    fun `updateMyProfile ignores client supplied member id and uses principal`() {
        val controller = controller()
        val updated = MemberProfileDto(
            memberId = 1L,
            nickname = "new",
            imgId = 7L,
            intro = "intro",
        )
        whenever(memberUseCase.updateMyProfile(eq(1L), org.mockito.kotlin.any()))
            .thenReturn(updated)

        val response = controller.updateMyProfile(
            principal = principal,
            request = UpdateProfileRequest(
                nickname = "new",
                imgId = 7L,
                intro = "intro",
            ),
        )

        assertSame(updated, response.data)
        verify(memberUseCase).updateMyProfile(
            memberId = eq(1L),
            dto = check {
                assertEquals(1L, it.memberId)
                assertEquals("new", it.nickname)
                assertEquals(7L, it.imgId)
                assertEquals("intro", it.intro)
            },
        )
    }

    @Test
    // Verifies that password changes are scoped to the authenticated member.
    // Actual password validation lives in MemberUseCase and is covered by use-case tests.
    fun `changePassword uses authenticated member id`() {
        val controller = controller()

        val response = controller.changePassword(
            principal = principal,
            request = ChangePasswordRequest(
                currentPassword = "old-password",
                newPassword = "new-password",
            ),
        )

        assertTrue(response.success)
        verify(memberUseCase).changePassword(
            memberId = 1L,
            currentPassword = "old-password",
            newPassword = "new-password",
        )
    }

    @Test
    // Verifies that withdraw can pass a null password for SNS accounts.
    // COMMON account password requirements are enforced by MemberUseCase, not the controller.
    fun `withdraw allows nullable password for sns members`() {
        val controller = controller()

        val response = controller.withdraw(
            principal = principal,
            request = WithdrawRequest(password = null),
        )

        assertTrue(response.success)
        verify(memberUseCase).withdraw(1L, null)
    }

    @Test
    // Verifies that protected member endpoints fail fast when Spring Security did not provide a principal.
    // This keeps unauthenticated requests from reaching profile/business logic.
    fun `profile endpoints require authentication`() {
        val controller = controller()

        val exception = assertThrows<BusinessException> {
            controller.getMyProfile(null)
        }

        assertEquals(ErrorCode.UNAUTHORIZED, exception.errorCode)
    }

    @Test
    fun `curation endpoints require authentication`() {
        val controller = controller()

        val exception = assertThrows<BusinessException> {
            controller.completeCuration(null)
        }

        assertEquals(ErrorCode.UNAUTHORIZED, exception.errorCode)
    }

    private fun controller() = MemberController(memberUseCase)
}
