package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareDto
import com.noLate.schedule.domain.ScheduleShareInboxDto
import com.noLate.schedule.domain.ScheduleShareInboxItemDto
import com.noLate.schedule.domain.ScheduleShareInvitation
import com.noLate.schedule.domain.ScheduleShareInvitationAcceptDto
import com.noLate.schedule.domain.ScheduleShareInvitationDto
import com.noLate.schedule.domain.ScheduleShareInvitationStatus
import com.noLate.schedule.domain.ScheduleShareInvitationSummaryDto
import com.noLate.schedule.domain.ScheduleShareOutboxDto
import com.noLate.schedule.domain.ScheduleShareOutboxItemDto
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import jakarta.transaction.Transactional
import org.springframework.context.ApplicationEventPublisher
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
    private val eventPublisher: ApplicationEventPublisher = ApplicationEventPublisher { _ -> },
    private val clock: Clock = Clock.systemUTC(),
    private val tokenGenerator: ShareInvitationTokenGenerator = SecureShareInvitationTokenGenerator(),
) {

    @Transactional
    fun getShareInbox(memberId: Long): ScheduleShareInboxDto {
        val scheduleShares = scheduleShareRepository
            .findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
                targetMemberId = memberId,
                status = ScheduleShareStatus.ACTIVE,
            )
        val categoryShares = categoryShareRepository
            .findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
                targetMemberId = memberId,
                status = ScheduleShareStatus.ACTIVE,
            )

        val scheduleResources = scheduleResources(scheduleShares.map { it.scheduleId })
        val categoryResources = categoryResources(categoryShares.map { it.categoryId })

        val receivedShares = buildList {
            scheduleShares.forEach { share ->
                val resource = scheduleResources[share.scheduleId]
                add(
                    ScheduleShareInboxItemDto(
                        shareId = requireNotNull(share.id).toString(),
                        resourceType = ScheduleShareResourceType.SCHEDULE,
                        resourceId = share.scheduleId.toString(),
                        title = resource?.title ?: "삭제된 일정",
                        color = resource?.color,
                        ownerMemberId = share.ownerMemberId,
                        ownerEmail = memberRepository.findByIdAndDeletedFalse(share.ownerMemberId)?.email,
                        permission = share.permission,
                        sharedAt = (share.createDt ?: share.createdAt)?.toString(),
                    )
                )
            }
            categoryShares.forEach { share ->
                val resource = categoryResources[share.categoryId]
                add(
                    ScheduleShareInboxItemDto(
                        shareId = requireNotNull(share.id).toString(),
                        resourceType = ScheduleShareResourceType.CATEGORY,
                        resourceId = share.categoryId.toString(),
                        title = resource?.title ?: "삭제된 카테고리",
                        color = resource?.color,
                        ownerMemberId = share.ownerMemberId,
                        ownerEmail = memberRepository.findByIdAndDeletedFalse(share.ownerMemberId)?.email,
                        permission = share.permission,
                        sharedAt = (share.createDt ?: share.createdAt)?.toString(),
                    )
                )
            }
        }.sortedByDescending { it.sharedAt ?: "" }

        return ScheduleShareInboxDto(
            // 링크 초대는 대상 사용자가 정해져 있지 않다.
            // 따라서 현재 링크 기반 모델에서는 "받은 대기 초대"를 서버가 inbox에 넣을 수 없다.
            // 향후 앱 ID/이메일 대상 초대를 추가하면 이 리스트에 채우면 된다.
            pendingInvitations = emptyList(),
            receivedShares = receivedShares,
        )
    }

    @Transactional
    fun getShareOutbox(ownerMemberId: Long): ScheduleShareOutboxDto {
        val now = Instant.now(clock)
        val scheduleShares = scheduleShareRepository
            .findAllByOwnerMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
                ownerMemberId = ownerMemberId,
                status = ScheduleShareStatus.ACTIVE,
            )
        val categoryShares = categoryShareRepository
            .findAllByOwnerMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
                ownerMemberId = ownerMemberId,
                status = ScheduleShareStatus.ACTIVE,
            )
        val invitations = invitationRepository
            .findAllByOwnerMemberIdAndDeletedFalseOrderByIdDesc(ownerMemberId)
            .filter { it.effectiveStatus(now) == ScheduleShareInvitationStatus.PENDING }

        val scheduleResources = scheduleResources(
            scheduleShares.map { it.scheduleId } +
                invitations.filter { it.resourceType == ScheduleShareResourceType.SCHEDULE }.map { it.resourceId }
        )
        val categoryResources = categoryResources(
            categoryShares.map { it.categoryId } +
                invitations.filter { it.resourceType == ScheduleShareResourceType.CATEGORY }.map { it.resourceId }
        )

        val sharedResources = buildList {
            scheduleShares.groupBy { it.scheduleId }.forEach { (scheduleId, shares) ->
                val resource = scheduleResources[scheduleId]
                add(
                    ScheduleShareOutboxItemDto(
                        resourceType = ScheduleShareResourceType.SCHEDULE,
                        resourceId = scheduleId.toString(),
                        title = resource?.title ?: "삭제된 일정",
                        color = resource?.color,
                        shareCount = shares.size,
                        shares = shares
                            .sortedBy { requireNotNull(it.id) }
                            .map { share ->
                                share.toDto(targetEmail = memberRepository.findByIdAndDeletedFalse(share.targetMemberId)?.email)
                            },
                    )
                )
            }
            categoryShares.groupBy { it.categoryId }.forEach { (categoryId, shares) ->
                val resource = categoryResources[categoryId]
                add(
                    ScheduleShareOutboxItemDto(
                        resourceType = ScheduleShareResourceType.CATEGORY,
                        resourceId = categoryId.toString(),
                        title = resource?.title ?: "삭제된 카테고리",
                        color = resource?.color,
                        shareCount = shares.size,
                        shares = shares
                            .sortedBy { requireNotNull(it.id) }
                            .map { share ->
                                share.toDto(targetEmail = memberRepository.findByIdAndDeletedFalse(share.targetMemberId)?.email)
                            },
                    )
                )
            }
        }.sortedWith(compareBy<ScheduleShareOutboxItemDto> { it.resourceType.name }.thenBy { it.title })

        val activeInvitations = invitations.map { invitation ->
            val resource = when (invitation.resourceType) {
                ScheduleShareResourceType.SCHEDULE -> scheduleResources[invitation.resourceId]
                ScheduleShareResourceType.CATEGORY -> categoryResources[invitation.resourceId]
            }
            ScheduleShareInvitationSummaryDto(
                id = requireNotNull(invitation.id).toString(),
                resourceType = invitation.resourceType,
                resourceId = invitation.resourceId.toString(),
                title = resource?.title ?: when (invitation.resourceType) {
                    ScheduleShareResourceType.SCHEDULE -> "삭제된 일정"
                    ScheduleShareResourceType.CATEGORY -> "삭제된 카테고리"
                },
                color = resource?.color,
                permission = invitation.permission,
                status = invitation.status,
                expiresAt = invitation.expiresAt.toString(),
                maxAcceptCount = invitation.maxAcceptCount,
                acceptedCount = invitation.acceptedCount,
            )
        }

        return ScheduleShareOutboxDto(
            sharedResources = sharedResources,
            activeInvitations = activeInvitations,
        )
    }

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
        targetAppId: Long? = null,
        permission: ScheduleSharePermission,
    ): ScheduleShareDto {
        val normalizedPermission = validateGrantablePermission(permission)
        val target = findTargetMember(targetEmail, targetAppId)

        val schedule = scheduleRepository.findOwnedActiveForShareUpdate(scheduleId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)

        val grant = grantScheduleShare(
            ownerMemberId = ownerMemberId,
            scheduleId = scheduleId,
            targetMember = target,
            permission = normalizedPermission,
        )

        publishShareGrantedIfNeeded(
            grant = grant,
            resourceType = ScheduleShareResourceType.SCHEDULE,
            resourceId = scheduleId,
            resourceTitle = schedule.title,
        )
        return grant.share
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
        targetAppId: Long? = null,
        permission: ScheduleSharePermission,
    ): ScheduleShareDto {
        val normalizedPermission = validateGrantablePermission(permission)
        val target = findTargetMember(targetEmail, targetAppId)

        val category = categoryRepository.findOwnedActiveForShareUpdate(categoryId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)

        val grant = grantCategoryShare(
            ownerMemberId = ownerMemberId,
            categoryId = categoryId,
            targetMember = target,
            permission = normalizedPermission,
        )

        publishShareGrantedIfNeeded(
            grant = grant,
            resourceType = ScheduleShareResourceType.CATEGORY,
            resourceId = categoryId,
            resourceTitle = category.title,
        )
        return grant.share
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
        val effectiveAt = Instant.now(clock)

        return invitationRepository
            .findAllByOwnerMemberIdAndResourceTypeAndResourceIdAndDeletedFalseOrderByIdDesc(
                ownerMemberId = ownerMemberId,
                resourceType = ScheduleShareResourceType.SCHEDULE,
                resourceId = scheduleId,
            )
            .map { it.toDto(effectiveAt = effectiveAt) }
    }

    @Transactional
    fun getCategoryInvitations(ownerMemberId: Long, categoryId: Long): List<ScheduleShareInvitationDto> {
        categoryRepository.findByIdAndMemberIdAndDeletedFalse(categoryId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
        val effectiveAt = Instant.now(clock)

        return invitationRepository
            .findAllByOwnerMemberIdAndResourceTypeAndResourceIdAndDeletedFalseOrderByIdDesc(
                ownerMemberId = ownerMemberId,
                resourceType = ScheduleShareResourceType.CATEGORY,
                resourceId = categoryId,
            )
            .map { it.toDto(effectiveAt = effectiveAt) }
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
                ).share
            }
            ScheduleShareResourceType.CATEGORY -> {
                categoryRepository.findByIdAndMemberIdAndDeletedFalse(invitation.resourceId, invitation.ownerMemberId)
                    ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
                grantCategoryShare(
                    ownerMemberId = invitation.ownerMemberId,
                    categoryId = invitation.resourceId,
                    targetMember = targetMember,
                    permission = invitation.permission,
                ).share
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

    private fun findTargetMember(targetEmail: String?, targetAppId: Long?): Member {
        val hasEmail = !targetEmail.isNullOrBlank()
        val hasAppId = targetAppId != null

        // 두 식별자가 함께 들어오면 어느 값을 신뢰해야 하는지 모호하고, 둘 다 없으면
        // 공유 대상 자체를 결정할 수 없다. API 경계에서 정확히 하나만 허용한다.
        if (hasEmail == hasAppId) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "Provide exactly one of targetEmail or targetAppId.",
            )
        }

        if (hasAppId) {
            val normalizedAppId = targetAppId
                ?.takeIf { it > 0L }
                ?: throw BusinessException(ErrorCode.INVALID_INPUT, "targetAppId must be a positive number.")
            return memberRepository.findByIdAndDeletedFalse(normalizedAppId)
                ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        }

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
    ): ShareGrantResult {
        val targetMemberId = requireNotNull(targetMember.id)
        rejectSelfShare(ownerMemberId, targetMemberId)

        val existing = scheduleShareRepository.findByScheduleIdAndTargetMemberId(
            scheduleId = scheduleId,
            targetMemberId = targetMemberId,
        )
        val newlyActivated = existing?.status != ScheduleShareStatus.ACTIVE
        val share = existing?.apply {
            activate(permission)
        } ?: ScheduleShare(
            scheduleId = scheduleId,
            ownerMemberId = ownerMemberId,
            targetMemberId = targetMemberId,
            permission = permission,
            status = ScheduleShareStatus.ACTIVE,
        )

        return ShareGrantResult(
            share = scheduleShareRepository.saveAndFlush(share)
                .toDto(targetEmail = targetMember.email),
            newlyActivated = newlyActivated,
        )
    }

    private fun grantCategoryShare(
        ownerMemberId: Long,
        categoryId: Long,
        targetMember: Member,
        permission: ScheduleSharePermission,
    ): ShareGrantResult {
        val targetMemberId = requireNotNull(targetMember.id)
        rejectSelfShare(ownerMemberId, targetMemberId)

        val existing = categoryShareRepository.findByCategoryIdAndTargetMemberId(
            categoryId = categoryId,
            targetMemberId = targetMemberId,
        )
        val newlyActivated = existing?.status != ScheduleShareStatus.ACTIVE
        val share = existing?.apply {
            activate(permission)
        } ?: ScheduleCategoryShare(
            categoryId = categoryId,
            ownerMemberId = ownerMemberId,
            targetMemberId = targetMemberId,
            permission = permission,
            status = ScheduleShareStatus.ACTIVE,
        )

        return ShareGrantResult(
            share = categoryShareRepository.saveAndFlush(share)
                .toDto(targetEmail = targetMember.email),
            newlyActivated = newlyActivated,
        )
    }

    /**
     * 공유 저장은 아직 현재 트랜잭션 안에 있다. 여기서는 도메인 이벤트만 발행하고,
     * 실제 외부 푸시 호출은 AFTER_COMMIT 리스너가 담당한다. 따라서 이후 DB 작업이
     * 실패해 롤백되면 수신자는 존재하지 않는 공유 알림을 받지 않는다.
     */
    private fun publishShareGrantedIfNeeded(
        grant: ShareGrantResult,
        resourceType: ScheduleShareResourceType,
        resourceId: Long,
        resourceTitle: String,
    ) {
        if (!grant.newlyActivated) return

        eventPublisher.publishEvent(
            ScheduleShareGrantedEvent(
                targetMemberId = grant.share.targetMemberId,
                resourceType = resourceType,
                resourceId = resourceId,
                resourceTitle = resourceTitle,
            )
        )
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
        if (invitation.effectiveStatus(now) == ScheduleShareInvitationStatus.EXPIRED) {
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

    private fun scheduleResources(scheduleIds: Collection<Long>): Map<Long, ShareResourceView> {
        val uniqueIds = scheduleIds.distinct()
        if (uniqueIds.isEmpty()) return emptyMap()

        return scheduleRepository.findAllById(uniqueIds)
            .filter { !it.deleted }
            .associate { schedule ->
                val scheduleId = requireNotNull(schedule.id)
                scheduleId to ShareResourceView(
                    title = schedule.title,
                    color = schedule.categorySnapshot?.color,
                )
            }
    }

    private fun categoryResources(categoryIds: Collection<Long>): Map<Long, ShareResourceView> {
        val uniqueIds = categoryIds.distinct()
        if (uniqueIds.isEmpty()) return emptyMap()

        return categoryRepository.findAllById(uniqueIds)
            .filter { !it.deleted }
            .associate { category ->
                val categoryId = requireNotNull(category.id)
                categoryId to ShareResourceView(
                    title = category.title,
                    color = category.color,
                )
            }
    }

    companion object {
        private const val DEFAULT_INVITATION_TTL_HOURS = 24L * 7L
        private const val MAX_INVITATION_TTL_HOURS = 24L * 30L
        private const val DEFAULT_MAX_ACCEPT_COUNT = 1
        private const val MAX_ACCEPT_COUNT = 20
    }
}

private data class ShareResourceView(
    val title: String,
    val color: String?,
)

private data class ShareGrantResult(
    val share: ScheduleShareDto,
    val newlyActivated: Boolean,
)
