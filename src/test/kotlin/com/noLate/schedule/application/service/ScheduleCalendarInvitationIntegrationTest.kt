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

@DataJpaTest
@Import(ScheduleCalendarService::class, ScheduleShareService::class)
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
        )
        val invitation = shareService.createCalendarInvitation(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            permission = ScheduleSharePermission.EDITOR,
            ttlHours = 24,
            maxAcceptCount = 1,
        )

        val accepted = shareService.acceptInvitation(requireNotNull(target.id), invitation.token)

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
        )
        shareService.createCalendarInvitation(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            permission = ScheduleSharePermission.VIEWER,
            ttlHours = 24,
            maxAcceptCount = 10,
        )

        calendarService.archiveCalendar(requireNotNull(owner.id), calendar.id)

        assertEquals(ScheduleShareInvitationStatus.REVOKED, invitationRepository.findAll().single().status)
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
        )
        calendarService.addMember(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            targetEmail = nextOwner.email,
            targetAppId = null,
            role = ScheduleCalendarRole.EDITOR,
        )
        val invitation = shareService.createCalendarInvitation(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            permission = ScheduleSharePermission.VIEWER,
            ttlHours = 24,
            maxAcceptCount = 10,
        )

        calendarService.transferOwnership(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            targetMemberId = requireNotNull(nextOwner.id),
        )

        assertEquals(ScheduleShareInvitationStatus.REVOKED, invitationRepository.findAll().single().status)
        val error = assertThrows(BusinessException::class.java) {
            shareService.acceptInvitation(requireNotNull(invitee.id), invitation.token)
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
