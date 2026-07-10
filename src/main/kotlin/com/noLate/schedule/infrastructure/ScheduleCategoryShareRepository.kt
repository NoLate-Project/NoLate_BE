package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleShareStatus
import org.springframework.data.jpa.repository.JpaRepository

interface ScheduleCategoryShareRepository : JpaRepository<ScheduleCategoryShare, Long> {
    fun findByCategoryIdAndTargetMemberId(categoryId: Long, targetMemberId: Long): ScheduleCategoryShare?

    fun findByIdAndCategoryIdAndDeletedFalse(id: Long, categoryId: Long): ScheduleCategoryShare?

    fun findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(
        categoryId: Long,
        status: ScheduleShareStatus,
    ): List<ScheduleCategoryShare>
}
