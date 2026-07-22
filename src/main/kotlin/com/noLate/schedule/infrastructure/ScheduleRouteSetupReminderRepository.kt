package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleRouteSetupReminder
import com.noLate.schedule.domain.ScheduleRouteSetupReminderStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface ScheduleRouteSetupReminderRepository : JpaRepository<ScheduleRouteSetupReminder, Long> {

    fun findByScheduleIdAndMemberIdAndScheduleFingerprint(
        scheduleId: Long,
        memberId: Long,
        scheduleFingerprint: String,
    ): ScheduleRouteSetupReminder?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select reminder
        from ScheduleRouteSetupReminder reminder
        where reminder.status = :status
          and reminder.nextAttemptAt <= :now
        order by reminder.id asc
        """
    )
    fun findDueForUpdate(
        @Param("status") status: ScheduleRouteSetupReminderStatus,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<ScheduleRouteSetupReminder>
}
