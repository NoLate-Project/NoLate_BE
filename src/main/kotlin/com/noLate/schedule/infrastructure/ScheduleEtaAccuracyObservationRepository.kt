package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleEtaAccuracyObservation
import org.springframework.data.jpa.repository.JpaRepository

interface ScheduleEtaAccuracyObservationRepository :
    JpaRepository<ScheduleEtaAccuracyObservation, Long> {

    fun findByScheduleIdAndMemberId(
        scheduleId: Long,
        memberId: Long,
    ): ScheduleEtaAccuracyObservation?

    fun deleteAllByMemberId(memberId: Long)

    fun deleteAllByScheduleIdIn(scheduleIds: Collection<Long>)
}
