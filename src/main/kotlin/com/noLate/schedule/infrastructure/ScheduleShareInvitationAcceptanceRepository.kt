package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleShareInvitationAcceptance
import org.springframework.data.jpa.repository.JpaRepository

interface ScheduleShareInvitationAcceptanceRepository :
    JpaRepository<ScheduleShareInvitationAcceptance, Long> {
    fun findByInvitationIdAndMemberId(
        invitationId: Long,
        memberId: Long,
    ): ScheduleShareInvitationAcceptance?

    fun deleteAllByMemberId(memberId: Long)
    fun deleteAllByInvitationIdIn(invitationIds: Collection<Long>)
}
