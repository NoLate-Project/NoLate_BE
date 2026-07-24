package com.noLate.schedule.application.service

import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 다중 인스턴스 worker의 짧은 DB transaction 경계를 담당한다.
 *
 * due row 잠금/PROCESSING 전이는 provider 호출 전에 커밋하고, 실제 ETA 조회와 발송은 잠금을
 * 보유하지 않은 별도 transaction에서 수행한다. 완료 전이는 다시 REQUIRES_NEW로 커밋해
 * 외부 호출을 감싼 transaction의 rollback과 분리한다.
 */
@Service
class SchedulePushJobCoordinator(
    private val repository: SchedulePushJobRepository,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimDueJobs(now: Instant, workerId: String): List<SchedulePushJob> {
        val dueJobs = repository
            .findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                now,
            )
        dueJobs.forEach { it.startProcessing(workerId, now) }
        repository.flush()
        return dueJobs
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recoverStaleProcessingJobs(
        now: Instant,
        processingTimeoutMinutes: Long,
        deliveryGraceMinutes: Long,
    ): Int {
        val timeoutBoundary = now.minus(processingTimeoutMinutes, ChronoUnit.MINUTES)
        val staleJobs = repository
            .findAllByStatusAndLockedAtLessThanEqualOrderByLockedAtAsc(
                SchedulePushJobStatus.PROCESSING,
                timeoutBoundary,
            )
        staleJobs.forEach { job ->
            if (job.isPastDeliveryWindow(now, deliveryGraceMinutes)) {
                job.complete()
            } else {
                job.recoverProcessingTimeout(
                    reason = "Processing timeout. lockedBy=${job.lockedBy}, lockedAt=${job.lockedAt}",
                    nextCheckAt = now,
                )
            }
        }
        repository.flush()
        return staleJobs.size
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun persist(job: SchedulePushJob, workerId: String) {
        job.id?.let { jobId ->
            val current = repository.findByIdForUpdate(jobId)
                ?: error("Schedule push job disappeared while processing. jobId=$jobId")
            check(
                current.status == SchedulePushJobStatus.PROCESSING &&
                    current.lockedBy == workerId
            ) {
                "Schedule push job lease lost. jobId=$jobId, expectedWorker=$workerId, " +
                    "actualStatus=${current.status}, actualWorker=${current.lockedBy}"
            }
        }
        repository.saveAndFlush(job)
    }

    /**
     * lazy schedule route를 읽는 동안만 persistence context를 제공한다. job 자체는 claim
     * transaction에서 분리된 detached entity이며 [persist]의 독립 transaction으로 저장된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun <T> execute(block: () -> T): T = block()
}
