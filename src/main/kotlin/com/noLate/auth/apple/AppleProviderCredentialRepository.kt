package com.noLate.auth.apple

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface AppleAuthorizationCodeReceiptRepository :
    JpaRepository<AppleAuthorizationCodeReceipt, Long> {
    fun existsByAuthorizationCodeHash(authorizationCodeHash: String): Boolean
}

interface AppleProviderCredentialRepository : JpaRepository<AppleProviderCredential, Long> {
    fun findByRefreshTokenHash(refreshTokenHash: String): AppleProviderCredential?

    fun countByStatus(status: AppleProviderCredentialStatus): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select credential
        from AppleProviderCredential credential
        where credential.id = :credentialId
        """
    )
    fun findByIdForUpdate(
        @Param("credentialId") credentialId: Long,
    ): AppleProviderCredential?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select credential
        from AppleProviderCredential credential
        where credential.memberId = :memberId
          and credential.status <> :revoked
        order by credential.id
        """
    )
    fun findAllRevocableByMemberIdForUpdate(
        @Param("memberId") memberId: Long,
        @Param("revoked") revoked: AppleProviderCredentialStatus =
            AppleProviderCredentialStatus.REVOKED,
    ): List<AppleProviderCredential>

    @Query(
        """
        select credential.id
        from AppleProviderCredential credential
        where credential.status = :status
          and credential.nextAttemptAt <= :now
        order by credential.nextAttemptAt, credential.id
        """
    )
    fun findDueIds(
        @Param("status") status: AppleProviderCredentialStatus,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<Long>

    @Query(
        """
        select credential.id
        from AppleProviderCredential credential
        where credential.status = :status
          and credential.captureExpiresAt <= :now
        order by credential.captureExpiresAt, credential.id
        """
    )
    fun findExpiredCaptureIds(
        @Param("status") status: AppleProviderCredentialStatus,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<Long>

    @Query(
        """
        select credential.id
        from AppleProviderCredential credential
        where credential.status = :status
          and credential.lockedAt <= :staleBefore
        order by credential.lockedAt, credential.id
        """
    )
    fun findStaleIds(
        @Param("status") status: AppleProviderCredentialStatus,
        @Param("staleBefore") staleBefore: Instant,
        pageable: Pageable,
    ): List<Long>
}
