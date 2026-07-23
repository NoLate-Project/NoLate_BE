package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ScheduleCalendarRepository : JpaRepository<ScheduleCalendar, Long> {

    fun findByIdAndStatusAndDeletedFalse(
        id: Long,
        status: ScheduleCalendarStatus = ScheduleCalendarStatus.ACTIVE,
    ): ScheduleCalendar?

    fun findByLegacyCategoryIdAndDeletedFalse(legacyCategoryId: Long): ScheduleCalendar?

    fun findAllByOwnerMemberIdAndStatusAndDeletedFalseOrderByIdAsc(
        ownerMemberId: Long,
        status: ScheduleCalendarStatus = ScheduleCalendarStatus.ACTIVE,
    ): List<ScheduleCalendar>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select c
        from ScheduleCalendar c
        where c.id = :calendarId
          and c.status = :status
          and c.deleted = false
        """
    )
    fun findActiveForUpdate(
        @Param("calendarId") calendarId: Long,
        @Param("status") status: ScheduleCalendarStatus = ScheduleCalendarStatus.ACTIVE,
    ): ScheduleCalendar?

    /**
     * 일정이 캘린더 사이를 이동할 때 원본·대상 캘린더를 항상 id 오름차순으로 잠근다.
     * 상태 조건을 쿼리에 넣지 않아 이미 보관된 원본도 잠글 수 있으며, 호출 서비스가 대상
     * 캘린더의 ACTIVE 상태를 잠금 획득 뒤 다시 검사한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select c
        from ScheduleCalendar c
        where c.id in :calendarIds
        order by c.id asc
        """
    )
    fun findAllForUpdate(
        @Param("calendarIds") calendarIds: Collection<Long>,
    ): List<ScheduleCalendar>

    @Query(
        """
        select distinct c
        from ScheduleCalendar c
        join ScheduleCalendarMember m on m.calendarId = c.id
        where m.memberId = :memberId
          and m.status = com.noLate.schedule.domain.ScheduleCalendarMemberStatus.ACTIVE
          and m.deleted = false
          and c.status = com.noLate.schedule.domain.ScheduleCalendarStatus.ACTIVE
          and c.deleted = false
        order by c.title asc, c.id asc
        """
    )
    fun findAllVisibleByMemberId(@Param("memberId") memberId: Long): List<ScheduleCalendar>
}
