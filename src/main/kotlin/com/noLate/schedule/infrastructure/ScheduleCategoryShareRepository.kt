package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleShareStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ScheduleCategoryShareRepository : JpaRepository<ScheduleCategoryShare, Long> {
    fun deleteAllByOwnerMemberIdOrTargetMemberId(ownerMemberId: Long, targetMemberId: Long)

    fun findAllByCategoryIdAndDeletedFalse(categoryId: Long): List<ScheduleCategoryShare>
    fun findByCategoryIdAndTargetMemberId(categoryId: Long, targetMemberId: Long): ScheduleCategoryShare?

    fun findByIdAndCategoryIdAndDeletedFalse(id: Long, categoryId: Long): ScheduleCategoryShare?

    fun findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(
        categoryId: Long,
        status: ScheduleShareStatus,
    ): List<ScheduleCategoryShare>

    fun findAllByCategoryIdInAndStatusAndDeletedFalseOrderByCategoryIdAscIdAsc(
        categoryIds: Collection<Long>,
        status: ScheduleShareStatus,
    ): List<ScheduleCategoryShare>

    fun findAllByCategoryIdInAndDeletedFalseOrderByCategoryIdAscIdAsc(
        categoryIds: Collection<Long>,
    ): List<ScheduleCategoryShare>

    fun findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
        targetMemberId: Long,
        status: ScheduleShareStatus,
    ): List<ScheduleCategoryShare>

    fun findAllByOwnerMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
        ownerMemberId: Long,
        status: ScheduleShareStatus,
    ): List<ScheduleCategoryShare>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select share
        from ScheduleCategoryShare share
        where share.categoryId = :categoryId
          and share.deleted = false
        order by share.id asc
        """
    )
    fun findAllByCategoryIdForUpdate(
        @Param("categoryId") categoryId: Long,
    ): List<ScheduleCategoryShare>
}
