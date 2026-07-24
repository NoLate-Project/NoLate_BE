package com.noLate.notification.infrastructure

import com.noLate.notification.domain.PushDelivery
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface PushDeliveryRepository : JpaRepository<PushDelivery, Long> {

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

    fun deleteAllByMemberId(memberId: Long)
}
