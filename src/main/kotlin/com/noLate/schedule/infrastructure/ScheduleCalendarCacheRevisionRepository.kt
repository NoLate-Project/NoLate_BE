package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleCalendarCacheRevision
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ScheduleCalendarCacheRevisionRepository :
    JpaRepository<ScheduleCalendarCacheRevision, Long> {

    @Query(
        "select cacheRevision.revision from ScheduleCalendarCacheRevision cacheRevision " +
            "where cacheRevision.memberId = :memberId"
    )
    fun findRevisionByMemberId(@Param("memberId") memberId: Long): Long?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select cacheRevision from ScheduleCalendarCacheRevision cacheRevision " +
            "where cacheRevision.memberId in :memberIds order by cacheRevision.memberId"
    )
    fun findAllByMemberIdsForUpdate(
        @Param("memberIds") memberIds: Collection<Long>,
    ): List<ScheduleCalendarCacheRevision>
}
