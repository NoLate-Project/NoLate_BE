package com.noLate.schedule.domain

/**
 * 일정에 연결된 이동 수단.
 *
 * 프론트의 TravelMode 값과 동일한 문자열로 직렬화되므로 이름 변경 시 API 호환성을 함께 확인해야 한다.
 */
enum class ScheduleTravelMode {
    /** 자동차 이동 */
    CAR,

    /** 대중교통 이동 */
    TRANSIT,

    /** 도보 이동 */
    WALK,

    /** 자전거 이동 */
    BIKE,

    /** 기타 이동 수단 */
    ETC
}
