package com.noLate.schedule.application.service

import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
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

    private fun cancelRevoked(schedules: List<Schedule>, memberIds: Collection<Long>) {
        val normalizedMemberIds = memberIds.distinct()
        val scheduleIds = schedules.mapNotNull { it.id }
        if (scheduleIds.isEmpty() || normalizedMemberIds.isEmpty()) return

        // resolveAll은 회원 한 명당 grant 저장소를 종류별 한 번만 조회한다. 캘린더에 일정이
        // 많아도 각 일정마다 직접/카테고리/캘린더 쿼리를 반복하지 않는다.
        val revokedPairs = normalizedMemberIds.flatMapTo(linkedSetOf()) { memberId ->
            val decisions = accessPolicy.resolveAll(memberId, schedules)
            scheduleIds.mapNotNull { scheduleId ->
                if (decisions[scheduleId]?.travelEnabled == true) null else scheduleId to memberId
            }
        }
        if (revokedPairs.isEmpty()) return

        pushJobRepository.findAllByScheduleIdInAndMemberIdIn(scheduleIds, normalizedMemberIds)
            .filter { it.status == SchedulePushJobStatus.ACTIVE || it.status == SchedulePushJobStatus.PROCESSING }
            .filter { it.scheduleId to it.memberId in revokedPairs }
            .forEach { it.cancel() }
    }
}
