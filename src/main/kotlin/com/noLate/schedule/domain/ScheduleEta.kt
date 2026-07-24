package com.noLate.schedule.domain

import java.time.Instant

enum class TrafficSource {
    LIVE_PROVIDER,
    SELECTED_ROUTE,
    SAVED_FALLBACK,
}

enum class ScheduleEtaConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

/**
 * 한 회원의 현재 출발 판단에 필요한 ETA 상태.
 *
 * 준비 시간과 안전 버퍼는 아직 제품 저장 모델이 없으므로 null을 반환한다. 0으로 채우면
 * 사용자가 명시적으로 0분을 선택했다는 잘못된 의미가 생기기 때문이다.
 */
data class ScheduleDepartureEtaStatusDto(
    val scheduleId: Long,
    val travelMinutes: Int?,
    val recommendedDepartureAt: Instant?,
    val evaluatedAt: Instant,
    val liveFetchedAt: Instant?,
    /** 이동 계획 자체가 없으면 출처도 없으므로 null이다. */
    val source: TrafficSource?,
    val stale: Boolean,
    /** 계산 가능한 ETA가 없으면 신뢰도도 평가하지 않아 null이다. */
    val confidence: ScheduleEtaConfidence?,
    val failureReason: String?,
    val lastTrafficChangeMinutes: Int?,
    val lastChangedAt: Instant?,
    val nextCheckAt: Instant?,
    val preparationMinutes: Int?,
    val preparationStartAt: Instant?,
    val safetyBufferMinutes: Int?,
    val timeZone: String,
)
