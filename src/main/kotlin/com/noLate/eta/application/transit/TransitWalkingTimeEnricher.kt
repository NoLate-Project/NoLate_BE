package com.noLate.eta.application.transit

import com.noLate.eta.application.port.TransitWalkingTimeProvider
import com.noLate.eta.domain.*
import com.noLate.eta.application.port.TransitTransferReferenceProvider
import org.springframework.stereotype.Component

@Component
class TransitWalkingTimeEnricher(
    private val providers: List<TransitWalkingTimeProvider> = emptyList(),
    private val references: List<TransitTransferReferenceProvider> = emptyList(),
) {
    fun enrich(journey: TransitJourney): TransitJourney {
        // 재시간표 연결 전에만 정밀화한다. 이미 선택한 열차의 시간축에서 초를 빼지 않는다.
        if (journey.rideLegs.any { it.timetable != null }) return journey
        val rides = journey.legs.indices.filter { journey.legs[it].isRide }
        val legs = journey.legs.toMutableList()
        for ((previous, next) in rides.zipWithNext()) {
            val walks = (previous + 1 until next).filter { legs[it].mode == TransitLegMode.WALK }
            // 공식 한 구간 값을 여러 도보에 중복 합산하지 않는다.
            if (walks.size != 1 || next - previous != 2) continue
            val index = walks.single()
            val walk = legs[index]
            val from = legs[previous]
            val to = legs[next]
            val station = from.to?.name?.takeIf { it == to.from?.name }
            val reference = if (from.mode == TransitLegMode.SUBWAY && to.mode == TransitLegMode.SUBWAY &&
                station != null && from.line?.seoulSubwayLine != null && to.line?.seoulSubwayLine != null) {
                references.firstNotNullOfOrNull { it.find(station, from.line.seoulSubwayLine, to.line.seoulSubwayLine) }
            } else null
            val verified = if (walk.durationSeconds == null && walk.walkingGeometry != null) {
                providers.singleOrNull { it.providerId == journey.provider }
                    ?.let { provider -> runCatching { provider.refine(walk.walkingGeometry, walk.durationMinutes) }.getOrNull() }
            } else null
            // 같은 전체 경로의 초를 채택해도 표시 분과의 정합을 유지한다. 다른 경로는 쓰지 않는다.
            val seconds = verified?.seconds?.takeIf { it > 0 && (it + 59) / 60 == walk.durationMinutes.toLong() }
            legs[index] = walk.copy(durationSeconds = seconds ?: walk.durationSeconds,
                walkingTiming = TransitWalkingTimingEvidence(
                    source = if (seconds != null) TransitWalkingTimingSource.ODSAY_FULL_PATH_SECONDS
                        else if (walk.durationSeconds != null) TransitWalkingTimingSource.PROVIDER_LINK_SECONDS
                        else TransitWalkingTimingSource.ROUTE_MINUTES,
                    originalDurationMinutes = walk.durationMinutes,
                    movementSeconds = seconds ?: walk.movementDurationSeconds,
                    fetchedAt = verified?.fetchedAt.takeIf { seconds != null },
                    sameOrderedPathVerified = seconds != null,
                    transferReference = reference,
                ))
        }
        return journey.copy(legs = legs)
    }
}
