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
        observedBoardingAt: Instant? = null,
        pendingTimetableBoardingAt: Instant? = null,
    ): Instant? {
        // 시간표의 미관측 열차는 알람/ETA 채택 근거가 아니라 다음 관측의 조회 경계다.
        // 출발 직후 15분까지만, 20분 안에 오는 후보를 1분 주기로 재확인한다.
        // 후보가 매번 바뀌어도 고빈도 조회가 일정 시작까지 무한 연장되지 않는다.
        val confirmationRefreshAt = pendingTimetableBoardingAt
            ?.takeIf { now.isBefore(it) && !it.isAfter(now.plus(20, ChronoUnit.MINUTES)) }
            ?.takeIf { now.isBefore(recommendedDepartureAt.plus(15, ChronoUnit.MINUTES)) }
            ?.let { minOf(now.plus(1, ChronoUnit.MINUTES), it,
                recommendedDepartureAt.plus(15, ChronoUnit.MINUTES)) }
        // 조회 중 출발 경계를 넘겨도 아직 탈 수 있는 관측 차량이 있으면 승차 전까지
        // 1분 주기로 확인한다. 출발 알림은 별도 정책이 담당하며 과거 경계를 재예약하지 않는다.
        if (!now.isBefore(recommendedDepartureAt)) {
            val observedRefreshAt = observedBoardingAt?.takeIf { it.isAfter(now) }
                ?.let { minOf(now.plus(1, ChronoUnit.MINUTES), it) }
            return listOfNotNull(observedRefreshAt, confirmationRefreshAt).minOrNull()
        }

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
