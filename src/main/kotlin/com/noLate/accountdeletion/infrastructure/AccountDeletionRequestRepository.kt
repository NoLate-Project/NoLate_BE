package com.noLate.accountdeletion.infrastructure

import com.noLate.accountdeletion.domain.AccountDeletionRequest
import com.noLate.accountdeletion.domain.AccountDeletionRequestStatus
import com.noLate.accountdeletion.application.AccountDeletionFailureCode
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface AccountDeletionRequestRepository : JpaRepository<AccountDeletionRequest, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from AccountDeletionRequest request where request.id = :requestId")
    fun findByIdForUpdate(
        @Param("requestId") requestId: String,
    ): AccountDeletionRequest?

    @Modifying
    @Query(
        """
        delete from AccountDeletionRequest request
        where request.retentionExpiresAt < :now
          and request.status <> :processing
        """
    )
    fun deleteExpiredBefore(
        @Param("now") now: Instant,
        @Param("processing") processing: AccountDeletionRequestStatus =
            AccountDeletionRequestStatus.PROCESSING,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update AccountDeletionRequest request
        set request.status = :rejected,
            request.failureCode = :failureCode,
            request.memberId = null,
            request.observedSessionGeneration = null,
            request.updatedAt = :now
        where request.status = :processing
          and (
            request.processingStartedAt < :cutoff
            or (request.processingStartedAt is null and request.updatedAt < :cutoff)
          )
        """
    )
    fun rejectStaleProcessing(
        @Param("cutoff") cutoff: Instant,
        @Param("now") now: Instant,
        @Param("processing") processing: AccountDeletionRequestStatus =
            AccountDeletionRequestStatus.PROCESSING,
        @Param("rejected") rejected: AccountDeletionRequestStatus =
            AccountDeletionRequestStatus.REJECTED,
        @Param("failureCode") failureCode: String =
            AccountDeletionFailureCode.OUTCOME_UNKNOWN.name,
    ): Int
}
