package com.noLate.schedule.application.service

import com.noLate.notification.liveactivity.application.LiveActivityLifecyclePublisher
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

class SchedulePushJobCoordinatorLiveActivityTest {
    @Test
    fun `terminal job transition records lifecycle intent inside coordinator persistence boundary`() {
        val repository = mock<SchedulePushJobRepository>()
        val lifecyclePublisher = mock<LiveActivityLifecyclePublisher>()
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val job = SchedulePushJob.create(
            memberId = 7,
            scheduleId = 41,
            scheduleAt = now.plusSeconds(3_600),
            departureAt = now.plusSeconds(1_800),
            monitorStartAt = now.minusSeconds(60),
            intervalMinutes = 20,
        )
        job.startProcessing("worker", now)
        job.complete()
        whenever(repository.saveAndFlush(job)).thenReturn(job)

        val persisted = SchedulePushJobCoordinator(
            repository,
            liveActivityLifecyclePublisher = lifecyclePublisher,
        ).persist(
            job,
            "worker",
            liveActivityEndReason = "JOB_COMPLETE",
        )

        assertThat(persisted).isTrue()
        verify(repository).saveAndFlush(job)
        verify(lifecyclePublisher).endMemberSchedule(7, 41, "JOB_COMPLETE")
    }
}
