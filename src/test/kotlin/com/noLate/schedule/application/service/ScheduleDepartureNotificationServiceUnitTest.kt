package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class ScheduleDepartureNotificationServiceUnitTest {

    @Mock
    lateinit var scheduleRepository: ScheduleRepository

    @Mock
    lateinit var scheduleShareRepository: ScheduleShareRepository

    @Mock
    lateinit var categoryShareRepository: ScheduleCategoryShareRepository

    @Mock
    lateinit var departureStatusRepository: ScheduleDepartureStatusRepository

    @Mock
    lateinit var notificationUseCase: NotificationUseCase

    private fun service() = ScheduleDepartureNotificationService(
        scheduleRepository = scheduleRepository,
        scheduleShareRepository = scheduleShareRepository,
        categoryShareRepository = categoryShareRepository,
        departureStatusRepository = departureStatusRepository,
        notificationUseCase = notificationUseCase,
    )

    @Test
    fun `owner can send a departure nudge to an active direct share participant`() {
        val schedule = schedule(ownerMemberId = 1L)
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 2L))
            .thenReturn(scheduleShare(targetMemberId = 2L))
        whenever(departureStatusRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 2L))
            .thenReturn(null)
        whenever(notificationUseCase.sendToMember(eq(2L), any(), any(), any(), anyOrNull(), eq(true)))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        val result = service().sendDepartureNudge(
            ownerMemberId = 1L,
            scheduleId = 10L,
            targetMemberId = 2L,
        )

        assertEquals(1, result.sentCount)
        verify(notificationUseCase).sendToMember(
            memberId = eq(2L),
            title = eq("출발 확인 요청"),
            body = eq("'팀 회의' 일정의 출발 여부를 알려주세요."),
            data = check {
                assertEquals("SCHEDULE_DEPARTURE_NUDGE", it["type"])
                assertEquals("10", it["scheduleId"])
                assertEquals("1", it["requestedByMemberId"])
            },
            inboxDeduplicationKey = anyOrNull(),
            persistInInbox = eq(true),
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
        whenever(notificationUseCase.sendToMember(eq(3L), any(), any(), any(), anyOrNull(), eq(true)))
            .thenReturn(NotificationSendResult(requestedCount = 0))

        val result = service().sendDepartureNudge(1L, 10L, 3L)

        assertEquals(0, result.requestedCount)
        verify(notificationUseCase).sendToMember(eq(3L), any(), any(), any(), anyOrNull(), eq(true))
    }

    @Test
    fun `non owner cannot send a nudge even when they are an editor`() {
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 2L)).thenReturn(null)

        val error = assertThrows(BusinessException::class.java) {
            service().sendDepartureNudge(2L, 10L, 3L)
        }

        assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, error.errorCode)
        verifyNoInteractions(notificationUseCase)
    }

    @Test
    fun `owner cannot nudge a member who is not an active participant`() {
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 1L)).thenReturn(schedule(ownerMemberId = 1L))
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 9L)).thenReturn(null)
        whenever(categoryShareRepository.findByCategoryIdAndTargetMemberId(5L, 9L)).thenReturn(null)

        val error = assertThrows(BusinessException::class.java) {
            service().sendDepartureNudge(1L, 10L, 9L)
        }

        assertEquals(ErrorCode.SCHEDULE_SHARE_NOT_FOUND, error.errorCode)
        verifyNoInteractions(notificationUseCase)
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
            service().sendDepartureNudge(1L, 10L, 2L)
        }

        assertEquals(ErrorCode.INVALID_STATE, error.errorCode)
        verifyNoInteractions(notificationUseCase)
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
}
