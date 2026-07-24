package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDateTime

interface ScheduleTravelPlanRepository : JpaRepository<ScheduleTravelPlan, Long> {
    fun deleteAllByMemberId(memberId: Long)
    fun deleteAllByScheduleIdIn(scheduleIds: Collection<Long>)

    @Query(
        """
        select distinct plan.memberId
        from ScheduleTravelPlan plan
        where plan.scheduleId in :scheduleIds
        order by plan.memberId
        """
    )
    fun findDistinctMemberIdsByScheduleIdIn(
        @Param("scheduleIds") scheduleIds: Collection<Long>,
    ): List<Long>

    fun findByScheduleIdAndMemberId(scheduleId: Long, memberId: Long): ScheduleTravelPlan?

    fun findByScheduleIdAndMemberIdAndDeletedFalse(scheduleId: Long, memberId: Long): ScheduleTravelPlan?

    fun findAllByScheduleIdAndDeletedFalse(scheduleId: Long): List<ScheduleTravelPlan>

    @Query(
        """
        select plan.memberId
        from ScheduleTravelPlan plan
        where plan.scheduleId = :scheduleId
          and plan.deleted = false
          and plan.notificationEnabled = true
        order by plan.memberId asc
        """
    )
    fun findNotificationEnabledMemberIdsByScheduleId(
        @Param("scheduleId") scheduleId: Long,
    ): List<Long>

    fun findAllByMemberIdAndScheduleIdInAndDeletedFalse(
        memberId: Long,
        scheduleIds: Collection<Long>,
    ): List<ScheduleTravelPlan>

    fun findAllByScheduleIdInAndMemberIdInAndDeletedFalse(
        scheduleIds: Collection<Long>,
        memberIds: Collection<Long>,
    ): List<ScheduleTravelPlan>

    /**
     * 최초 v4 배포에서 legacy push job을 명시적으로 비운 뒤 재구성할 참가자 plan 후보.
     *
     * 일정 소유자는 schedules.schedule_routes 기준 backfill이 authoritative하므로 제외한다.
     * schedule fingerprint의 current 여부는 Java의 [ScheduleTravelPlanFingerprint] 단일 규칙으로
     * 최종 확인한다. SQL에서 SHA-256 canonicalization을 중복 구현하지 않는다.
     */
    @Query(
        value = """
        select stp.*
        from schedule_travel_plans stp
        join schedules s on s.id = stp.schedule_id
        where stp.deleted = false
          and stp.notification_enabled = true
          and stp.member_id <> s.member_id
          and s.deleted = false
          and s.start_at > :now
          and not exists (
            select 1
            from schedule_push_job spj
            where spj.schedule_id = stp.schedule_id
              and spj.member_id = stp.member_id
          )
        order by s.start_at asc, stp.schedule_id asc, stp.member_id asc
        """,
        nativeQuery = true,
    )
    fun findNotificationEnabledParticipantsWithoutPushJob(
        @Param("now") now: Instant,
    ): List<ScheduleTravelPlan>

    @Query(
        value = """
        select count(*)
        from schedule_travel_plans stp
        where stp.member_id = :memberId
          and stp.notification_enabled = true
          and stp.deleted = false
          and stp.create_dt >= :monthStart
          and stp.create_dt < :nextMonthStart
        """,
        nativeQuery = true,
    )
    fun countMonthlyNotificationEnabledPlans(
        @Param("memberId") memberId: Long,
        @Param("monthStart") monthStart: LocalDateTime,
        @Param("nextMonthStart") nextMonthStart: LocalDateTime,
    ): Long
}
