package com.noLate.schedule.application.service

import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.data.domain.PageRequest
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
class SchedulePushJobCoordinator private constructor(
    private val repository: SchedulePushJobRepository,
    private val memberRepository: MemberRepository?,
    @Suppress("UNUSED_PARAMETER") legacyTestBoundary: Boolean,
) {
    @Autowired
    constructor(
        repository: SchedulePushJobRepository,
        memberRepository: MemberRepository,
    ) : this(repository, memberRepository, false)

    /** Unit tests that exercise detached worker policy without a persistence member fixture. */
    internal constructor(repository: SchedulePushJobRepository) : this(repository, null, true)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimNextDueJob(now: Instant, workerId: String): SchedulePushJob? {
        if (memberRepository == null) {
            val legacyTestJob = repository
                .findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                    SchedulePushJobStatus.ACTIVE,
                    now,
                    PageRequest.of(0, 1),
                )
                .singleOrNull()
                ?: return null
            legacyTestJob.startProcessing(workerId, now)
            repository.flush()
            return legacyTestJob
        }
        val candidate = repository
            .findDueCandidates(
                SchedulePushJobStatus.ACTIVE,
                now,
                PageRequest.of(0, 1),
            )
            .singleOrNull()
            ?: return null
        if (memberRepository.findByIdForUpdate(candidate.memberId)?.deleted != false) {
            return null
        }
        val dueJob = repository.findByIdForUpdate(candidate.id)
            ?.takeIf {
                it.memberId == candidate.memberId &&
                    it.status == SchedulePushJobStatus.ACTIVE &&
                    !it.nextCheckAt.isAfter(now)
            }
            ?: return null
        dueJob.startProcessing(workerId, now)
        repository.flush()
        return dueJob
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recoverStaleProcessingJobs(
        now: Instant,
        processingTimeoutMinutes: Long,
        deliveryGraceMinutes: Long,
        batchSize: Int,
    ): Int {
        val timeoutBoundary = now.minus(processingTimeoutMinutes, ChronoUnit.MINUTES)
        if (memberRepository == null) {
            val legacyTestJobs = repository
                .findAllByStatusAndLockedAtLessThanEqualOrderByLockedAtAsc(
                    SchedulePushJobStatus.PROCESSING,
                    timeoutBoundary,
                    PageRequest.of(0, batchSize.coerceIn(1, 200)),
                )
            legacyTestJobs.forEach { job ->
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
            return legacyTestJobs.size
        }
        val candidates = repository
            .findStaleCandidates(
                SchedulePushJobStatus.PROCESSING,
                timeoutBoundary,
                PageRequest.of(0, batchSize.coerceIn(1, 200)),
            )
        if (candidates.isEmpty()) return 0
        val activeMemberIds = memberRepository.findAllByIdsForUpdate(
            candidates.map { it.memberId }.distinct().sorted(),
        ).asSequence()
            .filterNot { it.deleted }
            .map { it.id }
            .toSet()
        val staleJobs = candidates.asSequence()
            .filter { it.memberId in activeMemberIds }
            .mapNotNull { it.id }
            .sorted()
            .mapNotNull(repository::findByIdForUpdate)
            .filter {
                it.memberId in activeMemberIds &&
                    it.status == SchedulePushJobStatus.PROCESSING &&
                    it.lockedAt?.isAfter(timeoutBoundary) == false
            }
            .toList()
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
        if (memberRepository != null &&
            memberRepository.findByIdForUpdate(job.memberId)?.deleted != false
        ) {
            return
        }
        job.id?.let { jobId ->
            val current = repository.findByIdForUpdate(jobId) ?: return
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
