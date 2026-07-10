package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleSharePermission
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
import java.time.Instant

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
            assertEquals(ScheduleShareStatus.ACTIVE, it.status)
        })
        assertEquals("100", result.id)
        assertEquals("friend@example.com", result.targetEmail)
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
