package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.QuickScheduleParseTelemetry
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface QuickScheduleParseTelemetryRepository :
    JpaRepository<QuickScheduleParseTelemetry, String> {

    fun findByAnalysisIdAndMemberId(analysisId: String, memberId: Long): QuickScheduleParseTelemetry?

    fun deleteAllByMemberId(memberId: Long)

    fun deleteAllByExpiresAtBefore(expiresAt: Instant): Long
}
