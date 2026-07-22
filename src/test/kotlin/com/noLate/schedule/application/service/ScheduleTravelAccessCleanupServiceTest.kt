package com.noLate.schedule.application.service

import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
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

    private fun service() = ScheduleTravelAccessCleanupService(
        scheduleRepository = scheduleRepository,
        pushJobRepository = pushJobRepository,
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
