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

interface SchedulePushJobCandidate {
    val id: Long
    val memberId: Long
}

interface SchedulePushJobAlarmBackfillCandidate {
    val jobId: Long
    val memberId: Long
    val scheduleId: Long
}

interface SchedulePushJobRepository : JpaRepository<SchedulePushJob, Long> {
    fun deleteAllByMemberId(memberId: Long)
    fun deleteAllByScheduleIdIn(scheduleIds: Collection<Long>)

    @Query(
        """
        select distinct job.memberId
        from SchedulePushJob job
        where job.scheduleId in :scheduleIds
        order by job.memberId
        """
    )
    fun findDistinctMemberIdsByScheduleIdIn(
        @Param("scheduleIds") scheduleIds: Collection<Long>,
    ): List<Long>

    @Query(
        """
        select count(job) from SchedulePushJob job
        where job.status = :status
          and job.nextCheckAt <= :dueAt
        """
    )
    fun countDue(
        @Param("status") status: SchedulePushJobStatus,
        @Param("dueAt") dueAt: Instant,
    ): Long

    @Query(
        """
        select min(job.nextCheckAt) from SchedulePushJob job
        where job.status = :status
          and job.nextCheckAt <= :dueAt
        """
    )
    fun findOldestDueAt(
        @Param("status") status: SchedulePushJobStatus,
        @Param("dueAt") dueAt: Instant,
    ): Instant?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from SchedulePushJob job where job.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): SchedulePushJob?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
        status: SchedulePushJobStatus,
        nextCheckAt: Instant,
    ): List<SchedulePushJob>

    @Query(
        """
        select job.id as id, job.memberId as memberId from SchedulePushJob job
        where job.status = :status
          and job.nextCheckAt <= :nextCheckAt
        order by job.nextCheckAt asc, job.id asc
        """
    )
    fun findDueCandidates(
        @Param("status") status: SchedulePushJobStatus,
        @Param("nextCheckAt") nextCheckAt: Instant,
        pageable: Pageable,
    ): List<SchedulePushJobCandidate>

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

    @Query(
        """
        select job.id as id, job.memberId as memberId from SchedulePushJob job
        where job.status = :status
          and job.lockedAt <= :lockedAt
        order by job.lockedAt asc, job.id asc
        """
    )
    fun findStaleCandidates(
        @Param("status") status: SchedulePushJobStatus,
        @Param("lockedAt") lockedAt: Instant,
        pageable: Pageable,
    ): List<SchedulePushJobCandidate>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByStatusAndLockedAtLessThanEqualOrderByLockedAtAsc(
        status: SchedulePushJobStatus,
        lockedAt: Instant,
        pageable: Pageable,
    ): List<SchedulePushJob>

    fun findAllByScheduleId(scheduleId: Long): List<SchedulePushJob>

    @Query(
        """
        select job.memberId from SchedulePushJob job
        where job.scheduleId = :scheduleId
        order by job.memberId asc
        """
    )
    fun findMemberIdsByScheduleId(
        @Param("scheduleId") scheduleId: Long,
    ): List<Long>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByScheduleIdOrderByIdAsc(scheduleId: Long): List<SchedulePushJob>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByScheduleIdInAndMemberIdIn(
        scheduleIds: Collection<Long>,
        memberIds: Collection<Long>,
    ): List<SchedulePushJob>

    fun findByScheduleIdAndMemberId(scheduleId: Long, memberId: Long): SchedulePushJob?

    @Query(
        value = """
        select job.id as jobId, job.member_id as memberId, job.schedule_id as scheduleId
        from schedule_push_job job
        join schedules schedule_row on schedule_row.id = job.schedule_id
        left join departure_alarm_sync_state alarm_state
          on alarm_state.member_id = job.member_id
         and alarm_state.schedule_id = job.schedule_id
        where job.status = 'ACTIVE'
          and schedule_row.deleted = false
          and schedule_row.start_at > :now
          and alarm_state.id is null
        order by job.member_id asc, job.schedule_id asc
        """,
        nativeQuery = true,
    )
    fun findAlarmSyncBackfillCandidates(
        @Param("now") now: Instant,
    ): List<SchedulePushJobAlarmBackfillCandidate>

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
