package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ScheduleCalendarMemberRepository : JpaRepository<ScheduleCalendarMember, Long> {

    fun findByCalendarIdAndMemberId(calendarId: Long, memberId: Long): ScheduleCalendarMember?

    fun findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(
        calendarId: Long,
        memberId: Long,
        status: ScheduleCalendarMemberStatus = ScheduleCalendarMemberStatus.ACTIVE,
    ): ScheduleCalendarMember?

    fun findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(
        calendarId: Long,
        status: ScheduleCalendarMemberStatus = ScheduleCalendarMemberStatus.ACTIVE,
    ): List<ScheduleCalendarMember>

    fun findAllByCalendarIdInAndStatusAndDeletedFalseOrderByCalendarIdAscIdAsc(
        calendarIds: Collection<Long>,
        status: ScheduleCalendarMemberStatus = ScheduleCalendarMemberStatus.ACTIVE,
    ): List<ScheduleCalendarMember>

    fun findAllByMemberIdAndStatusAndDeletedFalseOrderByIdAsc(
        memberId: Long,
        status: ScheduleCalendarMemberStatus = ScheduleCalendarMemberStatus.ACTIVE,
    ): List<ScheduleCalendarMember>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select m
        from ScheduleCalendarMember m
        where m.calendarId = :calendarId
          and m.memberId = :memberId
        """
    )
    fun findForUpdate(
        @Param("calendarId") calendarId: Long,
        @Param("memberId") memberId: Long,
    ): ScheduleCalendarMember?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select m
        from ScheduleCalendarMember m
        where m.calendarId = :calendarId
          and m.memberId in :memberIds
        order by m.memberId asc
        """
    )
    fun findAllForUpdate(
        @Param("calendarId") calendarId: Long,
        @Param("memberIds") memberIds: Collection<Long>,
    ): List<ScheduleCalendarMember>
}
