package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
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
import org.springframework.context.ApplicationEventPublisher
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
) {

    @Transactional
    fun createCalendar(
        ownerMemberId: Long,
        title: String?,
        color: String?,
        defaultContentMode: ScheduleShareContentMode?,
    ): ScheduleCalendarDto {
        memberRepository.findByIdAndDeletedFalse(ownerMemberId)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)

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
        val memberships = calendarMemberRepository
            .findAllByMemberIdAndStatusAndDeletedFalseOrderByIdAsc(memberId)
            .associateBy { it.calendarId }
        if (memberships.isEmpty()) return emptyList()

        return calendarRepository.findAllVisibleByMemberId(memberId).mapNotNull { calendar ->
            val membership = calendar.id?.let(memberships::get) ?: return@mapNotNull null
            calendar.toDto(
                membership = membership,
                memberCount = calendarMemberRepository
                    .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(requireNotNull(calendar.id))
                    .size,
            )
        }
    }

    @Transactional(readOnly = true)
    fun getCalendar(memberId: Long, calendarId: Long): ScheduleCalendarDto {
        val calendar = findActiveCalendar(calendarId)
        val membership = findActiveMembership(calendarId, memberId)
        return calendar.toDto(
            membership,
            calendarMemberRepository.findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId).size,
        )
    }

    @Transactional
    fun updateCalendar(
        ownerMemberId: Long,
        calendarId: Long,
        title: String?,
        color: String?,
        defaultContentMode: ScheduleShareContentMode?,
    ): ScheduleCalendarDto {
        val calendar = lockOwnedCalendar(calendarId, ownerMemberId)
        val ownerMembership = findActiveMembership(calendarId, ownerMemberId)
        val previousContentMode = calendar.defaultContentMode
        calendar.updateSettings(
            title = title?.let(::normalizeTitle) ?: calendar.title,
            color = color?.let(::normalizeColor) ?: calendar.color,
            defaultContentMode = defaultContentMode ?: calendar.defaultContentMode,
        )
        calendarRepository.saveAndFlush(calendar)
        if (
            previousContentMode == ScheduleShareContentMode.SCHEDULE_AND_TRAVEL &&
            calendar.defaultContentMode == ScheduleShareContentMode.SCHEDULE_ONLY
        ) {
            travelAccessCleanupService?.cancelRevokedForCalendar(
                calendarId,
                calendarMemberRepository
                    .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
                    .map { it.memberId },
            )
        }
        return calendar.toDto(
            ownerMembership,
            calendarMemberRepository.findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId).size,
        )
    }

    @Transactional(readOnly = true)
    fun getMembers(memberId: Long, calendarId: Long): List<ScheduleCalendarMemberDto> {
        findActiveCalendar(calendarId)
        findActiveMembership(calendarId, memberId)
        val memberships = calendarMemberRepository
            .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
        val members = memberRepository.findAllById(memberships.map { it.memberId })
            .associateBy { requireNotNull(it.id) }
        return memberships.map { it.toDto(members[it.memberId]) }
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
    ): ScheduleCalendarMemberDto {
        lockOwnedCalendar(calendarId, ownerMemberId)
        val target = findTargetMember(targetEmail, targetAppId)
        val targetMemberId = requireNotNull(target.id)
        if (targetMemberId == ownerMemberId) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "캘린더 소유자는 다시 초대할 수 없습니다.")
        }
        validateGrantableRole(role)

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
        }
        return saved.toDto(target)
    }

    @Transactional
    fun updateMember(
        ownerMemberId: Long,
        calendarId: Long,
        targetMemberId: Long,
        role: ScheduleCalendarRole?,
    ): ScheduleCalendarMemberDto {
        lockOwnedCalendar(calendarId, ownerMemberId)
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
        return saved.toDto(memberRepository.findByIdAndDeletedFalse(targetMemberId))
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
    ): ScheduleCalendarMemberDto {
        calendarRepository.findActiveForUpdate(calendarId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)
        val membership = calendarMemberRepository.findForUpdate(calendarId, memberId)
            ?.takeIf { !it.deleted && it.status == ScheduleCalendarMemberStatus.ACTIVE }
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)
        membership.updateRouteReminder(routeReminderEnabled)
        return calendarMemberRepository.saveAndFlush(membership)
            .toDto(memberRepository.findByIdAndDeletedFalse(memberId))
    }

    @Transactional
    fun removeMember(ownerMemberId: Long, calendarId: Long, targetMemberId: Long) {
        lockOwnedCalendar(calendarId, ownerMemberId)
        val membership = calendarMemberRepository.findForUpdate(calendarId, targetMemberId)
            ?.takeIf { !it.deleted && it.status == ScheduleCalendarMemberStatus.ACTIVE }
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_MEMBER_NOT_FOUND)
        if (membership.role == ScheduleCalendarRole.OWNER) {
            throw BusinessException(ErrorCode.INVALID_STATE, "캘린더 소유자는 제거할 수 없습니다.")
        }
        membership.remove()
        calendarMemberRepository.saveAndFlush(membership)
        travelAccessCleanupService?.cancelRevokedForCalendar(calendarId, listOf(targetMemberId))
    }

    @Transactional
    fun leaveCalendar(memberId: Long, calendarId: Long) {
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
        travelAccessCleanupService?.cancelRevokedForCalendar(calendarId, listOf(memberId))
    }

    /**
     * 캘린더 잠금 뒤 두 membership row를 member id 오름차순으로 잠근다. 이 순서는 반대 방향의
     * 동시 소유권 이전에서도 DB 교착을 피하기 위한 계약이므로 다른 서비스에서도 유지해야 한다.
     */
    @Transactional
    fun transferOwnership(ownerMemberId: Long, calendarId: Long, targetMemberId: Long): ScheduleCalendarDto {
        val calendar = lockOwnedCalendar(calendarId, ownerMemberId)
        val pendingInvitations = lockPendingCalendarInvitations(calendarId)
        if (targetMemberId == ownerMemberId) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "현재 소유자에게 소유권을 이전할 수 없습니다.")
        }

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

        return calendar.toDto(
            membership = currentOwner,
            memberCount = calendarMemberRepository
                .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
                .size,
        )
    }

    @Transactional
    fun archiveCalendar(ownerMemberId: Long, calendarId: Long) {
        val calendar = lockOwnedCalendar(calendarId, ownerMemberId)
        val pendingInvitations = lockPendingCalendarInvitations(calendarId)
        val affectedMemberIds = calendarMemberRepository
            .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
            .map { it.memberId }
        revokePendingCalendarInvitations(pendingInvitations)
        calendar.archive()
        calendarRepository.saveAndFlush(calendar)
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

    private fun findActiveCalendar(calendarId: Long): ScheduleCalendar =
        calendarRepository.findByIdAndStatusAndDeletedFalse(calendarId, ScheduleCalendarStatus.ACTIVE)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)

    private fun findActiveMembership(calendarId: Long, memberId: Long): ScheduleCalendarMember =
        calendarMemberRepository.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(
            calendarId,
            memberId,
            ScheduleCalendarMemberStatus.ACTIVE,
        ) ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)

    private fun findTargetMember(targetEmail: String?, targetAppId: Long?): Member {
        val normalizedEmail = targetEmail?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        val hasEmail = normalizedEmail != null
        val hasAppId = targetAppId != null
        if (hasEmail == hasAppId) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "targetEmail과 targetAppId 중 하나만 입력해야 합니다.")
        }
        return if (hasAppId) {
            val id = targetAppId?.takeIf { it > 0L }
                ?: throw BusinessException(ErrorCode.INVALID_INPUT, "targetAppId는 양수여야 합니다.")
            memberRepository.findByIdAndDeletedFalse(id)
        } else {
            memberRepository.findByEmailAndDeletedFalse(requireNotNull(normalizedEmail))
        } ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
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
