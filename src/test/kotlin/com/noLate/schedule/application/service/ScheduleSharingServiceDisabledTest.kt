package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationAcceptanceRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.mock.env.MockEnvironment

class ScheduleSharingServiceDisabledTest {

    private val disabledPolicy = ScheduleSharingAvailabilityPolicy(
        MockEnvironment().withProperty("schedule.sharing.enabled", "false"),
    )
    private val scheduleRepository = mock<ScheduleRepository>()
    private val scheduleShareRepository = mock<ScheduleShareRepository>()
    private val categoryRepository = mock<ScheduleCategoryRepository>()
    private val categoryShareRepository = mock<ScheduleCategoryShareRepository>()
    private val invitationRepository = mock<ScheduleShareInvitationRepository>()
    private val invitationAcceptanceRepository = mock<ScheduleShareInvitationAcceptanceRepository>()
    private val memberRepository = mock<MemberRepository>()
    private val calendarRepository = mock<ScheduleCalendarRepository>()
    private val calendarMemberRepository = mock<ScheduleCalendarMemberRepository>()

    private val shareService = ScheduleShareService(
        scheduleRepository = scheduleRepository,
        scheduleShareRepository = scheduleShareRepository,
        categoryRepository = categoryRepository,
        categoryShareRepository = categoryShareRepository,
        invitationRepository = invitationRepository,
        invitationAcceptanceRepository = invitationAcceptanceRepository,
        memberRepository = memberRepository,
        calendarRepository = calendarRepository,
        calendarMemberRepository = calendarMemberRepository,
        sharingAvailabilityPolicy = disabledPolicy,
    )

    private val calendarService = ScheduleCalendarService(
        calendarRepository = calendarRepository,
        calendarMemberRepository = calendarMemberRepository,
        memberRepository = memberRepository,
        invitationRepository = invitationRepository,
        sharingAvailabilityPolicy = disabledPolicy,
    )

    @Test
    fun `global off guards every share route before reading or mutating dormant rows`() {
        val calls: List<() -> Any?> = listOf(
            { shareService.getShareInbox(1L) },
            { shareService.getShareOutbox(1L) },
            { shareService.getScheduleShares(1L, 10L) },
            {
                shareService.shareSchedule(
                    ownerMemberId = 1L,
                    scheduleId = 10L,
                    targetEmail = null,
                    targetAppId = 2L,
                    permission = ScheduleSharePermission.VIEWER,
                    presentedSessionGeneration = 0L,
                )
            },
            {
                shareService.updateScheduleShare(
                    ownerMemberId = 1L,
                    scheduleId = 10L,
                    shareId = 100L,
                    permission = ScheduleSharePermission.EDITOR,
                    presentedSessionGeneration = 0L,
                )
            },
            { shareService.revokeScheduleShare(1L, 10L, 100L, 0L) },
            { shareService.getScheduleInvitations(1L, 10L) },
            {
                shareService.createScheduleInvitation(
                    ownerMemberId = 1L,
                    scheduleId = 10L,
                    permission = ScheduleSharePermission.VIEWER,
                    ttlHours = null,
                    maxAcceptCount = null,
                    presentedSessionGeneration = 0L,
                )
            },
            {
                shareService.revokeInvitation(
                    ownerMemberId = 1L,
                    resourceType = ScheduleShareResourceType.SCHEDULE,
                    resourceId = 10L,
                    invitationId = 1000L,
                    presentedSessionGeneration = 0L,
                )
            },
            { shareService.getCategoryShares(1L, 20L) },
            {
                shareService.shareCategory(
                    ownerMemberId = 1L,
                    categoryId = 20L,
                    targetEmail = null,
                    targetAppId = 2L,
                    permission = ScheduleSharePermission.VIEWER,
                    presentedSessionGeneration = 0L,
                )
            },
            {
                shareService.updateCategoryShare(
                    ownerMemberId = 1L,
                    categoryId = 20L,
                    shareId = 200L,
                    permission = ScheduleSharePermission.EDITOR,
                    presentedSessionGeneration = 0L,
                )
            },
            { shareService.revokeCategoryShare(1L, 20L, 200L, 0L) },
            { shareService.getCategoryInvitations(1L, 20L) },
            {
                shareService.createCategoryInvitation(
                    ownerMemberId = 1L,
                    categoryId = 20L,
                    permission = ScheduleSharePermission.VIEWER,
                    ttlHours = null,
                    maxAcceptCount = null,
                    presentedSessionGeneration = 0L,
                )
            },
            {
                shareService.revokeInvitation(
                    ownerMemberId = 1L,
                    resourceType = ScheduleShareResourceType.CATEGORY,
                    resourceId = 20L,
                    invitationId = 2000L,
                    presentedSessionGeneration = 0L,
                )
            },
            { shareService.acceptInvitation(2L, "retained-token", 0L) },
            { shareService.getCalendarInvitations(1L, 30L) },
            {
                shareService.createCalendarInvitation(
                    ownerMemberId = 1L,
                    calendarId = 30L,
                    permission = ScheduleSharePermission.VIEWER,
                    ttlHours = null,
                    maxAcceptCount = null,
                    presentedSessionGeneration = 0L,
                )
            },
            {
                shareService.revokeInvitation(
                    ownerMemberId = 1L,
                    resourceType = ScheduleShareResourceType.CALENDAR,
                    resourceId = 30L,
                    invitationId = 3000L,
                    presentedSessionGeneration = 0L,
                )
            },
        )

        calls.forEach(::assertFeatureDisabled)
        verifyDormantRepositoriesUntouched()
    }

    @Test
    fun `global off guards every shared calendar route before reading or mutating dormant rows`() {
        val calls: List<() -> Any?> = listOf(
            {
                calendarService.createCalendar(
                    ownerMemberId = 1L,
                    title = "dormant",
                    color = "#000000",
                    defaultContentMode = ScheduleShareContentMode.SCHEDULE_ONLY,
                    presentedSessionGeneration = 0L,
                )
            },
            { calendarService.getCalendars(1L) },
            { calendarService.getCalendar(1L, 30L) },
            {
                calendarService.updateCalendar(
                    ownerMemberId = 1L,
                    calendarId = 30L,
                    title = "updated",
                    color = null,
                    defaultContentMode = null,
                    presentedSessionGeneration = 0L,
                )
            },
            { calendarService.archiveCalendar(1L, 30L, 0L) },
            { calendarService.getMembers(1L, 30L) },
            {
                calendarService.addMember(
                    ownerMemberId = 1L,
                    calendarId = 30L,
                    targetEmail = null,
                    targetAppId = 2L,
                    role = ScheduleCalendarRole.VIEWER,
                    authenticatedActorMemberId = 1L,
                    presentedSessionGeneration = 0L,
                )
            },
            {
                calendarService.updateMember(
                    ownerMemberId = 1L,
                    calendarId = 30L,
                    targetMemberId = 2L,
                    role = ScheduleCalendarRole.EDITOR,
                    presentedSessionGeneration = 0L,
                )
            },
            {
                calendarService.updateMyPreferences(
                    memberId = 2L,
                    calendarId = 30L,
                    routeReminderEnabled = false,
                    presentedSessionGeneration = 0L,
                )
            },
            { calendarService.removeMember(1L, 30L, 2L, 0L) },
            { calendarService.leaveCalendar(2L, 30L, 0L) },
            { calendarService.transferOwnership(1L, 30L, 2L, 0L) },
        )

        calls.forEach(::assertFeatureDisabled)
        verifyDormantRepositoriesUntouched()
    }

    private fun assertFeatureDisabled(call: () -> Any?) {
        val failure = assertThrows<BusinessException> { call() }
        assertEquals(ErrorCode.FEATURE_DISABLED, failure.errorCode)
        assertEquals(ErrorCode.FEATURE_DISABLED.message, failure.message)
    }

    private fun verifyDormantRepositoriesUntouched() {
        verifyNoInteractions(
            scheduleRepository,
            scheduleShareRepository,
            categoryRepository,
            categoryShareRepository,
            invitationRepository,
            memberRepository,
            calendarRepository,
            calendarMemberRepository,
        )
    }
}
