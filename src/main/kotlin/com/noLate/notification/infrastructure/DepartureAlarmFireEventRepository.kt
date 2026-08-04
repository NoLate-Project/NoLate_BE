package com.noLate.notification.infrastructure

import com.noLate.notification.domain.DepartureAlarmFireEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DepartureAlarmFireEventRepository : JpaRepository<DepartureAlarmFireEvent, Long> {
    fun findByMemberIdAndClientEventId(
        memberId: Long,
        clientEventId: String,
    ): DepartureAlarmFireEvent?

    @Query(
        """
        select event from DepartureAlarmFireEvent event
        where event.memberId = :memberId
          and event.deviceFingerprint = :deviceFingerprint
          and event.alarmId = :alarmId
          and event.generation = :generation
          and (
            (:occurrenceId is null and event.occurrenceId is null) or
            event.occurrenceId = :occurrenceId
          )
          and event.scheduledFor = :scheduledFor
        """
    )
    fun findDuplicatePhysicalOccurrence(
        @Param("memberId") memberId: Long,
        @Param("deviceFingerprint") deviceFingerprint: String,
        @Param("alarmId") alarmId: String,
        @Param("generation") generation: Long,
        @Param("occurrenceId") occurrenceId: String?,
        @Param("scheduledFor") scheduledFor: java.time.Instant,
    ): DepartureAlarmFireEvent?

    fun deleteAllByMemberId(memberId: Long)

    fun deleteAllByScheduleIdIn(scheduleIds: Collection<Long>)

    @Query(
        """
        select distinct event.memberId
        from DepartureAlarmFireEvent event
        where event.scheduleId in :scheduleIds
        order by event.memberId
        """
    )
    fun findDistinctMemberIdsByScheduleIdIn(
        @Param("scheduleIds") scheduleIds: Collection<Long>,
    ): List<Long>
}
