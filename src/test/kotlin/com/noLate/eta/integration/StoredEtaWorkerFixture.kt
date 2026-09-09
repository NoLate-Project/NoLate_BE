package com.noLate.eta.integration

import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.lang.reflect.Modifier

/** 실제 DB 대신 저장된 ID·버전과 claim 시점의 독립 스냅샷을 제공한다. */
internal fun prepareStoredEtaWorkerFixture(repository: SchedulePushJobRepository, job: SchedulePushJob) {
    ReflectionTestUtils.setField(job, "id", 100L)
    ReflectionTestUtils.setField(job, "version", 0L)
    var stored = snapshot(job)
    whenever(repository.flush()).thenAnswer {
        // Worker의 이후 상태 변경이 저장된 PROCESSING lease까지 바꾸지 않도록 분리한다.
        stored = snapshot(job)
        null
    }
    whenever(repository.findByIdForUpdate(100L)).thenAnswer { stored }
    whenever(repository.saveAndFlush(any<SchedulePushJob>())).thenAnswer {
        val saved = it.getArgument<SchedulePushJob>(0)
        stored = snapshot(saved)
        saved
    }
}

private fun snapshot(job: SchedulePushJob): SchedulePushJob = SchedulePushJob.create(
    memberId = job.memberId,
    scheduleId = job.scheduleId,
    scheduleAt = job.scheduleAt,
    departureAt = job.departureAt,
    monitorStartAt = job.monitorStartAt,
    intervalMinutes = job.intervalMinutes,
).also { copy ->
    SchedulePushJob::class.java.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) }
        .forEach { field ->
            field.isAccessible = true
            field.set(copy, field.get(job))
        }
}
