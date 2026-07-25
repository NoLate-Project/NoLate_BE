package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.service.PreparedPushEvent
import com.noLate.notification.application.service.PushEventOutboxService
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleDepartureStatus
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.mockito.Mockito.lenient
import org.springframework.mock.env.MockEnvironment
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class ScheduleDepartureNotificationServiceUnitTest {

    @Mock
    lateinit var memberRepository: MemberRepository

    @Mock
    lateinit var scheduleRepository: ScheduleRepository

    @Mock
    lateinit var scheduleShareRepository: ScheduleShareRepository

    @Mock
    lateinit var categoryShareRepository: ScheduleCategoryShareRepository

    @Mock
    lateinit var departureStatusRepository: ScheduleDepartureStatusRepository

    @Mock
    lateinit var pushEventOutboxService: PushEventOutboxService

    @Mock
    lateinit var scheduleAccessPolicy: ScheduleAccessPolicy

    private val sharingAvailability = ScheduleSharingAvailabilityPolicy(
        MockEnvironment().withProperty("schedule.sharing.enabled", "true"),
    )

    @BeforeEach
    fun setUpMemberLocks() {
        lenient().whenever(memberRepository.findByIdForUpdate(any())).thenAnswer { invocation ->
            val memberId = invocation.getArgument<Long>(0)
            Member(
                id = memberId,
                name = "Member $memberId",
                password = "Password1!",
                email = "member-$memberId@example.com",
            )
        }
    }

    private fun service() = ScheduleDepartureNotificationService(
        memberRepository = memberRepository,
        scheduleRepository = scheduleRepository,
        scheduleShareRepository = scheduleShareRepository,
        categoryShareRepository = categoryShareRepository,
            departureStatusRepository = departureStatusRepository,
            pushEventOutboxService = pushEventOutboxService,
            sharingAvailability = sharingAvailability,
        )

    @Test
    fun `sharing off rejects nudge before reading participants or creating an outbox event`() {
        val disabledService = ScheduleDepartureNotificationService(
            memberRepository = memberRepository,
            scheduleRepository = scheduleRepository,
            scheduleShareRepository = scheduleShareRepository,
            categoryShareRepository = categoryShareRepository,
            departureStatusRepository = departureStatusRepository,
            pushEventOutboxService = pushEventOutboxService,
            sharingAvailability = ScheduleSharingAvailabilityPolicy(MockEnvironment()),
        )

        val failure = assertThrows(BusinessException::class.java) {
            disabledService.sendDepartureNudge(1L, 10L, 2L, 0L)
        }

        assertEquals(ErrorCode.FEATURE_DISABLED, failure.errorCode)
        verifyNoInteractions(scheduleRepository, pushEventOutboxService)
    }

    @Test
    fun `owner can send a departure nudge to an active direct share participant`() {
        val schedule = schedule(ownerMemberId = 1L)
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 2L))
            .thenReturn(scheduleShare(targetMemberId = 2L))
        whenever(departureStatusRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 2L))
            .thenReturn(null)
        whenever(
            pushEventOutboxService.enqueueDurable(
                eq(2L),
                any(),
                any(),
                any(),
                any(),
            )
        ).thenReturn(prepared(manifestRecipientCount = 1))

        val result = service().sendDepartureNudge(
            ownerMemberId = 1L,
            scheduleId = 10L,
            targetMemberId = 2L,
            presentedSessionGeneration = 0L,
        )

        assertEquals(1, result.requestedCount)
        assertEquals(0, result.attemptedCount)
        assertEquals(0, result.sentCount)
        verify(pushEventOutboxService).enqueueDurable(
            memberId = eq(2L),
            title = eq("출발 확인 요청"),
            body = eq("'팀 회의' 일정의 출발 여부를 알려주세요."),
            data = check {
                assertEquals("SCHEDULE_DEPARTURE_NUDGE", it["type"])
                assertEquals("10", it["scheduleId"])
                assertEquals("1", it["requestedByMemberId"])
            },
            deduplicationKey = check {
                assertEquals(
                    true,
                    it.startsWith("schedule-departure-nudge:10:1:2:"),
                )
            },
        )
    }

    @Test
    fun `category share participant is also a valid nudge target`() {
        val schedule = schedule(ownerMemberId = 1L).apply {
            updateCategorySnapshot("5", "공유", "#2979FF")
            // 배포 전 category_id backfill이 끝나지 않은 기존 일정도 snapshot으로 권한을 찾는다.
            categoryId = null
        }
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 3L)).thenReturn(null)
        whenever(categoryShareRepository.findByCategoryIdAndTargetMemberId(5L, 3L))
            .thenReturn(categoryShare(targetMemberId = 3L))
        whenever(departureStatusRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 3L))
            .thenReturn(null)
        whenever(
            pushEventOutboxService.enqueueDurable(
                eq(3L),
                any(),
                any(),
                any(),
                any(),
            )
        ).thenReturn(prepared(manifestRecipientCount = 0))

        val result = service().sendDepartureNudge(1L, 10L, 3L, 0L)

        assertEquals(0, result.requestedCount)
        verify(pushEventOutboxService).enqueueDurable(
            eq(3L),
            any(),
            any(),
            any(),
            any(),
        )
    }

    @Test
    fun `calendar travel member is a valid nudge target without a direct share row`() {
        val schedule = schedule(ownerMemberId = 1L)
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleAccessPolicy.travelMemberIds(schedule)).thenReturn(listOf(1L, 4L))
        whenever(departureStatusRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 4L))
            .thenReturn(null)
        whenever(
            pushEventOutboxService.enqueueDurable(
                eq(4L),
                any(),
                any(),
                any(),
                any(),
            )
        ).thenReturn(prepared(manifestRecipientCount = 1))
        val service = ScheduleDepartureNotificationService(
            memberRepository = memberRepository,
            scheduleRepository = scheduleRepository,
            scheduleShareRepository = scheduleShareRepository,
            categoryShareRepository = categoryShareRepository,
            departureStatusRepository = departureStatusRepository,
            pushEventOutboxService = pushEventOutboxService,
            sharingAvailability = sharingAvailability,
            scheduleAccessPolicy = scheduleAccessPolicy,
        )

        val result = service.sendDepartureNudge(1L, 10L, 4L, 0L)

        assertEquals(1, result.requestedCount)
        assertEquals(0, result.sentCount)
        verifyNoInteractions(scheduleShareRepository, categoryShareRepository)
    }

    @Test
    fun `calendar schedule only member is not a valid nudge target`() {
        val schedule = schedule(ownerMemberId = 1L)
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleAccessPolicy.travelMemberIds(schedule)).thenReturn(listOf(1L))
        val service = ScheduleDepartureNotificationService(
            memberRepository = memberRepository,
            scheduleRepository = scheduleRepository,
            scheduleShareRepository = scheduleShareRepository,
            categoryShareRepository = categoryShareRepository,
            departureStatusRepository = departureStatusRepository,
            pushEventOutboxService = pushEventOutboxService,
            sharingAvailability = sharingAvailability,
            scheduleAccessPolicy = scheduleAccessPolicy,
        )

        val error = assertThrows(BusinessException::class.java) {
            service.sendDepartureNudge(1L, 10L, 4L, 0L)
        }

        assertEquals(ErrorCode.SCHEDULE_SHARE_NOT_FOUND, error.errorCode)
        verifyNoInteractions(pushEventOutboxService)
    }

    @Test
    fun `non owner cannot send a nudge even when they are an editor`() {
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 2L)).thenReturn(null)

        val error = assertThrows(BusinessException::class.java) {
            service().sendDepartureNudge(2L, 10L, 3L, 0L)
        }

        assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, error.errorCode)
        verifyNoInteractions(pushEventOutboxService)
    }

    @Test
    fun `owner cannot nudge a member who is not an active participant`() {
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 1L)).thenReturn(schedule(ownerMemberId = 1L))
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 9L)).thenReturn(null)
        whenever(categoryShareRepository.findByCategoryIdAndTargetMemberId(5L, 9L)).thenReturn(null)

        val error = assertThrows(BusinessException::class.java) {
            service().sendDepartureNudge(1L, 10L, 9L, 0L)
        }

        assertEquals(ErrorCode.SCHEDULE_SHARE_NOT_FOUND, error.errorCode)
        verifyNoInteractions(pushEventOutboxService)
    }

    @Test
    fun `owner cannot nudge a participant who already departed`() {
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 1L)).thenReturn(schedule(ownerMemberId = 1L))
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 2L))
            .thenReturn(scheduleShare(targetMemberId = 2L))
        whenever(departureStatusRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 2L))
            .thenReturn(
                ScheduleDepartureStatus(
                    scheduleId = 10L,
                    memberId = 2L,
                    departedAt = Instant.parse("2026-07-22T01:00:00Z"),
                )
            )

        val error = assertThrows(BusinessException::class.java) {
            service().sendDepartureNudge(1L, 10L, 2L, 0L)
        }

        assertEquals(ErrorCode.INVALID_STATE, error.errorCode)
        verifyNoInteractions(pushEventOutboxService)
    }

    @Test
    fun `stale owner generation cannot persist or dispatch a departure nudge`() {
        whenever(memberRepository.findByIdForUpdate(1L)).thenReturn(
            Member(
                id = 1L,
                name = "Owner",
                password = "Password1!",
                email = "owner@example.com",
                sessionGeneration = 2L,
            )
        )

        val error = assertThrows(BusinessException::class.java) {
            service().sendDepartureNudge(
                ownerMemberId = 1L,
                scheduleId = 10L,
                targetMemberId = 2L,
                presentedSessionGeneration = 1L,
            )
        }

        assertEquals(ErrorCode.INVALID_TOKEN, error.errorCode)
        verify(scheduleRepository, never()).findOwnedScheduleDetail(any(), any())
        verifyNoInteractions(pushEventOutboxService)
    }

    private fun schedule(ownerMemberId: Long) = Schedule(
        id = 10L,
        memberId = ownerMemberId,
        categoryId = 5L,
        title = "팀 회의",
        startAt = Instant.parse("2026-07-22T02:00:00Z"),
        endAt = Instant.parse("2026-07-22T03:00:00Z"),
    )

    private fun scheduleShare(targetMemberId: Long) = ScheduleShare(
        id = 20L,
        scheduleId = 10L,
        ownerMemberId = 1L,
        targetMemberId = targetMemberId,
        permission = ScheduleSharePermission.VIEWER,
        status = ScheduleShareStatus.ACTIVE,
    )

    private fun categoryShare(targetMemberId: Long) = ScheduleCategoryShare(
        id = 30L,
        categoryId = 5L,
        ownerMemberId = 1L,
        targetMemberId = targetMemberId,
        permission = ScheduleSharePermission.VIEWER,
        status = ScheduleShareStatus.ACTIVE,
    )

    private fun prepared(manifestRecipientCount: Int): PreparedPushEvent =
        PreparedPushEvent(
            snapshot = null,
            logicalEventKey = "event:test",
            deliveryIds = emptyList(),
            manifestRecipientCount = manifestRecipientCount,
            inboxCreated = true,
            fenceAccepted = true,
        )
}
