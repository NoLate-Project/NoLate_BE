package com.noLate.sharing.infrastructure

import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.sharing.domain.SharingReport
import com.noLate.sharing.domain.SharingReportStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface SharingReportRepository : JpaRepository<SharingReport, Long> {
    fun findAllByReporterMemberIdAndDeletedFalseOrderByIdDesc(
        reporterMemberId: Long,
    ): List<SharingReport>

    fun findFirstByReporterMemberIdAndReportedMemberIdAndResourceTypeAndResourceIdAndStatusInAndDeletedFalseOrderByIdDesc(
        reporterMemberId: Long,
        reportedMemberId: Long,
        resourceType: ScheduleShareResourceType,
        resourceId: Long,
        statuses: Collection<SharingReportStatus>,
    ): SharingReport?

    fun countByReporterMemberIdAndCreatedAtAfterAndDeletedFalse(
        reporterMemberId: Long,
        createdAt: LocalDateTime,
    ): Long

    fun findTop100ByStatusInAndDeletedFalseOrderByIdAsc(
        statuses: Collection<SharingReportStatus>,
    ): List<SharingReport>

    fun countByStatusAndDeletedFalse(status: SharingReportStatus): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select report
        from SharingReport report
        where report.id = :id
          and report.deleted = false
        """
    )
    fun findByIdForUpdate(@Param("id") id: Long): SharingReport?
}
