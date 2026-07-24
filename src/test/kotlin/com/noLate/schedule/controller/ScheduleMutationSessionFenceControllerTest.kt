package com.noLate.schedule.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.service.ScheduleCategoryService
import com.noLate.schedule.application.service.ScheduleShareService
import com.noLate.schedule.domain.ScheduleCategorySettingDto
import com.noLate.schedule.domain.ScheduleShareInvitationAcceptDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ScheduleMutationSessionFenceControllerTest {

    @Mock
    lateinit var categoryService: ScheduleCategoryService

    @Mock
    lateinit var shareService: ScheduleShareService

    private val principal = MemberPrincipal(
        id = 7L,
        email = "member@example.com",
        name = "Member",
        accessTokenSessionGeneration = 11L,
    )

    @Test
    fun `category lazy default mutation receives signed session generation`() {
        val expected = emptyList<ScheduleCategorySettingDto>()
        whenever(categoryService.getCategories(7L, 11L)).thenReturn(expected)

        val result = ScheduleCategoryController(categoryService).getCategories(principal)

        assertEquals(expected, result.data)
        verify(categoryService).getCategories(7L, 11L)
    }

    @Test
    fun `share revoke and invitation accept receive signed session generation`() {
        val acceptResult = mock<ScheduleShareInvitationAcceptDto>()
        whenever(shareService.acceptInvitation(7L, "token", 11L)).thenReturn(acceptResult)

        ScheduleShareController(shareService).revokeScheduleShare(
            principal = principal,
            scheduleId = 20L,
            shareId = 30L,
        )
        val result = ShareInvitationController(shareService).acceptInvitation(principal, "token")

        verify(shareService).revokeScheduleShare(
            ownerMemberId = 7L,
            scheduleId = 20L,
            shareId = 30L,
            presentedSessionGeneration = 11L,
        )
        verify(shareService).acceptInvitation(7L, "token", 11L)
        assertEquals(acceptResult, result.data)
    }

    @Test
    fun `authenticated schedule share mutation without generation is invalid token`() {
        val legacyPrincipal = MemberPrincipal(
            id = 7L,
            email = "member@example.com",
            name = "Member",
        )

        val error = assertThrows<BusinessException> {
            ScheduleShareController(shareService).revokeScheduleInvitation(
                principal = legacyPrincipal,
                scheduleId = 20L,
                invitationId = 40L,
            )
        }

        assertEquals(ErrorCode.INVALID_TOKEN, error.errorCode)
        verifyNoInteractions(shareService)
    }
}
