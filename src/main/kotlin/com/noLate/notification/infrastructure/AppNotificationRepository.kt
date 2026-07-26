package com.noLate.notification.infrastructure

import com.noLate.notification.domain.AppNotification
import com.noLate.notification.domain.PushOutboxDispatchStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface AppNotificationDispatchCandidate {
    val id: Long
    val memberId: Long
}

interface AppNotificationRepository : JpaRepository<AppNotification, Long> {

    fun deleteAllByScheduleIdIn(scheduleIds: Collection<Long>)

    @Query(
        """
        select distinct notification.memberId
        from AppNotification notification
        where notification.scheduleId in :scheduleIds
        order by notification.memberId
        """
    )
    fun findDistinctMemberIdsByScheduleIdIn(
        @Param("scheduleIds") scheduleIds: Collection<Long>,
    ): List<Long>

    fun findAllByScheduleIdInAndMemberIdIn(
        scheduleIds: Collection<Long>,
        memberIds: Collection<Long>,
    ): List<AppNotification>

    fun findAllByCategoryIdAndMemberIdIn(
        categoryId: Long,
        memberIds: Collection<Long>,
    ): List<AppNotification>

    fun findAllByCalendarIdAndMemberIdIn(
        calendarId: Long,
        memberIds: Collection<Long>,
    ): List<AppNotification>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select notification from AppNotification notification where notification.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): AppNotification?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select notification
        from AppNotification notification
        where notification.id in :ids
        order by notification.id
        """
    )
    fun findAllByIdsForUpdate(@Param("ids") ids: Collection<Long>): List<AppNotification>

    fun findByMemberIdAndDeduplicationKey(
        memberId: Long,
        deduplicationKey: String,
    ): AppNotification?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select notification
        from AppNotification notification
        where notification.memberId = :memberId
          and notification.deduplicationKey = :deduplicationKey
        """
    )
    fun findByMemberIdAndDeduplicationKeyForUpdate(
        @Param("memberId") memberId: Long,
        @Param("deduplicationKey") deduplicationKey: String,
    ): AppNotification?

    fun findByMemberIdAndLogicalEventKey(
        memberId: Long,
        logicalEventKey: String,
    ): AppNotification?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select notification
        from AppNotification notification
        where notification.memberId = :memberId
          and notification.logicalEventKey = :logicalEventKey
        """
    )
    fun findByMemberIdAndLogicalEventKeyForUpdate(
        @Param("memberId") memberId: Long,
        @Param("logicalEventKey") logicalEventKey: String,
    ): AppNotification?

    /**
     * Non-locking candidate peek. The writer must lock the recipient member first and then lock
     * the selected source by ID before rechecking status/time and transitioning it.
     */
    @Query(
        """
        select notification.id as id, notification.memberId as memberId
        from AppNotification notification
        where notification.dispatchStatus = :dispatchStatus
          and notification.nextDispatchAt <= :nextDispatchAt
        order by notification.nextDispatchAt asc, notification.id asc
        """
    )
    fun findDueDispatchCandidates(
        @Param("dispatchStatus") dispatchStatus: PushOutboxDispatchStatus,
        @Param("nextDispatchAt") nextDispatchAt: Instant,
        pageable: Pageable,
    ): List<AppNotificationDispatchCandidate>

    @Query(
        """
        select notification.id as id, notification.memberId as memberId
        from AppNotification notification
        where notification.dispatchStatus = :dispatchStatus
          and notification.dispatchLockedAt <= :dispatchLockedAt
        order by notification.dispatchLockedAt asc, notification.id asc
        """
    )
    fun findStaleDispatchCandidates(
        @Param("dispatchStatus") dispatchStatus: PushOutboxDispatchStatus,
        @Param("dispatchLockedAt") dispatchLockedAt: Instant,
        pageable: Pageable,
    ): List<AppNotificationDispatchCandidate>

    @Query(
        """
        select count(notification) from AppNotification notification
        where notification.dispatchStatus = :dispatchStatus
          and notification.nextDispatchAt <= :dueAt
        """
    )
    fun countDueDispatches(
        @Param("dispatchStatus") dispatchStatus: PushOutboxDispatchStatus,
        @Param("dueAt") dueAt: Instant,
    ): Long

    @Query(
        """
        select min(notification.nextDispatchAt) from AppNotification notification
        where notification.dispatchStatus = :dispatchStatus
          and notification.nextDispatchAt <= :dueAt
        """
    )
    fun findOldestDueDispatchAt(
        @Param("dispatchStatus") dispatchStatus: PushOutboxDispatchStatus,
        @Param("dueAt") dueAt: Instant,
    ): Instant?

    @Query(
        """
        select count(notification) from AppNotification notification
        where notification.dispatchStatus = :dispatchStatus
          and notification.dispatchLockedAt <= :staleBefore
        """
    )
    fun countStaleDispatchLeases(
        @Param("dispatchStatus") dispatchStatus: PushOutboxDispatchStatus,
        @Param("staleBefore") staleBefore: Instant,
    ): Long

    fun findByIdAndMemberId(id: Long, memberId: Long): AppNotification?

    fun findAllByMemberIdOrderByIdDesc(memberId: Long): List<AppNotification>

    fun findAllByMemberIdOrderByIdDesc(
        memberId: Long,
        pageable: Pageable,
    ): List<AppNotification>

    fun findAllByMemberIdAndIdLessThanOrderByIdDesc(
        memberId: Long,
        id: Long,
        pageable: Pageable,
    ): List<AppNotification>

    fun findAllByMemberIdAndReadAtIsNullOrderByIdDesc(
        memberId: Long,
        pageable: Pageable,
    ): List<AppNotification>

    fun findAllByMemberIdAndReadAtIsNullAndIdLessThanOrderByIdDesc(
        memberId: Long,
        id: Long,
        pageable: Pageable,
    ): List<AppNotification>

    fun countByMemberIdAndReadAtIsNull(memberId: Long): Long

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update AppNotification notification
           set notification.readAt = :readAt
         where notification.memberId = :memberId
           and notification.readAt is null
        """
    )
    fun markAllRead(
        @Param("memberId") memberId: Long,
        @Param("readAt") readAt: Instant,
    ): Int

    fun deleteAllByMemberId(memberId: Long)
}
