package com.noLate.schedule.application.service

import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import com.noLate.notification.domain.AppNotification
import com.noLate.notification.domain.PushDelivery
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.domain.PushSendHistory
import com.noLate.notification.domain.PushSendStatus
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleRouteSetupReminder
import com.noLate.schedule.domain.ScheduleRouteSetupReminderStatus
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class ScheduleTravelAccessCleanupServiceTest {

    @Mock lateinit var scheduleRepository: ScheduleRepository
    @Mock lateinit var pushJobRepository: SchedulePushJobRepository
    @Mock lateinit var travelPlanRepository: ScheduleTravelPlanRepository
    @Mock lateinit var routeSetupReminderRepository: ScheduleRouteSetupReminderRepository
    @Mock lateinit var appNotificationRepository: AppNotificationRepository
    @Mock lateinit var pushDeliveryRepository: PushDeliveryRepository
    @Mock lateinit var pushSendHistoryRepository: PushSendHistoryRepository
    @Mock lateinit var accessPolicy: ScheduleAccessPolicy

    @Test
    fun `content mode reduction cancels an active member push job`() {
        val schedule = schedule()
        val job = pushJob()
        whenever(scheduleRepository.findById(10L)).thenReturn(Optional.of(schedule))
        whenever(accessPolicy.resolveAll(2L, listOf(schedule))).thenReturn(
            mapOf(10L to decision(travelEnabled = false))
        )
        whenever(pushJobRepository.findAllByScheduleIdInAndMemberIdIn(listOf(10L), listOf(2L)))
            .thenReturn(listOf(job))

        service().cancelRevokedForSchedule(10L, listOf(2L))

        assertEquals(SchedulePushJobStatus.CANCELED, job.status)
    }

    @Test
    fun `overlapping travel grant keeps the existing push job active`() {
        val schedule = schedule()
        whenever(scheduleRepository.findById(10L)).thenReturn(Optional.of(schedule))
        whenever(accessPolicy.resolveAll(2L, listOf(schedule))).thenReturn(
            mapOf(10L to decision(travelEnabled = true))
        )

        service().cancelRevokedForSchedule(10L, listOf(2L))

        verify(pushJobRepository, never()).findAllByScheduleIdInAndMemberIdIn(any(), any())
    }

    @Test
    fun `category revoke converges plan job marker source delivery and history for revoked pair`() {
        val schedule = schedule()
        val job = pushJob().apply {
            startProcessing("worker-before-revoke", Instant.parse("2026-07-24T23:00:00Z"))
        }
        val plan = ScheduleTravelPlan(
            id = 30L,
            scheduleId = 10L,
            memberId = 2L,
            notificationEnabled = true,
            scheduleFingerprint = "fingerprint",
        )
        val marker = ScheduleRouteSetupReminder(
            id = 40L,
            scheduleId = 10L,
            memberId = 2L,
            scheduleFingerprint = "fingerprint",
            status = ScheduleRouteSetupReminderStatus.PENDING,
        )
        val source = AppNotification(
            id = 50L,
            memberId = 2L,
            logicalEventKey = "logical:category-revoke",
            type = "SCHEDULE_DEPARTURE_REMINDER",
            scheduleId = 10L,
            title = "private title",
            body = "private body",
            dataJson = "{}",
            createdAt = Instant.parse("2026-07-24T23:00:00Z"),
        )
        val delivery = PushDelivery(
            id = 60L,
            memberId = 2L,
            eventKey = source.logicalEventKey,
            deviceKey = "device-sha256:test",
            tokenFingerprint = "f".repeat(64),
            tokenOwnershipVersion = 1L,
            platform = PushPlatform.ANDROID,
            scheduleId = 10L,
        )
        val history = PushSendHistory(
            id = 70L,
            memberId = 2L,
            scheduleId = 10L,
            title = "private title",
            body = "private body",
            dataJson = "{}",
            status = PushSendStatus.FAILED,
            sentAt = Instant.parse("2026-07-24T23:00:00Z"),
        )
        whenever(
            scheduleRepository.findAllByCategoryIdIncludingSnapshotAndDeletedFalseOrderByIdAsc(9L)
        ).thenReturn(listOf(schedule))
        whenever(accessPolicy.resolveAll(2L, listOf(schedule))).thenReturn(
            mapOf(10L to decision(travelEnabled = false))
        )
        whenever(pushJobRepository.findAllByScheduleIdInAndMemberIdIn(listOf(10L), listOf(2L)))
            .thenReturn(listOf(job))
        whenever(
            travelPlanRepository.findAllByScheduleIdInAndMemberIdInAndDeletedFalse(
                listOf(10L),
                listOf(2L),
            )
        ).thenReturn(listOf(plan))
        whenever(routeSetupReminderRepository.findAllByScheduleIdInAndMemberIdIn(listOf(10L), listOf(2L)))
            .thenReturn(listOf(marker))
        whenever(appNotificationRepository.findAllByScheduleIdInAndMemberIdIn(setOf(10L), setOf(2L)))
            .thenReturn(listOf(source))
        whenever(
            pushDeliveryRepository.findAllByMemberIdInAndEventKeyIn(
                setOf(2L),
                listOf(source.logicalEventKey),
            )
        ).thenReturn(listOf(delivery))
        whenever(pushDeliveryRepository.findAllByScheduleIdInAndMemberIdIn(setOf(10L), setOf(2L)))
            .thenReturn(emptyList())
        whenever(pushSendHistoryRepository.findAllByScheduleIdInAndMemberIdIn(setOf(10L), setOf(2L)))
            .thenReturn(listOf(history))
        whenever(appNotificationRepository.findAllByCategoryIdAndMemberIdIn(9L, listOf(2L)))
            .thenReturn(emptyList())

        service().cancelRevokedForCategory(9L, listOf(2L))

        assertEquals(SchedulePushJobStatus.CANCELED, job.status)
        assertEquals(true, plan.deleted)
        assertEquals(ScheduleRouteSetupReminderStatus.CANCELLED, marker.status)
        verify(pushDeliveryRepository).deleteAll(listOf(delivery))
        verify(appNotificationRepository).deleteAll(listOf(source))
        verify(pushSendHistoryRepository).deleteAll(listOf(history))
    }

    private fun service() = ScheduleTravelAccessCleanupService(
        scheduleRepository = scheduleRepository,
        pushJobRepository = pushJobRepository,
        travelPlanRepository = travelPlanRepository,
        routeSetupReminderRepository = routeSetupReminderRepository,
        appNotificationRepository = appNotificationRepository,
        pushDeliveryRepository = pushDeliveryRepository,
        pushSendHistoryRepository = pushSendHistoryRepository,
        accessPolicy = accessPolicy,
    )

    private fun decision(travelEnabled: Boolean) = ScheduleAccessDecision(
        canView = true,
        canEdit = false,
        travelEnabled = travelEnabled,
        canViewAllTravelPlans = false,
    )

    private fun schedule() = Schedule(
        id = 10L,
        memberId = 1L,
        title = "공유 일정",
        startAt = Instant.parse("2026-07-25T01:00:00Z"),
        endAt = Instant.parse("2026-07-25T02:00:00Z"),
    )

    private fun pushJob() = SchedulePushJob.create(
        memberId = 2L,
        scheduleId = 10L,
        scheduleAt = Instant.parse("2026-07-25T01:00:00Z"),
        departureAt = Instant.parse("2026-07-25T00:30:00Z"),
        monitorStartAt = Instant.parse("2026-07-24T23:30:00Z"),
        intervalMinutes = 20,
    )
}
