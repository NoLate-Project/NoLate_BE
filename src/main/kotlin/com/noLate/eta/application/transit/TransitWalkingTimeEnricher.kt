package com.noLate.eta.application.transit

import com.noLate.eta.application.port.TransitWalkingTimeProvider
import com.noLate.eta.application.port.VerifiedAccessWalk
import com.noLate.eta.application.port.WalkingPathRefinement
import com.noLate.eta.application.port.VerifiedWalkingTime
import com.noLate.eta.domain.*
import com.noLate.eta.application.port.TransitTransferReferenceProvider
import org.springframework.stereotype.Component
import java.time.Duration

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
        val walkingProvider = providers.singleOrNull { it.providerId == journey.provider }
        fun refineWalk(index: Int, reference: TransitTransferReference?, batched: VerifiedWalkingTime? = null,
                       useBatch: Boolean = false) {
            val walk = legs[index]
            val verified = if (useBatch) batched else if (walk.durationSeconds == null && walk.walkingGeometry != null) {
                walkingProvider?.let { provider ->
                    runCatching { provider.refine(walk.walkingGeometry, walk.durationMinutes) }.getOrNull()
                }
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
        val transferPairs = rides.zipWithNext().filter { (previous, next) ->
            next - previous == 2 && legs[previous + 1].mode == TransitLegMode.WALK
        }
        val last = legs.lastIndex
        val egressIndex = last.takeIf {
            journey.provider == "odsay" && rides.lastOrNull() == last - 1 && legs[last].mode == TransitLegMode.WALK &&
                legs[last].walkingGeometry?.providerQueryEndpoints != null
        }
        val accessIndex = 0.takeIf {
            journey.provider == "odsay" && rides.firstOrNull() == 1 && legs.first().mode == TransitLegMode.WALK &&
                legs.first().walkingGeometry?.providerQueryEndpoints != null
        }
        val accessPins = journey.entranceWalkEndpoints
        val exitPins = journey.exitWalkEndpoints
        val strictPaths = (transferPairs.map { (previous, _) -> previous + 1 } + listOfNotNull(accessIndex, egressIndex))
            .mapNotNull { index ->
                val leg = legs[index]
                val geometry = leg.walkingGeometry?.takeIf { leg.durationSeconds == null && it.providerQueryEndpoints != null }
                geometry?.let { index to WalkingPathRefinement(it, leg.durationMinutes) }
            }.toMap()
        val batch = walkingProvider?.takeIf { accessPins != null || exitPins != null || strictPaths.isNotEmpty() }?.let { provider ->
            runCatching { provider.refineBoundaries(accessPins, exitPins, strictPaths.values.toList()) }
                .getOrNull()
        }
        fun refineVerifiedWalk(index: Int, reference: TransitTransferReference? = null) {
            val path = strictPaths[index]
            refineWalk(index, reference, path?.let { batch?.unchanged?.get(it) }, useBatch = batch != null && path != null)
        }
        for ((previous, next) in transferPairs) {
            val index = previous + 1
            val from = legs[previous]
            val to = legs[next]
            val station = from.to?.name?.takeIf { it == to.from?.name }
            val reference = if (from.mode == TransitLegMode.SUBWAY && to.mode == TransitLegMode.SUBWAY &&
                station != null && from.line?.seoulSubwayLine != null && to.line?.seoulSubwayLine != null) {
                references.firstNotNullOfOrNull { it.find(station, from.line.seoulSubwayLine, to.line.seoulSubwayLine) }
            } else null
            refineVerifiedWalk(index, reference)
        }
        if (accessIndex != null) refineVerifiedWalk(accessIndex)
        if (egressIndex != null) refineVerifiedWalk(egressIndex)
        batch?.exit?.takeIf { it.seconds > legs[last].movementDurationSeconds }?.let {
            legs[last] = boundaryLeg(last, it, exitPins, TransitWalkingTimingSource.ODSAY_EXIT_WALK_SECONDS, legs[last].durationMinutes)
        }
        val access = batch?.access ?: return withRefinedWalks(journey, legs)
        val source = TransitWalkingTimingSource.ODSAY_ENTRANCE_WALK_SECONDS
        if (rides.firstOrNull() == 0) {
            legs.add(0, boundaryLeg(0, access, accessPins, source, 0))
            return withRefinedWalks(journey, legs.mapIndexed { index, leg -> leg.copy(sequence = index) })
        }
        if (rides.firstOrNull() == 1 && access.seconds > legs[0].movementDurationSeconds) {
            legs[0] = boundaryLeg(0, access, accessPins, source, legs[0].durationMinutes)
        }
        return withRefinedWalks(journey, legs)
    }

    private fun withRefinedWalks(journey: TransitJourney, legs: List<TransitJourneyLeg>): TransitJourney {
        // A verified entrance leg is needed even when there is no timetable to rebuild the journey.
        val verifiedEntrance = legs.firstOrNull()?.walkingTiming?.source ==
            TransitWalkingTimingSource.ODSAY_ENTRANCE_WALK_SECONDS
        // maasRP의 전체 시간축은 분 단위다. 공급자 링크의 초가 이미 파싱됐더라도
        // 분 단위 구간 합과 전체 시간이 정확히 일치하면 아직 전체에는 반영되지 않았다.
        val roundedLegTotal = journey.legs.sumOf { it.durationMinutes.toLong() } * 60
        val isRawMinuteTimeline = journey.legs.none { it.walkingTiming != null } &&
            Duration.between(journey.departureAt, journey.arrivalAt) == Duration.ofSeconds(roundedLegTotal)
        // 기존 지하철 도보만 보정할 때는 전체가 공급자의 분 단위 추정 합과 일치해야
        // 도보 차이를 반영할 수 있다. 불일치한 합계에서는 대기 등의 포함 범위를 알 수 없다.
        val rides = journey.rideLegs
        val isRawSubwayEstimate = isRawMinuteTimeline && roundedLegTotal == journey.totalMinutes.toLong() * 60 &&
            rides.isNotEmpty() && rides.all {
            it.mode == TransitLegMode.SUBWAY && it.timingBasis == TransitLegTimingBasis.ROUTE_ESTIMATE
        }
        if (journey.provider != "odsay" ||
            (!verifiedEntrance && !isRawSubwayEstimate && rides.none { it.mode == TransitLegMode.BUS })) {
            return journey.copy(legs = legs)
        }
        val oldWalk = journey.legs.filter { it.mode == TransitLegMode.WALK }.sumOf { walk ->
            if (isRawMinuteTimeline) walk.durationMinutes.toLong() * 60 else walk.movementDurationSeconds
        }
        val newWalk = legs.filter { it.mode == TransitLegMode.WALK }.sumOf { it.movementDurationSeconds }
        if (oldWalk == newWalk) return journey.copy(legs = legs)
        val arrivalAt = journey.arrivalAt.plusSeconds(newWalk - oldWalk)
        if (arrivalAt <= journey.departureAt) return journey
        val duration = Duration.between(journey.departureAt, arrivalAt)
        val minutes = duration.toMinutes() + if (duration.minusMinutes(duration.toMinutes()).isZero) 0 else 1
        return journey.copy(legs = legs, arrivalAt = arrivalAt, totalMinutes = minutes.toInt())
    }

    private fun boundaryLeg(index: Int, walk: VerifiedAccessWalk, queryPins: TransitWalkingQueryEndpoints?,
                            source: TransitWalkingTimingSource, originalMinutes: Int) =
        TransitJourneyLeg(index, TransitLegMode.WALK, ((walk.seconds + 59) / 60).toInt(),
            durationSeconds = walk.seconds,
            walkingGeometry = TransitWalkingGeometry(from = walk.pathStart, to = walk.pathEnd,
                distanceMeters = walk.distanceMeters, endpointBasis = TransitWalkingEndpointBasis.PROVIDER_PATH_ENDPOINTS,
                providerQueryEndpoints = queryPins),
            walkingTiming = TransitWalkingTimingEvidence(source, originalMinutes, walk.seconds, walk.fetchedAt))
}
