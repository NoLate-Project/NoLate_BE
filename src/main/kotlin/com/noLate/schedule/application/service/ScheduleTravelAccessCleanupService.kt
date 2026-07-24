package com.noLate.schedule.application.service

import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleRouteSetupReminderStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 이동 공유 권한이 줄어든 직후 더 이상 유효하지 않은 개인 출발 PushJob을 정리한다.
 *
 * 공유 row 하나가 해제돼도 직접 공유, legacy 카테고리, 캘린더 멤버십 중 다른 grant가 남아
 * 있을 수 있으므로 변경된 row만 보고 무조건 취소하면 안 된다. 변경 트랜잭션 안에서 중앙 접근
 * 정책을 다시 계산하고, 최종적으로 travelEnabled가 false가 된 `(schedule, member)`만 취소한다.
 */
@Service
class ScheduleTravelAccessCleanupService(
    private val scheduleRepository: ScheduleRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val routeSetupReminderRepository: ScheduleRouteSetupReminderRepository,
    private val appNotificationRepository: AppNotificationRepository,
    private val pushDeliveryRepository: PushDeliveryRepository,
    private val pushSendHistoryRepository: PushSendHistoryRepository,
    private val accessPolicy: ScheduleAccessPolicy,
) {

    @Transactional
    fun cancelRevokedForSchedule(scheduleId: Long, memberIds: Collection<Long>) {
        val schedule = scheduleRepository.findById(scheduleId).orElse(null)
            ?.takeUnless { it.deleted }
            ?: return
        cancelRevoked(listOf(schedule), memberIds)
    }

    @Transactional
    fun cancelRevokedForCalendar(calendarId: Long, memberIds: Collection<Long>) {
        val schedules = scheduleRepository.findAllByCalendarIdAndDeletedFalseOrderByIdAsc(calendarId)
        cancelRevoked(schedules, memberIds)
    }

    /**
     * Legacy category sharing applies to every current schedule linked by either the canonical
     * category FK or its compatibility snapshot. The caller must lock the owner and every affected
     * target member in ascending ID order before changing the category/share row, then invoke this
     * method in that same transaction.
     */
    @Transactional
    fun cancelRevokedForCategory(categoryId: Long, memberIds: Collection<Long>) {
        val schedules =
            scheduleRepository.findAllByCategoryIdIncludingSnapshotAndDeletedFalseOrderByIdAsc(categoryId)
        val revokedPairs = cancelRevoked(schedules, memberIds)
        val normalizedMemberIds = memberIds.distinct().sorted()
        if (normalizedMemberIds.isEmpty()) return

        // Category-share notification sources have category_id but no schedule_id. Remove their
        // frozen manifests under the same recipient locks so a drainer cannot navigate a revoked
        // category after the business transaction commits.
        val categorySources =
            appNotificationRepository.findAllByCategoryIdAndMemberIdIn(categoryId, normalizedMemberIds)
        deleteNotificationSources(categorySources, normalizedMemberIds)

        // `cancelRevoked` already removes schedule-bound sources for the exact revoked pairs.
        // Keeping this local value makes the category-specific source cleanup explicit.
        if (revokedPairs.isEmpty()) return
    }

    private fun cancelRevoked(
        schedules: List<Schedule>,
        memberIds: Collection<Long>,
    ): Set<Pair<Long, Long>> {
        val normalizedMemberIds = memberIds.distinct().sorted()
        val scheduleIds = schedules.mapNotNull { it.id }
        if (scheduleIds.isEmpty() || normalizedMemberIds.isEmpty()) return emptySet()

        // resolveAll은 회원 한 명당 grant 저장소를 종류별 한 번만 조회한다. 캘린더에 일정이
        // 많아도 각 일정마다 직접/카테고리/캘린더 쿼리를 반복하지 않는다.
        val revokedPairs = normalizedMemberIds.flatMapTo(linkedSetOf()) { memberId ->
            val decisions = accessPolicy.resolveAll(memberId, schedules)
            scheduleIds.mapNotNull { scheduleId ->
                if (decisions[scheduleId]?.travelEnabled == true) null else scheduleId to memberId
            }
        }
        if (revokedPairs.isEmpty()) return emptySet()

        pushJobRepository.findAllByScheduleIdInAndMemberIdIn(scheduleIds, normalizedMemberIds)
            .filter { it.status == SchedulePushJobStatus.ACTIVE || it.status == SchedulePushJobStatus.PROCESSING }
            .filter { it.scheduleId to it.memberId in revokedPairs }
            .forEach { it.cancel() }

        // A revoked participant plan is no longer an authoritative startup-backfill source. Soft
        // deletion retains audit/version history while preventing a restarted node from rebuilding
        // the participant job after the share row was revoked.
        travelPlanRepository
            .findAllByScheduleIdInAndMemberIdInAndDeletedFalse(scheduleIds, normalizedMemberIds)
            .filter { it.scheduleId to it.memberId in revokedPairs }
            .forEach { it.softDelete() }

        routeSetupReminderRepository
            .findAllByScheduleIdInAndMemberIdIn(scheduleIds, normalizedMemberIds)
            .filter { it.scheduleId to it.memberId in revokedPairs }
            .filter {
                it.status == ScheduleRouteSetupReminderStatus.PENDING ||
                    it.status == ScheduleRouteSetupReminderStatus.FAILED
            }
            .forEach { it.cancel() }

        val revokedScheduleIds = revokedPairs.mapTo(linkedSetOf()) { it.first }
        val revokedMemberIds = revokedPairs.mapTo(linkedSetOf()) { it.second }
        val sources = appNotificationRepository
            .findAllByScheduleIdInAndMemberIdIn(revokedScheduleIds, revokedMemberIds)
            .filter { it.scheduleId to it.memberId in revokedPairs }
        deleteNotificationSources(sources, revokedMemberIds)

        val orphanDeliveries = pushDeliveryRepository
            .findAllByScheduleIdInAndMemberIdIn(revokedScheduleIds, revokedMemberIds)
            .filter { it.scheduleId to it.memberId in revokedPairs }
        if (orphanDeliveries.isNotEmpty()) {
            pushDeliveryRepository.deleteAll(orphanDeliveries)
        }
        val histories = pushSendHistoryRepository
            .findAllByScheduleIdInAndMemberIdIn(revokedScheduleIds, revokedMemberIds)
            .filter { it.scheduleId to it.memberId in revokedPairs }
        if (histories.isNotEmpty()) {
            pushSendHistoryRepository.deleteAll(histories)
        }
        return revokedPairs
    }

    private fun deleteNotificationSources(
        sources: Collection<com.noLate.notification.domain.AppNotification>,
        memberIds: Collection<Long>,
    ) {
        if (sources.isEmpty()) return
        val eventKeys = sources.map { it.logicalEventKey }
        val deliveries =
            pushDeliveryRepository.findAllByMemberIdInAndEventKeyIn(memberIds, eventKeys)
        if (deliveries.isNotEmpty()) {
            pushDeliveryRepository.deleteAll(deliveries)
        }
        appNotificationRepository.deleteAll(sources)
    }
}
