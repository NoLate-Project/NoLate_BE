package com.noLate.schedule.application.service

import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class SchedulePushJobServiceTest {

    @Mock
    lateinit var repository: SchedulePushJobRepository

    @Test
    fun `알림이 활성화된 저장 일정은 monitor start 시각으로 push job을 등록한다`() {
        whenever(repository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(null)
        whenever(repository.save(any<SchedulePushJob>())).thenAnswer { it.getArgument(0) }
        val service = SchedulePushJobService(repository)

        val result = service.registerFromScheduleDto(
            memberId = 1L,
            scheduleDto = ScheduleDto(
                id = 10L,
                title = "회의",
                startAt = "2026-06-12T03:00:00Z",
                travelMinutes = 30,
                departAt = "2026-06-12T02:30:00Z",
                travelMode = ScheduleTravelMode.CAR,
                origin = SchedulePlaceDto(lat = 37.1, lng = 127.1),
                destination = SchedulePlaceDto(lat = 37.2, lng = 127.2),
                category = ScheduleCategoryDto(id = "1", title = "업무", color = "#000000"),
                notificationEnabled = true,
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = 20,
            ),
        )

        assertNotNull(result)
        assertEquals(Instant.parse("2026-06-12T01:30:00Z"), result?.monitorStartAt)
        assertEquals(result?.monitorStartAt, result?.nextCheckAt)
    }

    @Test
    fun `다시 알림 요청은 job을 5분 뒤로 재예약한다`() {
        val now = Instant.parse("2026-06-12T01:00:00Z")
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = Instant.parse("2026-06-12T02:00:00Z"),
            departureAt = now,
            monitorStartAt = now.minusSeconds(3600),
            intervalMinutes = 20,
        )
        whenever(repository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        SchedulePushJobService(
            schedulePushJobRepository = repository,
            departureSnoozeMinutes = 5,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        ).snoozeDepartureReminder(memberId = 1L, scheduleId = 10L)

        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(now.plusSeconds(300), job.nextCheckAt)
        assertEquals(now.plusSeconds(300), job.snoozedUntil)
    }

    @Test
    fun `출발 완료로 취소된 job은 다시 알림 요청으로 재활성화하지 않는다`() {
        val now = Instant.parse("2026-06-12T01:00:00Z")
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = Instant.parse("2026-06-12T02:00:00Z"),
            departureAt = now,
            monitorStartAt = now.minusSeconds(3600),
            intervalMinutes = 20,
        )
        job.cancel()
        whenever(repository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        SchedulePushJobService(
            schedulePushJobRepository = repository,
            departureSnoozeMinutes = 5,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        ).snoozeDepartureReminder(memberId = 1L, scheduleId = 10L)

        assertEquals(SchedulePushJobStatus.CANCELED, job.status)
        assertEquals(job.monitorStartAt, job.nextCheckAt)
        assertEquals(null, job.snoozedUntil)
    }
}
