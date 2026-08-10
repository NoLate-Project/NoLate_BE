package com.noLate.notification.infrastructure

import com.noLate.notification.domain.NotificationDeviceToken
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface NotificationDeviceTokenRepository : JpaRepository<NotificationDeviceToken, Long>{

    @Query(
        """
        select count(token) from NotificationDeviceToken token
        where token.dispatchLeaseId is not null
          and token.dispatchLeaseUntil is not null
          and token.dispatchLeaseUntil <= :now
        """
    )
    fun countExpiredDispatchLeases(@Param("now") now: java.time.Instant): Long

    fun findAllByMemberId(memberId: Long): List<NotificationDeviceToken>

    fun findAllByMemberIdAndRetirementRequestedFalse(
        memberId: Long,
    ): List<NotificationDeviceToken>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select token
        from NotificationDeviceToken token
        where token.memberId = :memberId
        order by token.id
        """
    )
    fun findAllByMemberIdForUpdate(
        @Param("memberId") memberId: Long,
    ): List<NotificationDeviceToken>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select token
        from NotificationDeviceToken token
        where token.memberId = :memberId
          and token.deviceFingerprint = :deviceFingerprint
        order by token.id
        """
    )
    fun findAllByMemberIdAndDeviceFingerprintForUpdate(
        @Param("memberId") memberId: Long,
        @Param("deviceFingerprint") deviceFingerprint: String,
    ): List<NotificationDeviceToken>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select token
        from NotificationDeviceToken token
        where token.memberId = :memberId
          and token.tokenFingerprint = :tokenFingerprint
        order by token.id
        """
    )
    fun findAllByMemberIdAndTokenFingerprintForUpdate(
        @Param("memberId") memberId: Long,
        @Param("tokenFingerprint") tokenFingerprint: String,
    ): List<NotificationDeviceToken>

    /**
     * token/device fingerprint 후보를 먼저 ID 순으로 확정한 뒤 아래 query에서 같은 순서로
     * 잠근다. 서로 다른 token/device가 교차하는 ownership transfer도 lock order가 같다.
     * 빈-range 동시 insert는 전역 unique 충돌 후 fresh transaction retry로 수렴한다.
     */
    @Query(
        """
        select token.id
        from NotificationDeviceToken token
        where token.tokenFingerprint = :tokenFingerprint
           or (:deviceFingerprint is not null and token.deviceFingerprint = :deviceFingerprint)
        order by token.id
        """
    )
    fun findRegistrationCandidateIds(
        @Param("tokenFingerprint") tokenFingerprint: String,
        @Param("deviceFingerprint") deviceFingerprint: String?,
    ): List<Long>

    @Query(
        """
        select distinct token.memberId
        from NotificationDeviceToken token
        where token.tokenFingerprint = :tokenFingerprint
           or (:deviceFingerprint is not null and token.deviceFingerprint = :deviceFingerprint)
        order by token.memberId
        """
    )
    fun findRegistrationCandidateOwnerMemberIds(
        @Param("tokenFingerprint") tokenFingerprint: String,
        @Param("deviceFingerprint") deviceFingerprint: String?,
    ): List<Long>

    @Query(
        "select token.id from NotificationDeviceToken token " +
            "where token.deviceFingerprint = :deviceFingerprint order by token.id"
    )
    fun findIdsByDeviceFingerprint(
        @Param("deviceFingerprint") deviceFingerprint: String,
    ): List<Long>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select token
        from NotificationDeviceToken token
        where token.id in :ids
        order by token.id
        """
    )
    fun findAllByIdsForUpdate(@Param("ids") ids: Collection<Long>): List<NotificationDeviceToken>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from NotificationDeviceToken token where token.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): NotificationDeviceToken?

    @Query(
        value = """
        select count(*)
        from schedule_live_activities
        where push_device_token_id = :tokenId
          and state = 'END_PENDING'
        """,
        nativeQuery = true,
    )
    fun countEndPendingLiveActivities(@Param("tokenId") tokenId: Long): Long

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
        delete from push_device_token
        where retirement_requested = true
          and (
            dispatch_lease_id is null
            or dispatch_lease_until is null
            or dispatch_lease_until <= :now
          )
          and not exists (
            select 1
            from live_activity_deliveries
            where live_activity_deliveries.push_device_token_id = push_device_token.id
              and live_activity_deliveries.operation = 'END'
              and live_activity_deliveries.status in (
                  'PENDING', 'FAILED', 'DISPATCHING', 'UNCERTAIN', 'EXHAUSTED'
              )
          )
          and not exists (
            select 1
            from schedule_live_activities
            where schedule_live_activities.push_device_token_id = push_device_token.id
              and schedule_live_activities.state = 'END_PENDING'
          )
        """,
        nativeQuery = true,
    )
    fun deleteExpiredRetired(@Param("now") now: java.time.Instant): Int

}
