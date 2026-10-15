package com.noLate.eta.domain

import java.time.Instant
import java.time.Duration

/** 경로 공급자와 무관하게 서울 실시간 API에서 대조할 수 있는 노선 식별자. */
enum class SeoulSubwayLine(val realtimeId: String, val displayName: String) {
    LINE_1("1001", "1호선"), LINE_2("1002", "2호선"), LINE_3("1003", "3호선"),
    LINE_4("1004", "4호선"), LINE_5("1005", "5호선"), LINE_6("1006", "6호선"),
    LINE_7("1007", "7호선"), LINE_8("1008", "8호선"), LINE_9("1009", "9호선"),
}

/**
 * 첫 정류장까지의 접근·노선·방향·종별·종착역·원천시각을 대조한 차량 관측.
 * 전체 여정의 도착시각이 아니며, 이 대기를 총 소요시간에 그대로 더하면 안 된다.
 */
data class TransitFirstBoardingObservation(
    val provider: String,
    val stationName: String,
    val lineName: String,
    val departureAt: Instant,
    val readyAt: Instant,
    val expectedBoardingAt: Instant,
    val sourceUpdatedAt: Instant,
    val evaluatedAt: Instant,
    val trainNumber: String? = null,
) {
    init {
        require(provider.isNotBlank() && stationName.isNotBlank() && lineName.isNotBlank())
        require(!expectedBoardingAt.isBefore(readyAt))
        require(!readyAt.isBefore(departureAt) && !departureAt.isBefore(evaluatedAt))
    }

    /** 저장 스냅샷의 grace 기간과 별개로 차량 관측 자체의 유효기간을 다시 확인한다. */
    fun isFreshAt(now: Instant, maxAgeSeconds: Long): Boolean =
        maxAgeSeconds > 0 &&
            !sourceUpdatedAt.isBefore(now.minusSeconds(maxAgeSeconds)) &&
            !sourceUpdatedAt.isAfter(now.plusSeconds(60)) &&
            !evaluatedAt.isBefore(now.minusSeconds(maxAgeSeconds)) &&
            !evaluatedAt.isAfter(now.plusSeconds(60)) &&
            // 조회 후 시간이 흘렀다면 접근시간과 승차 여유도 다시 적용한다.
            !expectedBoardingAt.isBefore(readyAt.plus(
                Duration.between(departureAt, maxOf(now, departureAt)),
            ))
}
