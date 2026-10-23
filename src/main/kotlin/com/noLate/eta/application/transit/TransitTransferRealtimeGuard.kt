package com.noLate.eta.application.transit

import com.noLate.eta.domain.TransitJourney
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind
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
)

/**
 * 첫 승차가 실시간이어도 뒤의 시간표 열차가 이미 지연됐다는 신선한 증거가 있으면
 * 그 시간표 ETA를 정상 결과로 채택하지 않는다. 미래 차량의 미관측은 지연 증거가 아니다.
 * 환승 승차역뿐 아니라 하차역의 동일 열차 도착 예측도 대조한다. 하차역 조회는
 * 그 역의 시간표 도착까지 15분 이내일 때만 수행하고, 먼 미래의 미관측은 지연으로 추정하지 않는다.
 */
@Component
class TransitTransferRealtimeGuard(private val arrivals: TransitArrivalService) {
    /** 출발지 재검색과 분리한다. 마지막 환승 열차 1편만 관측하며, 탑승 여부는 추정하지 않는다. */
    fun observeLastPlannedRide(route: EffectiveTransitRoute, evaluatedAt: Instant): PlannedJourneyArrivalObservation? {
        if (evaluatedAt.isBefore(route.departureAt)) return null
        val rides = route.segments.sortedBy { it.sequence }
            .filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }
        if (rides.size < 2 || rides.any {
                it.kind != EffectiveTransitRouteSegmentKind.SUBWAY || it.rideTiming == null
            }) return null
        val last = rides.last()
        val timing = last.rideTiming ?: return null
        val table = timing.timetable
        if (table.serviceDate != evaluatedAt.atZone(SEOUL).toLocalDate()) return null
        val boardingStation = last.fromName?.trim()?.takeIf(String::isNotBlank)
        val point = if (boardingStation != null && table.boardingAt.isWithinObservationWindow(evaluatedAt)) {
            Triple(boardingStation, table.boardingAt, timing.predictedBoardingAt)
        } else {
            val alightingStation = last.toName?.trim()?.takeIf(String::isNotBlank) ?: return null
            if (!table.arrivalAt.isWithinObservationWindow(evaluatedAt)) return null
            Triple(alightingStation, table.arrivalAt, timing.predictedArrivalAt)
        }
        val (stationName, scheduledAt, priorAt) = point
        val (observedAt, sourceAt) = observedArrivalAt(stationName, table, evaluatedAt) ?: return null
        // 앞선 편에 비해 더 늦은 동일 열차만 보수적으로 반영한다. 조기 도착으로
        // 환승이 어려워질 수 있는 관측은 탑승 확인 없이 도착시각 단축에 쓰지 않는다.
        if (!observedAt.isAfter(priorAt.plusSeconds(MIN_DELAY_SECONDS)) ||
            !observedAt.isAfter(scheduledAt.plusSeconds(MIN_DELAY_SECONDS))) return null
        val predictedArrivalAt = route.arrivalAt.plusSeconds(java.time.Duration.between(priorAt, observedAt).seconds)
        if (!predictedArrivalAt.isAfter(evaluatedAt)) return null
        return PlannedJourneyArrivalObservation(route.identity, predictedArrivalAt, sourceAt)
    }

    fun hasVerifiedLateTransfer(journey: TransitJourney, evaluatedAt: Instant): Boolean =
        observeLateTransfers(journey, evaluatedAt).isNotEmpty()

    fun observeLateTransfers(journey: TransitJourney, evaluatedAt: Instant): List<TransitRideRealtimeObservation> {
        if (journey.rideLegs.size < 2) return emptyList()
        val serviceDate = evaluatedAt.atZone(SEOUL).toLocalDate()
        val firstRideIndex = journey.legs.indexOfFirst { it.isRide }
        return journey.legs.withIndex().asSequence().filter { it.index > firstRideIndex && it.value.isRide }
            .mapNotNull { (index, leg) ->
            val table = leg.timetable ?: return@mapNotNull null
            if (leg.mode != TransitLegMode.SUBWAY || table.serviceDate != serviceDate) return@mapNotNull null
            val boardingStation = leg.from?.name?.trim()?.takeIf(String::isNotBlank)
            // 한 구간에 한 역만 조회한다. 승차역 조회 범위가 끝나야 하차역으로
            // 전환하며, 같은 평가에서 두 역을 연속 조회해 공개 API 한도를 소모하지 않는다.
            if (boardingStation != null && table.boardingAt.isWithinObservationWindow(evaluatedAt)) {
                return@mapNotNull lateArrivalAt(
                    boardingStation, table, table.boardingAt, evaluatedAt,
                )?.let { (expectedAt, sourceAt) ->
                    TransitRideRealtimeObservation(index, TransitRideObservationPoint.BOARDING, expectedAt, sourceAt)
                }
            }
            // 승차역을 이미 지난 차량도 하차역의 신선한 동일 편 관측으로 늦은 운행을 확인한다.
            val alightingStation = leg.to?.name?.trim()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            if (!table.arrivalAt.isWithinObservationWindow(evaluatedAt) ||
                alightingStation == boardingStation) return@mapNotNull null
            lateArrivalAt(alightingStation, table, table.arrivalAt, evaluatedAt)
                ?.let { (expectedAt, sourceAt) ->
                    TransitRideRealtimeObservation(index, TransitRideObservationPoint.ALIGHTING, expectedAt, sourceAt)
                }
        }.toList()
    }

    /** null은 확인된 지연이 없거나 공급자 조회 실패다. 같은 구간에서 다른 역으로 재시도하지 않는다. */
    private fun lateArrivalAt(
        stationName: String,
        table: com.noLate.eta.domain.TransitLegTimetable,
        scheduledAt: Instant,
        evaluatedAt: Instant,
    ): Pair<Instant, Instant>? = observedArrivalAt(stationName, table, evaluatedAt)
        ?.takeIf { (expectedAt, _) -> expectedAt.isAfter(scheduledAt.plusSeconds(MIN_DELAY_SECONDS)) }

    /** 역 도착 원본을 한 번만 조회한다. 성공·미관측 모두 같은 평가에서 다른 역으로 재시도하지 않는다. */
    private fun observedArrivalAt(
        stationName: String,
        table: com.noLate.eta.domain.TransitLegTimetable,
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

    private fun TransitArrivalDto.matches(
        stationName: String?,
        table: com.noLate.eta.domain.TransitLegTimetable,
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
        const val LOOKAHEAD_SECONDS = 15 * 60L
        const val MIN_DELAY_SECONDS = 60L
    }
}
