package com.noLate.journey.infrastructure

import com.noLate.journey.domain.JourneyGuidanceSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Pageable
import java.time.Instant

/** All mutations are serialized by the existing member row before reading this repository. */
interface JourneyGuidanceSessionRepository : JpaRepository<JourneyGuidanceSession, Long> {
    fun findBySessionId(sessionId: String): JourneyGuidanceSession?
    fun findFirstByMemberIdAndScheduleIdOrderByGenerationDesc(memberId: Long, scheduleId: Long): JourneyGuidanceSession?
    fun findAllByMemberIdAndEndedAtIsNull(memberId: Long): List<JourneyGuidanceSession>
    fun findAllByMemberIdAndScheduleIdAndEndedAtIsNull(memberId: Long, scheduleId: Long): List<JourneyGuidanceSession>
    fun deleteAllByMemberId(memberId: Long)
    fun deleteAllByScheduleIdIn(scheduleIds: Collection<Long>)
    fun deleteAllByEndedAtBefore(before: Instant)

    @Query("select s.id from JourneyGuidanceSession s where s.endedAt is null and s.id > :afterId order by s.id")
    fun findActiveIds(afterId: Long, pageable: Pageable): List<Long>

    @Query("select s.memberId from JourneyGuidanceSession s where s.id = :id")
    fun findOwnerMemberId(id: Long): Long?
}
