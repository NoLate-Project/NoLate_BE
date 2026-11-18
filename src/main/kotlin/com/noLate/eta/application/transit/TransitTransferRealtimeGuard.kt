package com.noLate.eta.application.transit

import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind
import com.noLate.schedule.application.EffectiveTransitRideTimingBasis
import com.noLate.schedule.application.PlannedJourneyArrivalObservation
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.domain.TransitArrivalStatus
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException

enum class TransitRideObservationPoint { BOARDING, ALIGHTING }

/** 원본 시간표와 분리된 동일 열차의 신선한 역 도착 예측. */
data class TransitRideRealtimeObservation(
    val legIndex: Int,
    val point: TransitRideObservationPoint,
    val expectedAt: Instant,
    val sourceUpdatedAt: Instant,
    val earlyBoardingArrival: Boolean = false,
)

/**
 * 뒤의 열차 지연 또는 환승역 조기 도착을 동일 편의 신선한 정보로 대조한다.
 * 미래 차량의 미관측은 운행 변경의 증거가 아니다.
 * 환승 승차역뿐 아니라 하차역의 동일 열차 도착 예측도 대조한다. 하차역 조회는
 * 그 역의 시간표 도착까지 15분 이내일 때만 수행하고, 먼 미래의 미관측은 지연으로 추정하지 않는다.
 */
@Component
class TransitTransferRealtimeGuard(private val arrivals: TransitArrivalService) {
    /** 출발지 재검색과 분리한다. 마지막 열차 1편만 관측하며, 탑승 여부는 추정하지 않는다. */
    fun observeLastPlannedRide(route: EffectiveTransitRoute, evaluatedAt: Instant): PlannedJourneyArrivalObservation? {
        if (evaluatedAt.isBefore(route.departureAt)) return null
        val rides = route.segments.sortedBy { it.sequence }
            .filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }
        if (rides.isEmpty() || rides.any {
                it.kind != EffectiveTransitRouteSegmentKind.SUBWAY || it.rideTiming == null
            }) return null
        val last = rides.last()
        val timing = last.rideTiming ?: return null
        val table = timing.timetable
        if (!table.isObservableServiceDay(route.departureAt, evaluatedAt)) return null
        val singleRide = rides.size == 1
        val pastBoarding = evaluatedAt.isAfter(timing.predictedBoardingAt)
        // A direct ride's boarding was already used for the departure plan. Only an alighting report may update it.
        if (singleRide && !pastBoarding) return null
        val boardingStation = last.fromName?.trim()?.takeIf(String::isNotBlank)
        val alightingStation = last.toName?.trim()?.takeIf(String::isNotBlank)
        val alightingInWindow = alightingStation != null &&
            (table.arrivalAt.isWithinObservationWindow(evaluatedAt) || timing.predictedArrivalAt.isWithinObservationWindow(evaluatedAt))
        val boardingInWindow = table.boardingAt.isWithinObservationWindow(evaluatedAt) ||
            timing.predictedBoardingAt.isWithinObservationWindow(evaluatedAt)
        // Once direct alighting evidence is in the plan, a boarding shift could count the same delay twice.
        val preferAlighting = singleRide || timing.basis == EffectiveTransitRideTimingBasis.TRANSFER_ALIGHTING_REALTIME ||
            (alightingInWindow && pastBoarding)
        val point = if (!preferAlighting && boardingStation != null && boardingInWindow) {
            val scheduledArrivalAt = table.boardingStationArrivalAt ?: table.boardingAt
            val priorArrivalAt = if (timing.basis == EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_ADJUSTED_TIMETABLE) {
                timing.predictedBoardingAt
            } else scheduledArrivalAt
            Triple(boardingStation, scheduledArrivalAt, priorArrivalAt)
        } else {
            if (alightingStation == null || !alightingInWindow) return null
            Triple(alightingStation, table.arrivalAt, timing.predictedArrivalAt)
        }
        val (stationName, scheduledAt, priorAt) = point
        val (observedAt, sourceAt) = observedArrivalAt(stationName, table, evaluatedAt) ?: return null
        if (timing.observationSourceUpdatedAt?.let { !sourceAt.isAfter(it) } == true) return null
        // 앞선 편에 비해 더 늦은 동일 열차만 보수적으로 반영한다. 조기 도착으로
        // 환승이 어려워질 수 있는 관측은 탑승 확인 없이 도착시각 단축에 쓰지 않는다.
        if (!observedAt.isAfter(maxOf(priorAt, scheduledAt))) return null
        val predictedArrivalAt = route.arrivalAt.plusSeconds(java.time.Duration.between(priorAt, observedAt).seconds)
        if (!predictedArrivalAt.isAfter(evaluatedAt)) return null
        return PlannedJourneyArrivalObservation(route.identity, predictedArrivalAt, sourceAt)
    }

    fun observeTransferChanges(journey: TransitJourney, evaluatedAt: Instant): List<TransitRideRealtimeObservation> {
        if (journey.rideLegs.size < 2) return emptyList()
        val firstRideIndex = journey.legs.indexOfFirst { it.isRide }
        return journey.legs.withIndex().asSequence().filter { it.index > firstRideIndex && it.value.isRide }
            .mapNotNull { (index, leg) ->
            val table = leg.timetable ?: return@mapNotNull null
            if (leg.mode != TransitLegMode.SUBWAY ||
                !table.isObservableServiceDay(journey.departureAt, evaluatedAt)) return@mapNotNull null
            val boardingStation = leg.from?.name?.trim()?.takeIf(String::isNotBlank)
            val alightingStation = leg.to?.name?.trim()?.takeIf(String::isNotBlank)
            val alightingInWindow = alightingStation != null && alightingStation != boardingStation &&
                table.arrivalAt.isWithinObservationWindow(evaluatedAt)
            // 승차 시각을 지났고 하차역이 관측 범위에 들어오면, 한 번의 조회를 하차역에 사용한다.
            val preferAlighting = alightingInWindow && evaluatedAt.isAfter(table.boardingAt)
            if (!preferAlighting && boardingStation != null && table.boardingAt.isWithinObservationWindow(evaluatedAt)) {
                val (expectedAt, sourceAt) = observedArrivalAt(boardingStation, table, evaluatedAt) ?: return@mapNotNull null
                val scheduledArrival = table.boardingStationArrivalAt
                // Even a small early shift can consume all of a tight transfer's slack.
                val early = scheduledArrival?.let(expectedAt::isBefore) == true
                val late = expectedAt.isAfter(scheduledArrival ?: table.boardingAt)
                if (!early && !late) return@mapNotNull null
                return@mapNotNull TransitRideRealtimeObservation(
                    index, TransitRideObservationPoint.BOARDING, expectedAt, sourceAt, earlyBoardingArrival = early,
                )
            }
            // 승차역을 이미 지난 차량도 하차역의 신선한 동일 편 관측으로 늦은 운행을 확인한다.
            if (alightingStation == null || !alightingInWindow) return@mapNotNull null
            lateArrivalAt(alightingStation, table, table.arrivalAt, evaluatedAt)
                ?.let { (expectedAt, sourceAt) ->
                    TransitRideRealtimeObservation(index, TransitRideObservationPoint.ALIGHTING, expectedAt, sourceAt)
                }
        }.toList()
    }

    /** A boarding observation does not establish when the first train will reach the transfer station. */
    internal fun observeFirstTransferAlighting(
        journey: TransitJourney,
        boardingPrediction: Instant,
        evaluatedAt: Instant,
    ): TransitRideRealtimeObservation? {
        val firstIndex = journey.legs.indexOfFirst { it.isRide }
        val first = journey.legs.getOrNull(firstIndex) ?: return null
        val table = first.timetable ?: return null
        val station = first.to?.name?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (journey.rideLegs.size < 2 || first.mode != TransitLegMode.SUBWAY ||
            !table.isObservableServiceDay(journey.departureAt, evaluatedAt) ||
            !table.arrivalAt.isWithinObservationWindow(evaluatedAt)) return null
        val (expectedAt, sourceAt) = observedArrivalAt(station, table, evaluatedAt) ?: return null
        if (!expectedAt.isAfter(boardingPrediction)) return null
        return TransitRideRealtimeObservation(firstIndex, TransitRideObservationPoint.ALIGHTING, expectedAt, sourceAt)
    }

    /** null은 확인된 지연이 없거나 공급자 조회 실패다. 같은 구간에서 다른 역으로 재시도하지 않는다. */
    private fun lateArrivalAt(
        stationName: String,
        table: TransitLegTimetable,
        scheduledAt: Instant,
        evaluatedAt: Instant,
    ): Pair<Instant, Instant>? = observedArrivalAt(stationName, table, evaluatedAt)
        ?.takeIf { (expectedAt, _) -> expectedAt.isAfter(scheduledAt) }

    /** 역 도착 원본을 한 번만 조회한다. 성공·미관측 모두 같은 평가에서 다른 역으로 재시도하지 않는다. */
    private fun observedArrivalAt(
        stationName: String,
        table: TransitLegTimetable,
        evaluatedAt: Instant,
    ): Pair<Instant, Instant>? {
        val selected = try {
            arrivals.getSubwayArrivalsForEta(
                stationName, table.line.displayName, null, table.directionCode, 10,
            )
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is InterruptedException ||
                Thread.currentThread().isInterrupted) throw failure
            return null
        }
        return selected.asSequence()
            .filter { it.matches(stationName, table, evaluatedAt) }
            .mapNotNull { row ->
                val sourceAt = Instant.parse(row.sourceUpdatedAt)
                val expectedAt = row.expectedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: row.waitSeconds?.takeIf { it >= 0 }?.let { sourceAt.plusSeconds(it.toLong()) }
                expectedAt?.takeIf { it.isAfter(evaluatedAt.minusSeconds(5)) }
                    ?.let { it to sourceAt }
            }
            .maxByOrNull { (_, sourceAt) -> sourceAt }
    }

    private fun Instant.isWithinObservationWindow(evaluatedAt: Instant): Boolean =
        !isBefore(evaluatedAt.minusSeconds(120)) &&
            !isAfter(evaluatedAt.plusSeconds(LOOKAHEAD_SECONDS))

    private fun TransitLegTimetable.isObservableServiceDay(departureAt: Instant, evaluatedAt: Instant): Boolean {
        val evaluated = evaluatedAt.atZone(SEOUL)
        val today = evaluated.toLocalDate()
        if (serviceDate == today) return true

        // The saved route can carry yesterday's explicit 24–26h timetable into today's early hours.
        // Never infer that a fresh row with a reused train number belongs to an old plan.
        val scheduledArrival = arrivalAt.atZone(SEOUL)
        return serviceDate == today.minusDays(1) && departureAt.atZone(SEOUL).toLocalDate() == serviceDate &&
            evaluated.hour < OVERNIGHT_END_HOUR && scheduledArrival.toLocalDate() == today &&
            scheduledArrival.hour < OVERNIGHT_END_HOUR
    }

    private fun TransitArrivalDto.matches(
        stationName: String?,
        table: TransitLegTimetable,
        evaluatedAt: Instant,
    ): Boolean {
        if (provider != "seoul-openapi" || kind != "SUBWAY" || !realtime ||
            subwayLineId != table.line.realtimeId || normalize(this.stationName) != normalize(stationName) ||
            trainNumber !in setOfNotNull(table.trainNumber, table.realtimeTrainNumber) ||
            normalize(destinationName) != normalize(table.terminalName) ||
            arrivalStatus == TransitArrivalStatus.DEPARTED ||
            freshnessEvidence != TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP ||
            express != (table.serviceClass == TransitServiceClass.EXPRESS)) return false
        val directionMatches = when (table.directionCode) {
            "UP" -> direction?.let { "상행" in it || "내선" in it } == true
            "DOWN" -> direction?.let { "하행" in it || "외선" in it } == true
            else -> false
        }
        if (!directionMatches) return false
        val sourceAt = sourceUpdatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
        return !sourceAt.isBefore(evaluatedAt.minusSeconds(120)) &&
            !sourceAt.isAfter(evaluatedAt.plusSeconds(5))
    }

    private fun normalize(value: String?): String? = value
        ?.replace(Regex("\\([^)]*\\)"), "")
        ?.replace(Regex("\\s+"), "")
        ?.removeSuffix("종착")
        ?.removeSuffix("방면")
        ?.removeSuffix("행")
        ?.removeSuffix("역")
        ?.takeIf(String::isNotBlank)

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        const val OVERNIGHT_END_HOUR = 3
        const val LOOKAHEAD_SECONDS = 15 * 60L
    }
}
