package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareInvitation
import com.noLate.schedule.domain.ScheduleShareInvitationStatus
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class ScheduleShareInvitationServiceUnitTest {

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

    private val clock = Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneOffset.UTC)
    private val tokenGenerator = ShareInvitationTokenGenerator { "plain-token" }
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
            clock = clock,
            tokenGenerator = tokenGenerator,
        )
    }

    @Test
    fun `createScheduleInvitation returns raw token once and stores only token hash`() {
        whenever(scheduleRepository.findOwnedActiveForShareUpdate(10L, 1L))
            .thenReturn(schedule(10L, 1L))
        whenever(invitationRepository.saveAndFlush(any<ScheduleShareInvitation>()))
            .thenAnswer { invocation ->
                invocation.getArgument<ScheduleShareInvitation>(0).apply { id = 50L }
            }

        val result = service.createScheduleInvitation(
            ownerMemberId = 1L,
            scheduleId = 10L,
            permission = ScheduleSharePermission.VIEWER,
            ttlHours = 24,
            maxAcceptCount = 1,
        )

        verify(invitationRepository).saveAndFlush(check {
            assertEquals(ScheduleShareResourceType.SCHEDULE, it.resourceType)
            assertEquals(10L, it.resourceId)
            assertEquals(1L, it.ownerMemberId)
            assertEquals(ScheduleShareInvitationStatus.PENDING, it.status)
            assertEquals(Instant.parse("2026-07-12T00:00:00Z"), it.expiresAt)
            assertNotEquals("plain-token", it.tokenHash)
            assertTrue(it.tokenHash.length >= 64)
        })
        assertEquals("plain-token", result.token)
        assertEquals("/api/share-invitations/plain-token/accept", result.acceptPath)
    }

    @Test
    fun `acceptInvitation creates schedule share for current member and marks invitation accepted`() {
        val invitation = ScheduleShareInvitation(
            id = 70L,
            resourceType = ScheduleShareResourceType.SCHEDULE,
            resourceId = 10L,
            ownerMemberId = 1L,
            permission = ScheduleSharePermission.VIEWER,
            tokenHash = ShareInvitationTokenHasher.hash("plain-token"),
            expiresAt = Instant.parse("2026-07-12T00:00:00Z"),
            maxAcceptCount = 1,
        )
        whenever(invitationRepository.findByTokenHashAndDeletedFalse(invitation.tokenHash))
            .thenReturn(invitation)
        whenever(invitationRepository.findActiveByTokenHashForUpdate(invitation.tokenHash))
            .thenReturn(invitation)
        whenever(memberRepository.findByIdAndDeletedFalse(2L))
            .thenReturn(Member(id = 2L, name = "Target", password = "Password1!", email = "target@example.com"))
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 1L))
            .thenReturn(schedule(10L, 1L))
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 2L))
            .thenReturn(null)
        whenever(scheduleShareRepository.saveAndFlush(any<ScheduleShare>()))
            .thenAnswer { invocation ->
                invocation.getArgument<ScheduleShare>(0).apply { id = 80L }
            }
        whenever(invitationRepository.saveAndFlush(invitation)).thenReturn(invitation)

        val result = service.acceptInvitation(
            currentMemberId = 2L,
            token = "plain-token",
        )

        verify(scheduleShareRepository).saveAndFlush(check {
            assertEquals(10L, it.scheduleId)
            assertEquals(1L, it.ownerMemberId)
            assertEquals(2L, it.targetMemberId)
        })
        assertEquals(ScheduleShareInvitationStatus.ACCEPTED, invitation.status)
        assertEquals(2L, invitation.acceptedMemberId)
        assertEquals("80", result.share.id)
    }

    @Test
    fun `acceptInvitation rejects expired invitation without relying on rolled back status mutation`() {
        val invitation = ScheduleShareInvitation(
            id = 70L,
            resourceType = ScheduleShareResourceType.CATEGORY,
            resourceId = 7L,
            ownerMemberId = 1L,
            permission = ScheduleSharePermission.VIEWER,
            tokenHash = ShareInvitationTokenHasher.hash("plain-token"),
            expiresAt = Instant.parse("2026-07-10T00:00:00Z"),
            maxAcceptCount = 1,
        )
        whenever(invitationRepository.findByTokenHashAndDeletedFalse(invitation.tokenHash))
            .thenReturn(invitation)
        whenever(invitationRepository.findActiveByTokenHashForUpdate(invitation.tokenHash))
            .thenReturn(invitation)
        whenever(memberRepository.findByIdAndDeletedFalse(2L))
            .thenReturn(Member(id = 2L, name = "Target", password = "Password1!", email = "target@example.com"))

        assertThrows<BusinessException> {
            service.acceptInvitation(currentMemberId = 2L, token = "plain-token")
        }
        assertEquals(ScheduleShareInvitationStatus.PENDING, invitation.status)
        assertEquals(ScheduleShareInvitationStatus.EXPIRED, invitation.effectiveStatus(clock.instant()))
        assertEquals(
            ScheduleShareInvitationStatus.EXPIRED,
            invitation.toDto(effectiveAt = clock.instant()).status,
        )
    }

    private fun schedule(id: Long, ownerId: Long): Schedule =
        Schedule(
            id = id,
            memberId = ownerId,
            title = "공유 일정",
            startAt = Instant.parse("2026-07-11T01:00:00Z"),
            endAt = Instant.parse("2026-07-11T02:00:00Z"),
        ).apply {
            updateCategorySnapshot("1", "팀", "#2196f3")
        }
}
