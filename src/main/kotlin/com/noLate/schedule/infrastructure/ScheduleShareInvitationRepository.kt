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
