package com.noLate.sharing.domain

import com.noLate.global.common.BaseEntity
import com.noLate.schedule.domain.ScheduleShareResourceType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

enum class SharingReportReason {
    UNWANTED_SHARING,
    HARASSMENT,
    SPAM,
    INAPPROPRIATE_CONTENT,
    PRIVACY_CONCERN,
    OTHER,
}

enum class SharingReportStatus {
    SUBMITTED,
    REVIEWING,
    RESOLVED,
    DISMISSED,
}

data class SharingReportDto(
    val id: Long,
    val reportedMemberId: Long,
    val resourceType: ScheduleShareResourceType,
    val resourceId: Long,
    val reason: SharingReportReason,
    val details: String?,
    val status: SharingReportStatus,
    val createdAt: String?,
    val updatedAt: String?,
    val resolvedAt: String?,
)

data class SharingModerationReportDto(
    val id: Long,
    val reporterMemberId: Long,
    val reporterEmail: String?,
    val reportedMemberId: Long,
    val reportedEmail: String?,
    val resourceType: ScheduleShareResourceType,
    val resourceId: Long,
    val reason: SharingReportReason,
    val details: String?,
    val status: SharingReportStatus,
    val moderatorMemberId: Long?,
    val resolutionNote: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val resolvedAt: String?,
)

data class SharingModerationDashboardDto(
    val counts: Map<SharingReportStatus, Long>,
    val reports: List<SharingModerationReportDto>,
)

data class BlockedSharingMemberDto(
    val memberId: Long,
    val name: String?,
    val email: String?,
    val blockedAt: String?,
)

@Entity
@Table(
    name = "sharing_member_blocks",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_sharing_member_blocks_pair",
            columnNames = ["blocker_member_id", "blocked_member_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_sharing_member_blocks_blocker", columnList = "blocker_member_id,deleted"),
        Index(name = "idx_sharing_member_blocks_blocked", columnList = "blocked_member_id,deleted"),
    ],
)
class SharingMemberBlock(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "blocker_member_id", nullable = false)
    var blockerMemberId: Long = 0L,

    @Column(name = "blocked_member_id", nullable = false)
    var blockedMemberId: Long = 0L,
) : BaseEntity() {
    fun reactivate() {
        deleted = false
        deletedAt = null
    }
}

@Entity
@Table(
    name = "sharing_reports",
    indexes = [
        Index(name = "idx_sharing_reports_reporter_created", columnList = "reporter_member_id,created_at"),
        Index(name = "idx_sharing_reports_status_created", columnList = "status,created_at"),
        Index(
            name = "idx_sharing_reports_resource",
            columnList = "resource_type,resource_id,reported_member_id",
        ),
    ],
)
class SharingReport(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "reporter_member_id", nullable = false)
    var reporterMemberId: Long = 0L,

    @Column(name = "reported_member_id", nullable = false)
    var reportedMemberId: Long = 0L,

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 30)
    var resourceType: ScheduleShareResourceType = ScheduleShareResourceType.SCHEDULE,

    @Column(name = "resource_id", nullable = false)
    var resourceId: Long = 0L,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    var reason: SharingReportReason = SharingReportReason.UNWANTED_SHARING,

    @Column(length = 500)
    var details: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: SharingReportStatus = SharingReportStatus.SUBMITTED,

    @Column(name = "moderator_member_id")
    var moderatorMemberId: Long? = null,

    @Column(name = "resolution_note", length = 500)
    var resolutionNote: String? = null,

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,
) : BaseEntity() {
    fun moderate(
        nextStatus: SharingReportStatus,
        moderatorMemberId: Long,
        resolutionNote: String?,
        moderatedAt: Instant,
    ) {
        if (status == nextStatus) return
        check(status == SharingReportStatus.SUBMITTED || status == SharingReportStatus.REVIEWING) {
            "A finalized sharing report cannot be changed."
        }
        check(nextStatus != SharingReportStatus.SUBMITTED) {
            "A sharing report cannot return to submitted."
        }
        status = nextStatus
        this.moderatorMemberId = moderatorMemberId
        this.resolutionNote = resolutionNote
        resolvedAt = if (
            nextStatus == SharingReportStatus.RESOLVED ||
            nextStatus == SharingReportStatus.DISMISSED
        ) moderatedAt else null
    }

    fun toDto() = SharingReportDto(
        id = requireNotNull(id),
        reportedMemberId = reportedMemberId,
        resourceType = resourceType,
        resourceId = resourceId,
        reason = reason,
        details = details,
        status = status,
        createdAt = (createDt ?: createdAt)?.toString(),
        updatedAt = (updateDt ?: updatedAt)?.toString(),
        resolvedAt = resolvedAt?.toString(),
    )
}
