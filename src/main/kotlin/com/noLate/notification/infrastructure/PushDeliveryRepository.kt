package com.noLate.notification.infrastructure

import com.noLate.notification.domain.PushDelivery
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PushDeliveryRepository : JpaRepository<PushDelivery, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delivery from PushDelivery delivery where delivery.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): PushDelivery?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByIdAndMemberIdAndEventKey(
        id: Long,
        memberId: Long,
        eventKey: String,
    ): PushDelivery?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByMemberIdAndEventKeyAndDeviceKey(
        memberId: Long,
        eventKey: String,
        deviceKey: String,
    ): PushDelivery?

    fun findAllByMemberIdAndEventKeyOrderByIdAsc(
        memberId: Long,
        eventKey: String,
    ): List<PushDelivery>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByMemberIdAndEventKey(
        memberId: Long,
        eventKey: String,
    ): List<PushDelivery>

    fun deleteAllByMemberId(memberId: Long)
}
