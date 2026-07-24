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
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleRouteSetupReminder
import com.noLate.schedule.domain.ScheduleRouteSetupReminderStatus
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
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
    @Mock lateinit var calendarRepository: ScheduleCalendarRepository
    @Mock lateinit var calendarMemberRepository: ScheduleCalendarMemberRepository

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
    fun `schedule-only downgrade preserves share notification state and removes travel state`() {
        val schedule = schedule()
        val travelSource = appNotification(
            id = 51L,
            eventKey = "logical:travel",
            type = "SCHEDULE_DEPARTURE_REMINDER",
        )
        val shareSource = appNotification(
            id = 52L,
            eventKey = "logical:share",
            type = "SCHEDULE_SHARE_RECEIVED",
        )
        val travelHistory = pushHistory(
            id = 71L,
            eventKey = travelSource.logicalEventKey,
            type = travelSource.type,
        )
        val shareHistory = pushHistory(
            id = 72L,
            eventKey = shareSource.logicalEventKey,
            type = shareSource.type,
        )
        val travelSourceDelivery = pushDelivery(
            id = 61L,
            eventKey = travelSource.logicalEventKey,
            type = travelSource.type,
        )
        val travelOrphanDelivery = pushDelivery(
            id = 62L,
            eventKey = "logical:travel-orphan",
            type = "ROUTE_SETUP_REMINDER",
        )
        val shareDelivery = pushDelivery(
            id = 63L,
            eventKey = shareSource.logicalEventKey,
            type = shareSource.type,
        )
        whenever(scheduleRepository.findById(10L)).thenReturn(Optional.of(schedule))
        whenever(accessPolicy.resolveAll(2L, listOf(schedule))).thenReturn(
            mapOf(10L to decision(canView = true, travelEnabled = false))
        )
        whenever(appNotificationRepository.findAllByScheduleIdInAndMemberIdIn(setOf(10L), setOf(2L)))
            .thenReturn(listOf(travelSource, shareSource))
        whenever(pushSendHistoryRepository.findAllByScheduleIdInAndMemberIdIn(setOf(10L), setOf(2L)))
            .thenReturn(listOf(travelHistory, shareHistory))
        whenever(
            pushSendHistoryRepository.findAllByMemberIdInAndLogicalEventKeyIn(
                setOf(2L),
                listOf(travelSource.logicalEventKey),
            )
        ).thenReturn(listOf(travelHistory))
        whenever(
            pushDeliveryRepository.findAllByMemberIdInAndEventKeyIn(
                setOf(2L),
                listOf(travelSource.logicalEventKey),
            )
        ).thenReturn(listOf(travelSourceDelivery))
        whenever(pushDeliveryRepository.findAllByScheduleIdInAndMemberIdIn(setOf(10L), setOf(2L)))
            .thenReturn(listOf(travelOrphanDelivery, shareDelivery))

        service().cancelRevokedForSchedule(10L, listOf(2L))

        verify(appNotificationRepository).deleteAll(listOf(travelSource))
        verify(pushSendHistoryRepository).deleteAll(listOf(travelHistory))
        verify(pushDeliveryRepository).deleteAll(listOf(travelSourceDelivery))
        verify(pushDeliveryRepository).deleteAll(listOf(travelOrphanDelivery))
        verify(appNotificationRepository, never()).deleteAll(listOf(shareSource))
        verify(pushSendHistoryRepository, never()).deleteAll(listOf(shareHistory))
        verify(pushDeliveryRepository, never()).deleteAll(listOf(shareDelivery))
    }

    @Test
    fun `schedule view revoke removes share notification state too`() {
        val schedule = schedule()
        val shareSource = appNotification(
            id = 53L,
            eventKey = "logical:revoked-share",
            type = "SCHEDULE_SHARE_RECEIVED",
        )
        val shareHistory = pushHistory(
            id = 73L,
            eventKey = shareSource.logicalEventKey,
            type = shareSource.type,
        )
        val shareDelivery = pushDelivery(
            id = 64L,
            eventKey = shareSource.logicalEventKey,
            type = shareSource.type,
        )
        whenever(scheduleRepository.findById(10L)).thenReturn(Optional.of(schedule))
        whenever(accessPolicy.resolveAll(2L, listOf(schedule))).thenReturn(
            mapOf(10L to decision(canView = false, travelEnabled = false))
        )
        whenever(appNotificationRepository.findAllByScheduleIdInAndMemberIdIn(setOf(10L), setOf(2L)))
            .thenReturn(listOf(shareSource))
        whenever(pushSendHistoryRepository.findAllByScheduleIdInAndMemberIdIn(setOf(10L), setOf(2L)))
            .thenReturn(listOf(shareHistory))
        whenever(
            pushSendHistoryRepository.findAllByMemberIdInAndLogicalEventKeyIn(
                setOf(2L),
                listOf(shareSource.logicalEventKey),
            )
        ).thenReturn(listOf(shareHistory))
        whenever(
            pushDeliveryRepository.findAllByMemberIdInAndEventKeyIn(
                setOf(2L),
                listOf(shareSource.logicalEventKey),
            )
        ).thenReturn(listOf(shareDelivery))
        whenever(pushDeliveryRepository.findAllByScheduleIdInAndMemberIdIn(setOf(10L), setOf(2L)))
            .thenReturn(emptyList())

        service().cancelRevokedForSchedule(10L, listOf(2L))

        verify(appNotificationRepository).deleteAll(listOf(shareSource))
        verify(pushSendHistoryRepository).deleteAll(listOf(shareHistory))
        verify(pushDeliveryRepository).deleteAll(listOf(shareDelivery))
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
            logicalEventKey = "logical:category-share-history",
            categoryId = 9L,
            payloadType = "CATEGORY_SHARE_RECEIVED",
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
            .thenReturn(emptyList())
        whenever(appNotificationRepository.findAllByCategoryIdAndMemberIdIn(9L, listOf(2L)))
            .thenReturn(emptyList())
        whenever(
            pushSendHistoryRepository.findAllByCategoryIdAndMemberIdInAndPayloadType(
                9L,
                listOf(2L),
                "CATEGORY_SHARE_RECEIVED",
            )
        )
            .thenReturn(listOf(history))

        service().cancelRevokedForCategory(9L, listOf(2L))

        assertEquals(SchedulePushJobStatus.CANCELED, job.status)
        assertEquals(true, plan.deleted)
        assertEquals(ScheduleRouteSetupReminderStatus.CANCELLED, marker.status)
        verify(pushDeliveryRepository).deleteAll(listOf(delivery))
        verify(appNotificationRepository).deleteAll(listOf(source))
        verify(pushSendHistoryRepository).deleteAll(listOf(history))
    }

    @Test
    fun `calendar membership revoke removes calendar-only inbox source and frozen delivery`() {
        val source = AppNotification(
            id = 80L,
            memberId = 2L,
            logicalEventKey = "logical:calendar-revoke",
            type = "CALENDAR_SHARE_RECEIVED",
            calendarId = 77L,
            title = "private calendar title",
            body = "private calendar body",
            dataJson = """{"calendarId":"77"}""",
            createdAt = Instant.parse("2026-07-24T23:00:00Z"),
        )
        val delivery = PushDelivery(
            id = 81L,
            memberId = 2L,
            eventKey = source.logicalEventKey,
            deviceKey = "device-sha256:calendar",
            tokenFingerprint = "c".repeat(64),
            tokenOwnershipVersion = 1L,
            platform = PushPlatform.ANDROID,
            calendarId = 77L,
        )
        val history = PushSendHistory(
            id = 82L,
            memberId = 2L,
            logicalEventKey = source.logicalEventKey,
            calendarId = 77L,
            payloadType = "CALENDAR_SHARE_RECEIVED",
            title = "private calendar title",
            body = "private calendar body",
            dataJson = """{"calendarId":"77"}""",
            status = PushSendStatus.SUCCESS,
            sentAt = Instant.parse("2026-07-24T23:00:01Z"),
        )
        whenever(scheduleRepository.findAllByCalendarIdAndDeletedFalseOrderByIdAsc(77L))
            .thenReturn(emptyList())
        whenever(calendarRepository.findByIdAndStatusAndDeletedFalse(77L))
            .thenReturn(ScheduleCalendar(id = 77L, ownerMemberId = 1L, title = "calendar"))
        whenever(
            calendarMemberRepository.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(77L, 2L)
        ).thenReturn(null)
        whenever(appNotificationRepository.findAllByCalendarIdAndMemberIdIn(77L, listOf(2L)))
            .thenReturn(listOf(source))
        whenever(
            pushSendHistoryRepository.findAllByCalendarIdAndMemberIdInAndPayloadType(
                77L,
                listOf(2L),
                "CALENDAR_SHARE_RECEIVED",
            )
        )
            .thenReturn(listOf(history))
        whenever(
            pushSendHistoryRepository.findAllByMemberIdInAndLogicalEventKeyIn(
                listOf(2L),
                listOf(source.logicalEventKey),
            )
        ).thenReturn(listOf(history))
        whenever(
            pushDeliveryRepository.findAllByMemberIdInAndEventKeyIn(
                listOf(2L),
                listOf(source.logicalEventKey),
            )
        ).thenReturn(listOf(delivery))

        service().cancelRevokedForCalendar(77L, listOf(2L))

        inOrder(
            pushSendHistoryRepository,
            pushDeliveryRepository,
            appNotificationRepository,
        ) {
            verify(pushSendHistoryRepository).deleteAll(listOf(history))
            verify(pushDeliveryRepository).deleteAll(listOf(delivery))
            verify(appNotificationRepository).deleteAll(listOf(source))
        }
    }

    @Test
    fun `calendar content-mode reduction keeps share inbox for active membership`() {
        val calendar = ScheduleCalendar(id = 77L, ownerMemberId = 1L, title = "calendar")
        val membership = ScheduleCalendarMember(
            id = 82L,
            calendarId = 77L,
            memberId = 2L,
            role = ScheduleCalendarRole.VIEWER,
        )
        whenever(scheduleRepository.findAllByCalendarIdAndDeletedFalseOrderByIdAsc(77L))
            .thenReturn(emptyList())
        whenever(calendarRepository.findByIdAndStatusAndDeletedFalse(77L))
            .thenReturn(calendar)
        whenever(
            calendarMemberRepository.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(77L, 2L)
        ).thenReturn(membership)

        service().cancelRevokedForCalendar(77L, listOf(2L))

        verify(appNotificationRepository, never())
            .findAllByCalendarIdAndMemberIdIn(any(), any())
        verify(pushSendHistoryRepository, never())
            .findAllByCalendarIdAndMemberIdInAndPayloadType(any(), any(), any())
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
        calendarRepository = calendarRepository,
        calendarMemberRepository = calendarMemberRepository,
    )

    private fun decision(
        travelEnabled: Boolean,
        canView: Boolean = true,
    ) = ScheduleAccessDecision(
        canView = canView,
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

    private fun appNotification(
        id: Long,
        eventKey: String,
        type: String,
    ) = AppNotification(
        id = id,
        memberId = 2L,
        logicalEventKey = eventKey,
        type = type,
        scheduleId = 10L,
        title = "private title",
        body = "private body",
        dataJson = "{}",
        createdAt = Instant.parse("2026-07-24T23:00:00Z"),
    )

    private fun pushHistory(
        id: Long,
        eventKey: String,
        type: String,
    ) = PushSendHistory(
        id = id,
        memberId = 2L,
        logicalEventKey = eventKey,
        scheduleId = 10L,
        payloadType = type,
        title = "private title",
        body = "private body",
        dataJson = "{}",
        status = PushSendStatus.FAILED,
        sentAt = Instant.parse("2026-07-24T23:00:00Z"),
    )

    private fun pushDelivery(
        id: Long,
        eventKey: String,
        type: String,
    ) = PushDelivery(
        id = id,
        memberId = 2L,
        eventKey = eventKey,
        deviceKey = "device-sha256:$id",
        tokenFingerprint = id.toString().padStart(64, '0'),
        tokenOwnershipVersion = 1L,
        platform = PushPlatform.ANDROID,
        scheduleId = 10L,
        payloadType = type,
    )
}
