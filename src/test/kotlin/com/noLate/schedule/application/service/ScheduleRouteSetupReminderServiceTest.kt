package com.noLate.schedule.application.service

import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.service.PreparedPushEvent
import com.noLate.notification.application.service.PushEventOutboxService
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleRouteSetupReminder
import com.noLate.schedule.domain.ScheduleRouteSetupReminderStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleType
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderCandidate
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class ScheduleRouteSetupReminderServiceTest {
    @Mock lateinit var scheduleRepository: ScheduleRepository
    @Mock lateinit var travelPlanRepository: ScheduleTravelPlanRepository
    @Mock lateinit var reminderRepository: ScheduleRouteSetupReminderRepository
    @Mock lateinit var memberRepository: MemberRepository
    @Mock lateinit var registrar: ScheduleRouteSetupReminderRegistrar
    @Mock lateinit var accessPolicy: ScheduleAccessPolicy
    @Mock lateinit var dispatchWriter: ScheduleRouteSetupReminderDispatchWriter
    @Mock lateinit var pushEventOutboxService: PushEventOutboxService

    private val reminderPolicy = RouteSetupReminderPolicy()
    private val now = Instant.parse("2026-07-23T00:00:00Z")

    @Test
    fun `dispatch bounds work and delegates one short transaction per marker`() {
        whenever(dispatchWriter.enqueueNext(now)).thenReturn(
            RouteSetupOutboxEnqueueOutcome.ENQUEUED,
            RouteSetupOutboxEnqueueOutcome.SKIPPED,
            RouteSetupOutboxEnqueueOutcome.NONE,
        )

        val result = service(batchSize = 5).dispatch(now)

        assertEquals(1, result)
        verify(dispatchWriter, times(3)).enqueueNext(now)
    }

    @Test
    fun `marker transaction freezes durable route payload without calling provider`() {
        val schedule = routeSchedule(10L, "병원", now.plusSeconds(60 * 60))
        val marker = reminder(101L, schedule)
        whenever(
            reminderRepository.findDueCandidates(
                eq(ScheduleRouteSetupReminderStatus.PENDING),
                eq(now),
                any<Pageable>(),
            )
        ).thenReturn(listOf(candidate(marker)))
        whenever(memberRepository.findByIdForUpdate(2L)).thenReturn(activeMember(2L))
        whenever(reminderRepository.findByIdForUpdate(101L)).thenReturn(marker)
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
        whenever(
            pushEventOutboxService.enqueueDurable(
                memberId = eq(2L),
                title = any(),
                body = any(),
                data = any(),
                deduplicationKey = any(),
            )
        ).thenReturn(prepared())
        val writer = dispatchWriter()

        val outcome = writer.enqueueNext(now)

        assertEquals(RouteSetupOutboxEnqueueOutcome.ENQUEUED, outcome)
        assertEquals(ScheduleRouteSetupReminderStatus.SENT, marker.status)
        val data = argumentCaptor<Map<String, String>>()
        verify(pushEventOutboxService).enqueueDurable(
            memberId = eq(2L),
            title = eq("경로를 설정해주세요"),
            body = eq("'병원' 일정이 3일 안에 시작돼요. 내 출발 경로를 확인해주세요."),
            data = data.capture(),
            deduplicationKey = eq("route-setup:2:marker:101"),
        )
        assertEquals("ROUTE_SETUP_REMINDER", data.firstValue["type"])
        assertEquals("10", data.firstValue["scheduleId"])
        assertEquals("10", data.firstValue["scheduleIds"])
        assertEquals("1", data.firstValue["count"])
    }

    @Test
    fun `withdrawn route recipient is canceled without creating an outbox`() {
        val schedule = routeSchedule(10L, "병원", now.plusSeconds(60 * 60))
        val marker = reminder(101L, schedule)
        whenever(
            reminderRepository.findDueCandidates(
                eq(ScheduleRouteSetupReminderStatus.PENDING),
                eq(now),
                any<Pageable>(),
            )
        ).thenReturn(listOf(candidate(marker)))
        whenever(memberRepository.findByIdForUpdate(2L)).thenReturn(
            activeMember(2L).apply { softDelete() }
        )
        whenever(reminderRepository.findByIdForUpdate(101L)).thenReturn(marker)

        val outcome = dispatchWriter().enqueueNext(now)

        assertEquals(RouteSetupOutboxEnqueueOutcome.SKIPPED, outcome)
        assertEquals(ScheduleRouteSetupReminderStatus.CANCELLED, marker.status)
        verify(pushEventOutboxService, org.mockito.kotlin.never()).enqueueDurable(
            memberId = any(),
            title = any(),
            body = any(),
            data = any(),
            deduplicationKey = any(),
        )
    }

    private fun service(batchSize: Int = 50) = ScheduleRouteSetupReminderService(
        scheduleRepository = scheduleRepository,
        travelPlanRepository = travelPlanRepository,
        registrar = registrar,
        accessPolicy = accessPolicy,
        reminderPolicy = reminderPolicy,
        dispatchWriter = dispatchWriter,
        batchSize = batchSize,
    )

    private fun dispatchWriter() = ScheduleRouteSetupReminderDispatchWriter(
        reminderRepository = reminderRepository,
        memberRepository = memberRepository,
        scheduleRepository = scheduleRepository,
        travelPlanRepository = travelPlanRepository,
        accessPolicy = accessPolicy,
        reminderPolicy = reminderPolicy,
        pushEventOutboxService = pushEventOutboxService,
    )

    private fun prepared() = PreparedPushEvent(
        snapshot = null,
        logicalEventKey = "key:" + "a".repeat(64),
        deliveryIds = emptyList(),
        manifestRecipientCount = 0,
        inboxCreated = true,
        fenceAccepted = true,
    )

    private fun activeMember(id: Long) = Member(
        id = id,
        name = "member-$id",
        password = "Password1!",
        email = "member-$id@example.com",
    )

    private fun reminder(id: Long, schedule: Schedule) = ScheduleRouteSetupReminder(
        id = id,
        scheduleId = requireNotNull(schedule.id),
        memberId = 2L,
        scheduleFingerprint =
            com.noLate.schedule.domain.ScheduleTravelPlanFingerprint.calculate(schedule),
        nextAttemptAt = now,
    )

    private fun candidate(marker: ScheduleRouteSetupReminder): ScheduleRouteSetupReminderCandidate =
        object : ScheduleRouteSetupReminderCandidate {
            override val id: Long = requireNotNull(marker.id)
            override val memberId: Long = marker.memberId
        }

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
