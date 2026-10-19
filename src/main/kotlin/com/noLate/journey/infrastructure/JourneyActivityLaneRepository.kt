package com.noLate.journey.infrastructure

import com.noLate.journey.domain.JourneyActivityLane
import com.noLate.journey.domain.JourneyActivityEndState
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.*
import org.springframework.data.repository.query.Param
import java.time.Instant

interface JourneyActivityLaneRepository : JpaRepository<JourneyActivityLane, Long> {
    fun findBySessionId(sessionId: String): JourneyActivityLane?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from JourneyActivityLane a where a.sessionId = :session")
    fun lockSession(@Param("session") session: String): JourneyActivityLane?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from JourneyActivityLane a where a.id = :id")
    fun lockId(@Param("id") id: Long): JourneyActivityLane?
    @Query("""select a.id from JourneyActivityLane a where a.endState = :ready or (a.endState = :none
        and exists (select p.id from AutomaticJourneyPreparation p where p.sessionId = a.sessionId and p.state in :terminal)) order by a.id""")
    fun candidates(@Param("ready") ready: JourneyActivityEndState, @Param("none") none: JourneyActivityEndState,
        @Param("terminal") terminal: Collection<com.noLate.journey.domain.AutomaticJourneyState>, page: Pageable): List<Long>
    @Query("select a.id from JourneyActivityLane a where a.endState = :state and a.claimedAt <= :boundary order by a.id")
    fun staleClaims(@Param("state") state: JourneyActivityEndState, @Param("boundary") boundary: Instant, page: Pageable): List<Long>
    fun deleteAllByMemberId(memberId: Long)
    fun deleteAllByScheduleIdIn(scheduleIds: Collection<Long>)
    fun deleteAllByRetentionExpiresAtBefore(now: Instant)
    fun countByEndState(state: JourneyActivityEndState): Long
}
