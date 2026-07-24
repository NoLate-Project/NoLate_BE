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

interface ScheduleRouteSetupReminderCandidate {
    val id: Long
    val memberId: Long
}

interface ScheduleRouteSetupReminderRepository : JpaRepository<ScheduleRouteSetupReminder, Long> {

    fun deleteAllByMemberId(memberId: Long)
    fun deleteAllByScheduleIdIn(scheduleIds: Collection<Long>)

    fun findAllByScheduleIdInAndMemberIdIn(
        scheduleIds: Collection<Long>,
        memberIds: Collection<Long>,
    ): List<ScheduleRouteSetupReminder>

    @Query(
        """
        select distinct reminder.memberId
        from ScheduleRouteSetupReminder reminder
        where reminder.scheduleId in :scheduleIds
        order by reminder.memberId
        """
    )
    fun findDistinctMemberIdsByScheduleIdIn(
        @Param("scheduleIds") scheduleIds: Collection<Long>,
    ): List<Long>

    fun findByScheduleIdAndMemberIdAndScheduleFingerprint(
        scheduleId: Long,
        memberId: Long,
        scheduleFingerprint: String,
    ): ScheduleRouteSetupReminder?

    /**
     * Non-locking candidate peek. The dispatch writer locks recipient member first, then this
     * marker by ID and revalidates status/time before creating the durable outbox.
     */
    @Query(
        """
        select reminder.id as id, reminder.memberId as memberId
        from ScheduleRouteSetupReminder reminder
        where reminder.status = :status
          and reminder.nextAttemptAt <= :now
        order by reminder.id asc
        """
    )
    fun findDueCandidates(
        @Param("status") status: ScheduleRouteSetupReminderStatus,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<ScheduleRouteSetupReminderCandidate>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select reminder
        from ScheduleRouteSetupReminder reminder
        where reminder.id = :id
        """
    )
    fun findByIdForUpdate(@Param("id") id: Long): ScheduleRouteSetupReminder?
}
