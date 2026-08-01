package com.noLate.notification.infrastructure

import com.noLate.notification.domain.DepartureAlarmScheduleReceipt
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DepartureAlarmScheduleReceiptRepository :
    JpaRepository<DepartureAlarmScheduleReceipt, Long> {
    fun findByMemberIdAndClientReceiptId(
        memberId: Long,
        clientReceiptId: String,
    ): DepartureAlarmScheduleReceipt?

    fun findByMemberIdAndDeviceFingerprintAndCommandReceiptKey(
        memberId: Long,
        deviceFingerprint: String,
        commandReceiptKey: String,
    ): DepartureAlarmScheduleReceipt?

    fun deleteAllByMemberId(memberId: Long)

    fun deleteAllByScheduleIdIn(scheduleIds: Collection<Long>)

    @Query(
        """
        select distinct receipt.memberId
        from DepartureAlarmScheduleReceipt receipt
        where receipt.scheduleId in :scheduleIds
        order by receipt.memberId
        """
    )
    fun findDistinctMemberIdsByScheduleIdIn(
        @Param("scheduleIds") scheduleIds: Collection<Long>,
    ): List<Long>
}
