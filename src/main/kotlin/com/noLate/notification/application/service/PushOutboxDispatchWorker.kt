package com.noLate.notification.application.service

import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.global.observability.PushOutboxMetricOutcome
import com.noLate.global.observability.recordSafely
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Safety outbox가 확인 성공을 얻은 뒤 source worker의 confirmed 지표를 멱등 복구한다.
 * 구현 실패 시 outbox를 완료하지 않으므로 다음 redrive(ALREADY_SUCCESS)가 다시 시도한다.
 */
fun interface PushOutboxConfirmedDeliveryReconciler {
    fun reconcile(snapshot: AppNotificationSnapshot, confirmedAt: Instant)
}

/** Expected authoritative-source wait; retry without consuming the outbox failure budget. */
open class PushOutboxDeferralException(message: String) : RuntimeException(message)

/**
 * business transaction이 만든 immutable notification outbox를 비운다.
 *
 * 외부 provider 호출은 claim transaction이 커밋된 뒤 실행된다. confirmed provider 실패만
 * PENDING으로 돌아가며, SUCCESS/INVALID_TOKEN/SUPERSEDED/DISPATCHING(ambiguous)은 delivery
 * manifest의 현재 terminal 결과로 간주한다. 다만 기존 provider 호출이 lease recovery 뒤
 * 확정 실패로 돌아오면 delivery FAILED와 outbox PENDING을 같은 transaction에서 다시 열어
 * 이 terminal 관측보다 늦은 확정 정보를 잃지 않는다.
 */
@Component
class PushOutboxDispatchWorker(
    private val notificationUseCase: NotificationUseCase,
    private val coordinator: PushOutboxDispatchCoordinator,
    private val clock: Clock,
    @Value("\${notification.push-outbox.enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${notification.push-outbox.batch-size:50}")
    private val batchSize: Int = 50,
    @Value("\${notification.push-outbox.max-attempts:5}")
    private val maxAttempts: Int = 5,
    @Value("\${notification.push-outbox.retry-delay-seconds:60}")
    private val retryDelaySeconds: Long = 60,
    @Value("\${notification.push-outbox.processing-timeout-seconds:600}")
    private val processingTimeoutSeconds: Long = 600,
    private val confirmedDeliveryReconcilers: List<PushOutboxConfirmedDeliveryReconciler> =
        emptyList(),
    private val operationalMetrics: NoLateOperationalMetrics? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val workerId = "push-outbox-${UUID.randomUUID()}"

    @Scheduled(fixedDelayString = "\${notification.push-outbox.fixed-delay-ms:30000}")
    fun runDueEvents() {
        if (enabled) {
            runDueEvents(Instant.now(clock))
        }
    }

    fun runDueEvents(now: Instant): Int {
        if (!enabled) return 0

        val boundedBatchSize = batchSize.coerceIn(1, 200)
        val recoveryAt = currentAtOrAfter(now)
        val recovered = coordinator.recoverStale(
            now = recoveryAt,
            processingTimeoutSeconds = processingTimeoutSeconds,
            batchSize = boundedBatchSize,
        )
        operationalMetrics.recordSafely {
            recordPushOutbox(PushOutboxMetricOutcome.STALE_LEASE_RECOVERED, recovered)
        }
        if (recovered > 0) {
            log.warn(
                "Recovered stale push outbox leases. count={}, checkedAt={}",
                recovered,
                recoveryAt,
            )
        }

        var claimed = 0
        repeat(boundedBatchSize) {
            // A previous provider call may have been slow. Stamp each new lease at the actual
            // claim time so the untouched tail cannot be recovered as stale immediately.
            val claimAt = currentAtOrAfter(now)
            val lease = coordinator.claimNextDue(claimAt, workerId)
                ?: return claimed
            claimed += 1
            operationalMetrics.recordSafely {
                recordPushOutbox(PushOutboxMetricOutcome.CLAIMED)
            }
            dispatch(lease, now)
        }
        return claimed
    }

    private fun dispatch(lease: PushOutboxDispatchLease, notBefore: Instant) {
        val result = try {
            notificationUseCase.redrivePersistedEvent(
                memberId = lease.memberId,
                logicalEventKey = lease.logicalEventKey,
                sourceLease = lease,
            )
        } catch (failure: Exception) {
            transitionAfterFailure(
                lease = lease,
                now = currentAtOrAfter(notBefore),
                reason = "REDRIVE_${failure.javaClass.simpleName}",
            )
            log.warn(
                "Push outbox redrive failed. notificationId={}, memberId={}, attempt={}, errorCode={}",
                lease.notificationId,
                lease.memberId,
                lease.attemptCount,
                failure.javaClass.simpleName,
            )
            return
        }

        val confirmedAt = when {
            result.sentCount > 0 -> Instant.now(clock)
            result.alreadyDeliveredCount > 0 -> result.alreadyDeliveredAt
            else -> null
        }
        if (confirmedAt != null && result.eventSnapshot != null) {
            try {
                confirmedDeliveryReconcilers.forEach {
                    it.reconcile(result.eventSnapshot, confirmedAt)
                }
            } catch (deferred: PushOutboxDeferralException) {
                deferWithoutFailureBudget(
                    lease = lease,
                    now = currentAtOrAfter(notBefore),
                    reason = "AUTHORITATIVE_SOURCE_PROCESSING",
                )
                return
            } catch (failure: Exception) {
                transitionAfterFailure(
                    lease = lease,
                    now = currentAtOrAfter(notBefore),
                    reason = "CONFIRMED_RECONCILIATION_${failure.javaClass.simpleName}",
                )
                log.warn(
                    "Push outbox confirmed-state reconciliation failed. notificationId={}, " +
                        "memberId={}, attempt={}, errorCode={}",
                    lease.notificationId,
                    lease.memberId,
                    lease.attemptCount,
                    failure.javaClass.simpleName,
                )
                return
            }
        }

        when {
            result.deferredCount > 0 -> {
                deferWithoutFailureBudget(
                    lease = lease,
                    now = currentAtOrAfter(notBefore),
                    reason = "AUTHORITATIVE_SOURCE_PROCESSING",
                )
            }

            result.retryableFailedCount > 0 -> {
                transitionAfterFailure(
                    lease,
                    currentAtOrAfter(notBefore),
                    "CONFIRMED_PROVIDER_FAILURE",
                )
            }

            isTerminal(lease, result) -> {
                val persisted = coordinator.complete(lease, currentAtOrAfter(notBefore))
                operationalMetrics.recordSafely {
                    recordPushOutbox(
                        if (persisted) {
                            PushOutboxMetricOutcome.COMPLETED
                        } else {
                            PushOutboxMetricOutcome.LEASE_LOST
                        }
                    )
                }
                log.info(
                    "Push outbox terminal. notificationId={}, memberId={}, attempt={}, persisted={}, " +
                        "sent={}, existingSuccess={}, ambiguous={}, invalid={}, exhausted={}, superseded={}",
                    lease.notificationId,
                    lease.memberId,
                    lease.attemptCount,
                    persisted,
                    result.sentCount,
                    result.alreadyDeliveredCount,
                    result.ambiguousCount,
                    result.invalidTokenCount,
                    result.exhaustedCount,
                    result.supersededCount,
                )
            }

            else -> {
                transitionAfterFailure(
                    lease,
                    currentAtOrAfter(notBefore),
                    "INCOMPLETE_FROZEN_MANIFEST",
                )
            }
        }
    }

    private fun currentAtOrAfter(notBefore: Instant): Instant =
        maxOf(notBefore, Instant.now(clock))

    private fun isTerminal(
        lease: PushOutboxDispatchLease,
        result: NotificationSendResult,
    ): Boolean =
        lease.manifestRecipientCount == 0 ||
            result.terminalManifestCount >= lease.manifestRecipientCount

    private fun transitionAfterFailure(
        lease: PushOutboxDispatchLease,
        now: Instant,
        reason: String,
    ) {
        val terminal = lease.failureCount + 1 >= maxAttempts.coerceAtLeast(1)
        val persisted = if (terminal) {
            coordinator.fail(lease, now, reason)
        } else {
            coordinator.retry(
                lease = lease,
                nextAt = now.plusSeconds(retryDelaySeconds.coerceAtLeast(1)),
                reason = reason,
            )
        }
        operationalMetrics.recordSafely {
            recordPushOutbox(
                if (!persisted) {
                    PushOutboxMetricOutcome.LEASE_LOST
                } else if (terminal) {
                    PushOutboxMetricOutcome.TERMINAL_FAILURE
                } else {
                    PushOutboxMetricOutcome.RETRY_SCHEDULED
                }
            )
        }
        log.warn(
            "Push outbox retry state updated. notificationId={}, memberId={}, attempt={}, terminal={}, " +
                "persisted={}, reason={}",
            lease.notificationId,
            lease.memberId,
            lease.attemptCount,
            terminal,
            persisted,
            reason,
        )
    }

    private fun deferWithoutFailureBudget(
        lease: PushOutboxDispatchLease,
        now: Instant,
        reason: String,
    ) {
        val persisted = coordinator.defer(
            lease = lease,
            nextAt = now.plusSeconds(retryDelaySeconds.coerceAtLeast(1)),
            reason = reason,
        )
        operationalMetrics.recordSafely {
            recordPushOutbox(
                if (persisted) {
                    PushOutboxMetricOutcome.DEFERRED
                } else {
                    PushOutboxMetricOutcome.LEASE_LOST
                }
            )
        }
        log.info(
            "Push outbox deferred. notificationId={}, memberId={}, attempt={}, " +
                "failureCount={}, persisted={}, reason={}",
            lease.notificationId,
            lease.memberId,
            lease.attemptCount,
            lease.failureCount,
            persisted,
            reason,
        )
    }
}
