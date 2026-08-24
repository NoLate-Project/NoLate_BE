package com.noLate.performance.infrastructure

import com.noLate.performance.domain.InteractionPerformanceEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface InteractionPerformanceEventRepository :
    JpaRepository<InteractionPerformanceEvent, String> {

    fun deleteAllByMemberId(memberId: Long)

    fun deleteAllByExpiresAtBefore(expiresAt: Instant): Long
}
