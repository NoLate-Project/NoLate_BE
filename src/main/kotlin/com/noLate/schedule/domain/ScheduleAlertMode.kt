package com.noLate.schedule.domain

/**
 * 사용자에게 출발 시각을 제시하는 강도다.
 *
 * STANDARD는 기존 notification channel을 그대로 사용한다. ALARM은 지원 기기에서
 * 정확한 로컬 알람을 함께 예약하되, 권한이나 OS 지원이 없으면 STANDARD로 안전하게
 * 폴백한다.
 */
enum class ScheduleAlertMode {
    STANDARD,
    ALARM,
}
