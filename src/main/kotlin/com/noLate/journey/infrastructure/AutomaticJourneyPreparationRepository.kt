package com.noLate.journey.infrastructure

import com.noLate.journey.domain.AutomaticJourneyPreparation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import com.noLate.journey.domain.AutomaticJourneyState
import java.time.Instant

/** Writes are serialized by the existing actor/recipient member fence before schedule access. */
interface AutomaticJourneyPreparationRepository : JpaRepository<AutomaticJourneyPreparation, Long> {
    fun findBySessionId(sessionId: String): AutomaticJourneyPreparation?
    fun findFirstByMemberIdAndScheduleIdOrderByGenerationDesc(memberId: Long, scheduleId: Long): AutomaticJourneyPreparation?
    fun deleteAllByMemberId(memberId: Long)
    fun deleteAllByScheduleIdIn(scheduleIds: Collection<Long>)
    fun deleteAllByRetentionExpiresAtBefore(now: Instant)

    // An atomic conditional update conflicts with stale optimistic writers, so an in-flight
    // departure cannot save ACTIVE over EXPIRED. Keep accepted receipts and terminal history.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""update AutomaticJourneyPreparation p set p.state = :terminal, p.endedAt = :now,
        p.version = p.version + 1 where p.state in :liveStates and p.expiresAt <= :now and p.preparedAt <= :now""")
    fun expireElapsed(@Param("now") now: Instant,
        @Param("liveStates") liveStates: Collection<AutomaticJourneyState>,
        @Param("terminal") terminal: AutomaticJourneyState): Int
}
