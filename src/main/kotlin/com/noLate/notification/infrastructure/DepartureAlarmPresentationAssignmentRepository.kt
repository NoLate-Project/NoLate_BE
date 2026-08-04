package com.noLate.notification.infrastructure

import com.noLate.notification.domain.DepartureAlarmPresentationAssignment
import org.springframework.data.jpa.repository.JpaRepository

interface DepartureAlarmPresentationAssignmentRepository :
    JpaRepository<DepartureAlarmPresentationAssignment, Long> {
    fun findAllByMemberIdAndLogicalEventKeyOrderByIdAsc(
        memberId: Long,
        logicalEventKey: String,
    ): List<DepartureAlarmPresentationAssignment>

    fun deleteAllByMemberId(memberId: Long)

    fun deleteAllByScheduleIdIn(scheduleIds: Collection<Long>)
}
