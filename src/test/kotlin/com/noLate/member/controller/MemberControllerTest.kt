package com.noLate.member.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.member.application.useCase.MemberUseCase
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class MemberControllerTest {

    @Mock
    lateinit var memberUseCase: MemberUseCase

    private val principal = MemberPrincipal(
        id = 1L,
        email = "user@test.com",
        name = "tester",
    )

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

    private fun controller() = MemberController(memberUseCase)
}
