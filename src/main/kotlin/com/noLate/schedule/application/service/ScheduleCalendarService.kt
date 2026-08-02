package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.application.cache.ScheduleCalendarCacheInvalidationEvent
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarDto
import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarMemberDto
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCalendarStatus
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleShareInvitation
import com.noLate.schedule.domain.ScheduleShareInvitationStatus
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.sharing.application.SharingBlockPolicy
import com.noLate.sharing.application.SharingMutationRateLimiter
import com.noLate.sharing.application.SharingMutationScope
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.CannotAcquireLockException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ScheduleCalendarService(
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val memberRepository: MemberRepository,
    private val invitationRepository: ScheduleShareInvitationRepository? = null,
    private val eventPublisher: ApplicationEventPublisher = ApplicationEventPublisher { _ -> },
    private val travelAccessCleanupService: ScheduleTravelAccessCleanupService? = null,
    private val mutationFenceObserver: ScheduleCalendarMutationFenceObserver? = null,
    private val sharingAvailabilityPolicy: ScheduleSharingAvailabilityPolicy,
    private val sharingBlockPolicy: SharingBlockPolicy? = null,
    private val sharingMutationRateLimiter: SharingMutationRateLimiter? = null,
) {

    @Transactional
    fun createCalendar(
        ownerMemberId: Long,
        title: String?,
        color: String?,
        defaultContentMode: ScheduleShareContentMode?,
        presentedSessionGeneration: Long,
    ): ScheduleCalendarDto {
        sharingAvailabilityPolicy.requireEnabled()
        lockCalendarMembers(
            memberIds = listOf(ownerMemberId),
            actorMemberId = ownerMemberId,
            presentedSessionGeneration = presentedSessionGeneration,
        )

        val calendar = calendarRepository.saveAndFlush(
            ScheduleCalendar(
                ownerMemberId = ownerMemberId,
                title = normalizeTitle(title),
                color = normalizeColor(color),
                defaultContentMode = defaultContentMode ?: ScheduleShareContentMode.SCHEDULE_ONLY,
            )
        )
        val calendarId = requireNotNull(calendar.id)
        val ownerMembership = calendarMemberRepository.saveAndFlush(
            ScheduleCalendarMember(
                calendarId = calendarId,
                memberId = ownerMemberId,
                role = ScheduleCalendarRole.OWNER,
            )
        )
        return calendar.toDto(ownerMembership, memberCount = 1)
    }

    @Transactional(readOnly = true)
    fun getCalendars(memberId: Long): List<ScheduleCalendarDto> {
        sharingAvailabilityPolicy.requireEnabled()
        val memberships = calendarMemberRepository
            .findAllByMemberIdAndStatusAndDeletedFalseOrderByIdAsc(memberId)
            .associateBy { it.calendarId }
        if (memberships.isEmpty()) return emptyList()

        return calendarRepository.findAllVisibleByMemberId(memberId).mapNotNull { calendar ->
            if (sharingBlockPolicy?.isInteractionBlocked(memberId, calendar.ownerMemberId) == true) {
                return@mapNotNull null
            }
            val membership = calendar.id?.let(memberships::get) ?: return@mapNotNull null
            calendar.toDto(
                membership = membership,
                memberCount = visibleMemberCount(memberId, requireNotNull(calendar.id)),
            )
        }
    }

    @Transactional(readOnly = true)
    fun getCalendar(memberId: Long, calendarId: Long): ScheduleCalendarDto {
        sharingAvailabilityPolicy.requireEnabled()
        val calendar = findActiveCalendar(calendarId)
        sharingBlockPolicy?.requireInteractionAllowed(memberId, calendar.ownerMemberId)
        val membership = findActiveMembership(calendarId, memberId)
        return calendar.toDto(
            membership,
            visibleMemberCount(memberId, calendarId),
        )
    }

    @Transactional
    fun updateCalendar(
        ownerMemberId: Long,
        calendarId: Long,
        title: String?,
        color: String?,
        defaultContentMode: ScheduleShareContentMode?,
        presentedSessionGeneration: Long,
    ): ScheduleCalendarDto {
        sharingAvailabilityPolicy.requireEnabled()
        val previewMemberIds = calendarMemberRepository
            .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
            .map { it.memberId }
        mutationFenceObserver?.afterMembershipPreview(calendarId)
        val lockedMembers = lockCalendarMembers(
            memberIds = previewMemberIds + ownerMemberId,
            actorMemberId = ownerMemberId,
            presentedSessionGeneration = presentedSessionGeneration,
        )
        val calendar = lockOwnedCalendar(calendarId, ownerMemberId)
        val affectedMemberIds = requireFrozenCalendarMemberFence(
            calendarId,
            lockedMembers.keys,
        )
        val ownerMembership = findActiveMembership(calendarId, ownerMemberId)
        val previousContentMode = calendar.defaultContentMode
        calendar.updateSettings(
            title = title?.let(::normalizeTitle) ?: calendar.title,
            color = color?.let(::normalizeColor) ?: calendar.color,
            defaultContentMode = defaultContentMode ?: calendar.defaultContentMode,
        )
        calendarRepository.saveAndFlush(calendar)
        publishCalendarCacheInvalidation(affectedMemberIds, "calendar-settings-updated")
        if (
            previousContentMode == ScheduleShareContentMode.SCHEDULE_AND_TRAVEL &&
            calendar.defaultContentMode == ScheduleShareContentMode.SCHEDULE_ONLY
        ) {
            travelAccessCleanupService?.cancelRevokedForCalendar(
                calendarId,
                affectedMemberIds,
            )
        }
        return calendar.toDto(
            ownerMembership,
            calendarMemberRepository.findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId).size,
        )
    }

    @Transactional(readOnly = true)
    fun getMembers(memberId: Long, calendarId: Long): List<ScheduleCalendarMemberDto> {
        sharingAvailabilityPolicy.requireEnabled()
        val calendar = findActiveCalendar(calendarId)
        sharingBlockPolicy?.requireInteractionAllowed(memberId, calendar.ownerMemberId)
        findActiveMembership(calendarId, memberId)
        val memberships = calendarMemberRepository
            .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
            .filterNot {
                sharingBlockPolicy?.isInteractionBlocked(memberId, it.memberId) == true
            }
        val members = memberRepository.findAllById(memberships.map { it.memberId })
            .associateBy { requireNotNull(it.id) }
        return memberships.map { it.toDto(members[it.memberId]) }
    }

    private fun visibleMemberCount(memberId: Long, calendarId: Long): Int {
        val memberIds = calendarMemberRepository
            .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
            .map { it.memberId }
        val blockedMemberIds = sharingBlockPolicy
            ?.blockedCounterpartIds(memberId, memberIds)
            .orEmpty()
        return memberIds.count { it !in blockedMemberIds }
    }

    /**
     * 캘린더 row가 이 캘린더의 모든 멤버 변경에 대한 직렬화 지점이다. 같은 대상을 이메일과
     * 앱 ID로 동시에 추가해도 한 트랜잭션만 기존 멤버 row를 생성/재활성화할 수 있고, DB의
     * `(calendar_id, member_id)` 유일키가 마지막 방어선이 된다.
     */
    @Transactional
    fun addMember(
        ownerMemberId: Long,
        calendarId: Long,
        targetEmail: String?,
        targetAppId: Long?,
        role: ScheduleCalendarRole,
        authenticatedActorMemberId: Long,
        presentedSessionGeneration: Long,
    ): ScheduleCalendarMemberDto {
        sharingAvailabilityPolicy.requireEnabled()
        if (authenticatedActorMemberId == ownerMemberId) {
            sharingMutationRateLimiter?.requirePermit(
                ownerMemberId,
                SharingMutationScope.DIRECT_SHARE,
            )
        }
        // 잠금 전에 target 엔티티를 persistence context에 올리면 lock 대기 중 withdrawal이
        // commit된 뒤에도 stale deleted=false 상태를 재사용할 수 있다. ID만 해석한 뒤
        // actor와 recipient를 함께 정렬 잠금하고, 잠긴 fresh Member로 상태를 검증한다.
        val targetMemberId = resolveTargetMemberId(targetEmail, targetAppId)
        mutationFenceObserver?.afterMembershipPreview(calendarId)
        val lockedMembers = lockCalendarMembers(
            memberIds = listOf(ownerMemberId, targetMemberId, authenticatedActorMemberId),
            actorMemberId = authenticatedActorMemberId,
            presentedSessionGeneration = presentedSessionGeneration,
        )
        val activeTarget = lockedMembers[targetMemberId]
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        lockOwnedCalendar(calendarId, ownerMemberId)
        if (targetMemberId == ownerMemberId) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "캘린더 소유자는 다시 초대할 수 없습니다.")
        }
        validateGrantableRole(role)
        sharingBlockPolicy?.requireInteractionAllowed(ownerMemberId, targetMemberId)

        val existing = calendarMemberRepository.findForUpdate(calendarId, targetMemberId)
        val newlyActivated = existing?.status != ScheduleCalendarMemberStatus.ACTIVE || existing.deleted
        val membership = existing
            ?.apply { activate(role) }
            ?: ScheduleCalendarMember(
                calendarId = calendarId,
                memberId = targetMemberId,
                role = role,
            )
        val saved = calendarMemberRepository.saveAndFlush(membership)
        if (newlyActivated) {
            val calendar = calendarRepository.findByIdAndStatusAndDeletedFalse(calendarId)
                ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)
            eventPublisher.publishEvent(
                ScheduleShareGrantedEvent(
                    targetMemberId = targetMemberId,
                    resourceType = ScheduleShareResourceType.CALENDAR,
                    resourceId = calendarId,
                    resourceTitle = calendar.title,
                )
            )
        } else {
            publishCalendarCacheInvalidation(listOf(targetMemberId), "calendar-member-role-updated")
        }
        return saved.toDto(activeTarget)
    }

    @Transactional
    fun updateMember(
        ownerMemberId: Long,
        calendarId: Long,
        targetMemberId: Long,
        role: ScheduleCalendarRole?,
        presentedSessionGeneration: Long,
    ): ScheduleCalendarMemberDto {
        sharingAvailabilityPolicy.requireEnabled()
        val lockedMembers = lockCalendarMembers(
            memberIds = listOf(ownerMemberId, targetMemberId),
            actorMemberId = ownerMemberId,
            presentedSessionGeneration = presentedSessionGeneration,
        )
        val activeTarget = lockedMembers[targetMemberId]
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        lockOwnedCalendar(calendarId, ownerMemberId)
        sharingBlockPolicy?.requireInteractionAllowed(ownerMemberId, targetMemberId)
        val membership = calendarMemberRepository.findForUpdate(calendarId, targetMemberId)
            ?.takeIf { !it.deleted && it.status == ScheduleCalendarMemberStatus.ACTIVE }
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_MEMBER_NOT_FOUND)
        if (membership.role == ScheduleCalendarRole.OWNER && role != null && role != ScheduleCalendarRole.OWNER) {
            throw BusinessException(ErrorCode.INVALID_STATE, "소유권 이전 API로 먼저 새 소유자를 지정해야 합니다.")
        }
        role?.let {
            validateGrantableRole(it)
            membership.changeRole(it)
        }
        val saved = calendarMemberRepository.saveAndFlush(membership)
        publishCalendarCacheInvalidation(listOf(targetMemberId), "calendar-member-role-updated")
        return saved.toDto(activeTarget)
    }

    /**
     * 역할 변경은 오너 권한이지만, D-3 경로 알림 수신 여부는 각 회원의 개인 설정이다.
     * 별도 API로 분리해 일반 멤버가 자신의 role까지 함께 올리는 mass-assignment를 막는다.
     */
    @Transactional
    fun updateMyPreferences(
        memberId: Long,
        calendarId: Long,
        routeReminderEnabled: Boolean,
        presentedSessionGeneration: Long,
    ): ScheduleCalendarMemberDto {
        sharingAvailabilityPolicy.requireEnabled()
        val lockedMembers = lockCalendarMembers(
            memberIds = listOf(memberId),
            actorMemberId = memberId,
            presentedSessionGeneration = presentedSessionGeneration,
        )
        calendarRepository.findActiveForUpdate(calendarId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)
        val membership = calendarMemberRepository.findForUpdate(calendarId, memberId)
            ?.takeIf { !it.deleted && it.status == ScheduleCalendarMemberStatus.ACTIVE }
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)
        membership.updateRouteReminder(routeReminderEnabled)
        return calendarMemberRepository.saveAndFlush(membership)
            .toDto(lockedMembers[memberId])
    }

    @Transactional
    fun removeMember(
        ownerMemberId: Long,
        calendarId: Long,
        targetMemberId: Long,
        presentedSessionGeneration: Long,
    ) {
        sharingAvailabilityPolicy.requireEnabled()
        lockCalendarMembers(
            memberIds = listOf(ownerMemberId, targetMemberId),
            actorMemberId = ownerMemberId,
            presentedSessionGeneration = presentedSessionGeneration,
        ).takeIf { targetMemberId in it }
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        lockOwnedCalendar(calendarId, ownerMemberId)
        val membership = calendarMemberRepository.findForUpdate(calendarId, targetMemberId)
            ?.takeIf { !it.deleted && it.status == ScheduleCalendarMemberStatus.ACTIVE }
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_MEMBER_NOT_FOUND)
        if (membership.role == ScheduleCalendarRole.OWNER) {
            throw BusinessException(ErrorCode.INVALID_STATE, "캘린더 소유자는 제거할 수 없습니다.")
        }
        membership.remove()
        calendarMemberRepository.saveAndFlush(membership)
        publishCalendarCacheInvalidation(listOf(targetMemberId), "calendar-member-removed")
        travelAccessCleanupService?.cancelRevokedForCalendar(calendarId, listOf(targetMemberId))
    }

    @Transactional
    fun leaveCalendar(
        memberId: Long,
        calendarId: Long,
        presentedSessionGeneration: Long,
    ) {
        sharingAvailabilityPolicy.requireEnabled()
        lockCalendarMembers(
            memberIds = listOf(memberId),
            actorMemberId = memberId,
            presentedSessionGeneration = presentedSessionGeneration,
        )
        val calendar = calendarRepository.findActiveForUpdate(calendarId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)
        val membership = calendarMemberRepository.findForUpdate(calendarId, memberId)
            ?.takeIf { !it.deleted && it.status == ScheduleCalendarMemberStatus.ACTIVE }
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_MEMBER_NOT_FOUND)
        if (calendar.ownerMemberId == memberId || membership.role == ScheduleCalendarRole.OWNER) {
            throw BusinessException(ErrorCode.INVALID_STATE, "소유권을 이전한 뒤 캘린더에서 나갈 수 있습니다.")
        }
        membership.leave()
        calendarMemberRepository.saveAndFlush(membership)
        publishCalendarCacheInvalidation(listOf(memberId), "calendar-member-left")
        travelAccessCleanupService?.cancelRevokedForCalendar(calendarId, listOf(memberId))
    }

    /**
     * actor/target member를 id 오름차순으로 먼저 잠근 뒤 calendar와 membership으로 진행한다.
     * 이 순서는 반대 방향의 동시 소유권 이전과 withdrawal에서도 DB 교착을 피하기 위한
     * 계약이므로 다른 서비스에서도 유지해야 한다.
     */
    @Transactional
    fun transferOwnership(
        ownerMemberId: Long,
        calendarId: Long,
        targetMemberId: Long,
        presentedSessionGeneration: Long,
    ): ScheduleCalendarDto {
        sharingAvailabilityPolicy.requireEnabled()
        lockCalendarMembers(
            memberIds = listOf(ownerMemberId, targetMemberId),
            actorMemberId = ownerMemberId,
            presentedSessionGeneration = presentedSessionGeneration,
        ).takeIf { targetMemberId in it }
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        val calendar = lockOwnedCalendar(calendarId, ownerMemberId)
        val pendingInvitations = lockPendingCalendarInvitations(calendarId)
        if (targetMemberId == ownerMemberId) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "현재 소유자에게 소유권을 이전할 수 없습니다.")
        }
        sharingBlockPolicy?.requireInteractionAllowed(ownerMemberId, targetMemberId)

        val locked = calendarMemberRepository
            .findAllForUpdate(calendarId, listOf(ownerMemberId, targetMemberId).sorted())
            .associateBy { it.memberId }
        val currentOwner = locked[ownerMemberId]
            ?.takeIf { !it.deleted && it.status == ScheduleCalendarMemberStatus.ACTIVE }
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_MEMBER_NOT_FOUND)
        val nextOwner = locked[targetMemberId]
            ?.takeIf { !it.deleted && it.status == ScheduleCalendarMemberStatus.ACTIVE }
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_MEMBER_NOT_FOUND)

        currentOwner.changeRole(ScheduleCalendarRole.EDITOR)
        nextOwner.changeRole(ScheduleCalendarRole.OWNER)
        calendar.transferOwnership(targetMemberId)
        calendarMemberRepository.saveAll(listOf(currentOwner, nextOwner))
        calendarMemberRepository.flush()
        calendarRepository.saveAndFlush(calendar)
        revokePendingCalendarInvitations(pendingInvitations)
        publishCalendarCacheInvalidation(
            listOf(ownerMemberId, targetMemberId),
            "calendar-ownership-transferred",
        )

        return calendar.toDto(
            membership = currentOwner,
            memberCount = calendarMemberRepository
                .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
                .size,
        )
    }

    @Transactional
    fun archiveCalendar(
        ownerMemberId: Long,
        calendarId: Long,
        presentedSessionGeneration: Long,
    ) {
        sharingAvailabilityPolicy.requireEnabled()
        val previewMemberIds = calendarMemberRepository
            .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
            .map { it.memberId }
        mutationFenceObserver?.afterMembershipPreview(calendarId)
        val lockedMembers = lockCalendarMembers(
            memberIds = previewMemberIds + ownerMemberId,
            actorMemberId = ownerMemberId,
            presentedSessionGeneration = presentedSessionGeneration,
        )
        val calendar = lockOwnedCalendar(calendarId, ownerMemberId)
        val affectedMemberIds = requireFrozenCalendarMemberFence(
            calendarId,
            lockedMembers.keys,
        )
        val pendingInvitations = lockPendingCalendarInvitations(calendarId)
        revokePendingCalendarInvitations(pendingInvitations)
        calendar.archive()
        calendarRepository.saveAndFlush(calendar)
        publishCalendarCacheInvalidation(affectedMemberIds, "calendar-archived")
        travelAccessCleanupService?.cancelRevokedForCalendar(calendarId, affectedMemberIds)
    }

    private fun lockOwnedCalendar(calendarId: Long, ownerMemberId: Long): ScheduleCalendar {
        val calendar = calendarRepository.findActiveForUpdate(calendarId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)
        if (calendar.ownerMemberId != ownerMemberId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
        return calendar
    }

    /**
     * Withdrawal 및 push-job cleanup과 동일한 전역 순서(member -> calendar -> job)를 쓴다.
     * 잠금 없는 membership preview는 대상 id 발견에만 사용하고, 권한/상태는 calendar 및
     * membership row를 잠근 뒤 다시 검증한다. actor의 signed generation도 같은 member lock
     * 안에서 검증하므로 security filter 통과 뒤 logout된 요청은 어떤 calendar write도 못 한다.
     */
    private fun lockCalendarMembers(
        memberIds: Collection<Long>,
        actorMemberId: Long,
        presentedSessionGeneration: Long,
    ): Map<Long, Member> {
        val activeMembers = memberRepository.findAllByIdsForUpdate(
            memberIds.distinct().sorted(),
        ).asSequence()
            .filterNot { it.deleted }
            .associateBy { requireNotNull(it.id) }
        val actor = activeMembers[actorMemberId]
            ?: throw BusinessException(
                ErrorCode.INVALID_TOKEN,
                "종료되었거나 존재하지 않는 로그인 세션입니다.",
            )
        if (actor.sessionGeneration != presentedSessionGeneration) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        }
        return activeMembers
    }

    /**
     * membership preview와 parent calendar lock 사이에 add/accept가 먼저 선형화되면, 새
     * participant는 이 transaction의 전역 member-id 잠금 집합에 포함되지 않았다. 그 상태로
     * travel cleanup/archive를 진행하지 않고 transient failure로 끝내 caller가 fresh preview로
     * 재시도하게 한다. 반대로 calendar lock을 이 mutation이 먼저 잡으면 후속 add가 기다리므로
     * 기존 frozen 집합으로 안전하게 선형화된다.
     */
    private fun requireFrozenCalendarMemberFence(
        calendarId: Long,
        lockedMemberIds: Set<Long>,
    ): List<Long> {
        val currentMemberIds = calendarMemberRepository
            .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
            .map { it.memberId }
        if (!lockedMemberIds.containsAll(currentMemberIds)) {
            throw CannotAcquireLockException(
                "Calendar membership changed while acquiring the member fence; retry.",
            )
        }
        return currentMemberIds
    }

    /**
     * 캘린더 row를 먼저 잠근 트랜잭션에서만 호출한다. 생성·수락·이전·보관이 모두 같은
     * 직렬화 지점을 거치므로, 이전 오너의 링크가 소유권 변경 뒤 살아남거나 보관 직전에
     * 생성된 링크가 PENDING으로 남는 간격을 닫는다.
     */
    private fun lockPendingCalendarInvitations(calendarId: Long) = invitationRepository
        ?.findAllPendingByResourceForUpdate(
            resourceType = ScheduleShareResourceType.CALENDAR,
            resourceId = calendarId,
            status = ScheduleShareInvitationStatus.PENDING,
        )
        .orEmpty()

    private fun revokePendingCalendarInvitations(invitations: List<ScheduleShareInvitation>) {
        if (invitations.isEmpty()) return
        invitations.forEach { it.revoke() }
        invitationRepository?.saveAll(invitations)
        invitationRepository?.flush()
    }

    private fun publishCalendarCacheInvalidation(memberIds: Collection<Long>, reason: String) {
        if (memberIds.isEmpty()) return
        eventPublisher.publishEvent(
            ScheduleCalendarCacheInvalidationEvent(
                memberIds = memberIds.toSet(),
                reason = reason,
            )
        )
    }

    private fun findActiveCalendar(calendarId: Long): ScheduleCalendar =
        calendarRepository.findByIdAndStatusAndDeletedFalse(calendarId, ScheduleCalendarStatus.ACTIVE)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)

    private fun findActiveMembership(calendarId: Long, memberId: Long): ScheduleCalendarMember =
        calendarMemberRepository.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(
            calendarId,
            memberId,
            ScheduleCalendarMemberStatus.ACTIVE,
        ) ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)

    private fun resolveTargetMemberId(targetEmail: String?, targetAppId: Long?): Long {
        val normalizedEmail = targetEmail?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        val hasEmail = normalizedEmail != null
        val hasAppId = targetAppId != null
        if (hasEmail == hasAppId) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "targetEmail과 targetAppId 중 하나만 입력해야 합니다.")
        }
        return if (hasAppId) {
            targetAppId?.takeIf { it > 0L }
                ?: throw BusinessException(ErrorCode.INVALID_INPUT, "targetAppId는 양수여야 합니다.")
        } else {
            memberRepository.findIdByEmailAndDeletedFalse(requireNotNull(normalizedEmail))
                ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        }
    }

    private fun validateGrantableRole(role: ScheduleCalendarRole) {
        if (role == ScheduleCalendarRole.OWNER) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "OWNER 역할은 소유권 이전으로만 부여할 수 있습니다.")
        }
    }

    private fun normalizeTitle(value: String?): String {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank() || normalized.length > 80) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "캘린더 이름은 1자 이상 80자 이하여야 합니다.")
        }
        return normalized
    }

    private fun normalizeColor(value: String?): String {
        val normalized = value?.trim()?.takeIf(String::isNotBlank) ?: "#2F80FF"
        if (!COLOR_PATTERN.matches(normalized)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "캘린더 색상은 #RRGGBB 형식이어야 합니다.")
        }
        return normalized.uppercase()
    }

    private fun ScheduleCalendar.toDto(
        membership: ScheduleCalendarMember,
        memberCount: Int,
    ): ScheduleCalendarDto = ScheduleCalendarDto(
        id = requireNotNull(id),
        title = title,
        color = color,
        defaultContentMode = defaultContentMode,
        status = status,
        ownerMemberId = ownerMemberId,
        myRole = membership.role,
        memberCount = memberCount,
        routeReminderEnabled = membership.routeReminderEnabled,
        createdAt = (createDt ?: createdAt)?.toString(),
        updatedAt = (updateDt ?: updatedAt)?.toString(),
    )

    private fun ScheduleCalendarMember.toDto(member: Member?): ScheduleCalendarMemberDto =
        ScheduleCalendarMemberDto(
            id = requireNotNull(id),
            calendarId = calendarId,
            memberId = memberId,
            name = member?.name,
            email = member?.email,
            role = role,
            status = status,
            routeReminderEnabled = routeReminderEnabled,
            joinedAt = (createDt ?: createdAt)?.toString(),
            updatedAt = (updateDt ?: updatedAt)?.toString(),
        )

    companion object {
        private val COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")
    }
}

/**
 * membership preview 뒤 동시 grant를 결정적으로 재현하는 test seam.
 * 운영 bean이 없으면 no-op이며 member/token 같은 식별자를 노출하지 않는다.
 */
fun interface ScheduleCalendarMutationFenceObserver {
    fun afterMembershipPreview(calendarId: Long)
}
