package com.noLate.sharing.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.cache.ScheduleCalendarCacheInvalidationEvent
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.sharing.domain.BlockedSharingMemberDto
import com.noLate.sharing.domain.SharingMemberBlock
import com.noLate.sharing.domain.SharingModerationDashboardDto
import com.noLate.sharing.domain.SharingModerationReportDto
import com.noLate.sharing.domain.SharingReport
import com.noLate.sharing.domain.SharingReportDto
import com.noLate.sharing.domain.SharingReportReason
import com.noLate.sharing.domain.SharingReportStatus
import com.noLate.sharing.infrastructure.SharingMemberBlockRepository
import com.noLate.sharing.infrastructure.SharingReportRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class SharingSafetyService(
    private val memberRepository: MemberRepository,
    private val blockRepository: SharingMemberBlockRepository,
    private val reportRepository: SharingReportRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val eventPublisher: ApplicationEventPublisher = ApplicationEventPublisher { _ -> },
    private val clock: Clock = Clock.systemUTC(),
    private val moderatorAccessPolicy: SharingModeratorAccessPolicy? = null,
) {
    @Transactional
    fun blockMember(
        blockerMemberId: Long,
        blockedMemberId: Long,
        presentedSessionGeneration: Long,
    ): BlockedSharingMemberDto {
        if (blockerMemberId == blockedMemberId) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "자기 자신을 차단할 수 없습니다.")
        }
        val members = lockMembers(blockerMemberId, blockedMemberId, presentedSessionGeneration)
        val target = members[blockedMemberId] ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        val existing = blockRepository.findPairForUpdate(blockerMemberId, blockedMemberId)
        val block = existing?.apply { reactivate() } ?: SharingMemberBlock(
            blockerMemberId = blockerMemberId,
            blockedMemberId = blockedMemberId,
        )
        val saved = blockRepository.saveAndFlush(block)
        invalidateMemberCalendars(
            setOf(blockerMemberId, blockedMemberId),
            "sharing-member-blocked",
        )
        return saved.toDto(target)
    }

    @Transactional
    fun unblockMember(
        blockerMemberId: Long,
        blockedMemberId: Long,
        presentedSessionGeneration: Long,
    ) {
        lockMembers(blockerMemberId, blockedMemberId, presentedSessionGeneration)
        blockRepository.findPairForUpdate(blockerMemberId, blockedMemberId)
            ?.takeUnless { it.deleted }
            ?.let {
                it.softDelete()
                blockRepository.saveAndFlush(it)
                invalidateMemberCalendars(
                    setOf(blockerMemberId, blockedMemberId),
                    "sharing-member-unblocked",
                )
            }
    }

    @Transactional(readOnly = true)
    fun getBlockedMembers(blockerMemberId: Long): List<BlockedSharingMemberDto> {
        val blocks = blockRepository.findAllByBlockerMemberIdAndDeletedFalseOrderByIdDesc(blockerMemberId)
        val members = memberRepository.findAllById(blocks.map { it.blockedMemberId })
            .filterNot { it.deleted }
            .associateBy { requireNotNull(it.id) }
        return blocks.map { block ->
            val member = members[block.blockedMemberId]
            BlockedSharingMemberDto(
                memberId = block.blockedMemberId,
                name = member?.name,
                email = member?.email,
                blockedAt = (block.createDt ?: block.createdAt)?.toString(),
            )
        }
    }

    @Transactional
    fun reportShare(
        reporterMemberId: Long,
        reportedMemberId: Long,
        resourceType: ScheduleShareResourceType,
        resourceId: Long,
        reason: SharingReportReason,
        details: String?,
        presentedSessionGeneration: Long,
    ): SharingReportDto {
        if (reporterMemberId == reportedMemberId || resourceId <= 0L) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }
        lockMembers(reporterMemberId, reportedMemberId, presentedSessionGeneration)
        requireReportableRelationship(
            reporterMemberId,
            reportedMemberId,
            resourceType,
            resourceId,
        )

        val openStatuses = listOf(SharingReportStatus.SUBMITTED, SharingReportStatus.REVIEWING)
        reportRepository
            .findFirstByReporterMemberIdAndReportedMemberIdAndResourceTypeAndResourceIdAndStatusInAndDeletedFalseOrderByIdDesc(
                reporterMemberId,
                reportedMemberId,
                resourceType,
                resourceId,
                openStatuses,
            )
            ?.let { return it.toDto() }

        val oneDayAgo = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(1)
        if (reportRepository.countByReporterMemberIdAndCreatedAtAfterAndDeletedFalse(reporterMemberId, oneDayAgo) >= 20) {
            throw BusinessException(ErrorCode.SHARING_REPORT_RATE_LIMITED)
        }

        return reportRepository.saveAndFlush(
            SharingReport(
                reporterMemberId = reporterMemberId,
                reportedMemberId = reportedMemberId,
                resourceType = resourceType,
                resourceId = resourceId,
                reason = reason,
                details = normalizeDetails(details),
            )
        ).toDto()
    }

    @Transactional(readOnly = true)
    fun getMyReports(reporterMemberId: Long): List<SharingReportDto> =
        reportRepository.findAllByReporterMemberIdAndDeletedFalseOrderByIdDesc(reporterMemberId)
            .map(SharingReport::toDto)

    @Transactional(readOnly = true)
    fun getModerationDashboard(
        moderatorMemberId: Long,
        statuses: Set<SharingReportStatus>,
    ): SharingModerationDashboardDto {
        requireModerator(moderatorMemberId)
        val selectedStatuses = statuses.ifEmpty { SharingReportStatus.entries.toSet() }
        val reports = reportRepository
            .findTop100ByStatusInAndDeletedFalseOrderByIdAsc(selectedStatuses)
        val members = memberRepository.findAllById(
            reports.flatMap { listOf(it.reporterMemberId, it.reportedMemberId) }.distinct(),
        ).associateBy { requireNotNull(it.id) }

        return SharingModerationDashboardDto(
            counts = SharingReportStatus.entries.associateWith { status ->
                reportRepository.countByStatusAndDeletedFalse(status)
            },
            reports = reports.map { report ->
                report.toModerationDto(
                    reporterEmail = members[report.reporterMemberId]?.email,
                    reportedEmail = members[report.reportedMemberId]?.email,
                )
            },
        )
    }

    @Transactional
    fun moderateReport(
        moderatorMemberId: Long,
        reportId: Long,
        status: SharingReportStatus,
        resolutionNote: String?,
    ): SharingModerationReportDto {
        requireModerator(moderatorMemberId)
        if (status == SharingReportStatus.SUBMITTED) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "신고 상태를 접수로 되돌릴 수 없습니다.")
        }
        val report = reportRepository.findByIdForUpdate(reportId)
            ?: throw BusinessException(ErrorCode.SHARING_REPORT_NOT_FOUND)
        val note = normalizeResolutionNote(resolutionNote)
        try {
            report.moderate(status, moderatorMemberId, note, clock.instant())
        } catch (_: IllegalStateException) {
            throw BusinessException(ErrorCode.INVALID_STATE, "처리가 끝난 신고는 다시 변경할 수 없습니다.")
        }
        val saved = reportRepository.saveAndFlush(report)
        val members = memberRepository.findAllById(
            listOf(saved.reporterMemberId, saved.reportedMemberId).distinct(),
        ).associateBy { requireNotNull(it.id) }
        return saved.toModerationDto(
            reporterEmail = members[saved.reporterMemberId]?.email,
            reportedEmail = members[saved.reportedMemberId]?.email,
        )
    }

    private fun lockMembers(
        actorMemberId: Long,
        targetMemberId: Long,
        presentedSessionGeneration: Long,
    ): Map<Long, Member> {
        val members = memberRepository.findAllByIdsForUpdate(
            listOf(actorMemberId, targetMemberId).distinct().sorted(),
        ).filterNot { it.deleted }.associateBy { requireNotNull(it.id) }
        val actor = members[actorMemberId]
            ?: throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        if (actor.sessionGeneration != presentedSessionGeneration) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        }
        return members
    }

    private fun requireReportableRelationship(
        reporterMemberId: Long,
        reportedMemberId: Long,
        resourceType: ScheduleShareResourceType,
        resourceId: Long,
    ) {
        val valid = when (resourceType) {
            ScheduleShareResourceType.SCHEDULE -> scheduleShareRepository
                .findByScheduleIdAndTargetMemberId(resourceId, reporterMemberId)
                ?.let {
                    !it.deleted && it.status == ScheduleShareStatus.ACTIVE &&
                        it.ownerMemberId == reportedMemberId
                } == true

            ScheduleShareResourceType.CATEGORY -> categoryShareRepository
                .findByCategoryIdAndTargetMemberId(resourceId, reporterMemberId)
                ?.let {
                    !it.deleted && it.status == ScheduleShareStatus.ACTIVE &&
                        it.ownerMemberId == reportedMemberId
                } == true

            ScheduleShareResourceType.CALENDAR -> {
                val calendar = calendarRepository.findByIdAndStatusAndDeletedFalse(resourceId)
                val membership = calendarMemberRepository
                    .findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(
                        resourceId,
                        reporterMemberId,
                        ScheduleCalendarMemberStatus.ACTIVE,
                    )
                calendar?.ownerMemberId == reportedMemberId && membership != null
            }
        }
        if (!valid) {
            throw BusinessException(
                ErrorCode.SHARING_REPORT_NOT_ALLOWED,
                "현재 공유받고 있는 항목만 신고할 수 있습니다.",
            )
        }
    }

    private fun normalizeDetails(details: String?): String? {
        val normalized = details?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (normalized.length > 500) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "신고 설명은 500자 이하여야 합니다.")
        }
        return normalized
    }

    private fun normalizeResolutionNote(note: String?): String? {
        val normalized = note?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (normalized.length > 500) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "처리 메모는 500자 이하여야 합니다.")
        }
        return normalized
    }

    private fun requireModerator(memberId: Long) {
        moderatorAccessPolicy?.requireModerator(memberId)
            ?: throw BusinessException(ErrorCode.FORBIDDEN)
    }

    private fun invalidateMemberCalendars(memberIds: Set<Long>, reason: String) {
        eventPublisher.publishEvent(
            ScheduleCalendarCacheInvalidationEvent(memberIds, reason)
        )
    }

    private fun SharingMemberBlock.toDto(member: Member) = BlockedSharingMemberDto(
        memberId = blockedMemberId,
        name = member.name,
        email = member.email,
        blockedAt = (createDt ?: createdAt)?.toString(),
    )

    private fun SharingReport.toModerationDto(
        reporterEmail: String?,
        reportedEmail: String?,
    ) = SharingModerationReportDto(
        id = requireNotNull(id),
        reporterMemberId = reporterMemberId,
        reporterEmail = reporterEmail,
        reportedMemberId = reportedMemberId,
        reportedEmail = reportedEmail,
        resourceType = resourceType,
        resourceId = resourceId,
        reason = reason,
        details = details,
        status = status,
        moderatorMemberId = moderatorMemberId,
        resolutionNote = resolutionNote,
        createdAt = (createDt ?: createdAt)?.toString(),
        updatedAt = (updateDt ?: updatedAt)?.toString(),
        resolvedAt = resolvedAt?.toString(),
    )
}
