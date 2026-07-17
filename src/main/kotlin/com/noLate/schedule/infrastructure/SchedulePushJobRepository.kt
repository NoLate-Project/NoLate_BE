package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import java.time.Instant


interface SchedulePushJobRepository : JpaRepository<SchedulePushJob, Long> {
    fun deleteAllByMemberId(memberId: Long)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
        status: SchedulePushJobStatus,
        nextCheckAt: Instant,
    ): List<SchedulePushJob>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByStatusAndLockedAtLessThanEqualOrderByLockedAtAsc(
        status: SchedulePushJobStatus,
        lockedAt: Instant,
    ): List<SchedulePushJob>

    fun findByScheduleId(scheduleId: Long): SchedulePushJob?

    fun findByScheduleIdAndMemberId(scheduleId: Long, memberId: Long): SchedulePushJob?
}
