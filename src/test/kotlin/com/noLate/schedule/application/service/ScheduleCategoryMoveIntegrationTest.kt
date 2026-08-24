package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleShareInvitation
import com.noLate.schedule.domain.ScheduleShareInvitationStatus
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@DataJpaTest
@Import(
    ScheduleCategoryService::class,
    ScheduleTravelAccessCleanupService::class,
    ScheduleAccessPolicy::class,
    ScheduleSharingAvailabilityPolicy::class,
)
@TestPropertySource(properties = ["schedule.sharing.enabled=true"])
class ScheduleCategoryMoveIntegrationTest @Autowired constructor(
    private val service: ScheduleCategoryService,
    private val memberRepository: MemberRepository,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val categoryRepository: ScheduleCategoryRepository,
    private val scheduleRepository: ScheduleRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val invitationRepository: ScheduleShareInvitationRepository,
) {

    @Test
    fun `move keeps category and schedule ids and creates a personal replacement when needed`() {
        val owner = member("move-owner")
        val calendar = writableCalendar(owner)
        category(
            owner = owner,
            title = "기본 공유",
            calendarId = requireNotNull(calendar.id),
            sortOrder = 2,
        )
        val source = category(owner, "운동")
        val first = schedule(owner, source, "아침 운동")
        val second = schedule(owner, source, "저녁 운동")

        val preview = service.previewMove(
            memberId = requireNotNull(owner.id),
            categoryId = requireNotNull(source.id),
            calendarId = requireNotNull(calendar.id),
            presentedSessionGeneration = owner.sessionGeneration,
        )
        val result = service.moveCategory(
            memberId = requireNotNull(owner.id),
            categoryId = requireNotNull(source.id),
            calendarId = requireNotNull(calendar.id),
            mergeIntoCategoryId = null,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        scheduleRepository.flush()

        assertEquals(2, preview.activeScheduleCount)
        assertEquals(null, preview.sameNameCategory)
        assertFalse(result.merged)
        assertEquals(source.id.toString(), result.category.id)
        assertEquals(2, result.movedScheduleCount)
        val movedCategory = categoryRepository.findById(requireNotNull(source.id)).orElseThrow()
        assertEquals(calendar.id, movedCategory.calendarId)
        assertFalse(movedCategory.deleted)
        val movedSchedules = scheduleRepository.findAllById(listOf(first.id, second.id))
        assertEquals(setOf(first.id, second.id), movedSchedules.map { it.id }.toSet())
        assertTrue(movedSchedules.all { it.calendarId == calendar.id })
        assertTrue(movedSchedules.all { it.categoryId == source.id })
        assertTrue(movedSchedules.all { it.categorySnapshot?.categoryId == source.id.toString() })
        val personal = categoryRepository
            .findByMemberIdAndCalendarIdIsNullAndDeletedFalseOrderBySortOrderAscIdAsc(requireNotNull(owner.id))
        assertEquals(listOf("개인"), personal.map { it.title })
        assertNotEquals(source.id, personal.single().id)
    }

    @Test
    fun `explicit same-name merge repoints category fk and snapshot without replacing schedules`() {
        val owner = member("merge-owner")
        val calendar = writableCalendar(owner)
        val source = category(owner, "업무")
        category(owner, "개인")
        val destination = category(owner, "업무", requireNotNull(calendar.id))
        val original = schedule(owner, source, "주간 회의")

        val preview = service.previewMove(
            memberId = requireNotNull(owner.id),
            categoryId = requireNotNull(source.id),
            calendarId = requireNotNull(calendar.id),
            presentedSessionGeneration = owner.sessionGeneration,
        )
        val result = service.moveCategory(
            memberId = requireNotNull(owner.id),
            categoryId = requireNotNull(source.id),
            calendarId = requireNotNull(calendar.id),
            mergeIntoCategoryId = requireNotNull(destination.id),
            presentedSessionGeneration = owner.sessionGeneration,
        )
        scheduleRepository.flush()

        assertEquals(destination.id.toString(), preview.sameNameCategory?.id)
        assertTrue(result.merged)
        assertEquals(destination.id.toString(), result.category.id)
        assertEquals(1, result.movedScheduleCount)
        val sourceAfter = categoryRepository.findById(requireNotNull(source.id)).orElseThrow()
        assertTrue(sourceAfter.deleted)
        val moved = scheduleRepository.findById(requireNotNull(original.id)).orElseThrow()
        assertEquals(original.id, moved.id)
        assertEquals(calendar.id, moved.calendarId)
        assertEquals(destination.id, moved.categoryId)
        assertEquals(destination.id.toString(), moved.categorySnapshot?.categoryId)
        assertEquals(destination.title, moved.categorySnapshot?.title)
        assertEquals(destination.color, moved.categorySnapshot?.color)
    }

    @Test
    fun `same-name category requires explicit merge and rolls back without changing scope`() {
        val owner = member("conflict-owner")
        val calendar = writableCalendar(owner)
        val source = category(owner, "업무")
        category(owner, "업무", requireNotNull(calendar.id))
        val original = schedule(owner, source, "충돌 검증")

        val failure = assertThrows<BusinessException> {
            service.moveCategory(
                memberId = requireNotNull(owner.id),
                categoryId = requireNotNull(source.id),
                calendarId = requireNotNull(calendar.id),
                mergeIntoCategoryId = null,
                presentedSessionGeneration = owner.sessionGeneration,
            )
        }

        assertEquals(ErrorCode.INVALID_STATE, failure.errorCode)
        assertEquals(null, source.calendarId)
        assertFalse(source.deleted)
        assertEquals(null, original.calendarId)
        assertEquals(source.id, original.categoryId)
    }

    @Test
    fun `move revokes legacy category grants and pending invitations`() {
        val owner = member("legacy-owner")
        val recipient = member("legacy-recipient")
        val calendar = writableCalendar(owner)
        val source = category(owner, "공부")
        val destination = category(owner, "공부", requireNotNull(calendar.id))
        val sharedSchedule = schedule(owner, source, "독서")
        val share = categoryShareRepository.saveAndFlush(
            ScheduleCategoryShare(
                categoryId = requireNotNull(source.id),
                ownerMemberId = requireNotNull(owner.id),
                targetMemberId = requireNotNull(recipient.id),
                permission = ScheduleSharePermission.EDITOR,
                status = ScheduleShareStatus.ACTIVE,
            )
        )
        val invitation = invitationRepository.saveAndFlush(
            ScheduleShareInvitation(
                resourceType = ScheduleShareResourceType.CATEGORY,
                resourceId = requireNotNull(source.id),
                ownerMemberId = requireNotNull(owner.id),
                permission = ScheduleSharePermission.VIEWER,
                tokenHash = "move-invitation-${System.nanoTime()}",
                status = ScheduleShareInvitationStatus.PENDING,
                expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            )
        )
        val pushJob = pushJobRepository.saveAndFlush(
            SchedulePushJob.create(
                memberId = requireNotNull(recipient.id),
                scheduleId = requireNotNull(sharedSchedule.id),
                scheduleAt = sharedSchedule.startAt,
                departureAt = sharedSchedule.startAt.minusSeconds(1_800),
                monitorStartAt = sharedSchedule.startAt.minusSeconds(3_600),
                intervalMinutes = 20,
            )
        )

        service.moveCategory(
            memberId = requireNotNull(owner.id),
            categoryId = requireNotNull(source.id),
            calendarId = requireNotNull(calendar.id),
            mergeIntoCategoryId = requireNotNull(destination.id),
            presentedSessionGeneration = owner.sessionGeneration,
        )
        categoryShareRepository.flush()
        invitationRepository.flush()

        assertEquals(
            ScheduleShareStatus.REVOKED,
            categoryShareRepository.findById(requireNotNull(share.id)).orElseThrow().status,
        )
        assertEquals(
            ScheduleShareInvitationStatus.REVOKED,
            invitationRepository.findById(requireNotNull(invitation.id)).orElseThrow().status,
        )
        assertEquals(
            SchedulePushJobStatus.CANCELED,
            pushJobRepository.findById(requireNotNull(pushJob.id)).orElseThrow().status,
        )
    }

    @Test
    fun `move rejects a legacy editor-authored active schedule`() {
        val owner = member("foreign-owner")
        val editor = member("foreign-editor")
        val calendar = writableCalendar(owner)
        val source = category(owner, "공유되었던 분류")
        val foreignSchedule = schedule(editor, source, "편집자가 만든 일정")

        val failure = assertThrows<BusinessException> {
            service.moveCategory(
                memberId = requireNotNull(owner.id),
                categoryId = requireNotNull(source.id),
                calendarId = requireNotNull(calendar.id),
                mergeIntoCategoryId = null,
                presentedSessionGeneration = owner.sessionGeneration,
            )
        }

        assertEquals(ErrorCode.INVALID_STATE, failure.errorCode)
        assertEquals(null, source.calendarId)
        assertEquals(null, foreignSchedule.calendarId)
    }

    private fun member(label: String): Member = memberRepository.saveAndFlush(
        Member(
            name = label,
            password = "Password1!",
            email = "$label-${System.nanoTime()}@example.com",
        )
    )

    private fun writableCalendar(owner: Member): ScheduleCalendar {
        val calendar = calendarRepository.saveAndFlush(
            ScheduleCalendar(
                ownerMemberId = requireNotNull(owner.id),
                title = "가족 캘린더",
                color = "#246BFE",
            )
        )
        calendarMemberRepository.saveAndFlush(
            ScheduleCalendarMember(
                calendarId = requireNotNull(calendar.id),
                memberId = requireNotNull(owner.id),
                role = ScheduleCalendarRole.OWNER,
            )
        )
        return calendar
    }

    private fun category(
        owner: Member,
        title: String,
        calendarId: Long? = null,
        sortOrder: Int = 0,
    ): ScheduleCategory = categoryRepository.saveAndFlush(
        ScheduleCategory(
            memberId = requireNotNull(owner.id),
            calendarId = calendarId,
            title = title,
            color = "#34C759",
            sortOrder = sortOrder,
        )
    )

    private fun schedule(owner: Member, category: ScheduleCategory, title: String): Schedule =
        scheduleRepository.saveAndFlush(
            Schedule(
                memberId = requireNotNull(owner.id),
                title = title,
                startAt = Instant.parse("2026-08-20T01:00:00Z"),
                endAt = Instant.parse("2026-08-20T02:00:00Z"),
            ).apply {
                updateCategorySnapshot(
                    categoryId = requireNotNull(category.id).toString(),
                    title = category.title,
                    color = category.color,
                )
            }
        )
}
