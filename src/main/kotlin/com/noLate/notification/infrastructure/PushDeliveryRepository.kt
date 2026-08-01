package com.noLate.notification.infrastructure

import com.noLate.notification.domain.PushDelivery
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface PushDeliveryRepository : JpaRepository<PushDelivery, Long> {

    @Query(
        """
        select count(delivery) from PushDelivery delivery
        where delivery.status = :status
          and delivery.deliveredAt >= :deliveredFrom
          and delivery.deliveredAt < :deliveredBefore
        """
    )
    fun countProviderSuccessCohort(
        @Param("status") status: com.noLate.notification.domain.PushDeliveryStatus,
        @Param("deliveredFrom") deliveredFrom: Instant,
        @Param("deliveredBefore") deliveredBefore: Instant,
    ): Long

    @Query(
        """
        select count(delivery) from PushDelivery delivery
        where delivery.status = :status
          and delivery.deliveredAt >= :deliveredFrom
          and delivery.deliveredAt < :deliveredBefore
          and delivery.deliveryAckCapabilityVersion = :capabilityVersion
        """
    )
    fun countAckEligibleProviderSuccessCohort(
        @Param("status") status: com.noLate.notification.domain.PushDeliveryStatus,
        @Param("deliveredFrom") deliveredFrom: Instant,
        @Param("deliveredBefore") deliveredBefore: Instant,
        @Param("capabilityVersion") capabilityVersion: Int,
    ): Long

    @Query(
        """
        select count(delivery) from PushDelivery delivery
        where delivery.status = :status
          and delivery.deliveredAt >= :deliveredFrom
          and delivery.deliveredAt < :deliveredBefore
          and delivery.deliveryAckCapabilityVersion = :capabilityVersion
          and delivery.clientReceivedAt is not null
        """
    )
    fun countAckEligibleClientReceivedCohort(
        @Param("status") status: com.noLate.notification.domain.PushDeliveryStatus,
        @Param("deliveredFrom") deliveredFrom: Instant,
        @Param("deliveredBefore") deliveredBefore: Instant,
        @Param("capabilityVersion") capabilityVersion: Int,
    ): Long

    @Query(
        """
        select count(delivery) from PushDelivery delivery
        where delivery.status = :status
          and delivery.lastAttemptedAt <= :attemptedBefore
        """
    )
    fun countAmbiguousBefore(
        @Param("status") status: com.noLate.notification.domain.PushDeliveryStatus,
        @Param("attemptedBefore") attemptedBefore: Instant,
    ): Long

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        delete from PushDelivery delivery
        where delivery.scheduleId in :scheduleIds
          and (
            delivery.payloadType is null or
            delivery.payloadType <> 'DEPARTURE_ALARM_SYNC'
          )
        """
    )
    fun deleteAllByScheduleIdIn(@Param("scheduleIds") scheduleIds: Collection<Long>)

    @Query(
        """
        select distinct delivery.memberId
        from PushDelivery delivery
        where delivery.scheduleId in :scheduleIds
        order by delivery.memberId
        """
    )
    fun findDistinctMemberIdsByScheduleIdIn(
        @Param("scheduleIds") scheduleIds: Collection<Long>,
    ): List<Long>

    fun findAllByScheduleIdInAndMemberIdIn(
        scheduleIds: Collection<Long>,
        memberIds: Collection<Long>,
    ): List<PushDelivery>

    fun findAllByMemberIdInAndEventKeyIn(
        memberIds: Collection<Long>,
        eventKeys: Collection<String>,
    ): List<PushDelivery>

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select delivery from PushDelivery delivery
        where delivery.memberId = :memberId
          and delivery.eventKey = :eventKey
          and delivery.deviceKey = :deviceKey
        """
    )
    fun findClientAckTargetForUpdate(
        @Param("memberId") memberId: Long,
        @Param("eventKey") eventKey: String,
        @Param("deviceKey") deviceKey: String,
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
