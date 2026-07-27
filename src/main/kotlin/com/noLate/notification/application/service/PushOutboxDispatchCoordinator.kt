package com.noLate.notification.application.service

import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.AppNotification
import com.noLate.notification.domain.PushOutboxDispatchStatus
import com.noLate.notification.infrastructure.AppNotificationRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 한 outbox event의 짧은 DB lease다.
 *
 * provider 호출 중에는 JPA transaction이나 row lock을 유지하지 않는다. 완료/재시도 전이는
 * [workerId]를 다시 확인하므로 stale worker가 복구 후 새 owner의 상태를 덮어쓸 수 없다.
 */
data class PushOutboxDispatchLease(
    val notificationId: Long,
    val memberId: Long,
    val logicalEventKey: String,
    val manifestRecipientCount: Int,
    val attemptCount: Int,
    val failureCount: Int = 0,
    val workerId: String,
)

/**
 * 다중 인스턴스 outbox drainer의 transaction 경계다.
 *
 * backlog 전체를 미리 선점하지 않고 provider 호출 직전에 due row 한 건만 PROCESSING으로
 * 바꾼다. 따라서 앞선 provider 호출이 느려도 아직 처리하지 않은 tail event의 lease가
 * 만료되지 않으며 다른 인스턴스가 tail을 처리할 수 있다.
 */
@Service
class PushOutboxDispatchCoordinator(
    private val writer: PushOutboxDispatchWriter,
) {
    fun claimNextDue(now: Instant, workerId: String): PushOutboxDispatchLease? =
        writer.claimNextDue(now, workerId)

    fun recoverStale(
        now: Instant,
        processingTimeoutSeconds: Long,
        batchSize: Int,
    ): Int =
        writer.recoverStale(
            now = now,
            staleBefore = now.minusSeconds(processingTimeoutSeconds.coerceAtLeast(1)),
            batchSize = batchSize,
        )

    fun complete(lease: PushOutboxDispatchLease, now: Instant): Boolean =
        writer.complete(lease, now)

    fun retry(
        lease: PushOutboxDispatchLease,
        nextAt: Instant,
        reason: String,
    ): Boolean =
        writer.retry(lease, nextAt, reason)

    fun fail(
        lease: PushOutboxDispatchLease,
        now: Instant,
        reason: String,
    ): Boolean =
        writer.fail(lease, now, reason)

    fun defer(
        lease: PushOutboxDispatchLease,
        nextAt: Instant,
        reason: String,
    ): Boolean =
        writer.defer(lease, nextAt, reason)
}

@Service
class PushOutboxDispatchWriter(
    private val repository: AppNotificationRepository,
    private val memberRepository: MemberRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimNextDue(now: Instant, workerId: String): PushOutboxDispatchLease? {
        val candidate = repository
            .findDueDispatchCandidates(
                PushOutboxDispatchStatus.PENDING,
                now,
                PageRequest.of(0, 1),
            )
            .singleOrNull()
            ?: return null
        val notificationId = candidate.id
        if (memberRepository.findActiveNotificationRecipientForUpdate(candidate.memberId) == null) {
            return null
        }
        val notification = repository.findByIdForUpdate(notificationId) ?: return null
        if (notification.memberId != candidate.memberId ||
            notification.dispatchStatus != PushOutboxDispatchStatus.PENDING ||
            notification.nextDispatchAt?.isAfter(now) != false
        ) {
            return null
        }
        if (!notification.claimDispatch(workerId, now)) {
            return null
        }
        repository.flush()
        return notification.toLease(workerId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recoverStale(
        now: Instant,
        staleBefore: Instant,
        batchSize: Int,
    ): Int {
        // Candidate reads take no lock. All member rows are then locked in ascending order before
        // source IDs, matching withdrawal's member -> source direction.
        val candidates = repository
            .findStaleDispatchCandidates(
                PushOutboxDispatchStatus.PROCESSING,
                staleBefore,
                PageRequest.of(0, batchSize.coerceIn(1, 200)),
            )
        if (candidates.isEmpty()) return 0
        val activeMemberIds = memberRepository.findAllByIdsForUpdate(
            candidates.map { it.memberId }.distinct().sorted(),
        ).asSequence()
            .filterNot { it.deleted }
            .map { it.id }
            .toSet()
        val sourceIds = candidates.asSequence()
            .filter { it.memberId in activeMemberIds }
            .mapNotNull { it.id }
            .sorted()
            .toList()
        if (sourceIds.isEmpty()) return 0
        val stale = repository.findAllByIdsForUpdate(sourceIds)
            .filter {
                it.memberId in activeMemberIds &&
                    it.dispatchStatus == PushOutboxDispatchStatus.PROCESSING &&
                    it.dispatchLockedAt?.isAfter(staleBefore) == false
            }
        val recovered = stale.count { it.recoverStaleDispatch(staleBefore, now) }
        repository.flush()
        return recovered
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun complete(lease: PushOutboxDispatchLease, now: Instant): Boolean {
        if (memberRepository.findActiveNotificationRecipientForUpdate(lease.memberId) == null) {
            return false
        }
        val current = findOwnedLease(lease) ?: return false
        current.completeDispatch(lease.workerId, now)
        repository.flush()
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun retry(
        lease: PushOutboxDispatchLease,
        nextAt: Instant,
        reason: String,
    ): Boolean {
        if (memberRepository.findActiveNotificationRecipientForUpdate(lease.memberId) == null) {
            return false
        }
        val current = findOwnedLease(lease) ?: return false
        current.retryDispatch(lease.workerId, nextAt, reason.sanitizedDispatchReason())
        repository.flush()
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fail(
        lease: PushOutboxDispatchLease,
        now: Instant,
        reason: String,
    ): Boolean {
        if (memberRepository.findActiveNotificationRecipientForUpdate(lease.memberId) == null) {
            return false
        }
        val current = findOwnedLease(lease) ?: return false
        current.failDispatch(lease.workerId, now, reason.sanitizedDispatchReason())
        repository.flush()
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun defer(
        lease: PushOutboxDispatchLease,
        nextAt: Instant,
        reason: String,
    ): Boolean {
        if (memberRepository.findActiveNotificationRecipientForUpdate(lease.memberId) == null) {
            return false
        }
        val current = findOwnedLease(lease) ?: return false
        current.deferDispatch(lease.workerId, nextAt, reason.sanitizedDispatchReason())
        repository.flush()
        return true
    }

    private fun findOwnedLease(lease: PushOutboxDispatchLease): AppNotification? {
        val current = repository.findByMemberIdAndLogicalEventKeyForUpdate(
            lease.memberId,
            lease.logicalEventKey,
        ) ?: return null
        return current.takeIf {
            it.id == lease.notificationId &&
                it.dispatchStatus == PushOutboxDispatchStatus.PROCESSING &&
                it.dispatchLockedBy == lease.workerId &&
                it.dispatchAttemptCount == lease.attemptCount
        }
    }
}

private fun AppNotification.toLease(workerId: String): PushOutboxDispatchLease =
    PushOutboxDispatchLease(
        notificationId = requireNotNull(id),
        memberId = memberId,
        logicalEventKey = logicalEventKey,
        manifestRecipientCount = manifestRecipientCount,
        attemptCount = dispatchAttemptCount,
        failureCount = dispatchFailureCount,
        workerId = workerId,
    )

/**
 * reason은 정해진 상태 코드만 받지만 방어적으로 문자 집합을 제한한다. provider 예외 원문,
 * payload, token/device id가 outbox row나 로그에 흘러들어가지 않게 한다.
 */
private fun String.sanitizedDispatchReason(): String =
    uppercase()
        .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
        .joinToString(separator = "")
        .take(120)
