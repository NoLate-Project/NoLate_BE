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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByTokenFingerprint(tokenFingerprint: String): List<NotificationDeviceToken>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByMemberIdAndDeviceFingerprint(
        memberId: Long,
        deviceFingerprint: String,
    ): List<NotificationDeviceToken>

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
