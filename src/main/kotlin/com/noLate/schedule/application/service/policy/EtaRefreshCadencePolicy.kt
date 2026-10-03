package com.noLate.schedule.application.service.policy

import java.time.Instant
import java.time.temporal.ChronoUnit

/** 구독 여부와 관계없이 출발까지 남은 시간으로 ETA 재조회 시각을 결정. */
object EtaRefreshCadencePolicy {

    // 기존 Job 생성, 정령에서 사용.
    const val INTERVAL_MINUTES = 10

    fun nextRefreshAt(
        now: Instant,
        recommendedDepartureAt: Instant,
    ): Instant? {
        // 추천 출발시각 이후의 알림 처리는 Worker의 기존 정책이 담당한다.
        if (!now.isBefore(recommendedDepartureAt)) return null

        val thirtyMinutesBefore =
            recommendedDepartureAt.minus(30, ChronoUnit.MINUTES)
        val fifteenMinutesBefore =
            recommendedDepartureAt.minus(15, ChronoUnit.MINUTES)

        // 다음 주기로 전환되는 경계를 건너뛰지 않도록 더 빠른 시각을 선택한다.
        return when {
            now.isBefore(thirtyMinutesBefore) -> minOf(
                now.plus(5, ChronoUnit.MINUTES),
                thirtyMinutesBefore,
            )

            now.isBefore(fifteenMinutesBefore) -> minOf(
                now.plus(2, ChronoUnit.MINUTES),
                fifteenMinutesBefore,
            )

            else -> minOf(
                now.plus(1, ChronoUnit.MINUTES),
                recommendedDepartureAt,
            )
        }
    }
}
