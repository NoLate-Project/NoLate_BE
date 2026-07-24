package com.noLate.member.application.service

import com.noLate.favorite.infrastructure.FavoritePlaceCategoryRepository
import com.noLate.favorite.infrastructure.FavoritePlaceRepository
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberConsentRepository
import com.noLate.member.infrastructure.MemberProfileRepository
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.member.infrastructure.MemberSettingRepository
import com.noLate.notification.application.service.NotificationTokenRetirementService
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import com.noLate.routehistory.infrastructure.RecentRoutePlaceRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleNotificationActionReceiptRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import com.noLate.schedule.application.service.ScheduleTravelAccessCleanupService
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCalendarStatus
import com.noLate.schedule.domain.ScheduleShareStatus
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class AccountWithdrawalFence(
    val member: Member,
    val ownedScheduleIds: Set<Long>,
    val lockedMemberIds: Set<Long>,
    /** 일정이 하나도 없는 category까지 포함한 owner category별 frozen share recipient. */
    val ownedCategoryShareTargets: Map<Long, Set<Long>> = emptyMap(),
)

/** 계정 경계를 넘을 수 있는 인증/기기/사용자 데이터를 한 트랜잭션에서 정리한다. */
@Service
class AccountCleanupService(
    private val memberRepository: MemberRepository,
    private val tokenRetirementService: NotificationTokenRetirementService,
    private val pushDeliveryRepository: PushDeliveryRepository,
    private val pushHistoryRepository: PushSendHistoryRepository,
    private val appNotificationRepository: AppNotificationRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val routeSetupReminderRepository: ScheduleRouteSetupReminderRepository,
    private val departureStatusRepository: ScheduleDepartureStatusRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val invitationRepository: ScheduleShareInvitationRepository,
    private val scheduleRepository: ScheduleRepository,
    private val categoryRepository: ScheduleCategoryRepository,
    private val favoriteRepository: FavoritePlaceRepository,
    private val favoriteCategoryRepository: FavoritePlaceCategoryRepository,
    private val recentRoutePlaceRepository: RecentRoutePlaceRepository,
    private val memberSettingRepository: MemberSettingRepository,
    private val memberProfileRepository: MemberProfileRepository,
    private val memberConsentRepository: MemberConsentRepository,
    private val notificationActionReceiptRepository: ScheduleNotificationActionReceiptRepository,
    private val travelAccessCleanupService: ScheduleTravelAccessCleanupService,
) {
    /**
     * Owner withdrawal and participant mutation share one member-row order. The first scan is only
     * a candidate snapshot; all owner/recipient rows are then locked by ascending ID and the scope
     * is re-read. A newly committed participant outside that frozen set aborts the transaction
     * instead of acquiring a lower member lock after schedule/source locks.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun lockWithdrawalFence(
        memberId: Long,
        presentedSessionGeneration: Long,
    ): AccountWithdrawalFence {
        val preview = snapshotOwnedNotificationScope(memberId)
        val memberIdsToLock = (preview.affectedMemberIds + memberId).toSortedSet()
        val lockedById = memberRepository.findAllByIdsForUpdate(memberIdsToLock)
            .associateBy { requireNotNull(it.id) }
        val member = lockedById[memberId]
            ?.takeUnless { it.deleted }
            ?: throw BusinessException(
                ErrorCode.INVALID_TOKEN,
                "종료되었거나 존재하지 않는 로그인 세션입니다.",
            )
        if (member.sessionGeneration != presentedSessionGeneration) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        }

        // Calendar ownership changes/archive also lock the owner member before the calendar row.
        // Therefore this read under the member lock is a stable linearization point: an ACTIVE
        // owner must transfer or archive before account withdrawal can continue.
        if (
            calendarRepository
                .findAllByOwnerMemberIdAndStatusAndDeletedFalseOrderByIdAsc(
                    memberId,
                    ScheduleCalendarStatus.ACTIVE,
                )
                .isNotEmpty()
        ) {
            throw BusinessException(
                ErrorCode.INVALID_STATE,
                "활성 공유 캘린더의 소유권을 이전하거나 캘린더를 보관한 뒤 탈퇴할 수 있습니다.",
            )
        }

        val current = snapshotOwnedNotificationScope(memberId)
        if (current.affectedMemberIds.any { it !in memberIdsToLock }) {
            throw ConcurrencyFailureException(
                "Account withdrawal participant scope changed while acquiring the member fence.",
            )
        }
        return AccountWithdrawalFence(
            member = member,
            ownedScheduleIds = preview.ownedScheduleIds + current.ownedScheduleIds,
            lockedMemberIds = memberIdsToLock,
            ownedCategoryShareTargets =
                (preview.ownedCategoryShareTargets.keys +
                    current.ownedCategoryShareTargets.keys)
                    .associateWith { categoryId ->
                        (
                            preview.ownedCategoryShareTargets[categoryId].orEmpty() +
                                current.ownedCategoryShareTargets[categoryId].orEmpty()
                            ).toSortedSet()
                    },
        )
    }

    /**
     * Compatibility entry point for internal tests and non-controller lifecycle callers. Public
     * withdrawal obtains this fence before password/session mutation through MemberUseCase.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun withdraw(member: Member) {
        val memberId = requireNotNull(member.id)
        withdraw(lockWithdrawalFence(memberId, member.sessionGeneration))
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun withdraw(fence: AccountWithdrawalFence) {
        val lockedMember = fence.member
        val memberId = requireNotNull(lockedMember.id)
        if (lockedMember.deleted) return

        // Calendar mutations use member -> calendar -> membership. Withdrawal must follow the same
        // order instead of bulk-deleting memberships after it has already touched schedule/outbox
        // rows. The member fence makes this preview stable against add/remove/leave, then calendar
        // and membership rows are locked in deterministic order and ordinary participants leave
        // with an auditable terminal state.
        leaveActiveCalendarMemberships(memberId)

        // All affected recipients are locked before schedule-bound state. Removing participant
        // rows first guarantees that deleting the owner schedule cannot leave a live job/outbox
        // that a restarted worker redrives.
        if (fence.ownedScheduleIds.isNotEmpty()) {
            notificationActionReceiptRepository.deleteAllByScheduleIdIn(fence.ownedScheduleIds)
            departureStatusRepository.deleteAllByScheduleIdIn(fence.ownedScheduleIds)
            routeSetupReminderRepository.deleteAllByScheduleIdIn(fence.ownedScheduleIds)
            travelPlanRepository.deleteAllByScheduleIdIn(fence.ownedScheduleIds)
            pushJobRepository.deleteAllByScheduleIdIn(fence.ownedScheduleIds)
            pushDeliveryRepository.deleteAllByScheduleIdIn(fence.ownedScheduleIds)
            pushHistoryRepository.deleteAllByScheduleIdIn(fence.ownedScheduleIds)
            appNotificationRepository.deleteAllByScheduleIdIn(fence.ownedScheduleIds)
        }

        // Revoke category grants before evaluating the central access policy. The frozen target
        // members were locked with the owner before any category/source row, including categories
        // with no schedules, so their CATEGORY_SHARE_RECEIVED sources can be removed safely.
        scheduleShareRepository.deleteAllByOwnerMemberIdOrTargetMemberId(memberId, memberId)
        categoryShareRepository.deleteAllByOwnerMemberIdOrTargetMemberId(memberId, memberId)
        fence.ownedCategoryShareTargets.forEach { (categoryId, targetMemberIds) ->
            travelAccessCleanupService.cancelRevokedForCategory(categoryId, targetMemberIds)
        }
        // An EDITOR may own a schedule that was filed in the withdrawing member's shared
        // category. Preserve the editor's schedule and immutable display snapshot, but detach the
        // canonical category id before the owner category is deleted. Leaving the removed id in
        // place would create an application-level orphan (the legacy schema has no category FK)
        // and could later confuse a reused/backfilled access boundary.
        val withdrawingCategoryIds = fence.ownedCategoryShareTargets.keys
        if (withdrawingCategoryIds.isNotEmpty() && fence.ownedScheduleIds.isNotEmpty()) {
            val retainedParticipantSchedules = scheduleRepository
                .findAllById(fence.ownedScheduleIds)
                .filter {
                    !it.deleted &&
                        it.memberId != memberId &&
                        it.categoryId?.let(withdrawingCategoryIds::contains) == true
                }
            retainedParticipantSchedules.forEach { it.categoryId = null }
            if (retainedParticipantSchedules.isNotEmpty()) {
                scheduleRepository.saveAllAndFlush(retainedParticipantSchedules)
            }
        }

        // 이후 전역 순서는 member -> schedule source -> immutable source ->
        // delivery/history -> device ownership이다.
        pushJobRepository.deleteAllByMemberId(memberId)
        routeSetupReminderRepository.deleteAllByMemberId(memberId)
        appNotificationRepository.deleteAllByMemberId(memberId)
        pushDeliveryRepository.deleteAllByMemberId(memberId)
        pushHistoryRepository.deleteAllByMemberId(memberId)
        notificationActionReceiptRepository.deleteAllByMemberId(memberId)
        departureStatusRepository.deleteAllByMemberId(memberId)
        travelPlanRepository.deleteAllByMemberId(memberId)
        invitationRepository.deleteAllByOwnerMemberId(memberId)

        scheduleRepository.deleteAll(scheduleRepository.findAllByMemberId(memberId))
        categoryRepository.deleteAll(categoryRepository.findAllByMemberId(memberId))
        favoriteRepository.deleteAll(favoriteRepository.findAllByMemberId(memberId))
        favoriteCategoryRepository.deleteAll(favoriteCategoryRepository.findAllByMemberId(memberId))
        recentRoutePlaceRepository.deleteAll(recentRoutePlaceRepository.findAllByMemberId(memberId))
        memberProfileRepository.deleteByMemberId(memberId)
        memberSettingRepository.deleteAllByMemberId(memberId)
        memberConsentRepository.deleteAllByMemberId(memberId)
        tokenRetirementService.retireAllByMember(memberId)

        // 회원 row는 감사/참조 안정성을 위해 남기되 재식별 정보를 제거하고 인증을 차단한다.
        lockedMember.name = "탈퇴 회원"
        lockedMember.email = "deleted-$memberId-${UUID.randomUUID()}@deleted.invalid"
        lockedMember.password = ""
        lockedMember.snsId = null
        lockedMember.tokensValidAfter = java.time.Instant.now()
        lockedMember.softDelete()
    }

    private fun leaveActiveCalendarMemberships(memberId: Long) {
        val calendarIds = calendarMemberRepository
            .findAllByMemberIdAndStatusAndDeletedFalseOrderByIdAsc(
                memberId,
                ScheduleCalendarMemberStatus.ACTIVE,
            )
            .map { it.calendarId }
            .distinct()
            .sorted()
        if (calendarIds.isEmpty()) return

        val calendarsById = calendarRepository.findAllForUpdate(calendarIds)
            .associateBy { requireNotNull(it.id) }
        val memberships = calendarIds.mapNotNull { calendarId ->
            calendarMemberRepository.findForUpdate(calendarId, memberId)
                ?.takeIf {
                    !it.deleted &&
                        it.status == ScheduleCalendarMemberStatus.ACTIVE
                }
        }
        val stillOwnedActiveCalendar = memberships.firstOrNull { membership ->
            val calendar = calendarsById[membership.calendarId]
            calendar != null &&
                !calendar.deleted &&
                calendar.status == ScheduleCalendarStatus.ACTIVE &&
                (
                    calendar.ownerMemberId == memberId ||
                        membership.role == ScheduleCalendarRole.OWNER
                    )
        }
        if (stillOwnedActiveCalendar != null) {
            throw BusinessException(
                ErrorCode.INVALID_STATE,
                "활성 공유 캘린더의 소유권을 이전하거나 캘린더를 보관한 뒤 탈퇴할 수 있습니다.",
            )
        }

        memberships.forEach { it.leave() }
        calendarMemberRepository.saveAllAndFlush(memberships)
    }

    private fun snapshotOwnedNotificationScope(memberId: Long): OwnedNotificationScope {
        val ownedCategoryIds = categoryRepository.findAllByMemberId(memberId)
            .asSequence()
            .filterNot { it.deleted }
            .mapNotNull { it.id }
            .toSortedSet()
        val categorySchedules = ownedCategoryIds.flatMap { categoryId ->
            scheduleRepository
                .findAllByCategoryIdIncludingSnapshotAndDeletedFalseOrderByIdAsc(categoryId)
        }
        // An EDITOR can create a schedule whose member_id is the editor while its category belongs
        // to the withdrawing owner. The category is still the disappearing access boundary, so
        // every schedule linked to an owned category must be included in the notification cleanup
        // scope even though the editor's schedule row itself remains their data.
        val schedules = (
            scheduleRepository.findAllByMemberId(memberId)
                .filterNot { it.deleted } +
                categorySchedules
            )
            .distinctBy { it.id }
            .sortedBy { it.id }
        val scheduleIds = schedules.mapNotNull { it.id }.toSortedSet()
        val affectedMemberIds = linkedSetOf(memberId)
        schedules.mapTo(affectedMemberIds) { it.memberId }
        if (scheduleIds.isNotEmpty()) {
            scheduleShareRepository
                .findAllByScheduleIdInAndStatusAndDeletedFalseOrderByScheduleIdAscIdAsc(
                    scheduleIds,
                    ScheduleShareStatus.ACTIVE,
                )
                .mapTo(affectedMemberIds) { it.targetMemberId }
        }

        val scheduleCategoryIds = schedules
            .mapNotNull { it.categoryId ?: it.categorySnapshot?.categoryId?.toLongOrNull() }
        val categoryIdsForScope = (ownedCategoryIds + scheduleCategoryIds).toSortedSet()
        val categoryShares = if (categoryIdsForScope.isEmpty()) {
            emptyList()
        } else {
            categoryShareRepository
                .findAllByCategoryIdInAndDeletedFalseOrderByCategoryIdAscIdAsc(categoryIdsForScope)
        }
        categoryShares.mapTo(affectedMemberIds) { it.targetMemberId }
        val ownedCategoryShareTargets = ownedCategoryIds.associateWith { categoryId ->
            categoryShares.asSequence()
                .filter { it.categoryId == categoryId }
                .map { it.targetMemberId }
                .toSortedSet()
        }

        val calendarIds = schedules.mapNotNull { it.calendarId }.distinct()
        if (calendarIds.isNotEmpty()) {
            calendarMemberRepository
                .findAllByCalendarIdInAndStatusAndDeletedFalseOrderByCalendarIdAscIdAsc(calendarIds)
                .mapTo(affectedMemberIds) { it.memberId }
        }

        if (scheduleIds.isNotEmpty()) {
            pushJobRepository.findDistinctMemberIdsByScheduleIdIn(scheduleIds)
                .forEach(affectedMemberIds::add)
            routeSetupReminderRepository.findDistinctMemberIdsByScheduleIdIn(scheduleIds)
                .forEach(affectedMemberIds::add)
            travelPlanRepository.findDistinctMemberIdsByScheduleIdIn(scheduleIds)
                .forEach(affectedMemberIds::add)
            departureStatusRepository.findDistinctMemberIdsByScheduleIdIn(scheduleIds)
                .forEach(affectedMemberIds::add)
            notificationActionReceiptRepository.findDistinctMemberIdsByScheduleIdIn(scheduleIds)
                .forEach(affectedMemberIds::add)
            appNotificationRepository.findDistinctMemberIdsByScheduleIdIn(scheduleIds)
                .forEach(affectedMemberIds::add)
            pushDeliveryRepository.findDistinctMemberIdsByScheduleIdIn(scheduleIds)
                .forEach(affectedMemberIds::add)
            pushHistoryRepository.findDistinctMemberIdsByScheduleIdIn(scheduleIds)
                .forEach(affectedMemberIds::add)
        }

        return OwnedNotificationScope(
            ownedScheduleIds = scheduleIds,
            affectedMemberIds = affectedMemberIds.toSortedSet(),
            ownedCategoryShareTargets = ownedCategoryShareTargets,
        )
    }
}

private data class OwnedNotificationScope(
    val ownedScheduleIds: Set<Long>,
    val affectedMemberIds: Set<Long>,
    val ownedCategoryShareTargets: Map<Long, Set<Long>>,
)
