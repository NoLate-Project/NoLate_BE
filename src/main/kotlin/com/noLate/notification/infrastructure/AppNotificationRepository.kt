package com.noLate.notification.infrastructure

import com.noLate.notification.domain.AppNotification
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface AppNotificationRepository : JpaRepository<AppNotification, Long> {

    fun findByMemberIdAndDeduplicationKey(
        memberId: Long,
        deduplicationKey: String,
    ): AppNotification?

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
