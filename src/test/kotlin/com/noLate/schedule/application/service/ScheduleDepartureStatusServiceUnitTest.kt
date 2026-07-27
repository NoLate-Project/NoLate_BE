package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.cache.ScheduleCalendarCacheInvalidationEvent
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.check
import org.mockito.kotlin.times
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
        whenever(memberRepository.findAllByIdsForUpdate(listOf(1L, 2L, 3L, 4L)))
            .thenReturn(
                listOf(
                    member(1L, "owner@nolate.test"),
                    member(2L, "target2@nolate.test"),
                    member(3L, "target3@nolate.test"),
                    member(4L, "target4@nolate.test"),
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
        val publishedEvents = argumentCaptor<Any>()
        verify(eventPublisher, times(2)).publishEvent(publishedEvents.capture())
        publishedEvents.allValues.filterIsInstance<ScheduleParticipantDepartedEvent>().single().let {
            assertEquals(scheduleId, it.scheduleId)
            assertEquals("공유 일정", it.scheduleTitle)
            assertEquals(targetMemberId, it.departedMemberId)
            assertEquals("member2", it.departedMemberLabel)
            assertEquals(listOf(1L, 3L, 4L), it.recipientMemberIds)
        }
        publishedEvents.allValues.filterIsInstance<ScheduleCalendarCacheInvalidationEvent>().single().let {
            assertEquals(setOf(1L, 2L, 3L, 4L), it.memberIds)
            assertEquals("schedule-participant-departed", it.reason)
        }
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
        whenever(memberRepository.findAllByIdsForUpdate(listOf(1L, 2L)))
            .thenReturn(
                listOf(
                    member(1L, "owner@nolate.test"),
                    member(2L, "target2@nolate.test"),
                )
            )

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
    fun `global off uses owner detail query and hides dormant participant departure action`() {
        whenever(scheduleAccessPolicy.isSharingDisabled()).thenReturn(true)
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 2L)).thenReturn(null)
        val ownerOnlyService = ScheduleDepartureStatusService(
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
            ownerOnlyService.markDeparted(memberId = 2L, scheduleId = 10L)
        }

        assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, error.errorCode)
        verify(scheduleRepository).findOwnedScheduleDetail(10L, 2L)
        verify(scheduleRepository, org.mockito.kotlin.never()).findScheduleDetail(10L, 2L)
        verifyNoInteractions(departureStatusRepository, eventPublisher)
    }

    @Test
    fun `notification action locks every participant by member id before validating generation`() {
        val schedule = scheduleEntity(id = 10L, ownerMemberId = 20L)
        whenever(scheduleRepository.findScheduleDetail(10L, 30L)).thenReturn(schedule)
        whenever(
            scheduleShareRepository.findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(
                10L,
                ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(
            listOf(
                scheduleShare(targetMemberId = 30L),
                scheduleShare(targetMemberId = 10L),
            )
        )
        whenever(
            categoryShareRepository.findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(
                5L,
                ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(emptyList())
        whenever(memberRepository.findAllByIdsForUpdate(listOf(10L, 20L, 30L)))
            .thenReturn(
                listOf(
                    member(10L, "member10@nolate.test"),
                    member(20L, "member20@nolate.test"),
                    member(30L, "member30@nolate.test").apply { sessionGeneration = 7L },
                )
            )

        service.lockNotificationActionMembers(
            memberId = 30L,
            scheduleId = 10L,
            presentedSessionGeneration = 7L,
        )

        verify(memberRepository).findAllByIdsForUpdate(listOf(10L, 20L, 30L))
    }

    @Test
    fun `notification action rejects a stale generation after participant locks`() {
        val schedule = scheduleEntity(id = 10L, ownerMemberId = 1L)
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(schedule)
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
        ).thenReturn(emptyList())
        whenever(memberRepository.findAllByIdsForUpdate(listOf(1L, 2L)))
            .thenReturn(
                listOf(
                    member(1L, "owner@nolate.test"),
                    member(2L, "target@nolate.test").apply { sessionGeneration = 4L },
                )
            )

        val failure = assertThrows(BusinessException::class.java) {
            service.lockNotificationActionMembers(
                memberId = 2L,
                scheduleId = 10L,
                presentedSessionGeneration = 3L,
            )
        }

        assertEquals(ErrorCode.INVALID_TOKEN, failure.errorCode)
    }

    @Test
    fun `notification action redrive keeps frozen recipients and never acquires a second member lock`() {
        val scheduleId = 10L
        val actorMemberId = 3L
        val schedule = scheduleEntity(id = scheduleId, ownerMemberId = 1L)
        whenever(scheduleRepository.findScheduleDetail(scheduleId, actorMemberId))
            .thenReturn(schedule)
        whenever(scheduleRepository.findActiveForDepartureUpdate(scheduleId)).thenReturn(schedule)
        whenever(
            scheduleShareRepository.findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(
                scheduleId,
                ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(
            // 최초 preview에는 4번이 있었지만, mutation 직전에는 revoke되고 더 낮은 2번이
            // 새로 grant되었다. 현재 action은 최초 집합을 확장해서 2번을 잠그거나 알리면 안 된다.
            listOf(
                scheduleShare(targetMemberId = actorMemberId),
                scheduleShare(targetMemberId = 4L),
            ),
            listOf(
                scheduleShare(targetMemberId = actorMemberId),
                scheduleShare(targetMemberId = 2L),
            ),
        )
        whenever(
            categoryShareRepository.findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(
                5L,
                ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(emptyList())
        whenever(memberRepository.findAllByIdsForUpdate(listOf(1L, 3L, 4L)))
            .thenReturn(
                listOf(
                    member(1L, "owner@nolate.test"),
                    member(3L, "actor@nolate.test").apply { sessionGeneration = 9L },
                    member(4L, "recipient4@nolate.test"),
                )
            )
        whenever(departureStatusRepository.findActiveForUpdate(scheduleId, actorMemberId))
            .thenReturn(null)
        whenever(departureStatusRepository.saveAndFlush(any<ScheduleDepartureStatus>()))
            .thenAnswer { it.getArgument(0) }
        whenever(memberRepository.findByIdAndDeletedFalse(actorMemberId))
            .thenReturn(member(actorMemberId, "actor@nolate.test"))

        val memberFence = service.lockNotificationActionMembers(
            memberId = actorMemberId,
            scheduleId = scheduleId,
            presentedSessionGeneration = 9L,
        )
        service.markDeparted(actorMemberId, scheduleId, memberFence)

        assertEquals(setOf(1L, 4L), memberFence.frozenRecipientMemberIds)
        verify(memberRepository, times(1)).findAllByIdsForUpdate(any())
        val publishedEvents = argumentCaptor<Any>()
        verify(eventPublisher, times(2)).publishEvent(publishedEvents.capture())
        publishedEvents.allValues.filterIsInstance<ScheduleParticipantDepartedEvent>().single().let {
            // 4번 revoke는 반영하되, preview 뒤 새 grant된 2번은 이번 action에 편입하지 않는다.
            assertEquals(listOf(1L), it.recipientMemberIds)
        }
        publishedEvents.allValues.filterIsInstance<ScheduleCalendarCacheInvalidationEvent>().single().let {
            // revision audience도 최초 동결 집합 밖의 2번을 뒤늦게 편입하지 않는다.
            assertEquals(setOf(1L, 3L, 4L), it.memberIds)
        }
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
