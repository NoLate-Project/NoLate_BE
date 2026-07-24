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
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleNotificationActionReceiptRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
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
        scheduleShareRepository.deleteAllByOwnerMemberIdOrTargetMemberId(memberId, memberId)
        categoryShareRepository.deleteAllByOwnerMemberIdOrTargetMemberId(memberId, memberId)
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

    private fun snapshotOwnedNotificationScope(memberId: Long): OwnedNotificationScope {
        val schedules = scheduleRepository.findAllByMemberId(memberId)
            .filterNot { it.deleted }
            .sortedBy { it.id }
        val scheduleIds = schedules.mapNotNull { it.id }.toSortedSet()
        if (scheduleIds.isEmpty()) {
            return OwnedNotificationScope(emptySet(), setOf(memberId))
        }

        val affectedMemberIds = linkedSetOf(memberId)
        scheduleShareRepository
            .findAllByScheduleIdInAndStatusAndDeletedFalseOrderByScheduleIdAscIdAsc(
                scheduleIds,
                ScheduleShareStatus.ACTIVE,
            )
            .mapTo(affectedMemberIds) { it.targetMemberId }

        val categoryIds = schedules
            .mapNotNull { it.categoryId ?: it.categorySnapshot?.categoryId?.toLongOrNull() }
            .distinct()
        if (categoryIds.isNotEmpty()) {
            categoryShareRepository
                .findAllByCategoryIdInAndStatusAndDeletedFalseOrderByCategoryIdAscIdAsc(
                    categoryIds,
                    ScheduleShareStatus.ACTIVE,
                )
                .mapTo(affectedMemberIds) { it.targetMemberId }
        }

        val calendarIds = schedules.mapNotNull { it.calendarId }.distinct()
        if (calendarIds.isNotEmpty()) {
            calendarMemberRepository
                .findAllByCalendarIdInAndStatusAndDeletedFalseOrderByCalendarIdAscIdAsc(calendarIds)
                .mapTo(affectedMemberIds) { it.memberId }
        }

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

        return OwnedNotificationScope(scheduleIds, affectedMemberIds.toSortedSet())
    }
}

private data class OwnedNotificationScope(
    val ownedScheduleIds: Set<Long>,
    val affectedMemberIds: Set<Long>,
)
