package com.noLate.schedule.application.service

import com.noLate.notification.application.service.PushDispatchFence
import com.noLate.notification.application.service.PushDispatchFenceValidator
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

@Service
class SchedulePushDispatchFenceValidator(
    private val repository: SchedulePushJobRepository,
    private val clock: Clock,
) : PushDispatchFenceValidator {
    override fun validate(fence: PushDispatchFence): Boolean {
        val job = repository.findByIdForUpdate(fence.jobId) ?: return false
        val identityValid =
            job.notificationGeneration == fence.notificationGeneration &&
                job.notificationInputFingerprint == fence.notificationInputFingerprint &&
                (fence.expectedMemberId == null || job.memberId == fence.expectedMemberId) &&
                (fence.expectedScheduleId == null || job.scheduleId == fence.expectedScheduleId)
        val leaseValid =
            !fence.requireWorkerLease ||
                (
                    job.status == SchedulePushJobStatus.PROCESSING &&
                        job.lockedBy == fence.workerId &&
                        job.version == fence.jobVersion
                    )
        val sourceStateValid =
            fence.requireWorkerLease || job.status != SchedulePushJobStatus.CANCELED
        val valid = identityValid && leaseValid && sourceStateValid
        if (valid && fence.requireWorkerLease) {
            check(
                repository.heartbeatLeaseWithoutVersion(
                    fence.jobId,
                    Instant.now(clock),
                ) == 1
            ) {
                "Schedule push lease heartbeat target disappeared. jobId=${fence.jobId}"
            }
        }
        return valid
    }
}
