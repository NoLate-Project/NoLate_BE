package com.noLate.schedule.application.cache

import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.application.service.ScheduleShareGrantedEvent
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.TreeSet

data class ScheduleCalendarCacheInvalidationEvent(
    val memberIds: Set<Long>,
    val reason: String,
)

@Component
class ScheduleCalendarCacheInvalidationListener(
    private val coordinator: ScheduleCalendarCacheInvalidationCoordinator,
) {
    @EventListener
    fun onInvalidated(event: ScheduleCalendarCacheInvalidationEvent) {
        coordinator.register(event.memberIds, event.reason)
    }

    @EventListener
    fun onShareGranted(event: ScheduleShareGrantedEvent) {
        coordinator.register(
            memberIds = listOf(event.targetMemberId),
            reason = "${event.resourceType.name.lowercase()}-share-granted",
        )
    }
}

/**
 * 한 transaction에서 발행된 모든 invalidation audience를 합친 뒤 terminal
 * BEFORE_COMMIT synchronization에서 revision row를 한 번만 잠근다.
 *
 * 이벤트별로 즉시 잠그면 {B} 이벤트 다음 {A} 이벤트가 B -> A 순서로 lock을 쌓을 수
 * 있다. 여기서는 TreeSet으로 합친 전체를 오름차순 한 번만 잠그며 LOWEST_PRECEDENCE로
 * 다른 BEFORE_COMMIT outbox 작업이 끝난 뒤 실행해 이후 DB lock이 생기지 않게 한다.
 */
@Component
class ScheduleCalendarCacheInvalidationCoordinator(
    private val revisionService: ScheduleCalendarCacheRevisionService,
) {
    private val resourceKey = Any()

    fun register(memberIds: Collection<Long>, reason: String) {
        if (memberIds.isEmpty()) return
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Schedule calendar cache invalidation requires an active transaction."
        }
        check(TransactionSynchronizationManager.isSynchronizationActive()) {
            "Schedule calendar cache invalidation requires transaction synchronization."
        }

        val accumulator = currentAccumulator()
            ?: InvalidationAccumulator().also { created ->
                TransactionSynchronizationManager.bindResource(resourceKey, created)
                TransactionSynchronizationManager.registerSynchronization(
                    InvalidationSynchronization(created)
                )
            }
        accumulator.memberIds.addAll(memberIds)
        accumulator.reasons += reason
    }

    private fun currentAccumulator(): InvalidationAccumulator? =
        TransactionSynchronizationManager.getResource(resourceKey) as? InvalidationAccumulator

    private inner class InvalidationSynchronization(
        private val accumulator: InvalidationAccumulator,
    ) : TransactionSynchronization, Ordered {
        override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

        override fun suspend() {
            if (TransactionSynchronizationManager.getResource(resourceKey) === accumulator) {
                TransactionSynchronizationManager.unbindResource(resourceKey)
            }
        }

        override fun resume() {
            check(!TransactionSynchronizationManager.hasResource(resourceKey)) {
                "Schedule calendar cache invalidation accumulator already bound on resume."
            }
            TransactionSynchronizationManager.bindResource(resourceKey, accumulator)
        }

        override fun beforeCommit(readOnly: Boolean) {
            revisionService.incrementMembers(
                memberIds = accumulator.memberIds,
                reason = accumulator.reasons.joinToString(","),
            )
        }

        override fun afterCompletion(status: Int) {
            if (TransactionSynchronizationManager.getResource(resourceKey) === accumulator) {
                TransactionSynchronizationManager.unbindResource(resourceKey)
            }
        }
    }

    private class InvalidationAccumulator {
        val memberIds = TreeSet<Long>()
        val reasons = linkedSetOf<String>()
    }
}

@Component
class ScheduleCalendarCacheAudienceResolver(
    private val scheduleShareRepository: ScheduleShareRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
) {
    fun resolve(schedule: Schedule): Set<Long> = buildSet {
        add(schedule.memberId)

        schedule.id?.let { scheduleId ->
            scheduleShareRepository
                .findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(
                    scheduleId,
                    ScheduleShareStatus.ACTIVE,
                )
                .forEach { add(it.targetMemberId) }
        }

        schedule.categoryId?.let { categoryId ->
            categoryShareRepository
                .findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(
                    categoryId,
                    ScheduleShareStatus.ACTIVE,
                )
                .forEach { add(it.targetMemberId) }
        }

        schedule.calendarId?.let { calendarId ->
            calendarMemberRepository
                .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
                .forEach { add(it.memberId) }
        }
    }
}
