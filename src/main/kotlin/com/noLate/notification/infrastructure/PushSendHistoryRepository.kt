package com.noLate.notification.infrastructure

import com.noLate.notification.domain.PushSendHistory
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PushSendHistoryRepository : JpaRepository<PushSendHistory, Long> {

    fun deleteAllByMemberId(memberId: Long)

    fun findAllByMemberIdOrderBySentAtDesc(memberId: Long, pageable: Pageable): List<PushSendHistory>

    fun findAllByScheduleIdOrderBySentAtDesc(scheduleId: Long, pageable: Pageable): List<PushSendHistory>
}
