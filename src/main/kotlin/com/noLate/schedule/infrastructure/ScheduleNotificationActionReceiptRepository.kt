package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleNotificationActionReceipt
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ScheduleNotificationActionReceiptRepository :
    JpaRepository<ScheduleNotificationActionReceipt, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select receipt
        from ScheduleNotificationActionReceipt receipt
        where receipt.keyFingerprint = :keyFingerprint
        """
    )
    fun findByKeyFingerprintForUpdate(
        @Param("keyFingerprint") keyFingerprint: String,
    ): ScheduleNotificationActionReceipt?

    fun findByKeyFingerprint(keyFingerprint: String): ScheduleNotificationActionReceipt?

    fun deleteAllByMemberId(memberId: Long)
    fun deleteAllByScheduleIdIn(scheduleIds: Collection<Long>)

    @Query(
        """
        select distinct receipt.memberId
        from ScheduleNotificationActionReceipt receipt
        where receipt.scheduleId in :scheduleIds
        order by receipt.memberId
        """
    )
    fun findDistinctMemberIdsByScheduleIdIn(
        @Param("scheduleIds") scheduleIds: Collection<Long>,
    ): List<Long>
}
