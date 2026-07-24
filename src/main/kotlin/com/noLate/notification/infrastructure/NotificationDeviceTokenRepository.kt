package com.noLate.notification.infrastructure

import com.noLate.notification.domain.NotificationDeviceToken
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface NotificationDeviceTokenRepository : JpaRepository<NotificationDeviceToken, Long>{

    fun findAllByMemberId(memberId: Long): List<NotificationDeviceToken>

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

    fun deleteByMemberIdAndDeviceFingerprint(memberId: Long, deviceFingerprint: String)

    fun deleteAllByMemberId(memberId: Long)

    fun deleteByMemberIdAndTokenFingerprint(memberId: Long, tokenFingerprint: String)

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        delete from NotificationDeviceToken token
        where token.id = :id
          and token.memberId = :memberId
          and token.tokenFingerprint = :tokenFingerprint
          and token.ownershipVersion = :ownershipVersion
        """
    )
    fun deleteByOwnershipSnapshot(
        @Param("id") id: Long,
        @Param("memberId") memberId: Long,
        @Param("tokenFingerprint") tokenFingerprint: String,
        @Param("ownershipVersion") ownershipVersion: Long,
    ): Int

}
