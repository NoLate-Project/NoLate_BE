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

    fun findByMemberIdAndDeviceFingerprintAndAlarmIdAndGenerationAndScheduledFor(
        memberId: Long,
        deviceFingerprint: String,
        alarmId: String,
        generation: Long,
        scheduledFor: java.time.Instant,
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
