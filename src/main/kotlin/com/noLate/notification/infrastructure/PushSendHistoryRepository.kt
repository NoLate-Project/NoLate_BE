package com.noLate.notification.infrastructure

import com.noLate.notification.domain.PushSendHistory
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PushSendHistoryRepository : JpaRepository<PushSendHistory, Long> {

    fun deleteAllByMemberId(memberId: Long)
    fun deleteAllByScheduleIdIn(scheduleIds: Collection<Long>)

    @Query(
        """
        select distinct history.memberId
        from PushSendHistory history
        where history.scheduleId in :scheduleIds
        order by history.memberId
        """
    )
    fun findDistinctMemberIdsByScheduleIdIn(
        @Param("scheduleIds") scheduleIds: Collection<Long>,
    ): List<Long>

    fun findAllByScheduleIdInAndMemberIdIn(
        scheduleIds: Collection<Long>,
        memberIds: Collection<Long>,
    ): List<PushSendHistory>

    fun findAllByMemberIdOrderBySentAtDesc(memberId: Long, pageable: Pageable): List<PushSendHistory>

    fun findAllByScheduleIdOrderBySentAtDesc(scheduleId: Long, pageable: Pageable): List<PushSendHistory>
}
