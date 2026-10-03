package com.noLate.schedule.application.service.policy

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode

/** Input validity is shared by every member and is independent of subscription or usage. */
object ScheduleNotificationPolicy {
    const val DEFAULT_LEAD_MINUTES = 120
    const val DEFAULT_INTERVAL_MINUTES = 10

    fun validate(notificationEnabled: Boolean, leadMinutes: Int?, intervalMinutes: Int?) {
        if (!notificationEnabled) return
        if ((leadMinutes ?: DEFAULT_LEAD_MINUTES) !in 10..120) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "알림 시작 시간은 10~120분 사이여야 합니다.")
        }
        val interval = intervalMinutes ?: DEFAULT_INTERVAL_MINUTES
        if (interval !in 10..60 || interval % 5 != 0) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "알림 간격은 10~60분 사이의 5분 단위여야 합니다.")
        }
    }
}
