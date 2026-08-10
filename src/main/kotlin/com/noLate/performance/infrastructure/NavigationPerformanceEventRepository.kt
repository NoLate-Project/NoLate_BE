package com.noLate.performance.infrastructure

import com.noLate.performance.domain.NavigationPerformanceEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface NavigationPerformanceEventRepository :
    JpaRepository<NavigationPerformanceEvent, String> {

    fun deleteAllByMemberId(memberId: Long)

    fun deleteAllByExpiresAtBefore(expiresAt: Instant): Long
}
