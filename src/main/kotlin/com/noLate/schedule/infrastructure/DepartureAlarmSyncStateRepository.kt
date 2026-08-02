package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import com.noLate.schedule.domain.DepartureAlarmSyncState
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface DepartureAlarmSyncStateRepository :
    JpaRepository<DepartureAlarmSyncState, Long> {

    fun findAllByMemberIdOrderByScheduleIdAsc(memberId: Long): List<DepartureAlarmSyncState>

    fun findAllByScheduleIdOrderByMemberIdAsc(scheduleId: Long): List<DepartureAlarmSyncState>

    fun deleteAllByMemberId(memberId: Long)

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select state from DepartureAlarmSyncState state
        where state.memberId = :memberId
          and state.scheduleId = :scheduleId
        """
    )
    fun findByMemberIdAndScheduleIdForUpdate(
        @Param("memberId") memberId: Long,
        @Param("scheduleId") scheduleId: Long,
    ): DepartureAlarmSyncState?

    @Query(
        """
        select state.id from DepartureAlarmSyncState state
        where state.operation = :operation
          and state.triggerAt <= :triggerAt
        order by state.triggerAt asc, state.id asc
        """
    )
    fun findExpiredUpsertIds(
        @Param("operation") operation: DepartureAlarmSyncOperation,
        @Param("triggerAt") triggerAt: Instant,
        pageable: Pageable,
    ): List<Long>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from DepartureAlarmSyncState state where state.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): DepartureAlarmSyncState?
}
