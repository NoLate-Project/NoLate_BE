package com.noLate.schedule.application.service

import com.noLate.notification.application.service.PushDispatchFence
import com.noLate.notification.application.service.PushDispatchFenceDecision
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
    override fun validate(fence: PushDispatchFence): Boolean =
        evaluate(fence) == PushDispatchFenceDecision.ACCEPT

    override fun evaluate(fence: PushDispatchFence): PushDispatchFenceDecision {
        val job = repository.findByIdForUpdate(fence.jobId)
            ?: return PushDispatchFenceDecision.REJECT_TERMINAL
        val identityValid =
            job.notificationGeneration == fence.notificationGeneration &&
                job.notificationInputFingerprint == fence.notificationInputFingerprint &&
                (fence.expectedMemberId == null || job.memberId == fence.expectedMemberId) &&
                (fence.expectedScheduleId == null || job.scheduleId == fence.expectedScheduleId)
        if (!identityValid) {
            return PushDispatchFenceDecision.REJECT_TERMINAL
        }

        if (!fence.requireWorkerLease) {
            return when (job.status) {
                SchedulePushJobStatus.ACTIVE -> PushDispatchFenceDecision.ACCEPT
                SchedulePushJobStatus.PROCESSING -> PushDispatchFenceDecision.RETRY_LATER
                SchedulePushJobStatus.COMPLETED,
                SchedulePushJobStatus.FAILED,
                SchedulePushJobStatus.CANCELED -> PushDispatchFenceDecision.REJECT_TERMINAL
            }
        }

        val leaseValid =
            job.status == SchedulePushJobStatus.PROCESSING &&
                job.lockedBy == fence.workerId &&
                job.version == fence.jobVersion
        if (leaseValid) {
            check(
                repository.heartbeatLeaseWithoutVersion(
                    fence.jobId,
                    Instant.now(clock),
                ) == 1
            ) {
                "Schedule push lease heartbeat target disappeared. jobId=${fence.jobId}"
            }
        }
        return if (leaseValid) {
            PushDispatchFenceDecision.ACCEPT
        } else {
            PushDispatchFenceDecision.REJECT_TERMINAL
        }
    }
}
