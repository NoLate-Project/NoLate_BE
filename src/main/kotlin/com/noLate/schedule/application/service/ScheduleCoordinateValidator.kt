package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode

/** 일정과 개인 이동 계획의 모든 입력 경계에서 공유하는 좌표 규칙. */
internal object ScheduleCoordinateValidator {
    fun validateOptional(
        fieldLabel: String,
        lat: Double?,
        lng: Double?,
    ): ValidScheduleCoordinates? {
        if (lat == null && lng == null) return null
        if (lat == null || lng == null) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "$fieldLabel 위도와 경도는 함께 입력해야 합니다.",
            )
        }
        if (!lat.isFinite() || lat !in -90.0..90.0) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "$fieldLabel 위도는 -90~90 범위의 유한한 값이어야 합니다.",
            )
        }
        if (!lng.isFinite() || lng !in -180.0..180.0) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "$fieldLabel 경도는 -180~180 범위의 유한한 값이어야 합니다.",
            )
        }
        return ValidScheduleCoordinates(lat = lat, lng = lng)
    }
}

internal data class ValidScheduleCoordinates(
    val lat: Double,
    val lng: Double,
)
