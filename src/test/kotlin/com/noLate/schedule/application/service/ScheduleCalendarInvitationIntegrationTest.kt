package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleShareInvitationStatus
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

@DataJpaTest
@Import(
    ScheduleCalendarService::class,
    ScheduleShareService::class,
    ScheduleSharingAvailabilityPolicy::class,
)
@TestPropertySource(properties = ["schedule.sharing.enabled=true"])
class ScheduleCalendarInvitationIntegrationTest @Autowired constructor(
    private val calendarService: ScheduleCalendarService,
    private val shareService: ScheduleShareService,
    private val memberRepository: MemberRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val invitationRepository: ScheduleShareInvitationRepository,
) {

    @Test
    fun `calendar link acceptance creates membership and keeps legacy share envelope compatible`() {
        val owner = member("calendar-link-owner")
        val target = member("calendar-link-target")
        val calendar = calendarService.createCalendar(
            ownerMemberId = requireNotNull(owner.id),
            title = "가족 이동",
            color = "#2F80FF",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        val invitation = shareService.createCalendarInvitation(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            permission = ScheduleSharePermission.EDITOR,
            ttlHours = 24,
            maxAcceptCount = 1,
            presentedSessionGeneration = owner.sessionGeneration,
        )

        val accepted = shareService.acceptInvitation(
            requireNotNull(target.id),
            invitation.token,
            target.sessionGeneration,
        )

        assertEquals(ScheduleShareResourceType.CALENDAR, accepted.invitation.resourceType)
        assertEquals(calendar.id.toString(), accepted.share.resourceId)
        assertEquals(ScheduleShareContentMode.SCHEDULE_AND_TRAVEL, accepted.share.contentMode)
        assertEquals(ScheduleCalendarRole.EDITOR, accepted.calendarMembership?.role)
        assertNotNull(
            calendarMemberRepository.findByCalendarIdAndMemberId(calendar.id, requireNotNull(target.id))
        )
        assertEquals(1, invitationRepository.findAll().single().acceptedCount)

        val inbox = shareService.getShareInbox(requireNotNull(target.id))
        assertEquals(1, inbox.receivedShares.count { it.resourceType == ScheduleShareResourceType.CALENDAR })
        assertEquals(ScheduleShareContentMode.SCHEDULE_AND_TRAVEL, inbox.receivedShares.single().contentMode)
        val outbox = shareService.getShareOutbox(requireNotNull(owner.id))
        assertEquals(1, outbox.sharedResources.single { it.resourceType == ScheduleShareResourceType.CALENDAR }.shareCount)
    }

    @Test
    fun `archiving calendar revokes every pending calendar invitation`() {
        val owner = member("calendar-archive-owner")
        val calendar = calendarService.createCalendar(
            ownerMemberId = requireNotNull(owner.id),
            title = "종료할 캘린더",
            color = "#2F80FF",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_ONLY,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        shareService.createCalendarInvitation(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            permission = ScheduleSharePermission.VIEWER,
            ttlHours = 24,
            maxAcceptCount = 10,
            presentedSessionGeneration = owner.sessionGeneration,
        )

        calendarService.archiveCalendar(
            requireNotNull(owner.id),
            calendar.id,
            owner.sessionGeneration,
        )

        assertEquals(ScheduleShareInvitationStatus.REVOKED, invitationRepository.findAll().single().status)
    }

    @Test
    fun `revoked calendar invitation cannot create a membership`() {
        val owner = member("calendar-revoke-owner")
        val target = member("calendar-revoke-target")
        val ownerId = requireNotNull(owner.id)
        val targetId = requireNotNull(target.id)
        val calendar = calendarService.createCalendar(
            ownerMemberId = ownerId,
            title = "폐기할 링크 캘린더",
            color = "#2F80FF",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_ONLY,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        val invitation = shareService.createCalendarInvitation(
            ownerMemberId = ownerId,
            calendarId = calendar.id,
            permission = ScheduleSharePermission.VIEWER,
            ttlHours = 24,
            maxAcceptCount = 10,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        shareService.revokeInvitation(
            ownerMemberId = ownerId,
            resourceType = ScheduleShareResourceType.CALENDAR,
            resourceId = calendar.id,
            invitationId = invitation.id.toLong(),
            presentedSessionGeneration = owner.sessionGeneration,
        )

        val error = assertThrows(BusinessException::class.java) {
            shareService.acceptInvitation(
                currentMemberId = targetId,
                token = invitation.token,
                presentedSessionGeneration = target.sessionGeneration,
            )
        }

        assertEquals(ErrorCode.INVALID_STATE, error.errorCode)
        assertEquals(ScheduleShareInvitationStatus.REVOKED, invitationRepository.findAll().single().status)
        assertEquals(null, calendarMemberRepository.findByCalendarIdAndMemberId(calendar.id, targetId))
    }

    @Test
    fun `ownership transfer revokes links issued by the previous owner`() {
        val owner = member("calendar-transfer-owner")
        val nextOwner = member("calendar-transfer-next-owner")
        val invitee = member("calendar-transfer-invitee")
        val calendar = calendarService.createCalendar(
            ownerMemberId = requireNotNull(owner.id),
            title = "소유권 이전 캘린더",
            color = "#2F80FF",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_ONLY,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        calendarService.addMember(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            targetEmail = nextOwner.email,
            targetAppId = null,
            role = ScheduleCalendarRole.EDITOR,
            authenticatedActorMemberId = requireNotNull(owner.id),
            presentedSessionGeneration = owner.sessionGeneration,
        )
        val invitation = shareService.createCalendarInvitation(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            permission = ScheduleSharePermission.VIEWER,
            ttlHours = 24,
            maxAcceptCount = 10,
            presentedSessionGeneration = owner.sessionGeneration,
        )

        calendarService.transferOwnership(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            targetMemberId = requireNotNull(nextOwner.id),
            presentedSessionGeneration = owner.sessionGeneration,
        )

        assertEquals(ScheduleShareInvitationStatus.REVOKED, invitationRepository.findAll().single().status)
        val error = assertThrows(BusinessException::class.java) {
            shareService.acceptInvitation(
                requireNotNull(invitee.id),
                invitation.token,
                invitee.sessionGeneration,
            )
        }
        assertEquals(ErrorCode.SCHEDULE_SHARE_INVITATION_NOT_FOUND, error.errorCode)
    }

    private fun member(label: String): Member = memberRepository.saveAndFlush(
        Member(
            name = label,
            password = "Password1!",
            email = "$label-${System.nanoTime()}@example.com",
        )
    )
}
