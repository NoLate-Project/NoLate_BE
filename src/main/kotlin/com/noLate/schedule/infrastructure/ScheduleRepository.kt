package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.Schedule
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDateTime

interface ScheduleRepository : JpaRepository<Schedule, Long> {
    @Query(
        value = """
        select s.*
        from schedules s
        where s.deleted = false
          and (
            s.member_id = :memberId
            or exists (
              select 1
              from schedule_shares ss
              where ss.schedule_id = s.id
                and ss.target_member_id = :memberId
                and ss.status = 'ACTIVE'
                and ss.deleted = false
            )
            or exists (
              select 1
              from schedule_category_shares scs
              where scs.target_member_id = :memberId
                and scs.status = 'ACTIVE'
                and scs.deleted = false
                and (
                  scs.category_id = s.category_id
                  or exists (
                    select 1
                    from schedule_category_snapshots scsnap
                    where scsnap.schedule_id = s.id
                      and scsnap.category_id = concat('', scs.category_id)
                  )
                )
            )
          )
        order by s.start_at asc
        """,
        nativeQuery = true
    )
    fun findScheduleList(@Param("memberId") memberId: Long): List<Schedule>

    @Query(
        value = """
        select s.*
        from schedules s
        where s.id = :scheduleId
          and s.deleted = false
          and (
            s.member_id = :memberId
            or exists (
              select 1
              from schedule_shares ss
              where ss.schedule_id = s.id
                and ss.target_member_id = :memberId
                and ss.status = 'ACTIVE'
                and ss.deleted = false
            )
            or exists (
              select 1
              from schedule_category_shares scs
              where scs.target_member_id = :memberId
                and scs.status = 'ACTIVE'
                and scs.deleted = false
                and (
                  scs.category_id = s.category_id
                  or exists (
                    select 1
                    from schedule_category_snapshots scsnap
                    where scsnap.schedule_id = s.id
                      and scsnap.category_id = concat('', scs.category_id)
                  )
                )
            )
          )
        """,
        nativeQuery = true
    )
    fun findScheduleDetail(
        @Param("scheduleId") scheduleId: Long,
        @Param("memberId") memberId: Long,
    ): Schedule?

    @Query(
        value = """
        select s.*
        from schedules s
        where s.deleted = false
          and s.start_at <= :rangeEnd
          and s.end_at >= :rangeStart
          and (
            s.member_id = :memberId
            or exists (
              select 1
              from schedule_shares ss
              where ss.schedule_id = s.id
                and ss.target_member_id = :memberId
                and ss.status = 'ACTIVE'
                and ss.deleted = false
            )
            or exists (
              select 1
              from schedule_category_shares scs
              where scs.target_member_id = :memberId
                and scs.status = 'ACTIVE'
                and scs.deleted = false
                and (
                  scs.category_id = s.category_id
                  or exists (
                    select 1
                    from schedule_category_snapshots scsnap
                    where scsnap.schedule_id = s.id
                      and scsnap.category_id = concat('', scs.category_id)
                  )
                )
            )
          )
        order by s.start_at asc
        """,
        nativeQuery = true
    )
    fun findOverlappingScheduleList(
        @Param("memberId") memberId: Long,
        @Param("rangeStart") rangeStart: Instant,
        @Param("rangeEnd") rangeEnd: Instant,
    ): List<Schedule>

    @Query(
        value = """
        select s.*
        from schedules s
        where s.deleted = false
          and s.end_at >= :fromAt
          and (
            s.member_id = :memberId
            or exists (
              select 1
              from schedule_shares ss
              where ss.schedule_id = s.id
                and ss.target_member_id = :memberId
                and ss.status = 'ACTIVE'
                and ss.deleted = false
            )
            or exists (
              select 1
              from schedule_category_shares scs
              where scs.target_member_id = :memberId
                and scs.status = 'ACTIVE'
                and scs.deleted = false
                and (
                  scs.category_id = s.category_id
                  or exists (
                    select 1
                    from schedule_category_snapshots scsnap
                    where scsnap.schedule_id = s.id
                      and scsnap.category_id = concat('', scs.category_id)
                  )
                )
            )
          )
        order by s.start_at asc
        """,
        nativeQuery = true
    )
    fun findUpcomingScheduleList(
        @Param("memberId") memberId: Long,
        @Param("fromAt") fromAt: Instant,
        pageable: Pageable,
    ): List<Schedule>

    @Query(
        value = """
        select s.*
        from schedules s
        left join schedule_routes sr on sr.schedule_id = s.id
        left join schedule_category_snapshots sc on sc.schedule_id = s.id
        where s.deleted = false
          and (
            s.member_id = :memberId
            or exists (
              select 1
              from schedule_shares ss
              where ss.schedule_id = s.id
                and ss.target_member_id = :memberId
                and ss.status = 'ACTIVE'
                and ss.deleted = false
            )
            or exists (
              select 1
              from schedule_category_shares scs
              where scs.target_member_id = :memberId
                and scs.status = 'ACTIVE'
                and scs.deleted = false
                and (
                  scs.category_id = s.category_id
                  or exists (
                    select 1
                    from schedule_category_snapshots scsnap
                    where scsnap.schedule_id = s.id
                      and scsnap.category_id = concat('', scs.category_id)
                  )
                )
            )
          )
          and (:keyword is null
               or lower(s.title) like lower(concat('%', :keyword, '%'))
               or lower(coalesce(sr.location_name, '')) like lower(concat('%', :keyword, '%'))
               or lower(coalesce(s.notes, '')) like lower(concat('%', :keyword, '%')))
          and (:categoryId is null or sc.category_id = :categoryId)
          and (:rangeStart is null or s.end_at >= :rangeStart)
          and (:rangeEnd is null or s.start_at <= :rangeEnd)
        order by s.start_at asc
        """,
        nativeQuery = true
    )
    fun searchScheduleList(
        @Param("memberId") memberId: Long,
        @Param("keyword") keyword: String?,
        @Param("categoryId") categoryId: String?,
        @Param("rangeStart") rangeStart: Instant?,
        @Param("rangeEnd") rangeEnd: Instant?,
    ): List<Schedule>

    @Query(
        value = """
        select s.*
        from schedules s
        join schedule_routes sr on sr.schedule_id = s.id
        where s.deleted = false
          and s.start_at >= :fromAt
          and s.start_at <= :toAt
          and (sr.depart_at is not null or sr.travel_minutes is not null or sr.route_json is not null)
          and (
            s.member_id = :memberId
            or exists (
              select 1
              from schedule_shares ss
              where ss.schedule_id = s.id
                and ss.target_member_id = :memberId
                and ss.status = 'ACTIVE'
                and ss.deleted = false
            )
            or exists (
              select 1
              from schedule_category_shares scs
              where scs.target_member_id = :memberId
                and scs.status = 'ACTIVE'
                and scs.deleted = false
                and (
                  scs.category_id = s.category_id
                  or exists (
                    select 1
                    from schedule_category_snapshots scsnap
                    where scsnap.schedule_id = s.id
                      and scsnap.category_id = concat('', scs.category_id)
                  )
                )
            )
          )
        order by s.start_at asc
        """,
        nativeQuery = true
    )
    fun findDepartureReadyScheduleList(
        @Param("memberId") memberId: Long,
        @Param("fromAt") fromAt: Instant,
        @Param("toAt") toAt: Instant,
    ): List<Schedule>

    @Query(
        value = """
        select s.*
        from schedules s
        join schedule_routes sr on sr.schedule_id = s.id
        left join schedule_push_job spj on spj.schedule_id = s.id
        where s.deleted = false
          and sr.notification_enabled = true
          and s.start_at > :now
          and spj.id is null
        order by s.start_at asc
        """,
        nativeQuery = true,
    )
    fun findNotificationEnabledWithoutPushJob(
        @Param("now") now: Instant,
    ): List<Schedule>

    @Query(
        value = """
        select count(*)
        from schedules s
        join schedule_routes sr on sr.schedule_id = s.id
        where s.member_id = :memberId
          and sr.notification_enabled = true
          and s.create_dt >= :monthStart
          and s.create_dt < :nextMonthStart
        """,
        nativeQuery = true,
    )
    fun countMonthlySmartSchedules(
        @Param("memberId") memberId: Long,
        @Param("monthStart") monthStart: LocalDateTime,
        @Param("nextMonthStart") nextMonthStart: LocalDateTime,
    ): Long

    @Query(
        """
        select s
        from Schedule s
        where s.id = :scheduleId
          and s.memberId = :memberId
          and s.deleted = false
        """
    )
    fun findOwnedScheduleDetail(
        @Param("scheduleId") scheduleId: Long,
        @Param("memberId") memberId: Long,
    ): Schedule?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select s
        from Schedule s
        where s.id = :scheduleId
          and s.memberId = :memberId
          and s.deleted = false
        """
    )
    fun findOwnedActiveForShareUpdate(
        @Param("scheduleId") scheduleId: Long,
        @Param("memberId") memberId: Long,
    ): Schedule?
}
