package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleCategory
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ScheduleCategoryRepository : JpaRepository<ScheduleCategory, Long> {
    fun findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(memberId: Long): List<ScheduleCategory>

    fun findByIdAndMemberIdAndDeletedFalse(id: Long, memberId: Long): ScheduleCategory?

    @Query(
        value = """
        select distinct c.*
        from schedule_categories c
        where c.deleted = false
          and (
            c.member_id = :memberId
            or exists (
              select 1
              from schedule_category_shares scs
              where scs.category_id = c.id
                and scs.target_member_id = :memberId
                and scs.status = 'ACTIVE'
                and scs.deleted = false
            )
          )
        order by c.sort_order asc, c.id asc
        """,
        nativeQuery = true,
    )
    fun findVisibleCategories(@Param("memberId") memberId: Long): List<ScheduleCategory>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select c
        from ScheduleCategory c
        where c.id = :categoryId
          and c.memberId = :memberId
          and c.deleted = false
        """
    )
    fun findOwnedActiveForShareUpdate(
        @Param("categoryId") categoryId: Long,
        @Param("memberId") memberId: Long,
    ): ScheduleCategory?

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
