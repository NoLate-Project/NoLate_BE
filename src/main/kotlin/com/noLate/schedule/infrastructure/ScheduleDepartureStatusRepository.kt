package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleDepartureStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ScheduleDepartureStatusRepository : JpaRepository<ScheduleDepartureStatus, Long> {
    fun deleteAllByMemberId(memberId: Long)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select s
        from ScheduleDepartureStatus s
        where s.scheduleId = :scheduleId
          and s.memberId = :memberId
          and s.deleted = false
        """
    )
    fun findActiveForUpdate(
        @Param("scheduleId") scheduleId: Long,
        @Param("memberId") memberId: Long,
    ): ScheduleDepartureStatus?

    fun findAllByScheduleIdAndDeletedFalse(scheduleId: Long): List<ScheduleDepartureStatus>
}
