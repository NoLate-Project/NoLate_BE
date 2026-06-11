package com.noLate.schedule.application.service

import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class SchedulePushJobWorkerTest {

    @Mock
    lateinit var pushJobRepository: SchedulePushJobRepository

    @Mock
    lateinit var scheduleRepository: ScheduleRepository

    @Mock
    lateinit var trafficClient: TrafficClient

    @Mock
    lateinit var notificationUseCase: NotificationUseCase

    @Test
    fun `due job을 검출하면 교통 조회 후 사용자 푸시 데이터를 만들고 다음 체크를 예약한다`() {
        val now = Instant.parse("2026-06-12T01:00:00Z")
        val schedule = schedule()
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = Instant.parse("2026-06-12T02:00:00Z"),
            monitorStartAt = now.minusSeconds(60),
            intervalMinutes = 20,
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                now,
            )
        ).thenReturn(listOf(job))
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(47)
        whenever(
            notificationUseCase.sendToMember(
                memberId = any(),
                title = any(),
                body = any(),
                data = any(),
            )
        ).thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        val worker = SchedulePushJobWorker(
            pushJobRepository = pushJobRepository,
            scheduleRepository = scheduleRepository,
            trafficClient = trafficClient,
            notificationUseCase = notificationUseCase,
            retryDelayMinutes = 5,
        )

        assertEquals(1, worker.runDueJobs(now))

        verify(trafficClient, times(1)).getTravelMinutes(check<TrafficRequest> {
            assertEquals(37.1, it.originLat)
            assertEquals(127.2, it.destinationLng)
            assertEquals(ScheduleTravelMode.CAR, it.travelMode)
            assertEquals(30, it.fallbackTravelMinutes)
        })
        verify(notificationUseCase).sendToMember(
            memberId = eq(1L),
            title = eq("출발 시간 안내"),
            body = eq("회의까지 현재 교통 기준 47분이 걸립니다."),
            data = check {
                assertEquals("SCHEDULE_TRAFFIC", it["type"])
                assertEquals("10", it["scheduleId"])
                assertEquals("47", it["travelMinutes"])
                assertEquals("2026-06-12T02:13:00Z", it["recommendedDepartureAt"])
            },
        )
        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(47, job.lastTravelMinutes)
        assertEquals(now, job.lastCheckedAt)
        assertNotNull(job.lastPushedAt)
        assertEquals(1, job.checkCount)
        assertEquals(Instant.parse("2026-06-12T01:20:00Z"), job.nextCheckAt)
    }

    private fun schedule(): Schedule =
        Schedule(
            id = 10L,
            memberId = 1L,
            title = "회의",
            startAt = Instant.parse("2026-06-12T03:00:00Z"),
            endAt = Instant.parse("2026-06-12T04:00:00Z"),
        ).apply {
            updateRoute(
                travelMinutes = 30,
                departAt = Instant.parse("2026-06-12T02:30:00Z"),
                travelMode = ScheduleTravelMode.CAR,
                locationName = "회사",
                originName = "집",
                originAddress = null,
                originLat = 37.1,
                originLng = 127.1,
                destinationName = "회사",
                destinationAddress = null,
                destinationLat = 37.2,
                destinationLng = 127.2,
                routeJson = null,
                notificationEnabled = true,
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = 20,
            )
        }
}
