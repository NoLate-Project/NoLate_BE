package com.noLate.member.application.service

import com.noLate.favorite.infrastructure.FavoritePlaceCategoryRepository
import com.noLate.favorite.infrastructure.FavoritePlaceRepository
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberConsentRepository
import com.noLate.member.infrastructure.MemberProfileRepository
import com.noLate.member.infrastructure.MemberSettingRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import com.noLate.routehistory.infrastructure.RecentRoutePlaceRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleNotificationActionReceiptRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 계정 경계를 넘을 수 있는 인증/기기/사용자 데이터를 한 트랜잭션에서 정리한다. */
@Service
class AccountCleanupService(
    private val deviceTokenRepository: NotificationDeviceTokenRepository,
    private val pushDeliveryRepository: PushDeliveryRepository,
    private val pushHistoryRepository: PushSendHistoryRepository,
    private val appNotificationRepository: AppNotificationRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val departureStatusRepository: ScheduleDepartureStatusRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
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
    @Transactional
    fun withdraw(member: Member) {
        val memberId = requireNotNull(member.id)

        // Provider worker와 동일한 lock 방향을 유지한다:
        // schedule job -> immutable source -> delivery/history -> device ownership.
        // MemberSessionFenceService가 member lock과 refresh revoke를 이미 완료했다.
        pushJobRepository.deleteAllByMemberId(memberId)
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
        deviceTokenRepository.deleteAllByMemberId(memberId)

        // 회원 row는 감사/참조 안정성을 위해 남기되 재식별 정보를 제거하고 인증을 차단한다.
        member.name = "탈퇴 회원"
        member.email = "deleted-$memberId-${UUID.randomUUID()}@deleted.invalid"
        member.password = ""
        member.snsId = null
        member.tokensValidAfter = java.time.Instant.now()
        member.softDelete()
    }
}
