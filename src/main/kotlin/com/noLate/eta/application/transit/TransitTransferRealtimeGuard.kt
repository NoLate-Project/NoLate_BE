package com.noLate.eta.application.transit

import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.eta.domain.SeoulSubwayServiceDay
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.EffectiveTransitRideTiming
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind
import com.noLate.schedule.application.EffectiveTransitRideTimingBasis
import com.noLate.schedule.application.PlannedJourneyArrivalObservation
import com.noLate.schedule.application.ObservedTransitSubwayService
import com.noLate.schedule.application.EffectiveTransitRouteSegment
import com.noLate.schedule.application.ReportedSubwayBoardingEvidence
import com.noLate.schedule.application.ReportedSubwayAlightingEvidence
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.domain.TransitArrivalStatus
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException

enum class TransitRideObservationPoint { BOARDING, ALIGHTING }

/** 원본 시간표와 분리된 동일 열차의 신선한 역 도착 예측. */
data class TransitRideRealtimeObservation(
    val legIndex: Int,
    val point: TransitRideObservationPoint,
    val expectedAt: Instant,
    val sourceUpdatedAt: Instant,
    val earlyBoardingArrival: Boolean = false,
    /** 신선한 ARRIVED 상태가 게시된 시각. 미래 예보나 승객의 탑승 확인이 아니다. */
    val reportedBoardingArrival: Boolean = false,
) {
    init {
        require(!reportedBoardingArrival || point == TransitRideObservationPoint.BOARDING && expectedAt == sourceUpdatedAt)
    }
}

internal data class PlannedTransferObservations(
    val current: List<TransitRideRealtimeObservation> = emptyList(),
    val originalBoardings: Map<Int, TransitRideRealtimeObservation> = emptyMap(),
    val serviceCandidates: Map<Int, List<ObservedSubwayRideCandidate>> = emptyMap(),
    val additionalEndpointReads: Int = 0,
    val reportedBoardings: List<ReportedSubwayBoardingEvidence> = emptyList(),
    val reportedAlightings: List<ReportedSubwayAlightingEvidence> = emptyList(),
)

internal data class LastSubwayRideEvidence(
    val forecast: PlannedJourneyArrivalObservation? = null,
    val report: ReportedSubwayAlightingEvidence? = null,
)

/** 별칭 대조가 아니라, 양 끝 역이 같은 실제 차량에 제공한 두 개의 예보다. */
internal data class ObservedSubwayRideCandidate(
    val service: ObservedTransitSubwayService,
    val boardingAt: Instant,
    val alightingAt: Instant,
    val boardingSourceAt: Instant,
    val alightingSourceAt: Instant,
    val reportedBoarding: ReportedSubwayBoardingEvidence? = null,
) {
    init {
        require(boardingAt < alightingAt)
        if (reportedBoarding == null) require(java.time.Duration.between(boardingSourceAt, alightingSourceAt).abs().seconds <= 125)
        else require(reportedBoarding.service == service && reportedBoarding.sourceUpdatedAt == boardingAt &&
            boardingSourceAt == boardingAt && alightingSourceAt <= alightingAt)
    }
}

/**
 * 뒤의 열차 지연 또는 환승역 조기 도착을 동일 편의 신선한 정보로 대조한다.
 * 미래 차량의 미관측은 운행 변경의 증거가 아니다.
 * 환승 승차역뿐 아니라 하차역의 동일 열차 도착 예측도 대조한다. 하차역 조회는
 * 그 역의 시간표 도착까지 15분 이내일 때만 수행하고, 먼 미래의 미관측은 지연으로 추정하지 않는다.
 */
@Component
class TransitTransferRealtimeGuard(private val arrivals: TransitArrivalService) {
    /** 저장 계획의 구간을 각각 대조한다. 실제 승차 여부를 추정하거나 출발지를 재검색하지 않는다. */
    fun observePlannedRideChanges(route: EffectiveTransitRoute, evaluatedAt: Instant): List<TransitRideRealtimeObservation> =
        observePlannedTransfers(route, evaluatedAt, emptyMap()).current

    internal fun observePlannedTransfers(route: EffectiveTransitRoute, evaluatedAt: Instant,
                                         originalTrains: Map<Int, EffectiveTransitRideTiming>,
                                         recheckBoardingIndex: Int? = null,
                                         reportedBoardings: List<ReportedSubwayBoardingEvidence> = emptyList()): PlannedTransferObservations {
        if (evaluatedAt < route.departureAt) return PlannedTransferObservations()
        val segments = route.segments.sortedBy { it.sequence }
        val firstRide = segments.indexOfFirst { it.kind != EffectiveTransitRouteSegmentKind.WALK }
        if (firstRide < 0 || segments.any {
                it.kind != EffectiveTransitRouteSegmentKind.WALK &&
                    (it.kind != EffectiveTransitRouteSegmentKind.SUBWAY || it.rideTiming == null)
            }) return PlannedTransferObservations()
        val originalBoardings = mutableMapOf<Int, TransitRideRealtimeObservation>()
        val serviceCandidates = mutableMapOf<Int, List<ObservedSubwayRideCandidate>>()
        val serviceLookups = mutableListOf<Pair<Int, () -> Unit>>()
        val reportedAlightings = mutableListOf<ReportedSubwayAlightingEvidence>()
        val records = reportedBoardings.filter { record -> segments.any { record.matches(route, it, evaluatedAt) } }.toMutableList()
        fun recordBoarding(segment: EffectiveTransitRouteSegment, rows: List<TransitArrivalDto>) {
            for (record in reportedBoardingStates(route, segment, evaluatedAt, rows)) {
                val old = records.firstOrNull { it.segmentSequence == record.segmentSequence && it.service == record.service }
                // 이후 ARRIVED 재게시로 탑승 가능한 시각을 뒤로 미루지 않는다.
                if (old != null && old.sourceUpdatedAt <= record.sourceUpdatedAt) continue
                if (old != null) records.remove(old)
                if (records.size < 32) records += record
            }
        }
        fun addCandidates(index: Int, candidates: List<ObservedSubwayRideCandidate>) {
            if (candidates.isNotEmpty()) serviceCandidates[index] = (serviceCandidates[index].orEmpty() + candidates)
                .distinctBy { listOf(it.service, it.boardingAt, it.alightingAt, it.boardingSourceAt, it.alightingSourceAt) }
        }
        val reads = mutableMapOf<Triple<String, String, String>, List<TransitArrivalDto>?>()
        var aborted = false
        var additionalEndpointReads = 0
        fun read(station: String, table: TransitLegTimetable, additional: Boolean = false): List<TransitArrivalDto>? {
            if (aborted) return null
            val key = Triple(station, table.line.realtimeId, table.directionCode)
            if (key in reads) return reads[key]
            // 한 평가에서 보충 역은 최대 한 곳이다. 다음 평가에서 이어서 확인할 수 있다.
            if (additional && additionalEndpointReads >= 1) return null
            if (additional) additionalEndpointReads++
            val rows = try {
                arrivals.getSubwayArrivalsForEta(station, table.line.displayName, null, table.directionCode, 10)
            } catch (failure: Exception) {
                if (failure is CancellationException || failure is InterruptedException ||
                    Thread.currentThread().isInterrupted) throw failure
                aborted = true
                null
            }
            reads[key] = rows
            return rows
        }
        val current = segments.withIndex().mapNotNull { (index, segment) ->
            val timing = segment.rideTiming ?: return@mapNotNull null
            val table = timing.timetable
            if (!table.isObservableServiceDay(route.departureAt, evaluatedAt)) return@mapNotNull null
            val pastBoarding = evaluatedAt > timing.predictedBoardingAt
            // 계획 시각이 지난 지연 차량도 확인한다. 원본 응답의 120초 신선도는 그대로 검증한다.
            val alightingInWindow = (if (timing.observedService == null) listOf(table.arrivalAt, timing.predictedArrivalAt)
                else listOf(timing.predictedArrivalAt)).any {
                it >= evaluatedAt.minusSeconds(20 * 60L) && it <= evaluatedAt.plusSeconds(LOOKAHEAD_SECONDS)
            }
            // 예정 승차가 지났다는 이유로 이미 탔다고 판단하지 않는다. 지연된 같은 편의
            // 새 승차역 예보도 확인하며, 응답 원본의 120초 신선도와 미래 도착 조건은 유지한다.
            val boardingInWindow = (timing.observedService == null && table.boardingAt.isWithinObservationWindow(evaluatedAt, 20 * 60L)) ||
                timing.predictedBoardingAt.isWithinObservationWindow(evaluatedAt, 20 * 60L)
            val original = originalTrains[index]?.takeIf {
                it.timetable.boardingAt.isWithinObservationWindow(evaluatedAt, 20 * 60L)
            }
            val point = when {
                // 승차가 불확실했던 구간은 기존 조회 창 안에서 같은 승차역을 다시 확인한다.
                index == recheckBoardingIndex && boardingInWindow -> TransitRideObservationPoint.BOARDING
                pastBoarding && alightingInWindow -> TransitRideObservationPoint.ALIGHTING
                boardingInWindow || original != null -> TransitRideObservationPoint.BOARDING
                else -> return@mapNotNull null
            }
            val station = (if (point == TransitRideObservationPoint.ALIGHTING) segment.toName else segment.fromName)
                ?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val rows = read(station, table) ?: return@mapNotNull null
            if (point == TransitRideObservationPoint.BOARDING) recordBoarding(segment, rows)
            if (point == TransitRideObservationPoint.ALIGHTING) {
                reportedAlightingState(route, segment, station, evaluatedAt, rows)?.let {
                    reportedAlightings += it
                    // This is a past station status, not another forecast for this ride.
                    return@mapNotNull null
                }
            }
            if (point == TransitRideObservationPoint.ALIGHTING) addCandidates(index,
                pairedReportedServices(route, segment, records, endpointForecasts(station, table, evaluatedAt, rows), evaluatedAt))
            // 한 승차역 응답에 있는 원래 편과 변경된 편을 각각 식별한다.
            if (point == TransitRideObservationPoint.BOARDING && original != null) {
                val seen = observedArrivalAt(station, original.timetable, evaluatedAt, rows,
                    requirePendingStatus = original.timetable.boardingAt <= evaluatedAt)
                val priorSource = original.boardingSourceAt()
                // Without a station arrival timetable, a forecast before departure can also describe normal dwell.
                if (seen != null && seen.first > evaluatedAt &&
                    (original.timetable.boardingStationArrivalAt != null || seen.first >= original.timetable.boardingAt) &&
                    (priorSource == null || seen.second > priorSource ||
                        seen.second == priorSource && seen.first == original.predictedBoardingAt)) {
                    originalBoardings[index] = TransitRideRealtimeObservation(index, point, seen.first, seen.second,
                        earlyBoardingArrival = original.timetable.boardingStationArrivalAt?.let { seen.first < it } == true)
                }
            }
            if (point == TransitRideObservationPoint.BOARDING && !boardingInWindow) return@mapNotNull null
            val reportedBoarding = if (point == TransitRideObservationPoint.BOARDING && timing.observedService == null) {
                reportedLateBoardingArrival(station, table, evaluatedAt, rows)
            } else null
            val sameService = reportedBoarding
                ?: observedArrivalAt(station, table, evaluatedAt, rows, timing.observedService)
            // 실제 편의 승차 예보만으로 비교용 다른 열차의 주행시간을 옮겨 쓰지 않는다.
            val needsPair = sameService == null ||
                (timing.observedService != null && point == TransitRideObservationPoint.BOARDING)
            if (needsPair && boardingInWindow && alightingInWindow &&
                timing.predictedArrivalAt <= evaluatedAt.plusSeconds(LOOKAHEAD_SECONDS) &&
                hasOrderedRide(segment)) {
                val from = requireNotNull(segment.fromName).trim()
                val to = requireNotNull(segment.toName).trim()
                val eligibleHere = endpointForecasts(station, table, evaluatedAt, rows)
                // 빈 목록/지난 차량만 있는 경우에는 보충 HTTP를 사용하지 않는다.
                if (eligibleHere.isNotEmpty() || records.any { it.segmentSequence == segment.sequence }) {
                    // 모든 구간의 기본 관측 뒤에 보충 조회를 배정한다. 지난 첫 구간이
                    // 가까운 환승의 하차역 조회 예산을 먼저 소진하지 않도록 한다.
                    val lookup: () -> Unit = {
                        val other = if (point == TransitRideObservationPoint.ALIGHTING) from else to
                        val otherRows = read(other, table, additional = true)
                        if (otherRows != null) {
                            if (other == from) recordBoarding(segment, otherRows)
                            val boards = if (station == from) eligibleHere else endpointForecasts(from, table, evaluatedAt, otherRows)
                            val alights = if (station == to) eligibleHere else endpointForecasts(to, table, evaluatedAt, otherRows)
                            val candidates = pairedServices(segment, table, boards, alights, evaluatedAt)
                            addCandidates(index, candidates + pairedReportedServices(route, segment, records, alights, evaluatedAt))
                        }
                    }
                    serviceLookups += index to lookup
                }
            }
            if (timing.observedService != null && point == TransitRideObservationPoint.BOARDING) return@mapNotNull null
            val (expected, sourceAt) = sameService ?: return@mapNotNull null
            val previousSource = if (point == TransitRideObservationPoint.ALIGHTING) timing.alightingSourceAt()
                else timing.boardingSourceAt()
            if (previousSource != null && sourceAt <= previousSource) {
                // 이전 버전은 첫 열차의 이른 역 도착만으로 하차까지 앞당겼다. 같은 원본의
                // 같은 예보로 그 계획을 한 번 바로잡을 수 있지만, 더 오래된 예보는 쓸 수 없다.
                val confirmsLegacyEarlyBoarding = sourceAt == previousSource && index == firstRide &&
                    point == TransitRideObservationPoint.BOARDING &&
                    timing.basis == EffectiveTransitRideTimingBasis.PLANNED_RIDE_BOARDING_REALTIME &&
                    expected == timing.predictedBoardingAt && expected < (table.boardingStationArrivalAt ?: table.boardingAt) &&
                    timing.predictedArrivalAt < table.arrivalAt
                if (!confirmsLegacyEarlyBoarding) return@mapNotNull null
            }
            if (point == TransitRideObservationPoint.ALIGHTING && expected <= timing.predictedBoardingAt) return@mapNotNull null
            TransitRideRealtimeObservation(index, point, expected, sourceAt,
                earlyBoardingArrival = point == TransitRideObservationPoint.BOARDING &&
                    expected < (table.boardingStationArrivalAt ?: table.boardingAt),
                reportedBoardingArrival = reportedBoarding != null)
        }
        val prioritized = serviceLookups.sortedWith(compareBy<Pair<Int, () -> Unit>> {
            if (requireNotNull(segments[it.first].rideTiming).predictedBoardingAt >= evaluatedAt) 0 else 1
        }.thenBy { requireNotNull(segments[it.first].rideTiming).predictedBoardingAt })
        for ((_, lookup) in prioritized) {
            if (aborted) break
            lookup()
        }
        return PlannedTransferObservations(current, originalBoardings, serviceCandidates, additionalEndpointReads, records,
            reportedAlightings)
    }

    private fun reportedAlightingState(route: EffectiveTransitRoute, segment: EffectiveTransitRouteSegment, station: String,
                                       evaluatedAt: Instant, rows: List<TransitArrivalDto>): ReportedSubwayAlightingEvidence? {
        if (!hasOrderedRide(segment)) return null
        val timing = requireNotNull(segment.rideTiming)
        val table = timing.timetable
        val service = timing.observedService
        val acceptedNumbers = service?.let { setOf(it.trainNumber) } ?: setOfNotNull(table.trainNumber, table.realtimeTrainNumber)
        val scoped = rows.filter { row ->
            row.provider == "seoul-openapi" && row.kind == "SUBWAY" && row.subwayLineId == table.line.realtimeId &&
                normalize(row.stationName) == normalize(station) && row.trainNumber in acceptedNumbers
        }
        fun parse(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val postedAt = scoped.mapNotNull { parse(it.sourceUpdatedAt) }.maxOrNull() ?: return null
        if (postedAt !in evaluatedAt.minusSeconds(120)..evaluatedAt || postedAt <= timing.predictedBoardingAt ||
            timing.alightingSourceAt()?.let { postedAt < it } == true) return null
        val latest = scoped.filter { parse(it.sourceUpdatedAt) == postedAt }
        if (latest.any { row ->
                !row.matchesEndpoint(station, table, evaluatedAt, service?.terminalName) ||
                    row.arrivalStatus != TransitArrivalStatus.ARRIVED || row.waitSeconds != 0 || parse(row.expectedAt) != postedAt
            }) return null
        val received = latest.map { parse(it.observedAt) }.distinct().singleOrNull() ?: return null
        if (received !in postedAt..minOf(postedAt.plusSeconds(120), evaluatedAt.plusSeconds(5))) return null
        val number = latest.mapNotNull { it.trainNumber }.distinct().singleOrNull()
            ?.takeIf { it.matches(Regex("[A-Z]?[0-9]{1,12}")) && it.any { digit -> digit in '1'..'9' } } ?: return null
        val terminal = normalize(table.terminalName) ?: return null
        val identified = service ?: ObservedTransitSubwayService("seoul-openapi", table.line, table.serviceDate,
            number, table.directionCode, table.serviceClass, terminal, requireNotNull(segment.fromName).trim(), station)
        return ReportedSubwayAlightingEvidence(route.identity, route.departureAt, segment.sequence, identified,
            postedAt, evaluatedAt, received).takeIf { it.matches(route, segment, evaluatedAt) }
    }

    /** 미래 예보가 아닌 ARRIVED/대기0/예보=게시시각만 과거 상태로 기록한다. */
    private fun reportedBoardingStates(route: EffectiveTransitRoute, segment: EffectiveTransitRouteSegment,
                                      evaluatedAt: Instant, rows: List<TransitArrivalDto>): List<ReportedSubwayBoardingEvidence> {
        val table = segment.rideTiming?.timetable ?: return emptyList()
        val from = segment.fromName ?: return emptyList()
        if (!hasOrderedRide(segment)) return emptyList()
        return rows.filter { it.provider == "seoul-openapi" && it.kind == "SUBWAY" &&
            it.subwayLineId == table.line.realtimeId && normalize(it.stationName) == normalize(from) &&
            it.trainNumber?.matches(Regex("[A-Z]?[0-9]{1,12}")) == true && it.trainNumber.any { digit -> digit in '1'..'9' } }
            .groupBy { requireNotNull(it.trainNumber) }.mapNotNull { (number, group) ->
                fun stamp(row: TransitArrivalDto) = row.sourceUpdatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val source = group.mapNotNull(::stamp).maxOrNull() ?: return@mapNotNull null
                if (source > evaluatedAt || source < route.departureAt) return@mapNotNull null
                val latest = group.filter { stamp(it) == source }
                if (latest.any { !it.matchesEndpoint(from, table, evaluatedAt) || it.arrivalStatus != TransitArrivalStatus.ARRIVED ||
                        it.waitSeconds != 0 || it.expectedAt?.let { value -> runCatching { Instant.parse(value) }.getOrNull() } != source })
                    return@mapNotNull null
                val received = latest.map { row -> row.observedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } }
                    .distinct().singleOrNull() ?: return@mapNotNull null
                if (received < source || received > evaluatedAt.plusSeconds(5) ||
                    received > source.plusSeconds(120)) return@mapNotNull null
                val terminal = latest.map { normalize(it.destinationName) }.distinct().singleOrNull() ?: return@mapNotNull null
                ReportedSubwayBoardingEvidence(route.identity, route.departureAt, segment.sequence,
                    ObservedTransitSubwayService("seoul-openapi", table.line, table.serviceDate, number, table.directionCode,
                        table.serviceClass, terminal, from.trim(), requireNotNull(segment.toName).trim()), source, evaluatedAt, received)
            }
    }

    private fun pairedReportedServices(route: EffectiveTransitRoute, segment: EffectiveTransitRouteSegment,
                                       records: List<ReportedSubwayBoardingEvidence>, alights: Map<String, EndpointForecast>,
                                       evaluatedAt: Instant): List<ObservedSubwayRideCandidate> = records.mapNotNull { record ->
        if (!record.matches(route, segment, evaluatedAt)) return@mapNotNull null
        val alight = alights[record.service.trainNumber] ?: return@mapNotNull null
        if (normalize(record.service.terminalName) != alight.terminal ||
            alight.expectedAt <= evaluatedAt || alight.expectedAt <= record.sourceUpdatedAt) return@mapNotNull null
        ObservedSubwayRideCandidate(record.service, record.sourceUpdatedAt, alight.expectedAt,
            record.sourceUpdatedAt, alight.sourceAt, record)
    }

    /** 출발지 재검색과 분리한다. 마지막 열차 1편만 관측하며, 탑승 여부는 추정하지 않는다. */
    @JvmOverloads
    fun observeLastPlannedRide(route: EffectiveTransitRoute, evaluatedAt: Instant,
                               allowAlightingRecovery: Boolean = false): PlannedJourneyArrivalObservation? =
        readLastPlannedRide(route, evaluatedAt, allowAlightingRecovery, includeReport = false)?.forecast

    internal fun readLastPlannedRide(route: EffectiveTransitRoute, evaluatedAt: Instant,
                                     allowAlightingRecovery: Boolean, includeReport: Boolean = true): LastSubwayRideEvidence? {
        if (evaluatedAt.isBefore(route.departureAt)) return null
        val rides = route.segments.sortedBy { it.sequence }
            .filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }
        if (rides.isEmpty() || rides.any {
                it.kind != EffectiveTransitRouteSegmentKind.SUBWAY || it.rideTiming == null
            }) return null
        val last = rides.last()
        val timing = last.rideTiming ?: return null
        // 실제 선택 편은 양 끝 예보와 함께 전체 경로 갱신에서 처리한다. 비교 시간표의
        // 운행시간을 다른 차량에 적용하는 구형 마지막 구간 보정은 사용하지 않는다.
        if (timing.observedService != null && !includeReport) return null
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
        val preferAlighting = singleRide || timing.alightingSourceAt() != null ||
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
        val rows = stationRows(stationName, table)
        val atBoardingStop = stationName == boardingStation
        if (includeReport && !atBoardingStop && pastBoarding) {
            reportedAlightingState(route, last, stationName, evaluatedAt, rows)?.let {
                return LastSubwayRideEvidence(report = it)
            }
        }
        if (timing.observedService != null) return null
        val (observedAt, sourceAt) = observedArrivalAt(stationName, table, evaluatedAt, rows) ?: return null
        val previousSource = if (atBoardingStop) timing.boardingSourceAt() else timing.alightingSourceAt()
        if (previousSource != null && sourceAt <= previousSource) return null
        if (allowAlightingRecovery && !atBoardingStop && observedAt <= evaluatedAt) return null
        if (observedAt <= maxOf(priorAt, scheduledAt)) {
            // Once the final train's delay was observed at its alighting station, a newer pending
            // forecast can reduce that delay. The schedule and all boarding constraints still apply.
            if (!allowAlightingRecovery || !singleRide || !pastBoarding || atBoardingStop || previousSource == null ||
                timing.basis != EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME || observedAt < scheduledAt) return null
            val exact = endpointForecasts(stationName, table, evaluatedAt, rows)
                .filterKeys { it == table.trainNumber || it == table.realtimeTrainNumber }.values.distinct().singleOrNull()
            if (exact?.expectedAt != observedAt || exact.sourceAt != sourceAt ||
                observedArrivalAt(stationName, table, evaluatedAt, rows, requirePendingStatus = true) != (observedAt to sourceAt)) return null
        }
        val predictedArrivalAt = route.arrivalAt.plus(Duration.between(priorAt, observedAt))
        if (!predictedArrivalAt.isAfter(evaluatedAt)) return null
        return LastSubwayRideEvidence(forecast = PlannedJourneyArrivalObservation(route.identity, predictedArrivalAt, sourceAt))
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

    /** 같은 첫 열차가 하차역에 도착할 시각을 승차역 예보와 독립적으로 확인한다. */
    internal fun observeFirstRideAlighting(
        journey: TransitJourney,
        boardingPrediction: Instant,
        evaluatedAt: Instant,
    ): TransitRideRealtimeObservation? {
        val firstIndex = journey.legs.indexOfFirst { it.isRide }
        val first = journey.legs.getOrNull(firstIndex) ?: return null
        val table = first.timetable ?: return null
        val station = first.to?.name?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (first.mode != TransitLegMode.SUBWAY ||
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
    private fun stationRows(stationName: String, table: TransitLegTimetable): List<TransitArrivalDto> =
        try {
            arrivals.getSubwayArrivalsForEta(
                stationName, table.line.displayName, null, table.directionCode, 10,
            )
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is InterruptedException ||
                Thread.currentThread().isInterrupted) throw failure
            emptyList()
        }

    /**
     * 출발 뒤의 기존 계획에만 사용한다. 정상 정차 시간을 지연으로 세지 않도록 원래 출발보다
     * 늦게 게시된 ARRIVED/대기 0/예보=게시시각만 허용한다. 게시 시각은 정확한 물리 도착 초가 아니다.
     * 지난 미래 예보·DEPARTED·미확인 정차는 여전히 제외하며, 새 출발 안내의 탑승 가능 근거로 쓰지 않는다.
     */
    private fun reportedLateBoardingArrival(
        station: String, table: TransitLegTimetable, evaluatedAt: Instant, rows: List<TransitArrivalDto>,
    ): Pair<Instant, Instant>? {
        if (table.boardingStationArrivalAt == null) return null
        val matches = rows.filter { it.matches(station, table, evaluatedAt) }
        val sourceAt = matches.maxOfOrNull { Instant.parse(it.sourceUpdatedAt) } ?: return null
        if (sourceAt > evaluatedAt || sourceAt <= table.boardingAt) return null
        val latest = matches.filter { Instant.parse(it.sourceUpdatedAt) == sourceAt }
        if (latest.any { it.arrivalStatus != TransitArrivalStatus.ARRIVED || it.waitSeconds != 0 ||
                it.expectedAt?.let { value -> runCatching { Instant.parse(value) }.getOrNull() } != sourceAt }) return null
        return sourceAt to sourceAt
    }

    private fun observedArrivalAt(
        stationName: String,
        table: TransitLegTimetable,
        evaluatedAt: Instant,
        rows: List<TransitArrivalDto> = stationRows(stationName, table),
        service: ObservedTransitSubwayService? = null,
        requirePendingStatus: Boolean = false,
    ): Pair<Instant, Instant>? {
        if (service != null) {
            if (!service.matchesReference(table)) return null
            val forecast = endpointForecasts(stationName, table, evaluatedAt, rows)[service.trainNumber] ?: return null
            return forecast.expectedAt to forecast.sourceAt
        }
        val matches = rows.filter { it.matches(stationName, table, evaluatedAt, service) }
        val sourceAt = matches.maxOfOrNull { Instant.parse(it.sourceUpdatedAt) } ?: return null
        val latest = matches.filter { Instant.parse(it.sourceUpdatedAt) == sourceAt }
        if (latest.any { row ->
                when (row.arrivalStatus) {
                    TransitArrivalStatus.DEPARTED -> true
                    TransitArrivalStatus.ARRIVED, TransitArrivalStatus.UNKNOWN -> requirePendingStatus
                    else -> false
                }
            }) return null
        val expectedAt = latest.map { row ->
            row.expectedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: row.waitSeconds?.takeIf { it >= 0 }?.let { sourceAt.plusSeconds(it.toLong()) }
        }.distinct().singleOrNull() ?: return null
        return expectedAt.takeIf { it.isAfter(evaluatedAt.minusSeconds(5)) }?.let { it to sourceAt }
    }

    private fun Instant.isWithinObservationWindow(evaluatedAt: Instant, lookbackSeconds: Long = 120): Boolean =
        !isBefore(evaluatedAt.minusSeconds(lookbackSeconds)) &&
            !isAfter(evaluatedAt.plusSeconds(LOOKAHEAD_SECONDS))

    private fun TransitArrivalDto.matches(
        stationName: String?,
        table: TransitLegTimetable,
        evaluatedAt: Instant,
        service: ObservedTransitSubwayService? = null,
    ): Boolean {
        val numbers = service?.let { setOf(it.trainNumber) } ?: setOfNotNull(table.trainNumber, table.realtimeTrainNumber)
        return trainNumber in numbers && matchesEndpoint(stationName, table, evaluatedAt, service?.terminalName)
    }

    private fun TransitArrivalDto.matchesEndpoint(
        stationName: String?, table: TransitLegTimetable, evaluatedAt: Instant, terminalName: String? = null,
    ): Boolean {
        if (provider != "seoul-openapi" || kind != "SUBWAY" || !realtime ||
            subwayLineId != table.line.realtimeId || normalize(this.stationName) != normalize(stationName) ||
            normalize(destinationName) != normalize(terminalName ?: table.terminalName) ||
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

    private data class EndpointForecast(val expectedAt: Instant, val sourceAt: Instant, val terminal: String)

    /** 같은 번호라도 최신 행의 방향·종착·시각이 상충하면 해당 차량을 제외한다. */
    private fun endpointForecasts(station: String, table: TransitLegTimetable, evaluatedAt: Instant,
                                  rows: List<TransitArrivalDto>): Map<String, EndpointForecast> =
        rows.filter { it.provider == "seoul-openapi" && it.kind == "SUBWAY" &&
            it.subwayLineId == table.line.realtimeId && normalize(it.stationName) == normalize(station) &&
            it.trainNumber?.matches(Regex("[A-Z]?[0-9]{1,12}")) == true &&
            it.trainNumber.any { digit -> digit in '1'..'9' } }
            .groupBy { requireNotNull(it.trainNumber) }.mapNotNull { (train, group) ->
                fun source(row: TransitArrivalDto) = row.sourceUpdatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val stamp = group.mapNotNull(::source).maxOrNull() ?: return@mapNotNull null
                val latest = group.filter { source(it) == stamp }
                if (latest.any { !it.matchesEndpoint(station, table, evaluatedAt) ||
                        it.arrivalStatus in setOf(TransitArrivalStatus.UNKNOWN, TransitArrivalStatus.DEPARTED) ||
                        it.waitSeconds?.let { seconds -> seconds < 0 } == true } ||
                    latest.map { it.arrivalStatus }.distinct().size != 1) return@mapNotNull null
                val expected = latest.map { row ->
                    val explicit = row.expectedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    val fromWait = row.waitSeconds?.let { stamp.plusSeconds(it.toLong()) }
                    if (explicit != null && fromWait != null && explicit != fromWait) null
                    else explicit ?: fromWait
                }.distinct().singleOrNull() ?: return@mapNotNull null
                if (expected <= evaluatedAt.minusSeconds(5) || expected > evaluatedAt.plusSeconds(LOOKAHEAD_SECONDS))
                    return@mapNotNull null
                val terminal = latest.map { normalize(it.destinationName) }.distinct().singleOrNull() ?: return@mapNotNull null
                train to EndpointForecast(expected, stamp, terminal)
            }.toMap()

    private fun hasOrderedRide(segment: EffectiveTransitRouteSegment): Boolean {
        val from = segment.fromName?.trim()?.takeIf { it.length in 1..100 } ?: return false
        val to = segment.toName?.trim()?.takeIf { it.length in 1..100 } ?: return false
        val order = segment.orderedStopNames.map(::normalize)
        return order.size in 2..512 && order.none { it == null } && order.distinct().size == order.size &&
            order.first() == normalize(from) && order.last() == normalize(to) && normalize(from) != normalize(to)
    }

    private fun pairedServices(segment: EffectiveTransitRouteSegment, table: TransitLegTimetable,
                               boards: Map<String, EndpointForecast>, alights: Map<String, EndpointForecast>,
                               evaluatedAt: Instant): List<ObservedSubwayRideCandidate> =
        boards.mapNotNull { (train, boarding) ->
            val alighting = alights[train] ?: return@mapNotNull null
            if (boarding.terminal != alighting.terminal || alighting.expectedAt <= evaluatedAt ||
                alighting.expectedAt <= boarding.expectedAt ||
                java.time.Duration.between(boarding.sourceAt, alighting.sourceAt).abs().seconds > 125) return@mapNotNull null
            ObservedSubwayRideCandidate(ObservedTransitSubwayService("seoul-openapi", table.line, table.serviceDate,
                train, table.directionCode, table.serviceClass, boarding.terminal,
                requireNotNull(segment.fromName).trim(), requireNotNull(segment.toName).trim()),
                boarding.expectedAt, alighting.expectedAt, boarding.sourceAt, alighting.sourceAt)
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
        const val LOOKAHEAD_SECONDS = 15 * 60L
    }
}
