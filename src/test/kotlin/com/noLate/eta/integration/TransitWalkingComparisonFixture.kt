package com.noLate.eta.integration

import com.noLate.eta.domain.*

/** 비교용 입력 치환일 뿐이다. 운영 도보값을 교정하거나 앱 정확도의 증거로 사용하지 않는다. */
internal object TransitWalkingComparisonFixture {
    fun replaceWalkingSeconds(original: TransitJourney, seconds: List<Long>): TransitJourney {
        require(seconds.size == 3 && seconds.all { it in 0..1800 })
        require(original.legs.map { it.mode } == listOf(
            TransitLegMode.WALK, TransitLegMode.SUBWAY, TransitLegMode.WALK,
            TransitLegMode.SUBWAY, TransitLegMode.WALK,
        ))
        // 이미 선택한 열차에서 시간을 빼면 새 환승 가능성을 검증할 수 없으므로 추정 입력만 받는다.
        require(original.legs.all { it.timetable == null && it.timingBasis != TransitLegTimingBasis.TIMETABLE })
        var walkingIndex = 0
        var cursor = original.departureAt
        val legs = original.legs.map { leg ->
            val duration = if (leg.mode == TransitLegMode.WALK) seconds[walkingIndex++] else leg.movementDurationSeconds
            val start = cursor
            cursor = cursor.plusSeconds(duration)
            leg.copy(durationSeconds = duration, durationMinutes = ((duration + 59) / 60).toInt(),
                scheduledDepartureAt = start, scheduledArrivalAt = cursor)
        }
        val total = legs.sumOf { it.movementDurationSeconds }
        require(total > 0)
        return original.copy(legs = legs, arrivalAt = cursor, totalMinutes = ((total + 59) / 60).toInt())
    }
}
