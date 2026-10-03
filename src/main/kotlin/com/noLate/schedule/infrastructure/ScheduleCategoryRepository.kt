package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleCategory
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ScheduleCategoryRepository : JpaRepository<ScheduleCategory, Long> {
    fun findAllByMemberId(memberId: Long): List<ScheduleCategory>
    fun findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(memberId: Long): List<ScheduleCategory>
    fun findByMemberIdAndCalendarIdIsNullAndDeletedFalseOrderBySortOrderAscIdAsc(memberId: Long): List<ScheduleCategory>
    fun findAllByCalendarIdAndDeletedFalseOrderByIdAsc(calendarId: Long): List<ScheduleCategory>

    @Query(
        value = """
        select c.*
        from schedule_categories c
        where c.deleted = false
          and (
            (c.calendar_id is null and c.member_id = :memberId)
            or exists (
              select 1
              from schedule_calendars cal
              where cal.id = c.calendar_id
                and cal.owner_member_id = :memberId
                and cal.status = 'ACTIVE'
                and cal.deleted = false
            )
          )
        order by c.sort_order asc, c.id asc
        """,
        nativeQuery = true,
    )
    fun findResourceOwnedCategories(@Param("memberId") memberId: Long): List<ScheduleCategory>

    fun findByIdAndMemberIdAndDeletedFalse(id: Long, memberId: Long): ScheduleCategory?
    fun existsByIdAndMemberIdAndCalendarIdIsNullAndDeletedFalse(id: Long, memberId: Long): Boolean

    /**
     * 카테고리 이동/공유 mutation이 엔티티를 persistence context에 올리기 전에 잠금 대상을
     * 발견하기 위한 scalar projection이다. calendarId가 null인 개인 카테고리도 한 row로
     * 구분해야 하므로 projection 자체의 null 여부로 삭제/부재를 판단한다.
     */
    @Query(
        """
        select c.memberId as memberId, c.calendarId as calendarId
        from ScheduleCategory c
        where c.id = :categoryId
          and c.deleted = false
        """
    )
    fun findActiveScope(
        @Param("categoryId") categoryId: Long,
    ): ScheduleCategoryScope?

    fun findFirstByCalendarIdAndTitleIgnoreCaseAndDeletedFalseOrderByIdAsc(
        calendarId: Long,
        title: String,
    ): ScheduleCategory?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select c
        from ScheduleCategory c
        where c.id = :categoryId
          and c.deleted = false
        """
    )
    fun findActiveForScheduleAssignment(
        @Param("categoryId") categoryId: Long,
    ): ScheduleCategory?

    /** Existing schedules keep a deleted category row as immutable ownership/calendar lineage. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ScheduleCategory c where c.id = :categoryId")
    fun findByIdForUpdate(@Param("categoryId") categoryId: Long): ScheduleCategory?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select c
        from ScheduleCategory c
        where c.calendarId = :calendarId
          and c.deleted = false
        order by c.id asc
        """
    )
    fun findAllActiveByCalendarIdForUpdate(
        @Param("calendarId") calendarId: Long,
    ): List<ScheduleCategory>

    @Query(
        value = """
        select distinct c.*
        from schedule_categories c
        where c.deleted = false
          and (
            c.member_id = :memberId
            or exists (
              select 1
              from schedule_calendar_members scm
              join schedule_calendars cal on cal.id = scm.calendar_id
              where scm.calendar_id = c.calendar_id
                and scm.member_id = :memberId
                and scm.status = 'ACTIVE'
                and scm.deleted = false
                and cal.status = 'ACTIVE'
                and cal.deleted = false
            )
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
          and c.calendar_id is null
          and c.deleted = false
        """,
        nativeQuery = true,
    )
    fun findMaxSortOrder(@Param("memberId") memberId: Long): Int

    @Query(
        value = """
        select coalesce(max(c.sort_order), -1)
        from schedule_categories c
        where c.calendar_id = :calendarId
          and c.deleted = false
        """,
        nativeQuery = true,
    )
    fun findMaxSortOrderByCalendarId(@Param("calendarId") calendarId: Long): Int
}

interface ScheduleCategoryScope {
    fun getMemberId(): Long
    fun getCalendarId(): Long?
}
