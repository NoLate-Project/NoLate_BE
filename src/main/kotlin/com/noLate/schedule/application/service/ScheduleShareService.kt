package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareDto
import com.noLate.schedule.domain.ScheduleShareInvitation
import com.noLate.schedule.domain.ScheduleShareInvitationAcceptDto
import com.noLate.schedule.domain.ScheduleShareInvitationDto
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

@Service
class ScheduleShareService(
    private val scheduleRepository: ScheduleRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val categoryRepository: ScheduleCategoryRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val invitationRepository: ScheduleShareInvitationRepository,
    private val memberRepository: MemberRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val tokenGenerator: ShareInvitationTokenGenerator = SecureShareInvitationTokenGenerator(),
) {

    /**
     * 개별 일정을 다른 회원에게 공유한다.
     *
     * 동시성 설계:
     * - 같은 owner/schedule에 대한 공유 생성 요청은 먼저 schedules row를 PESSIMISTIC_WRITE로 잠근다.
     * - 락이 걸린 동안 같은 schedule-target 조합을 다시 조회하므로 대부분의 중복 생성 경합은
     *   애플리케이션 레벨에서 "기존 row 재활성화/권한 갱신"으로 수렴한다.
     * - 그래도 배포 환경 차이, 락 타임아웃, 직접 SQL 유입 같은 예외 경로가 있을 수 있어
     *   DB에는 (schedule_id, target_member_id) 유니크 제약을 마지막 방어선으로 둔다.
     */
    @Transactional
    fun shareSchedule(
        ownerMemberId: Long,
        scheduleId: Long,
        targetEmail: String?,
        permission: ScheduleSharePermission,
    ): ScheduleShareDto {
        val normalizedPermission = validateGrantablePermission(permission)
        val target = findTargetMember(targetEmail)

        scheduleRepository.findOwnedActiveForShareUpdate(scheduleId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)

        return grantScheduleShare(
            ownerMemberId = ownerMemberId,
            scheduleId = scheduleId,
            targetMember = target,
            permission = normalizedPermission,
        )
    }

    @Transactional
    fun updateScheduleShare(
        ownerMemberId: Long,
        scheduleId: Long,
        shareId: Long,
        permission: ScheduleSharePermission,
    ): ScheduleShareDto {
        val normalizedPermission = validateGrantablePermission(permission)
        scheduleRepository.findOwnedActiveForShareUpdate(scheduleId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)

        val share = scheduleShareRepository.findByIdAndScheduleIdAndDeletedFalse(shareId, scheduleId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_SHARE_NOT_FOUND)
        if (share.ownerMemberId != ownerMemberId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        share.activate(normalizedPermission)
        return scheduleShareRepository.saveAndFlush(share)
            .toDto(targetEmail = memberRepository.findByIdAndDeletedFalse(share.targetMemberId)?.email)
    }

    @Transactional
    fun revokeScheduleShare(ownerMemberId: Long, scheduleId: Long, shareId: Long) {
        scheduleRepository.findOwnedActiveForShareUpdate(scheduleId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)

        val share = scheduleShareRepository.findByIdAndScheduleIdAndDeletedFalse(shareId, scheduleId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_SHARE_NOT_FOUND)
        if (share.ownerMemberId != ownerMemberId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        share.revoke()
        scheduleShareRepository.saveAndFlush(share)
    }

    @Transactional
    fun getScheduleShares(ownerMemberId: Long, scheduleId: Long): List<ScheduleShareDto> {
        scheduleRepository.findOwnedScheduleDetail(scheduleId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)

        return scheduleShareRepository
            .findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(scheduleId, ScheduleShareStatus.ACTIVE)
            .map { share ->
                share.toDto(targetEmail = memberRepository.findByIdAndDeletedFalse(share.targetMemberId)?.email)
            }
    }

    /**
     * 카테고리 공유는 "이 카테고리에 속한 일정 묶음"을 공유하는 의미다.
     * 개별 일정 공유와 같은 동시성 전략을 사용하되, 직렬화 기준 row가 schedules가 아니라
     * schedule_categories라는 점만 다르다.
     */
    @Transactional
    fun shareCategory(
        ownerMemberId: Long,
        categoryId: Long,
        targetEmail: String?,
        permission: ScheduleSharePermission,
    ): ScheduleShareDto {
        val normalizedPermission = validateGrantablePermission(permission)
        val target = findTargetMember(targetEmail)

        categoryRepository.findOwnedActiveForShareUpdate(categoryId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)

        return grantCategoryShare(
            ownerMemberId = ownerMemberId,
            categoryId = categoryId,
            targetMember = target,
            permission = normalizedPermission,
        )
    }

    @Transactional
    fun updateCategoryShare(
        ownerMemberId: Long,
        categoryId: Long,
        shareId: Long,
        permission: ScheduleSharePermission,
    ): ScheduleShareDto {
        val normalizedPermission = validateGrantablePermission(permission)
        categoryRepository.findOwnedActiveForShareUpdate(categoryId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)

        val share = categoryShareRepository.findByIdAndCategoryIdAndDeletedFalse(shareId, categoryId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_SHARE_NOT_FOUND)
        if (share.ownerMemberId != ownerMemberId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        share.activate(normalizedPermission)
        return categoryShareRepository.saveAndFlush(share)
            .toDto(targetEmail = memberRepository.findByIdAndDeletedFalse(share.targetMemberId)?.email)
    }

    @Transactional
    fun revokeCategoryShare(ownerMemberId: Long, categoryId: Long, shareId: Long) {
        categoryRepository.findOwnedActiveForShareUpdate(categoryId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)

        val share = categoryShareRepository.findByIdAndCategoryIdAndDeletedFalse(shareId, categoryId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_SHARE_NOT_FOUND)
        if (share.ownerMemberId != ownerMemberId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        share.revoke()
        categoryShareRepository.saveAndFlush(share)
    }

    @Transactional
    fun getCategoryShares(ownerMemberId: Long, categoryId: Long): List<ScheduleShareDto> {
        categoryRepository.findByIdAndMemberIdAndDeletedFalse(categoryId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)

        return categoryShareRepository
            .findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(categoryId, ScheduleShareStatus.ACTIVE)
            .map { share ->
                share.toDto(targetEmail = memberRepository.findByIdAndDeletedFalse(share.targetMemberId)?.email)
            }
    }

    @Transactional
    fun createScheduleInvitation(
        ownerMemberId: Long,
        scheduleId: Long,
        permission: ScheduleSharePermission,
        ttlHours: Long?,
        maxAcceptCount: Int?,
    ): ScheduleShareInvitationDto {
        val normalizedPermission = validateGrantablePermission(permission)
        scheduleRepository.findOwnedActiveForShareUpdate(scheduleId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)

        return createInvitation(
            ownerMemberId = ownerMemberId,
            resourceType = ScheduleShareResourceType.SCHEDULE,
            resourceId = scheduleId,
            permission = normalizedPermission,
            ttlHours = ttlHours,
            maxAcceptCount = maxAcceptCount,
        )
    }

    @Transactional
    fun createCategoryInvitation(
        ownerMemberId: Long,
        categoryId: Long,
        permission: ScheduleSharePermission,
        ttlHours: Long?,
        maxAcceptCount: Int?,
    ): ScheduleShareInvitationDto {
        val normalizedPermission = validateGrantablePermission(permission)
        categoryRepository.findOwnedActiveForShareUpdate(categoryId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)

        return createInvitation(
            ownerMemberId = ownerMemberId,
            resourceType = ScheduleShareResourceType.CATEGORY,
            resourceId = categoryId,
            permission = normalizedPermission,
            ttlHours = ttlHours,
            maxAcceptCount = maxAcceptCount,
        )
    }

    @Transactional
    fun getScheduleInvitations(ownerMemberId: Long, scheduleId: Long): List<ScheduleShareInvitationDto> {
        scheduleRepository.findOwnedScheduleDetail(scheduleId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)

        return invitationRepository
            .findAllByOwnerMemberIdAndResourceTypeAndResourceIdAndDeletedFalseOrderByIdDesc(
                ownerMemberId = ownerMemberId,
                resourceType = ScheduleShareResourceType.SCHEDULE,
                resourceId = scheduleId,
            )
            .map { it.toDto() }
    }

    @Transactional
    fun getCategoryInvitations(ownerMemberId: Long, categoryId: Long): List<ScheduleShareInvitationDto> {
        categoryRepository.findByIdAndMemberIdAndDeletedFalse(categoryId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)

        return invitationRepository
            .findAllByOwnerMemberIdAndResourceTypeAndResourceIdAndDeletedFalseOrderByIdDesc(
                ownerMemberId = ownerMemberId,
                resourceType = ScheduleShareResourceType.CATEGORY,
                resourceId = categoryId,
            )
            .map { it.toDto() }
    }

    /**
     * 초대 링크 수락.
     *
     * 링크 토큰은 bearer credential에 가깝기 때문에 원문을 DB에 저장하지 않는다.
     * 수락 시에는 요청 토큰을 해시한 뒤 invitation row를 PESSIMISTIC_WRITE로 잠근다.
     * 이렇게 하면 두 기기가 같은 단일 사용 링크를 동시에 눌러도 acceptedCount 검증과
     * 실제 share row 생성이 하나의 직렬화된 흐름으로 처리된다.
     */
    @Transactional
    fun acceptInvitation(currentMemberId: Long, token: String?): ScheduleShareInvitationAcceptDto {
        val targetMember = memberRepository.findByIdAndDeletedFalse(currentMemberId)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        val tokenHash = hashInvitationToken(normalizeToken(token))
        val invitation = invitationRepository.findActiveByTokenHashForUpdate(tokenHash)
            ?: throw BusinessException(ErrorCode.SCHEDULE_SHARE_INVITATION_NOT_FOUND)

        rejectSelfShare(invitation.ownerMemberId, currentMemberId)
        validateInvitationAcceptable(invitation)

        val share = when (invitation.resourceType) {
            ScheduleShareResourceType.SCHEDULE -> {
                scheduleRepository.findOwnedScheduleDetail(invitation.resourceId, invitation.ownerMemberId)
                    ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
                grantScheduleShare(
                    ownerMemberId = invitation.ownerMemberId,
                    scheduleId = invitation.resourceId,
                    targetMember = targetMember,
                    permission = invitation.permission,
                )
            }
            ScheduleShareResourceType.CATEGORY -> {
                categoryRepository.findByIdAndMemberIdAndDeletedFalse(invitation.resourceId, invitation.ownerMemberId)
                    ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
                grantCategoryShare(
                    ownerMemberId = invitation.ownerMemberId,
                    categoryId = invitation.resourceId,
                    targetMember = targetMember,
                    permission = invitation.permission,
                )
            }
        }

        invitation.accept(
            memberId = currentMemberId,
            acceptedAt = Instant.now(clock),
        )
        val savedInvitation = invitationRepository.saveAndFlush(invitation)

        return ScheduleShareInvitationAcceptDto(
            invitation = savedInvitation.toDto(),
            share = share,
        )
    }

    private fun findTargetMember(targetEmail: String?): Member {
        val normalizedEmail = normalizeEmail(targetEmail)
        return memberRepository.findByEmailAndDeletedFalse(normalizedEmail)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
    }

    private fun createInvitation(
        ownerMemberId: Long,
        resourceType: ScheduleShareResourceType,
        resourceId: Long,
        permission: ScheduleSharePermission,
        ttlHours: Long?,
        maxAcceptCount: Int?,
    ): ScheduleShareInvitationDto {
        val normalizedTtlHours = validateTtlHours(ttlHours)
        val normalizedMaxAcceptCount = validateMaxAcceptCount(maxAcceptCount)
        val token = tokenGenerator.generate()
        val invitation = ScheduleShareInvitation(
            ownerMemberId = ownerMemberId,
            resourceType = resourceType,
            resourceId = resourceId,
            permission = permission,
            tokenHash = hashInvitationToken(token),
            expiresAt = Instant.now(clock).plus(normalizedTtlHours, ChronoUnit.HOURS),
            maxAcceptCount = normalizedMaxAcceptCount,
        )

        return invitationRepository.saveAndFlush(invitation)
            .toDto(token = token)
    }

    private fun grantScheduleShare(
        ownerMemberId: Long,
        scheduleId: Long,
        targetMember: Member,
        permission: ScheduleSharePermission,
    ): ScheduleShareDto {
        val targetMemberId = requireNotNull(targetMember.id)
        rejectSelfShare(ownerMemberId, targetMemberId)

        val share = scheduleShareRepository.findByScheduleIdAndTargetMemberId(
            scheduleId = scheduleId,
            targetMemberId = targetMemberId,
        )?.apply {
            activate(permission)
        } ?: ScheduleShare(
            scheduleId = scheduleId,
            ownerMemberId = ownerMemberId,
            targetMemberId = targetMemberId,
            permission = permission,
            status = ScheduleShareStatus.ACTIVE,
        )

        return scheduleShareRepository.saveAndFlush(share)
            .toDto(targetEmail = targetMember.email)
    }

    private fun grantCategoryShare(
        ownerMemberId: Long,
        categoryId: Long,
        targetMember: Member,
        permission: ScheduleSharePermission,
    ): ScheduleShareDto {
        val targetMemberId = requireNotNull(targetMember.id)
        rejectSelfShare(ownerMemberId, targetMemberId)

        val share = categoryShareRepository.findByCategoryIdAndTargetMemberId(
            categoryId = categoryId,
            targetMemberId = targetMemberId,
        )?.apply {
            activate(permission)
        } ?: ScheduleCategoryShare(
            categoryId = categoryId,
            ownerMemberId = ownerMemberId,
            targetMemberId = targetMemberId,
            permission = permission,
            status = ScheduleShareStatus.ACTIVE,
        )

        return categoryShareRepository.saveAndFlush(share)
            .toDto(targetEmail = targetMember.email)
    }

    private fun normalizeEmail(value: String?): String {
        val normalized = value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "targetEmail is required.")

        if (normalized.length > 255 || !normalized.contains("@")) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "targetEmail must be a valid email.")
        }

        return normalized
    }

    private fun validateGrantablePermission(permission: ScheduleSharePermission): ScheduleSharePermission {
        if (permission == ScheduleSharePermission.OWNER) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "OWNER permission is reserved for the resource owner.",
            )
        }
        return permission
    }

    private fun validateTtlHours(ttlHours: Long?): Long {
        val normalized = ttlHours ?: DEFAULT_INVITATION_TTL_HOURS
        if (normalized !in 1..MAX_INVITATION_TTL_HOURS) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "ttlHours must be between 1 and $MAX_INVITATION_TTL_HOURS.",
            )
        }
        return normalized
    }

    private fun validateMaxAcceptCount(maxAcceptCount: Int?): Int {
        val normalized = maxAcceptCount ?: DEFAULT_MAX_ACCEPT_COUNT
        if (normalized !in 1..MAX_ACCEPT_COUNT) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "maxAcceptCount must be between 1 and $MAX_ACCEPT_COUNT.",
            )
        }
        return normalized
    }

    private fun validateInvitationAcceptable(invitation: ScheduleShareInvitation) {
        val now = Instant.now(clock)
        if (!now.isBefore(invitation.expiresAt)) {
            invitation.markExpired()
            invitationRepository.saveAndFlush(invitation)
            throw BusinessException(ErrorCode.INVALID_STATE, "초대 링크가 만료되었습니다.")
        }
        if (invitation.acceptedCount >= invitation.maxAcceptCount) {
            throw BusinessException(ErrorCode.INVALID_STATE, "초대 링크 수락 가능 횟수를 초과했습니다.")
        }
    }

    private fun normalizeToken(value: String?): String {
        return value?.trim()?.takeIf { it.isNotBlank() }
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "token is required.")
    }

    private fun hashInvitationToken(token: String): String =
        ShareInvitationTokenHasher.hash(token)

    private fun rejectSelfShare(ownerMemberId: Long, targetMemberId: Long) {
        if (ownerMemberId == targetMemberId) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "자기 자신에게 공유할 수 없습니다.")
        }
    }

    companion object {
        private const val DEFAULT_INVITATION_TTL_HOURS = 24L * 7L
        private const val MAX_INVITATION_TTL_HOURS = 24L * 30L
        private const val DEFAULT_MAX_ACCEPT_COUNT = 1
        private const val MAX_ACCEPT_COUNT = 20
    }
}
