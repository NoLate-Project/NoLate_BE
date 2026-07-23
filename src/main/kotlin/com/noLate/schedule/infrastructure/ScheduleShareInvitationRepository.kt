package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleShareInvitation
import com.noLate.schedule.domain.ScheduleShareInvitationStatus
import com.noLate.schedule.domain.ScheduleShareResourceType
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ScheduleShareInvitationRepository : JpaRepository<ScheduleShareInvitation, Long> {
    fun deleteAllByOwnerMemberId(ownerMemberId: Long)

    fun findByIdAndOwnerMemberIdAndResourceTypeAndResourceIdAndDeletedFalse(
        id: Long,
        ownerMemberId: Long,
        resourceType: ScheduleShareResourceType,
        resourceId: Long,
    ): ScheduleShareInvitation?

    fun findByTokenHashAndDeletedFalse(tokenHash: String): ScheduleShareInvitation?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select i
        from ScheduleShareInvitation i
        where i.id = :id
        """
    )
    fun findByIdForUpdate(@Param("id") id: Long): ScheduleShareInvitation?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select i
        from ScheduleShareInvitation i
        where i.tokenHash = :tokenHash
          and i.deleted = false
          and i.status = 'PENDING'
        """
    )
    fun findActiveByTokenHashForUpdate(@Param("tokenHash") tokenHash: String): ScheduleShareInvitation?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select i
        from ScheduleShareInvitation i
        where i.resourceType = :resourceType
          and i.resourceId = :resourceId
          and i.status = :status
          and i.deleted = false
        order by i.id asc
        """
    )
    fun findAllPendingByResourceForUpdate(
        @Param("resourceType") resourceType: ScheduleShareResourceType,
        @Param("resourceId") resourceId: Long,
        @Param("status") status: ScheduleShareInvitationStatus = ScheduleShareInvitationStatus.PENDING,
    ): List<ScheduleShareInvitation>

    fun findAllByOwnerMemberIdAndResourceTypeAndResourceIdAndDeletedFalseOrderByIdDesc(
        ownerMemberId: Long,
        resourceType: ScheduleShareResourceType,
        resourceId: Long,
    ): List<ScheduleShareInvitation>

    fun findAllByOwnerMemberIdAndDeletedFalseOrderByIdDesc(
        ownerMemberId: Long,
    ): List<ScheduleShareInvitation>

    fun findAllByStatusAndDeletedFalse(status: ScheduleShareInvitationStatus): List<ScheduleShareInvitation>
}
