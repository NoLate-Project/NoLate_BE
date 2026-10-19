package com.noLate.journey.infrastructure

import com.noLate.journey.domain.JourneyWalkingSnapshot
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.*
import org.springframework.data.repository.query.Param

interface JourneyWalkingSnapshotRepository : JpaRepository<JourneyWalkingSnapshot, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from JourneyWalkingSnapshot s where s.memberId = :member and s.scheduleId = :schedule")
    fun findOwnedForUpdate(@Param("member") member: Long, @Param("schedule") schedule: Long): JourneyWalkingSnapshot?
    fun deleteAllByMemberId(memberId: Long)
    fun deleteAllByScheduleIdIn(scheduleIds: Collection<Long>)
    @Modifying
    @Query("delete from JourneyWalkingSnapshot s where s.expiresAtMs <= :now")
    fun purgeExpired(@Param("now") now: Long): Int
}
