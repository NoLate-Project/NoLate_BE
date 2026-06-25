package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleCategory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ScheduleCategoryRepository : JpaRepository<ScheduleCategory, Long> {
    fun findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(memberId: Long): List<ScheduleCategory>

    fun findByIdAndMemberIdAndDeletedFalse(id: Long, memberId: Long): ScheduleCategory?

    @Query(
        value = """
        select coalesce(max(c.sort_order), -1)
        from schedule_categories c
        where c.member_id = :memberId
          and c.deleted = false
        """,
        nativeQuery = true,
    )
    fun findMaxSortOrder(@Param("memberId") memberId: Long): Int
}
