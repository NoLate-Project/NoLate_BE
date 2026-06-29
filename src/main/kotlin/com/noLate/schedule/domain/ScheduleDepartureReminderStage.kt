package com.noLate.schedule.domain

/**
 * 권장 출발 알림 이후의 자동 재푸시 경계를 식별한다.
 *
 * boundaryOffsetMinutes는 "첫 지금 출발 알림" 또는 "일정 시작" 중 어느 기준을 쓰는지에 따라
 * 해석이 달라지므로, 실제 시각 계산은 DepartureReminderPolicy에 둔다.
 */
enum class ScheduleDepartureReminderStage {
    DEPART_NOW,
    AFTER_DEPARTURE_3,
    AFTER_DEPARTURE_7,
    BEFORE_SCHEDULE_3,
    BEFORE_SCHEDULE_1,
}
