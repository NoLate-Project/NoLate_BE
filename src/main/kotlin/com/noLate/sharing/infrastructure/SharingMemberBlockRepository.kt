package com.noLate.sharing.infrastructure

import com.noLate.sharing.domain.SharingMemberBlock
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SharingMemberBlockRepository : JpaRepository<SharingMemberBlock, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select b from SharingMemberBlock b
        where b.blockerMemberId = :blockerMemberId
          and b.blockedMemberId = :blockedMemberId
        """
    )
    fun findPairForUpdate(
        @Param("blockerMemberId") blockerMemberId: Long,
        @Param("blockedMemberId") blockedMemberId: Long,
    ): SharingMemberBlock?

    fun findAllByBlockerMemberIdAndDeletedFalseOrderByIdDesc(
        blockerMemberId: Long,
    ): List<SharingMemberBlock>

    @Query(
        """
        select count(b) > 0 from SharingMemberBlock b
        where b.deleted = false
          and (
            (b.blockerMemberId = :firstMemberId and b.blockedMemberId = :secondMemberId)
            or
            (b.blockerMemberId = :secondMemberId and b.blockedMemberId = :firstMemberId)
          )
        """
    )
    fun existsActiveEitherDirection(
        @Param("firstMemberId") firstMemberId: Long,
        @Param("secondMemberId") secondMemberId: Long,
    ): Boolean

    @Query(
        """
        select case
          when b.blockerMemberId = :memberId then b.blockedMemberId
          else b.blockerMemberId
        end
        from SharingMemberBlock b
        where b.deleted = false
          and (
            (b.blockerMemberId = :memberId and b.blockedMemberId in :candidateMemberIds)
            or
            (b.blockedMemberId = :memberId and b.blockerMemberId in :candidateMemberIds)
          )
        """
    )
    fun findBlockedCounterpartIds(
        @Param("memberId") memberId: Long,
        @Param("candidateMemberIds") candidateMemberIds: Collection<Long>,
    ): List<Long>

    fun deleteAllByBlockerMemberIdOrBlockedMemberId(
        blockerMemberId: Long,
        blockedMemberId: Long,
    )
}
