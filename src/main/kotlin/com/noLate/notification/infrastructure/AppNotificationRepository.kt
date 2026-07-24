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

interface AppNotificationRepository : JpaRepository<AppNotification, Long> {

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByDispatchStatusAndNextDispatchAtLessThanEqualOrderByNextDispatchAtAscIdAsc(
        dispatchStatus: PushOutboxDispatchStatus,
        nextDispatchAt: Instant,
        pageable: Pageable,
    ): List<AppNotification>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByDispatchStatusAndDispatchLockedAtLessThanEqualOrderByDispatchLockedAtAscIdAsc(
        dispatchStatus: PushOutboxDispatchStatus,
        dispatchLockedAt: Instant,
        pageable: Pageable,
    ): List<AppNotification>

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
