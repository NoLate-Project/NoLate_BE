package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleTravelPlan
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ScheduleTravelPlanRepository : JpaRepository<ScheduleTravelPlan, Long> {
    fun deleteAllByMemberId(memberId: Long)

    fun findByScheduleIdAndMemberId(scheduleId: Long, memberId: Long): ScheduleTravelPlan?

    fun findByScheduleIdAndMemberIdAndDeletedFalse(scheduleId: Long, memberId: Long): ScheduleTravelPlan?

    fun findAllByScheduleIdAndDeletedFalse(scheduleId: Long): List<ScheduleTravelPlan>

    fun findAllByMemberIdAndScheduleIdInAndDeletedFalse(
        memberId: Long,
        scheduleIds: Collection<Long>,
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
