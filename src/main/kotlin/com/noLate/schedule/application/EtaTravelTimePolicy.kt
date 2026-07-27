package com.noLate.schedule.application

import kotlin.math.ceil

/**
 * 저장 경로, 선택 경로 JSON, provider 응답이 동일한 이동 시간 범위를 사용하게 하는 제품 정책.
 */
object EtaTravelTimePolicy {
    const val DEFAULT_MAX_TRAVEL_MINUTES = 1_440
    const val MAX_CONFIGURABLE_TRAVEL_MINUTES = 10_080

    fun requireValidMaximum(maxTravelMinutes: Int) {
        require(maxTravelMinutes in 1..MAX_CONFIGURABLE_TRAVEL_MINUTES) {
            "schedule.traffic.max-travel-minutes는 1~$MAX_CONFIGURABLE_TRAVEL_MINUTES" +
                "분이어야 합니다."
        }
    }

    fun isValid(minutes: Int?, maxTravelMinutes: Int): Boolean {
        requireValidMaximum(maxTravelMinutes)
        return minutes != null && minutes in 1..maxTravelMinutes
    }

    fun normalizeMinutes(value: Double, maxTravelMinutes: Int): Int? {
        requireValidMaximum(maxTravelMinutes)
        if (!value.isFinite() || value <= 0) return null
        val rounded = ceil(value)
        if (!rounded.isFinite() || rounded > maxTravelMinutes) return null
        return rounded.toInt()
    }
}
