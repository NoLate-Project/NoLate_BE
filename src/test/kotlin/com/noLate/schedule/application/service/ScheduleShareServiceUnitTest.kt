package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleShareInvitation
import com.noLate.schedule.domain.ScheduleShareInvitationStatus
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class ScheduleShareServiceUnitTest {

    @Mock
    lateinit var scheduleRepository: ScheduleRepository

    @Mock
    lateinit var scheduleShareRepository: ScheduleShareRepository

    @Mock
    lateinit var categoryRepository: ScheduleCategoryRepository

    @Mock
    lateinit var categoryShareRepository: ScheduleCategoryShareRepository

    @Mock
    lateinit var invitationRepository: ScheduleShareInvitationRepository

    @Mock
    lateinit var memberRepository: MemberRepository

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @Mock
    lateinit var travelAccessCleanupService: ScheduleTravelAccessCleanupService

    private val clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: ScheduleShareService

    @BeforeEach
    fun setUp() {
        service = ScheduleShareService(
            scheduleRepository = scheduleRepository,
            scheduleShareRepository = scheduleShareRepository,
            categoryRepository = categoryRepository,
            categoryShareRepository = categoryShareRepository,
            invitationRepository = invitationRepository,
            memberRepository = memberRepository,
            eventPublisher = eventPublisher,
            clock = clock,
            travelAccessCleanupService = travelAccessCleanupService,
        )
    }

    @Test
    fun `shareSchedule creates active viewer share for another member email`() {
        val ownerId = 1L
        val target = member(id = 2L, email = "friend@example.com")

        whenever(scheduleRepository.findOwnedActiveForShareUpdate(10L, ownerId))
            .thenReturn(schedule(id = 10L, ownerId = ownerId))
        whenever(memberRepository.findByEmailAndDeletedFalse("friend@example.com"))
            .thenReturn(target)
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 2L))
            .thenReturn(null)
        whenever(scheduleShareRepository.saveAndFlush(any<com.noLate.schedule.domain.ScheduleShare>()))
            .thenAnswer { invocation ->
                invocation.getArgument<com.noLate.schedule.domain.ScheduleShare>(0).apply { id = 100L }
            }

        val result = service.shareSchedule(
            ownerMemberId = ownerId,
            scheduleId = 10L,
            targetEmail = " Friend@Example.com ",
            permission = ScheduleSharePermission.VIEWER,
        )

        verify(scheduleShareRepository).saveAndFlush(check {
            assertEquals(10L, it.scheduleId)
            assertEquals(ownerId, it.ownerMemberId)
            assertEquals(2L, it.targetMemberId)
            assertEquals(ScheduleSharePermission.VIEWER, it.permission)
            assertEquals(ScheduleShareContentMode.SCHEDULE_AND_TRAVEL, it.contentMode)
            assertEquals(ScheduleShareStatus.ACTIVE, it.status)
        })
        assertEquals("100", result.id)
        assertEquals("friend@example.com", result.targetEmail)
        verify(eventPublisher).publishEvent(check<ScheduleShareGrantedEvent> {
            assertEquals(ScheduleShareResourceType.SCHEDULE, it.resourceType)
            assertEquals(10L, it.resourceId)
            assertEquals(2L, it.targetMemberId)
            assertEquals("공유 일정", it.resourceTitle)
        })
    }

    @Test
    fun `shareSchedule resolves target by app id when email is omitted`() {
        val ownerId = 1L
        val target = member(id = 2L, email = "friend@example.com")

        whenever(scheduleRepository.findOwnedActiveForShareUpdate(10L, ownerId))
            .thenReturn(schedule(id = 10L, ownerId = ownerId))
        whenever(memberRepository.findByIdAndDeletedFalse(2L)).thenReturn(target)
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 2L)).thenReturn(null)
        whenever(scheduleShareRepository.saveAndFlush(any<ScheduleShare>()))
            .thenAnswer { invocation -> invocation.getArgument<ScheduleShare>(0).apply { id = 101L } }

        val result = service.shareSchedule(
            ownerMemberId = ownerId,
            scheduleId = 10L,
            targetEmail = null,
            targetAppId = 2L,
            permission = ScheduleSharePermission.VIEWER,
        )

        assertEquals(2L, result.targetMemberId)
        assertEquals("friend@example.com", result.targetEmail)
        verify(memberRepository).findByIdAndDeletedFalse(2L)
    }

    @Test
    fun `shareSchedule rejects ambiguous target with both email and app id`() {
        assertThrows<BusinessException> {
            service.shareSchedule(
                ownerMemberId = 1L,
                scheduleId = 10L,
                targetEmail = "friend@example.com",
                targetAppId = 2L,
                permission = ScheduleSharePermission.VIEWER,
            )
        }

        verify(scheduleRepository, never()).findOwnedActiveForShareUpdate(any(), any())
        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `shareSchedule does not publish another notification event for an already active share`() {
        val ownerId = 1L
        val target = member(id = 2L, email = "friend@example.com")
        val existing = ScheduleShare(
            id = 30L,
            scheduleId = 10L,
            ownerMemberId = ownerId,
            targetMemberId = 2L,
            permission = ScheduleSharePermission.VIEWER,
            status = ScheduleShareStatus.ACTIVE,
        )

        whenever(scheduleRepository.findOwnedActiveForShareUpdate(10L, ownerId))
            .thenReturn(schedule(id = 10L, ownerId = ownerId))
        whenever(memberRepository.findByEmailAndDeletedFalse("friend@example.com")).thenReturn(target)
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 2L)).thenReturn(existing)
        whenever(scheduleShareRepository.saveAndFlush(existing)).thenReturn(existing)

        service.shareSchedule(
            ownerMemberId = ownerId,
            scheduleId = 10L,
            targetEmail = "friend@example.com",
            permission = ScheduleSharePermission.EDITOR,
        )

        assertEquals(ScheduleSharePermission.EDITOR, existing.permission)
        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `shareSchedule can narrow an active grant to schedule only`() {
        val target = member(id = 2L, email = "friend@example.com")
        val existing = ScheduleShare(
            id = 30L,
            scheduleId = 10L,
            ownerMemberId = 1L,
            targetMemberId = 2L,
            permission = ScheduleSharePermission.VIEWER,
            contentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
        )
        whenever(scheduleRepository.findOwnedActiveForShareUpdate(10L, 1L))
            .thenReturn(schedule(id = 10L, ownerId = 1L))
        whenever(memberRepository.findByEmailAndDeletedFalse("friend@example.com")).thenReturn(target)
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 2L)).thenReturn(existing)
        whenever(scheduleShareRepository.saveAndFlush(existing)).thenReturn(existing)

        val result = service.shareSchedule(
            ownerMemberId = 1L,
            scheduleId = 10L,
            targetEmail = "friend@example.com",
            permission = ScheduleSharePermission.VIEWER,
            contentMode = ScheduleShareContentMode.SCHEDULE_ONLY,
        )

        assertEquals(ScheduleShareContentMode.SCHEDULE_ONLY, existing.contentMode)
        assertEquals(ScheduleShareContentMode.SCHEDULE_ONLY, result.contentMode)
        verify(travelAccessCleanupService).cancelRevokedForSchedule(10L, listOf(2L))
    }

    @Test
    fun `shareSchedule rejects sharing a schedule with its owner`() {
        val ownerId = 1L

        whenever(scheduleRepository.findOwnedActiveForShareUpdate(10L, ownerId))
            .thenReturn(schedule(id = 10L, ownerId = ownerId))
        whenever(memberRepository.findByEmailAndDeletedFalse("owner@example.com"))
            .thenReturn(member(id = ownerId, email = "owner@example.com"))

        assertThrows<BusinessException> {
            service.shareSchedule(
                ownerMemberId = ownerId,
                scheduleId = 10L,
                targetEmail = "owner@example.com",
                permission = ScheduleSharePermission.VIEWER,
            )
        }

        verify(scheduleShareRepository, never()).saveAndFlush(any())
    }

    @Test
    fun `shareCategory reactivates existing revoked share instead of inserting duplicate row`() {
        val ownerId = 1L
        val target = member(id = 2L, email = "friend@example.com")
        val existing = com.noLate.schedule.domain.ScheduleCategoryShare(
            id = 30L,
            categoryId = 7L,
            ownerMemberId = ownerId,
            targetMemberId = 2L,
            permission = ScheduleSharePermission.VIEWER,
            status = ScheduleShareStatus.REVOKED,
        )

        whenever(categoryRepository.findOwnedActiveForShareUpdate(7L, ownerId))
            .thenReturn(ScheduleCategory(id = 7L, memberId = ownerId, title = "팀"))
        whenever(memberRepository.findByEmailAndDeletedFalse("friend@example.com"))
            .thenReturn(target)
        whenever(categoryShareRepository.findByCategoryIdAndTargetMemberId(7L, 2L))
            .thenReturn(existing)
        whenever(categoryShareRepository.saveAndFlush(existing)).thenReturn(existing)

        val result = service.shareCategory(
            ownerMemberId = ownerId,
            categoryId = 7L,
            targetEmail = "friend@example.com",
            permission = ScheduleSharePermission.EDITOR,
        )

        assertEquals("30", result.id)
        assertEquals(ScheduleSharePermission.EDITOR, existing.permission)
        assertEquals(ScheduleShareStatus.ACTIVE, existing.status)
        verify(categoryShareRepository).saveAndFlush(existing)
        verify(eventPublisher).publishEvent(check<ScheduleShareGrantedEvent> {
            assertEquals(ScheduleShareResourceType.CATEGORY, it.resourceType)
            assertEquals(7L, it.resourceId)
            assertEquals(2L, it.targetMemberId)
            assertEquals("팀", it.resourceTitle)
        })
    }

    @Test
    fun `getShareInbox aggregates received shares for virtual member ids`() {
        val ownerId = 101L
        val targetId = 202L
        val scheduleShare = ScheduleShare(
            id = 301L,
            scheduleId = 10L,
            ownerMemberId = ownerId,
            targetMemberId = targetId,
            permission = ScheduleSharePermission.VIEWER,
            status = ScheduleShareStatus.ACTIVE,
        )
        val categoryShare = ScheduleCategoryShare(
            id = 302L,
            categoryId = 7L,
            ownerMemberId = ownerId,
            targetMemberId = targetId,
            permission = ScheduleSharePermission.EDITOR,
            status = ScheduleShareStatus.ACTIVE,
        )

        whenever(scheduleShareRepository.findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
            targetMemberId = targetId,
            status = ScheduleShareStatus.ACTIVE,
        )).thenReturn(listOf(scheduleShare))
        whenever(categoryShareRepository.findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
            targetMemberId = targetId,
            status = ScheduleShareStatus.ACTIVE,
        )).thenReturn(listOf(categoryShare))
        whenever(scheduleRepository.findAllById(listOf(10L)))
            .thenReturn(listOf(schedule(id = 10L, ownerId = ownerId)))
        whenever(categoryRepository.findAllById(listOf(7L)))
            .thenReturn(listOf(ScheduleCategory(id = 7L, memberId = ownerId, title = "공유 업무", color = "#34c759")))
        whenever(memberRepository.findByIdAndDeletedFalse(ownerId))
            .thenReturn(member(id = ownerId, email = "owner@example.com"))

        val result = service.getShareInbox(memberId = targetId)

        assertEquals(0, result.pendingInvitations.size)
        assertEquals(2, result.receivedShares.size)
        assertEquals(
            setOf(ScheduleShareResourceType.SCHEDULE, ScheduleShareResourceType.CATEGORY),
            result.receivedShares.map { it.resourceType }.toSet(),
        )
        assertEquals("owner@example.com", result.receivedShares.first().ownerEmail)
    }

    @Test
    fun `getShareOutbox aggregates shared resources and active links for virtual owner id`() {
        val ownerId = 101L
        val scheduleShare = ScheduleShare(
            id = 401L,
            scheduleId = 10L,
            ownerMemberId = ownerId,
            targetMemberId = 202L,
            permission = ScheduleSharePermission.VIEWER,
            status = ScheduleShareStatus.ACTIVE,
        )
        val secondScheduleShare = ScheduleShare(
            id = 402L,
            scheduleId = 10L,
            ownerMemberId = ownerId,
            targetMemberId = 203L,
            permission = ScheduleSharePermission.COMMENTER,
            status = ScheduleShareStatus.ACTIVE,
        )
        val categoryShare = ScheduleCategoryShare(
            id = 403L,
            categoryId = 7L,
            ownerMemberId = ownerId,
            targetMemberId = 204L,
            permission = ScheduleSharePermission.EDITOR,
            status = ScheduleShareStatus.ACTIVE,
        )
        val activeInvitation = ScheduleShareInvitation(
            id = 501L,
            resourceType = ScheduleShareResourceType.CATEGORY,
            resourceId = 7L,
            ownerMemberId = ownerId,
            permission = ScheduleSharePermission.VIEWER,
            tokenHash = "hashed-token",
            status = ScheduleShareInvitationStatus.PENDING,
            expiresAt = Instant.parse("2026-07-20T00:00:00Z"),
            maxAcceptCount = 5,
            acceptedCount = 1,
        )
        val expiredInvitation = ScheduleShareInvitation(
            id = 502L,
            resourceType = ScheduleShareResourceType.SCHEDULE,
            resourceId = 11L,
            ownerMemberId = ownerId,
            permission = ScheduleSharePermission.VIEWER,
            tokenHash = "expired-token-hash",
            status = ScheduleShareInvitationStatus.PENDING,
            expiresAt = Instant.parse("2026-07-14T23:59:59Z"),
            maxAcceptCount = 1,
        )

        whenever(scheduleShareRepository.findAllByOwnerMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
            ownerMemberId = ownerId,
            status = ScheduleShareStatus.ACTIVE,
        )).thenReturn(listOf(scheduleShare, secondScheduleShare))
        whenever(categoryShareRepository.findAllByOwnerMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
            ownerMemberId = ownerId,
            status = ScheduleShareStatus.ACTIVE,
        )).thenReturn(listOf(categoryShare))
        whenever(invitationRepository.findAllByOwnerMemberIdAndDeletedFalseOrderByIdDesc(ownerId))
            .thenReturn(listOf(activeInvitation, expiredInvitation))
        whenever(scheduleRepository.findAllById(listOf(10L)))
            .thenReturn(listOf(schedule(id = 10L, ownerId = ownerId)))
        whenever(categoryRepository.findAllById(listOf(7L)))
            .thenReturn(listOf(ScheduleCategory(id = 7L, memberId = ownerId, title = "공유 업무", color = "#34c759")))
        whenever(memberRepository.findByIdAndDeletedFalse(202L))
            .thenReturn(member(id = 202L, email = "target-a@example.com"))
        whenever(memberRepository.findByIdAndDeletedFalse(203L))
            .thenReturn(member(id = 203L, email = "target-b@example.com"))
        whenever(memberRepository.findByIdAndDeletedFalse(204L))
            .thenReturn(member(id = 204L, email = "target-c@example.com"))

        val result = service.getShareOutbox(ownerMemberId = ownerId)

        assertEquals(2, result.sharedResources.size)
        assertEquals(1, result.activeInvitations.size)
        assertEquals("공유 업무", result.activeInvitations.single().title)
        assertEquals(2, result.sharedResources.first { it.resourceType == ScheduleShareResourceType.SCHEDULE }.shareCount)
        assertEquals(1, result.sharedResources.first { it.resourceType == ScheduleShareResourceType.CATEGORY }.shareCount)
    }

    private fun member(id: Long, email: String): Member =
        Member(
            id = id,
            name = "User $id",
            password = "Password1!",
            email = email,
        )

    private fun schedule(id: Long, ownerId: Long): Schedule =
        Schedule(
            id = id,
            memberId = ownerId,
            title = "공유 일정",
            startAt = Instant.parse("2026-07-10T01:00:00Z"),
            endAt = Instant.parse("2026-07-10T02:00:00Z"),
        ).apply {
            updateCategorySnapshot("7", "팀", "#2196f3")
        }
}
