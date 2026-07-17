package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareStatus
import org.springframework.data.jpa.repository.JpaRepository

interface ScheduleShareRepository : JpaRepository<ScheduleShare, Long> {
    fun deleteAllByOwnerMemberIdOrTargetMemberId(ownerMemberId: Long, targetMemberId: Long)
    fun findByScheduleIdAndTargetMemberId(scheduleId: Long, targetMemberId: Long): ScheduleShare?

    fun findByIdAndScheduleIdAndDeletedFalse(id: Long, scheduleId: Long): ScheduleShare?

    fun findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(
        scheduleId: Long,
        status: ScheduleShareStatus,
    ): List<ScheduleShare>

    fun findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
        targetMemberId: Long,
        status: ScheduleShareStatus,
    ): List<ScheduleShare>

    fun findAllByOwnerMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
        ownerMemberId: Long,
        status: ScheduleShareStatus,
    ): List<ScheduleShare>
}
