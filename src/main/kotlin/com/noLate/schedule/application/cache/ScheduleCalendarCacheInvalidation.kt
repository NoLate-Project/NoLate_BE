package com.noLate.schedule.application.cache

import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.application.service.ScheduleShareGrantedEvent
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

data class ScheduleCalendarCacheInvalidationEvent(
    val memberIds: Set<Long>,
    val reason: String,
)

@Component
class ScheduleCalendarCacheInvalidationListener(
    private val cacheService: ScheduleCalendarCacheService,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onInvalidated(event: ScheduleCalendarCacheInvalidationEvent) {
        cacheService.invalidateMembers(event.memberIds, event.reason)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onShareGranted(event: ScheduleShareGrantedEvent) {
        cacheService.invalidateMembers(
            memberIds = listOf(event.targetMemberId),
            reason = "${event.resourceType.name.lowercase()}-share-granted",
        )
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
