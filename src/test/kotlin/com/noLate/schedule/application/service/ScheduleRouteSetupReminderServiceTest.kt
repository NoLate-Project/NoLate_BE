package com.noLate.schedule.application.service

import com.noLate.notification.application.service.AppNotificationRecordResult
import com.noLate.notification.application.service.AppNotificationService
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.domain.AppNotification
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleRouteSetupReminder
import com.noLate.schedule.domain.ScheduleRouteSetupReminderStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleType
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class ScheduleRouteSetupReminderServiceTest {
    @Mock lateinit var scheduleRepository: ScheduleRepository
    @Mock lateinit var travelPlanRepository: ScheduleTravelPlanRepository
    @Mock lateinit var reminderRepository: ScheduleRouteSetupReminderRepository
    @Mock lateinit var registrar: ScheduleRouteSetupReminderRegistrar
    @Mock lateinit var accessPolicy: ScheduleAccessPolicy
    @Mock lateinit var appNotificationService: AppNotificationService
    @Mock lateinit var notificationUseCase: NotificationUseCase

    private val reminderPolicy = RouteSetupReminderPolicy()
    private val now = Instant.parse("2026-07-23T00:00:00Z")

    @Test
    fun `dispatch groups multiple due schedules into one member notification`() {
        val firstSchedule = routeSchedule(10L, "병원", now.plusSeconds(60 * 60))
        val secondSchedule = routeSchedule(11L, "회의", now.plusSeconds(2 * 60 * 60))
        val first = reminder(101L, firstSchedule)
        val second = reminder(102L, secondSchedule)
        whenever(reminderRepository.findDueForUpdate(eq(ScheduleRouteSetupReminderStatus.PENDING), eq(now), any()))
            .thenReturn(listOf(first, second))
        whenever(scheduleRepository.findById(10L)).thenReturn(Optional.of(firstSchedule))
        whenever(scheduleRepository.findById(11L)).thenReturn(Optional.of(secondSchedule))
        listOf(firstSchedule, secondSchedule).forEach { schedule ->
            whenever(accessPolicy.resolve(2L, schedule)).thenReturn(
                ScheduleAccessDecision(
                    canView = true,
                    canEdit = false,
                    travelEnabled = true,
                    canViewAllTravelPlans = false,
                )
            )
            whenever(accessPolicy.routeReminderEnabled(2L, schedule)).thenReturn(true)
            whenever(travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(requireNotNull(schedule.id), 2L))
                .thenReturn(null)
        }
        whenever(appNotificationService.recordWithResult(eq(2L), any(), any(), any(), any()))
            .thenReturn(
                AppNotificationRecordResult(
                    notification = AppNotification(
                        memberId = 2L,
                        type = "ROUTE_SETUP_REMINDER",
                        title = "경로를 설정해주세요",
                        body = "",
                        dataJson = "{}",
                        createdAt = now,
                    ),
                    created = true,
                )
            )
        whenever(notificationUseCase.sendToMember(eq(2L), any(), any(), any(), eq(null), eq(false)))
            .thenReturn(NotificationSendResult(requestedCount = 0))
        val service = service()

        val sentGroups = service.dispatch(now)

        assertEquals(1, sentGroups)
        assertEquals(ScheduleRouteSetupReminderStatus.SENT, first.status)
        assertEquals(ScheduleRouteSetupReminderStatus.SENT, second.status)
        verify(notificationUseCase).sendToMember(
            memberId = eq(2L),
            title = eq("경로를 설정해주세요"),
            body = eq("3일 안에 시작하는 일정 2개의 내 출발 경로를 확인해주세요."),
            data = check {
                assertEquals("ROUTE_SETUP_REMINDER", it["type"])
                assertEquals("10,11", it["scheduleIds"])
                assertEquals("2", it["count"])
            },
            inboxDeduplicationKey = eq(null),
            persistInInbox = eq(false),
        )
    }

    @Test
    fun `dispatch does not resend push when the logical notification already exists without a failed attempt`() {
        val schedule = routeSchedule(10L, "병원", now.plusSeconds(60 * 60))
        val marker = reminder(101L, schedule)
        whenever(reminderRepository.findDueForUpdate(eq(ScheduleRouteSetupReminderStatus.PENDING), eq(now), any()))
            .thenReturn(listOf(marker))
        whenever(scheduleRepository.findById(10L)).thenReturn(Optional.of(schedule))
        whenever(accessPolicy.resolve(2L, schedule)).thenReturn(
            ScheduleAccessDecision(
                canView = true,
                canEdit = false,
                travelEnabled = true,
                canViewAllTravelPlans = false,
            )
        )
        whenever(accessPolicy.routeReminderEnabled(2L, schedule)).thenReturn(true)
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 2L))
            .thenReturn(null)
        whenever(appNotificationService.recordWithResult(eq(2L), any(), any(), any(), any()))
            .thenReturn(
                AppNotificationRecordResult(
                    notification = AppNotification(
                        memberId = 2L,
                        type = "ROUTE_SETUP_REMINDER",
                        title = "경로를 설정해주세요",
                        body = "",
                        dataJson = "{}",
                        createdAt = now,
                    ),
                    created = false,
                )
            )

        val sentGroups = service().dispatch(now)

        assertEquals(1, sentGroups)
        assertEquals(ScheduleRouteSetupReminderStatus.SENT, marker.status)
        verifyNoInteractions(notificationUseCase)
    }

    @Test
    fun `dispatch retries push when an earlier provider failure incremented the marker attempt`() {
        val schedule = routeSchedule(10L, "병원", now.plusSeconds(60 * 60))
        val marker = reminder(101L, schedule).apply {
            retryOrFail(
                now = now.minusSeconds(600),
                reason = "provider unavailable",
                maxAttempts = 3,
                retryDelaySeconds = 300,
            )
        }
        whenever(reminderRepository.findDueForUpdate(eq(ScheduleRouteSetupReminderStatus.PENDING), eq(now), any()))
            .thenReturn(listOf(marker))
        whenever(scheduleRepository.findById(10L)).thenReturn(Optional.of(schedule))
        whenever(accessPolicy.resolve(2L, schedule)).thenReturn(
            ScheduleAccessDecision(
                canView = true,
                canEdit = false,
                travelEnabled = true,
                canViewAllTravelPlans = false,
            )
        )
        whenever(accessPolicy.routeReminderEnabled(2L, schedule)).thenReturn(true)
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 2L))
            .thenReturn(null)
        whenever(appNotificationService.recordWithResult(eq(2L), any(), any(), any(), any()))
            .thenReturn(
                AppNotificationRecordResult(
                    notification = AppNotification(
                        memberId = 2L,
                        type = "ROUTE_SETUP_REMINDER",
                        title = "경로를 설정해주세요",
                        body = "",
                        dataJson = "{}",
                        createdAt = now,
                    ),
                    created = false,
                )
            )
        whenever(notificationUseCase.sendToMember(eq(2L), any(), any(), any(), eq(null), eq(false)))
            .thenReturn(NotificationSendResult(requestedCount = 0))

        val sentGroups = service().dispatch(now)

        assertEquals(1, sentGroups)
        assertEquals(ScheduleRouteSetupReminderStatus.SENT, marker.status)
        verify(notificationUseCase).sendToMember(eq(2L), any(), any(), any(), eq(null), eq(false))
    }

    private fun service() = ScheduleRouteSetupReminderService(
        scheduleRepository = scheduleRepository,
        travelPlanRepository = travelPlanRepository,
        reminderRepository = reminderRepository,
        registrar = registrar,
        accessPolicy = accessPolicy,
        reminderPolicy = reminderPolicy,
        appNotificationService = appNotificationService,
        notificationUseCase = notificationUseCase,
        batchSize = 50,
        maxAttempts = 3,
        retryDelaySeconds = 300,
    )

    private fun reminder(id: Long, schedule: Schedule) = ScheduleRouteSetupReminder(
        id = id,
        scheduleId = requireNotNull(schedule.id),
        memberId = 2L,
        scheduleFingerprint = com.noLate.schedule.domain.ScheduleTravelPlanFingerprint.calculate(schedule),
        nextAttemptAt = now,
    )

    private fun routeSchedule(id: Long, title: String, startAt: Instant) = Schedule(
        id = id,
        memberId = 1L,
        title = title,
        startAt = startAt,
        endAt = startAt.plusSeconds(60 * 60),
        scheduleType = ScheduleType.ROUTE,
    ).apply {
        updateRoute(
            travelMinutes = 20,
            departAt = null,
            departedAt = null,
            travelMode = ScheduleTravelMode.TRANSIT,
            locationName = title,
            originName = "오너 출발지",
            originAddress = null,
            originLat = 37.5,
            originLng = 127.0,
            destinationName = title,
            destinationAddress = null,
            destinationLat = 37.55,
            destinationLng = 126.97,
            routeJson = "{}",
            notificationEnabled = false,
            notificationLeadMinutes = null,
            notificationIntervalMinutes = null,
        )
    }
}
