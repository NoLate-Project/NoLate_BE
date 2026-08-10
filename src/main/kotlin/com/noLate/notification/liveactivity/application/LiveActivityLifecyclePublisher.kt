package com.noLate.notification.liveactivity.application

import com.noLate.notification.liveactivity.domain.LiveActivityLifecycleIntent
import com.noLate.notification.liveactivity.domain.LiveActivityLifecycleScopeType
import com.noLate.notification.liveactivity.infrastructure.LiveActivityLifecycleIntentRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

sealed interface LiveActivityLifecycleScope {
    data class MemberSchedule(val memberId: Long, val scheduleId: Long) : LiveActivityLifecycleScope
    data class Schedule(val scheduleId: Long) : LiveActivityLifecycleScope
    data class Member(val memberId: Long) : LiveActivityLifecycleScope
}

data class LiveActivityLifecycleEndEvent(val intentId: Long)

/**
 * Persists lifecycle intent in the source transaction before publishing a post-commit nudge. If
 * the process stops after source commit, the pending row remains and the recovery worker applies
 * it later; there is no source-commit-to-outbox dual-write window.
 */
@Component
class LiveActivityLifecyclePublisher(
    private val publisher: ApplicationEventPublisher,
    private val intentRepository: LiveActivityLifecycleIntentRepository,
) {
    fun endMemberSchedule(memberId: Long, scheduleId: Long, reason: String) = publish(
        LiveActivityLifecycleScope.MemberSchedule(memberId, scheduleId),
        reason,
    )

    fun endSchedule(scheduleId: Long, reason: String) = publish(
        LiveActivityLifecycleScope.Schedule(scheduleId),
        reason,
    )

    fun endMember(memberId: Long, reason: String) = publish(
        LiveActivityLifecycleScope.Member(memberId),
        reason,
    )

    private fun publish(scope: LiveActivityLifecycleScope, reason: String) {
        val intent = when (scope) {
            is LiveActivityLifecycleScope.MemberSchedule -> LiveActivityLifecycleIntent(
                scopeType = LiveActivityLifecycleScopeType.MEMBER_SCHEDULE,
                memberId = scope.memberId,
                scheduleId = scope.scheduleId,
                reason = reason.take(80),
            )
            is LiveActivityLifecycleScope.Schedule -> LiveActivityLifecycleIntent(
                scopeType = LiveActivityLifecycleScopeType.SCHEDULE,
                scheduleId = scope.scheduleId,
                reason = reason.take(80),
            )
            is LiveActivityLifecycleScope.Member -> LiveActivityLifecycleIntent(
                scopeType = LiveActivityLifecycleScopeType.MEMBER,
                memberId = scope.memberId,
                reason = reason.take(80),
            )
        }
        val intentId = requireNotNull(intentRepository.saveAndFlush(intent).id)
        publisher.publishEvent(LiveActivityLifecycleEndEvent(intentId))
    }
}

@Service
class LiveActivityLifecycleIntentProcessor(
    private val intentRepository: LiveActivityLifecycleIntentRepository,
    private val service: ScheduleLiveActivityService,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${notification.live-activity.lifecycle-max-attempts:20}")
    private val maxAttempts: Int = 20,
    @Value("\${notification.live-activity.lifecycle-retry-base-seconds:30}")
    private val retryBaseSeconds: Long = 30,
) {
    init {
        require(maxAttempts in 1..100)
        require(retryBaseSeconds in 1..3_600)
    }

    /** Projection and completion share one transaction; repeated processing remains idempotent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun process(intentId: Long) {
        val intent = intentRepository.findByIdForUpdate(intentId) ?: return
        if (intent.completedAt != null) return
        when (intent.scopeType) {
            LiveActivityLifecycleScopeType.MEMBER_SCHEDULE -> service.endForMemberSchedule(
                requireNotNull(intent.memberId),
                requireNotNull(intent.scheduleId),
                intent.reason,
            )
            LiveActivityLifecycleScopeType.SCHEDULE -> service.endAllForSchedule(
                requireNotNull(intent.scheduleId),
                intent.reason,
            )
            LiveActivityLifecycleScopeType.MEMBER -> service.endAllForMember(
                requireNotNull(intent.memberId),
                intent.reason,
            )
        }
        intent.markCompleted(Instant.now(clock))
    }

    /** Failure accounting commits independently after the projection transaction rolled back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFailure(intentId: Long, failure: Throwable): Boolean {
        val intent = intentRepository.findByIdForUpdate(intentId) ?: return false
        return intent.markFailed(
            now = Instant.now(clock),
            errorCode = failure.javaClass.simpleName,
            maxAttempts = maxAttempts,
            baseSeconds = retryBaseSeconds,
        )
    }
}

@Component
class LiveActivityLifecycleEndListener(
    private val processor: LiveActivityLifecycleIntentProcessor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true,
    )
    fun onEnd(event: LiveActivityLifecycleEndEvent) {
        processSafely(event.intentId, "POST_COMMIT")
    }

    internal fun processSafely(intentId: Long, source: String) {
        runCatching { processor.process(intentId) }
            .onFailure { failure ->
                val deadLettered = runCatching { processor.recordFailure(intentId, failure) }
                    .getOrDefault(false)
                log.warn(
                    "Live Activity lifecycle intent remains pending. " +
                        "intentId={}, source={}, errorCode={}, deadLettered={}",
                    intentId,
                    source,
                    failure.javaClass.simpleName,
                    deadLettered,
                )
            }
    }
}

@Component
class LiveActivityLifecycleRecoveryWorker(
    private val intentRepository: LiveActivityLifecycleIntentRepository,
    private val listener: LiveActivityLifecycleEndListener,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${notification.live-activity.lifecycle-completed-retention-hours:168}")
    private val completedRetentionHours: Long = 168,
) {
    init {
        require(completedRetentionHours in 1..2_160)
    }

    @Scheduled(fixedDelayString = "\${notification.live-activity.lifecycle-recovery-millis:30000}")
    fun recoverPending() {
        intentRepository.findPendingIds(Instant.now(clock), PageRequest.of(0, 100))
            .forEach { listener.processSafely(it, "RECOVERY_SCAN") }
    }

    @Scheduled(
        fixedDelayString =
            "\${notification.live-activity.lifecycle-completed-cleanup-millis:3600000}",
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun cleanupCompleted() {
        val ids = intentRepository.findCompletedIds(
            Instant.now(clock).minus(completedRetentionHours, ChronoUnit.HOURS),
            PageRequest.of(0, 500),
        )
        if (ids.isNotEmpty()) intentRepository.deleteAllByIdInBatch(ids)
    }
}
