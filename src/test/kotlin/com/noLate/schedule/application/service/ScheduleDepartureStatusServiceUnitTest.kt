package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleDepartureParticipantRole
import com.noLate.schedule.domain.ScheduleDepartureStatus
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class ScheduleDepartureStatusServiceUnitTest {

    @Mock
    lateinit var scheduleRepository: ScheduleRepository

    @Mock
    lateinit var departureStatusRepository: ScheduleDepartureStatusRepository

    @Mock
    lateinit var scheduleShareRepository: ScheduleShareRepository

    @Mock
    lateinit var categoryShareRepository: ScheduleCategoryShareRepository

    @Mock
    lateinit var memberRepository: MemberRepository

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @Mock
    lateinit var scheduleAccessPolicy: ScheduleAccessPolicy

    private val now = Instant.parse("2026-07-11T01:20:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private lateinit var service: ScheduleDepartureStatusService

    @BeforeEach
    fun setUp() {
        service = ScheduleDepartureStatusService(
            scheduleRepository = scheduleRepository,
            departureStatusRepository = departureStatusRepository,
            scheduleShareRepository = scheduleShareRepository,
            categoryShareRepository = categoryShareRepository,
            memberRepository = memberRepository,
            eventPublisher = eventPublisher,
            clock = clock,
        )
    }

    @Test
    fun `markDeparted creates current member departure status for accessible shared schedule`() {
        val scheduleId = 10L
        val targetMemberId = 2L
        val schedule = scheduleEntity(id = scheduleId, ownerMemberId = 1L)
        whenever(scheduleRepository.findScheduleDetail(scheduleId, targetMemberId)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForDepartureUpdate(scheduleId)).thenReturn(schedule)
        whenever(departureStatusRepository.findActiveForUpdate(scheduleId, targetMemberId)).thenReturn(null)
        whenever(
            scheduleShareRepository.findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(
                scheduleId,
                ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(
            listOf(
                scheduleShare(targetMemberId = targetMemberId),
                scheduleShare(targetMemberId = 3L),
            )
        )
        whenever(
            categoryShareRepository.findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(
                5L,
                ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(
            listOf(
                categoryShare(targetMemberId = 3L),
                categoryShare(targetMemberId = 4L),
            )
        )
        whenever(memberRepository.findByIdAndDeletedFalse(targetMemberId))
            .thenReturn(member(targetMemberId, "target2@nolate.test"))
        whenever(departureStatusRepository.saveAndFlush(any<ScheduleDepartureStatus>()))
            .thenAnswer { it.getArgument(0) }

        val result = service.markDeparted(targetMemberId, scheduleId)

        verify(departureStatusRepository).saveAndFlush(check {
            assertEquals(scheduleId, it.scheduleId)
            assertEquals(targetMemberId, it.memberId)
            assertEquals(now, it.departedAt)
        })
        assertEquals(now, result.departedAt)
        verify(eventPublisher).publishEvent(check<ScheduleParticipantDepartedEvent> {
            assertEquals(scheduleId, it.scheduleId)
            assertEquals("공유 일정", it.scheduleTitle)
            assertEquals(targetMemberId, it.departedMemberId)
            assertEquals("member2", it.departedMemberLabel)
            assertEquals(listOf(1L, 3L, 4L), it.recipientMemberIds)
        })
    }

    @Test
    fun `markDeparted keeps first departure time when same participant repeats request`() {
        val scheduleId = 10L
        val targetMemberId = 2L
        val firstDepartedAt = Instant.parse("2026-07-11T01:00:00Z")
        val schedule = scheduleEntity(id = scheduleId, ownerMemberId = 1L)
        val existing = ScheduleDepartureStatus(
            id = 40L,
            scheduleId = scheduleId,
            memberId = targetMemberId,
            departedAt = firstDepartedAt,
        )
        whenever(scheduleRepository.findScheduleDetail(scheduleId, targetMemberId)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForDepartureUpdate(scheduleId)).thenReturn(schedule)
        whenever(departureStatusRepository.findActiveForUpdate(scheduleId, targetMemberId)).thenReturn(existing)
        whenever(departureStatusRepository.saveAndFlush(existing)).thenReturn(existing)

        val result = service.markDeparted(targetMemberId, scheduleId)

        assertEquals(firstDepartedAt, result.departedAt)
        verify(departureStatusRepository).saveAndFlush(check {
            assertEquals(firstDepartedAt, it.departedAt)
        })
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `schedule only recipient cannot publish a departure state`() {
        val schedule = scheduleEntity(id = 10L, ownerMemberId = 1L)
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(schedule)
        whenever(scheduleAccessPolicy.resolve(2L, schedule)).thenReturn(
            ScheduleAccessDecision(
                canView = true,
                canEdit = false,
                travelEnabled = false,
                canViewAllTravelPlans = false,
            )
        )
        val policyBackedService = ScheduleDepartureStatusService(
            scheduleRepository = scheduleRepository,
            departureStatusRepository = departureStatusRepository,
            scheduleShareRepository = scheduleShareRepository,
            categoryShareRepository = categoryShareRepository,
            memberRepository = memberRepository,
            eventPublisher = eventPublisher,
            clock = clock,
            scheduleAccessPolicy = scheduleAccessPolicy,
        )

        val error = assertThrows(BusinessException::class.java) {
            policyBackedService.markDeparted(memberId = 2L, scheduleId = 10L)
        }

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode)
        verifyNoInteractions(departureStatusRepository, eventPublisher)
    }

    @Test
    fun `viewer sees departure states but not other participants email addresses`() {
        val dto = scheduleDto(
            scheduleId = 10L,
            ownerMemberId = 1L,
            categoryId = "5",
            ownerDepartedAt = "2026-07-11T01:00:00Z",
        )
        whenever(
            scheduleShareRepository.findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(
                10L,
                ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(listOf(scheduleShare(targetMemberId = 2L)))
        whenever(
            categoryShareRepository.findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(
                5L,
                ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(listOf(categoryShare(targetMemberId = 3L)))
        whenever(departureStatusRepository.findAllByScheduleIdAndDeletedFalse(10L))
            .thenReturn(listOf(ScheduleDepartureStatus(scheduleId = 10L, memberId = 2L, departedAt = now)))
        whenever(memberRepository.findByIdAndDeletedFalse(2L)).thenReturn(member(2L, "target2@nolate.test"))

        val result = service.attachDepartureParticipants(currentMemberId = 2L, scheduleDto = dto)

        assertEquals(now.toString(), result.myDepartedAt)
        assertEquals(3, result.departureParticipants.size)
        assertEquals(ScheduleDepartureParticipantRole.OWNER, result.departureParticipants[0].role)
        assertEquals(null, result.departureParticipants[0].email)
        assertEquals("target2@nolate.test", result.departureParticipants[1].email)
        assertEquals(null, result.departureParticipants[2].email)
        assertTrue(result.departureParticipants[0].departed)
        assertTrue(result.departureParticipants[1].departed)
        assertFalse(result.departureParticipants[2].departed)
    }

    private fun scheduleEntity(id: Long, ownerMemberId: Long): Schedule =
        Schedule(
            id = id,
            memberId = ownerMemberId,
            title = "공유 일정",
            startAt = Instant.parse("2026-07-11T02:00:00Z"),
            endAt = Instant.parse("2026-07-11T03:00:00Z"),
        ).apply {
            updateCategorySnapshot(categoryId = "5", title = "공유 캘린더", color = "#34C759")
        }

    private fun scheduleDto(
        scheduleId: Long,
        ownerMemberId: Long,
        categoryId: String,
        ownerDepartedAt: String?,
    ): ScheduleDto =
        ScheduleDto(
            id = scheduleId,
            ownerMemberId = ownerMemberId,
            title = "공유 일정",
            startAt = "2026-07-11T02:00:00Z",
            endAt = "2026-07-11T03:00:00Z",
            category = ScheduleCategoryDto(id = categoryId, title = "공유 캘린더", color = "#34C759"),
            departedAt = ownerDepartedAt,
        )

    private fun scheduleShare(targetMemberId: Long): ScheduleShare =
        ScheduleShare(
            id = targetMemberId,
            scheduleId = 10L,
            ownerMemberId = 1L,
            targetMemberId = targetMemberId,
            permission = ScheduleSharePermission.COMMENTER,
        )

    private fun categoryShare(targetMemberId: Long): ScheduleCategoryShare =
        ScheduleCategoryShare(
            id = targetMemberId,
            categoryId = 5L,
            ownerMemberId = 1L,
            targetMemberId = targetMemberId,
            permission = ScheduleSharePermission.VIEWER,
        )

    private fun member(id: Long, email: String): Member =
        Member(id = id, name = "member$id", email = email)
}
