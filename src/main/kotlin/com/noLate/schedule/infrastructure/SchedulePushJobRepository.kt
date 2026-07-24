package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.repository.query.Param
import org.springframework.data.domain.Pageable
import java.time.Instant


interface SchedulePushJobRepository : JpaRepository<SchedulePushJob, Long> {
    fun deleteAllByMemberId(memberId: Long)

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from SchedulePushJob job where job.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): SchedulePushJob?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
        status: SchedulePushJobStatus,
        nextCheckAt: Instant,
    ): List<SchedulePushJob>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
        status: SchedulePushJobStatus,
        nextCheckAt: Instant,
        pageable: Pageable,
    ): List<SchedulePushJob>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByStatusAndLockedAtLessThanEqualOrderByLockedAtAsc(
        status: SchedulePushJobStatus,
        lockedAt: Instant,
    ): List<SchedulePushJob>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByStatusAndLockedAtLessThanEqualOrderByLockedAtAsc(
        status: SchedulePushJobStatus,
        lockedAt: Instant,
        pageable: Pageable,
    ): List<SchedulePushJob>

    fun findAllByScheduleId(scheduleId: Long): List<SchedulePushJob>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByScheduleIdOrderByIdAsc(scheduleId: Long): List<SchedulePushJob>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByScheduleIdInAndMemberIdIn(
        scheduleIds: Collection<Long>,
        memberIds: Collection<Long>,
    ): List<SchedulePushJob>

    fun findByScheduleIdAndMemberId(scheduleId: Long, memberId: Long): SchedulePushJob?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select job from SchedulePushJob job
        where job.scheduleId = :scheduleId
          and job.memberId = :memberId
        """
    )
    fun findByScheduleIdAndMemberIdForUpdate(
        @Param("scheduleId") scheduleId: Long,
        @Param("memberId") memberId: Long,
    ): SchedulePushJob?

    /**
     * Provider 직전 fence heartbeat는 claim transaction에서 반환된 detached job의
     * optimistic version을 바꾸면 안 된다. row lock을 잡고 identity를 검증한 직후
     * locked_at만 갱신해 긴 처리 중 stale recovery가 lease를 빼앗지 못하게 한다.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "update schedule_push_job set locked_at = :lockedAt where id = :id",
        nativeQuery = true,
    )
    fun heartbeatLeaseWithoutVersion(
        @Param("id") id: Long,
        @Param("lockedAt") lockedAt: Instant,
    ): Int
}
