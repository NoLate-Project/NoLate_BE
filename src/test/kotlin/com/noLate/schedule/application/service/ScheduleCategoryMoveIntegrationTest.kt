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
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.domain.ScheduleType
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import com.noLate.sharing.application.SharingBlockPolicy
import com.noLate.sharing.domain.SharingMemberBlock
import com.noLate.sharing.infrastructure.SharingMemberBlockRepository
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
    SharingBlockPolicy::class,
)
@TestPropertySource(properties = ["schedule.sharing.enabled=true"])
class ScheduleCategoryMoveIntegrationTest @Autowired constructor(
    private val service: ScheduleCategoryService,
    private val accessPolicy: ScheduleAccessPolicy,
    private val memberRepository: MemberRepository,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val categoryRepository: ScheduleCategoryRepository,
    private val scheduleRepository: ScheduleRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val invitationRepository: ScheduleShareInvitationRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val blockRepository: SharingMemberBlockRepository,
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
    fun `same-name move preserves category identity and allows both categories`() {
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
            mergeIntoCategoryId = null,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        scheduleRepository.flush()

        assertEquals(destination.id.toString(), preview.sameNameCategory?.id)
        assertFalse(result.merged)
        assertEquals(source.id.toString(), result.category.id)
        assertEquals(1, result.movedScheduleCount)
        val sourceAfter = categoryRepository.findById(requireNotNull(source.id)).orElseThrow()
        assertFalse(sourceAfter.deleted)
        assertEquals(calendar.id, sourceAfter.calendarId)
        assertEquals(
            2,
            categoryRepository.findAllByCalendarIdAndDeletedFalseOrderByIdAsc(requireNotNull(calendar.id))
                .count { it.title == "업무" },
        )
        val moved = scheduleRepository.findById(requireNotNull(original.id)).orElseThrow()
        assertEquals(original.id, moved.id)
        assertEquals(calendar.id, moved.calendarId)
        assertEquals(source.id, moved.categoryId)
        assertEquals(source.id.toString(), moved.categorySnapshot?.categoryId)
        assertEquals(source.title, moved.categorySnapshot?.title)
        assertEquals(source.color, moved.categorySnapshot?.color)
    }

    @Test
    fun `explicit category merge is rejected and rolls back without changing scope`() {
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
                mergeIntoCategoryId = 999L,
                presentedSessionGeneration = owner.sessionGeneration,
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT, failure.errorCode)
        assertEquals(null, source.calendarId)
        assertFalse(source.deleted)
        assertEquals(null, original.calendarId)
        assertEquals(source.id, original.categoryId)
    }

    @Test
    fun `move preserves direct category grants pending invitations and participant jobs`() {
        val owner = member("legacy-owner")
        val recipient = member("legacy-recipient")
        val calendar = writableCalendar(owner)
        val source = category(owner, "공부")
        category(owner, "공부", requireNotNull(calendar.id))
        val sharedSchedule = schedule(owner, source, "독서").also {
            it.scheduleType = ScheduleType.ROUTE
            scheduleRepository.saveAndFlush(it)
        }
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
            mergeIntoCategoryId = null,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        categoryShareRepository.flush()
        invitationRepository.flush()

        assertEquals(
            ScheduleShareStatus.ACTIVE,
            categoryShareRepository.findById(requireNotNull(share.id)).orElseThrow().status,
        )
        assertEquals(
            ScheduleShareInvitationStatus.PENDING,
            invitationRepository.findById(requireNotNull(invitation.id)).orElseThrow().status,
        )
        assertEquals(
            SchedulePushJobStatus.ACTIVE,
            pushJobRepository.findById(requireNotNull(pushJob.id)).orElseThrow().status,
        )
        val recipientId = requireNotNull(recipient.id)
        val destinationCalendarId = requireNotNull(calendar.id)
        assertTrue(
            calendarMemberRepository.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(
                destinationCalendarId,
                recipientId,
            ) == null,
        )
        val movedSchedule = scheduleRepository.findById(requireNotNull(sharedSchedule.id)).orElseThrow()
        assertTrue(accessPolicy.resolve(recipientId, movedSchedule).canEdit)
        val receivedCategory = service.getCategories(recipientId, recipient.sessionGeneration)
            .single { it.id == source.id.toString() }
        assertEquals(ScheduleSharePermission.EDITOR, receivedCategory.sharePermission)
        assertFalse(receivedCategory.canManageMetadata)
        assertFalse(receivedCategory.canManageAudience)
    }

    @Test
    fun `move keeps an editor-authored schedule inside the owner-managed category`() {
        val owner = member("foreign-owner")
        val editor = member("foreign-editor")
        val calendar = writableCalendar(owner)
        val source = category(owner, "공유되었던 분류")
        val foreignSchedule = schedule(editor, source, "편집자가 만든 일정")

        val result = service.moveCategory(
            memberId = requireNotNull(owner.id),
            categoryId = requireNotNull(source.id),
            calendarId = requireNotNull(calendar.id),
            mergeIntoCategoryId = null,
            presentedSessionGeneration = owner.sessionGeneration,
        )

        assertEquals(1, result.movedScheduleCount)
        assertEquals(calendar.id, categoryRepository.findById(requireNotNull(source.id)).orElseThrow().calendarId)
        val persisted = scheduleRepository.findById(requireNotNull(foreignSchedule.id)).orElseThrow()
        assertEquals(editor.id, persisted.memberId)
        assertEquals(calendar.id, persisted.calendarId)
        assertEquals(source.id, persisted.categoryId)
    }

    @Test
    fun `move to a different owner cancels a blocked direct recipients travel state`() {
        val sourceOwner = member("blocked-move-source-owner")
        val destinationOwner = member("blocked-move-destination-owner")
        val directRecipient = member("blocked-move-recipient")
        val sourceOwnerId = requireNotNull(sourceOwner.id)
        val destinationOwnerId = requireNotNull(destinationOwner.id)
        val recipientId = requireNotNull(directRecipient.id)
        val sourceCalendar = writableCalendar(sourceOwner)
        val destinationCalendar = writableCalendar(destinationOwner)
        calendarMemberRepository.saveAndFlush(
            ScheduleCalendarMember(
                calendarId = requireNotNull(destinationCalendar.id),
                memberId = sourceOwnerId,
                role = ScheduleCalendarRole.EDITOR,
            ),
        )
        val source = category(
            sourceOwner,
            "소유자 변경 분류",
            requireNotNull(sourceCalendar.id),
        )
        val routeSchedule = schedule(sourceOwner, source, "차단 수신자 이동").also {
            it.calendarId = sourceCalendar.id
            it.scheduleType = ScheduleType.ROUTE
            scheduleRepository.saveAndFlush(it)
        }
        val grant = categoryShareRepository.saveAndFlush(
            ScheduleCategoryShare(
                categoryId = requireNotNull(source.id),
                ownerMemberId = sourceOwnerId,
                targetMemberId = recipientId,
                permission = ScheduleSharePermission.EDITOR,
            ),
        )
        val directScheduleGrant = scheduleShareRepository.saveAndFlush(
            ScheduleShare(
                scheduleId = requireNotNull(routeSchedule.id),
                ownerMemberId = sourceOwnerId,
                targetMemberId = recipientId,
                permission = ScheduleSharePermission.EDITOR,
                contentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            ),
        )
        val job = pushJobRepository.saveAndFlush(
            SchedulePushJob.create(
                memberId = recipientId,
                scheduleId = requireNotNull(routeSchedule.id),
                scheduleAt = routeSchedule.startAt,
                departureAt = routeSchedule.startAt.minusSeconds(1_800),
                monitorStartAt = routeSchedule.startAt.minusSeconds(3_600),
                intervalMinutes = 20,
            ),
        )
        val plan = travelPlanRepository.saveAndFlush(
            ScheduleTravelPlan(
                scheduleId = requireNotNull(routeSchedule.id),
                memberId = recipientId,
                notificationEnabled = true,
                scheduleFingerprint = "blocked-owner-change",
            ),
        )
        blockRepository.saveAndFlush(
            SharingMemberBlock(
                blockerMemberId = destinationOwnerId,
                blockedMemberId = recipientId,
            ),
        )

        val preview = service.previewMove(
            memberId = sourceOwnerId,
            categoryId = requireNotNull(source.id),
            calendarId = requireNotNull(destinationCalendar.id),
            presentedSessionGeneration = sourceOwner.sessionGeneration,
        )
        service.moveCategory(
            memberId = sourceOwnerId,
            categoryId = requireNotNull(source.id),
            calendarId = requireNotNull(destinationCalendar.id),
            mergeIntoCategoryId = null,
            presentedSessionGeneration = sourceOwner.sessionGeneration,
        )

        assertEquals(1, preview.gainedAccessMemberCount)
        assertEquals(1, preview.lostAccessMemberCount)
        assertEquals(destinationOwnerId, categoryShareRepository.findById(requireNotNull(grant.id)).orElseThrow().ownerMemberId)
        assertEquals(
            destinationOwnerId,
            scheduleShareRepository.findById(requireNotNull(directScheduleGrant.id)).orElseThrow().ownerMemberId,
        )
        assertEquals(
            SchedulePushJobStatus.CANCELED,
            pushJobRepository.findById(requireNotNull(job.id)).orElseThrow().status,
        )
        assertFalse(
            travelPlanRepository.findById(requireNotNull(plan.id)).orElseThrow().deleted,
            "차단은 되돌릴 수 있으므로 개인 이동계획은 dormant 상태로 보존해야 합니다.",
        )
    }

    @Test
    fun `move preview excludes schedule-only grants from category audience delta`() {
        val sourceOwner = member("preview-schedule-only-source-owner")
        val destinationOwner = member("preview-schedule-only-destination-owner")
        val scheduleOnlyRecipient = member("preview-schedule-only-recipient")
        val sourceOwnerId = requireNotNull(sourceOwner.id)
        val destinationOwnerId = requireNotNull(destinationOwner.id)
        val recipientId = requireNotNull(scheduleOnlyRecipient.id)
        val destinationCalendar = writableCalendar(destinationOwner)
        val destinationCalendarId = requireNotNull(destinationCalendar.id)
        calendarMemberRepository.saveAndFlush(
            ScheduleCalendarMember(
                calendarId = destinationCalendarId,
                memberId = sourceOwnerId,
                role = ScheduleCalendarRole.EDITOR,
            ),
        )
        val source = category(sourceOwner, "일정만 직접 공유한 분류")
        val sharedSchedule = schedule(sourceOwner, source, "일정만 공유")
        scheduleShareRepository.saveAndFlush(
            ScheduleShare(
                scheduleId = requireNotNull(sharedSchedule.id),
                ownerMemberId = sourceOwnerId,
                targetMemberId = recipientId,
                permission = ScheduleSharePermission.VIEWER,
                contentMode = ScheduleShareContentMode.SCHEDULE_ONLY,
            ),
        )
        blockRepository.saveAndFlush(
            SharingMemberBlock(
                blockerMemberId = destinationOwnerId,
                blockedMemberId = recipientId,
            ),
        )

        val preview = service.previewMove(
            memberId = sourceOwnerId,
            categoryId = requireNotNull(source.id),
            calendarId = destinationCalendarId,
            presentedSessionGeneration = sourceOwner.sessionGeneration,
        )

        assertEquals(0, preview.retainedDirectShareCount)
        assertEquals(1, preview.retainedDirectScheduleShareCount)
        assertEquals(1, preview.gainedAccessMemberCount)
        assertEquals(0, preview.lostAccessMemberCount)
    }

    @Test
    fun `shared category owner can move into an editor destination with exact audience delta`() {
        val actor = member("shared-move-actor")
        val sourceOnly = member("shared-move-source-only")
        val overlap = member("shared-move-overlap")
        val destinationOwner = member("shared-move-destination-owner")
        val destinationOnly = member("shared-move-destination-only")
        val directRecipient = member("shared-move-direct")
        val actorId = requireNotNull(actor.id)
        val destinationOwnerId = requireNotNull(destinationOwner.id)
        val sourceCalendar = writableCalendar(actor)
        val sourceCalendarId = requireNotNull(sourceCalendar.id)
        listOf(sourceOnly, overlap).forEach { member ->
            calendarMemberRepository.saveAndFlush(
                ScheduleCalendarMember(
                    calendarId = sourceCalendarId,
                    memberId = requireNotNull(member.id),
                    role = ScheduleCalendarRole.VIEWER,
                ),
            )
        }
        val destinationCalendar = writableCalendar(destinationOwner)
        val destinationCalendarId = requireNotNull(destinationCalendar.id)
        listOf(
            actor to ScheduleCalendarRole.EDITOR,
            destinationOnly to ScheduleCalendarRole.VIEWER,
            overlap to ScheduleCalendarRole.VIEWER,
        ).forEach { (member, role) ->
            calendarMemberRepository.saveAndFlush(
                ScheduleCalendarMember(
                    calendarId = destinationCalendarId,
                    memberId = requireNotNull(member.id),
                    role = role,
                ),
            )
        }
        val source = category(actor, "프로젝트", sourceCalendarId)
        val movedSchedule = schedule(actor, source, "공유 회의").also {
            it.calendarId = sourceCalendarId
            scheduleRepository.saveAndFlush(it)
        }
        val share = categoryShareRepository.saveAndFlush(
            ScheduleCategoryShare(
                categoryId = requireNotNull(source.id),
                ownerMemberId = actorId,
                targetMemberId = requireNotNull(directRecipient.id),
                permission = ScheduleSharePermission.EDITOR,
            ),
        )
        val invitation = invitationRepository.saveAndFlush(
            ScheduleShareInvitation(
                resourceType = ScheduleShareResourceType.CATEGORY,
                resourceId = requireNotNull(source.id),
                ownerMemberId = actorId,
                permission = ScheduleSharePermission.VIEWER,
                tokenHash = "shared-move-${System.nanoTime()}",
                expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            ),
        )

        val preview = service.previewMove(
            memberId = actorId,
            categoryId = requireNotNull(source.id),
            calendarId = destinationCalendarId,
            presentedSessionGeneration = actor.sessionGeneration,
        )
        val result = service.moveCategory(
            memberId = actorId,
            categoryId = requireNotNull(source.id),
            calendarId = destinationCalendarId,
            mergeIntoCategoryId = null,
            presentedSessionGeneration = actor.sessionGeneration,
        )

        assertEquals(3, preview.sourceCalendarMemberCount)
        assertEquals(4, preview.destinationCalendarMemberCount)
        assertEquals(1, preview.retainedDirectShareCount)
        assertEquals(2, preview.gainedAccessMemberCount)
        assertEquals(1, preview.lostAccessMemberCount)
        assertEquals(ScheduleSharePermission.EDITOR, result.category.sharePermission)
        assertEquals(destinationCalendarId, categoryRepository.findById(requireNotNull(source.id)).orElseThrow().calendarId)
        assertEquals(destinationCalendarId, scheduleRepository.findById(requireNotNull(movedSchedule.id)).orElseThrow().calendarId)
        val retainedShare = categoryShareRepository.findById(requireNotNull(share.id)).orElseThrow()
        assertEquals(ScheduleShareStatus.ACTIVE, retainedShare.status)
        assertEquals(destinationOwnerId, retainedShare.ownerMemberId)
        val retainedInvitation = invitationRepository.findById(requireNotNull(invitation.id)).orElseThrow()
        assertEquals(ScheduleShareInvitationStatus.PENDING, retainedInvitation.status)
        assertEquals(destinationOwnerId, retainedInvitation.ownerMemberId)
    }

    @Test
    fun `destination viewer cannot move a shared category`() {
        val actor = member("shared-move-viewer-actor")
        val destinationOwner = member("shared-move-viewer-owner")
        val sourceCalendar = writableCalendar(actor)
        val destinationCalendar = writableCalendar(destinationOwner)
        calendarMemberRepository.saveAndFlush(
            ScheduleCalendarMember(
                calendarId = requireNotNull(destinationCalendar.id),
                memberId = requireNotNull(actor.id),
                role = ScheduleCalendarRole.VIEWER,
            ),
        )
        val source = category(actor, "이동 불가", requireNotNull(sourceCalendar.id))

        val failure = assertThrows<BusinessException> {
            service.previewMove(
                memberId = requireNotNull(actor.id),
                categoryId = requireNotNull(source.id),
                calendarId = requireNotNull(destinationCalendar.id),
                presentedSessionGeneration = actor.sessionGeneration,
            )
        }

        assertEquals(ErrorCode.FORBIDDEN, failure.errorCode)
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
