package com.noLate.eta.application.transit

import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.application.port.DatedTransitTimetableProvider
import com.noLate.eta.application.port.SEOUL_DATED_TIMETABLE_PROVIDER_ID
import com.noLate.eta.domain.*
import com.noLate.eta.resilience.EtaCalculationDeadline
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CancellationException

data class TransitTimetablePreview(
    val departureAt: Instant,
    val arrivalAt: Instant,
    val rides: List<TransitLegTimetable>,
    val transferTimings: List<TransitTransferTimingEvidence>,
    val entranceWalk: TransitTimetableEntranceWalk? = null,
    val exitWalk: TransitTimetableEntranceWalk? = null,
)

data class TransitTimetableEntranceWalk(val seconds: Long, val distanceMeters: Long)

internal fun TransitJourney.requiresDatedTimetableForAdjacentRingRide(): Boolean =
    provider == "odsay" && rideLegs.any { leg ->
        leg.line?.seoulSubwayLine == SeoulSubwayLine.LINE_2 && leg.directionCode == null &&
            leg.orderedStopNames.size == 2 && leg.nextStopName == leg.to?.name
    }

/** 전 구간을 확인한 경우에만 추정 여정을 시간표 여정으로 교체한다. 부분 성공은 ETA가 아니다. */
@Component
class TransitJourneyTimetableEnricher(
    private val providers: List<TransitTimetableProvider>,
    @Value("\${eta.transit.boarding-buffer-seconds:60}") internal val boardingBufferSeconds: Long = 60,
    @Value("\${eta.transit.transfer-confidence-margin-seconds:60}") private val transferMarginSeconds: Long = 60,
    @Value("\${eta.transit.transfer-buffer-seconds:0}") private val transferBufferSeconds: Long = 0,
    private val rideArrivalPredictor: TransitRideArrivalPredictor = TransitRideArrivalPredictor(),
    private val datedTimetableProvider: DatedTransitTimetableProvider? = null,
    private val calculationDeadline: EtaCalculationDeadline = EtaCalculationDeadline(),
) {
    init {
        require(boardingBufferSeconds in 0..600 && transferMarginSeconds in 0..600 && transferBufferSeconds in 0..600)
    }

    /** Keep the requested door departure fixed, including any wait before the first train. */
    fun planDepartingAt(
        journey: TransitJourney,
        departureAt: Instant,
        evaluatedAt: Instant,
        maxTravelMinutes: Int,
        datedTimetable: DatedTransitTimetableProvider? = null,
    ): TransitTimetablePreview? {
        if (departureAt.nano != 0 || departureAt < evaluatedAt) return null
        val requested = journey.copy(requestedDepartureAt = departureAt, departureAt = departureAt)
        val planned = if (datedTimetable == null) enrich(requested, evaluatedAt, maxTravelMinutes)
        else enrichWith(requested, evaluatedAt, maxTravelMinutes, firstBoardingResolver = null) { query, leg ->
            listOfNotNull(datedTimetable.findRide(query, leg.orderedStopNames))
        } ?: return null
        val rides = planned.rideLegs.map { it.timetable ?: return null }
        if (rides.isEmpty() || planned.arrivalAt <= departureAt ||
            rides.any { it.boardingAt < departureAt }) return null
        return buildPreview(planned, departureAt, planned.arrivalAt, rides)
    }

    /** The selected route stays fixed. Follow the timetable backwards from the appointment. */
    fun planArrivalBy(
        journey: TransitJourney,
        targetArrivalAt: Instant,
        evaluatedAt: Instant,
        maxTravelMinutes: Int,
        datedTimetable: DatedTransitTimetableProvider? = null,
    ): TransitTimetablePreview? {
        if (journey.rideLegs.isEmpty() || journey.legs.any {
                it.mode != TransitLegMode.WALK && (it.mode != TransitLegMode.SUBWAY || it.line?.seoulSubwayLine == null)
            }) return null
        val date = SeoulSubwayServiceDay.dateAt(targetArrivalAt)
        val maxSeconds = maxTravelMinutes.toLong() * 60
        val earliest = maxOf(evaluatedAt, targetArrivalAt.minusSeconds(maxSeconds),
            SeoulSubwayServiceDay.startsAt(date))
        if (!earliest.isBefore(targetArrivalAt)) return null
        if (datedTimetable != null) {
            return planDatedArrivalBy(journey, targetArrivalAt, evaluatedAt, earliest, maxTravelMinutes, datedTimetable)
        }
        if (!SeoulSubwayServiceDay.hasCurrentStationTables(date, evaluatedAt)) return null
        return providers.firstNotNullOfOrNull { provider ->
            val rides = mutableListOf<TransitLegTimetable>()
            var latest = targetArrivalAt
            for (leg in journey.legs.asReversed()) {
                if (leg.mode == TransitLegMode.WALK) {
                    latest = latest.minusSeconds(leg.movementDurationSeconds)
                    continue
                }
                val line = requireNotNull(leg.line)
                val canonical = requireNotNull(line.seoulSubwayLine)
                val query = TransitTimetableQuery(canonical, leg.from?.name ?: return null, leg.to?.name ?: return null,
                    leg.nextStopName, line.serviceClass, earliest, leg.directionCode, serviceDate = date)
                val ride = provider.findRides(query)
                    .filter { it.line == canonical && it.serviceDate == date && it.serviceClass == line.serviceClass &&
                        (leg.directionCode == null || it.directionCode == leg.directionCode) &&
                        it.boardingAt >= earliest && it.arrivalAt <= latest }
                    .maxWithOrNull(compareBy<TransitLegTimetable> { it.boardingAt }.thenByDescending { it.arrivalAt })
                    ?: return@firstNotNullOfOrNull null
                rides.add(ride)
                val margin = if (rides.size == journey.rideLegs.size) boardingBufferSeconds
                    else maxOf(transferBufferSeconds, transferMarginSeconds)
                latest = ride.boardingAt.minusSeconds(margin)
            }
            val egressSeconds = journey.legs.takeLastWhile { !it.isRide }.sumOf { it.movementDurationSeconds }
            val arrivalAt = rides.first().arrivalAt.plusSeconds(egressSeconds)
            if (latest < earliest || Duration.between(latest, arrivalAt).seconds !in 1..maxSeconds) null
            else buildPreview(journey, latest, arrivalAt, rides.asReversed().toList())
        }
    }

    /** The dated endpoint searches forward. Keep only complete connections within the arrival deadline. */
    private fun planDatedArrivalBy(
        journey: TransitJourney,
        targetArrivalAt: Instant,
        evaluatedAt: Instant,
        earliest: Instant,
        maxTravelMinutes: Int,
        provider: DatedTransitTimetableProvider,
    ): TransitTimetablePreview? {
        val accessAndBoarding = journey.legs.takeWhile { !it.isRide }.sumOf { it.movementDurationSeconds } +
            boardingBufferSeconds
        val transferCount = journey.rideLegs.size - 1
        val estimatedSeconds = journey.legs.sumOf { it.movementDurationSeconds } + boardingBufferSeconds +
            transferCount * maxOf(transferBufferSeconds, transferMarginSeconds)
        val firstWholeSecond = if (earliest.nano == 0) earliest else Instant.ofEpochSecond(earliest.epochSecond + 1)
        var searchAt = maxOf(firstWholeSecond, Instant.ofEpochSecond(targetArrivalAt.epochSecond - estimatedSeconds))
        val tried = mutableSetOf<Instant>()
        val ridesByQuery = mutableMapOf<Pair<TransitTimetableQuery, List<String>>, List<TransitLegTimetable>>()
        val lookup = DatedTransitTimetableProvider { query, stops ->
            ridesByQuery.getOrPut(query to stops) { listOfNotNull(provider.findRide(query, stops)) }.singleOrNull()
        }
        var best: TransitTimetablePreview? = null
        repeat(3) { index ->
            if (searchAt >= targetArrivalAt || !tried.add(searchAt)) return best
            val plan = try {
                planDepartingAt(journey, searchAt, evaluatedAt, maxTravelMinutes, lookup)
            } catch (failure: Exception) {
                if (failure is CancellationException || failure is InterruptedException ||
                    Thread.currentThread().isInterrupted) throw failure
                return best ?: throw failure
            }
            if (plan != null && plan.arrivalAt <= targetArrivalAt) {
                val latestDeparture = plan.rides.first().boardingAt.minusSeconds(accessAndBoarding)
                best = plan.copy(departureAt = latestDeparture)
                // A later first train might still make the connection. Advance past this boarding cutoff.
                searchAt = latestDeparture.plusSeconds(1)
                return@repeat
            }
            if (best != null) return best
            val lateBy = plan?.let { Duration.between(targetArrivalAt, it.arrivalAt).seconds } ?: 0L
            // An earlier probe must also cross a possible plateau where multiple starts select the same train.
            val backtrack = maxOf(lateBy + 1, (index + 1L) * 5 * 60)
            searchAt = maxOf(firstWholeSecond, searchAt.minusSeconds(backtrack))
        }
        return best
    }

    private fun buildPreview(
        journey: TransitJourney,
        departureAt: Instant,
        arrivalAt: Instant,
        rides: List<TransitLegTimetable>,
    ): TransitTimetablePreview {
        val transfers = journey.legs.indices.filter { journey.legs[it].isRide }.zipWithNext().mapIndexed { index, (from, to) ->
            TransitTransferTimingEvidence(
                previousArrivalAt = rides[index].arrivalAt,
                walkingSeconds = journey.legs.subList(from + 1, to).sumOf { it.movementDurationSeconds },
                bufferSeconds = transferBufferSeconds,
                confidenceMarginSeconds = transferMarginSeconds,
                boardingAt = rides[index + 1].boardingAt,
            )
        }
        val access = journey.legs.firstOrNull()
            ?.takeIf { it.walkingTiming?.source in setOf(TransitWalkingTimingSource.ODSAY_ENTRANCE_WALK_SECONDS,
                TransitWalkingTimingSource.ODSAY_FULL_PATH_SECONDS) }
            ?.let { leg -> TransitTimetableEntranceWalk(leg.movementDurationSeconds, requireNotNull(leg.walkingGeometry?.distanceMeters)) }
        val exit = journey.legs.lastOrNull()
            ?.takeIf { it.walkingTiming?.source == TransitWalkingTimingSource.ODSAY_EXIT_WALK_SECONDS }
            ?.let { leg -> TransitTimetableEntranceWalk(leg.movementDurationSeconds, requireNotNull(leg.walkingGeometry?.distanceMeters)) }
        return TransitTimetablePreview(departureAt, arrivalAt, rides, transfers, access, exit)
    }

    fun enrich(
        journey: TransitJourney,
        evaluatedAt: Instant,
        maxTravelMinutes: Int,
        firstBoardingResolver: ((TransitLegTimetable) -> TransitFirstBoardingObservation?)? = null,
    ): TransitJourney {
        // Tmap은 정수 초 출발, 분 단위 공급자는 정수 분 출발을 쓴다.
        // 소수초 입력을 초 모델에 암묵적으로 절삭하거나 과거 출발을 허용하지 않는다.
        if (journey.departureAt.nano != 0 || journey.departureAt.isBefore(evaluatedAt) ||
            journey.rideLegs.any { it.mode != TransitLegMode.SUBWAY } ||
            journey.rideLegs.all { it.timingBasis == TransitLegTimingBasis.TIMETABLE }) return journey
        if (journey.requiresDatedTimetableForAdjacentRingRide()) {
            val dated = datedTimetableProvider ?: return journey
            return try {
                // The dated endpoint returns one forward connection. Resolve its exact train before applying realtime.
                enrichWith(journey, evaluatedAt, maxTravelMinutes, firstBoardingResolver = null) { query, leg ->
                    listOfNotNull(dated.findRide(query, leg.orderedStopNames))
                } ?: journey
            } catch (failure: Exception) {
                if (failure is CancellationException || failure is InterruptedException ||
                    Thread.currentThread().isInterrupted) throw failure
                journey
            }
        }
        // 공급자를 혼합해 부분 성공을 조립하지 않는다. 한 공급자가 전체 여정을 증명해야 한다.
        return providers.firstNotNullOfOrNull { provider ->
            runCatching {
                enrichWith(journey, evaluatedAt, maxTravelMinutes, firstBoardingResolver) { query, _ ->
                    provider.findRides(query)
                }
            }.getOrNull()
        } ?: journey
    }

    /**
     * 검증한 첫 열차는 그대로 두고, 그 지연으로 탈 수 없게 된 이후 환승 시간표만 다시 연결한다.
     * 첫 열차의 원본 시간표를 실시간 시각으로 덮어쓰지 않으며, 부분 회복은 채택하지 않는다.
     */
    fun reconnectTransfers(
        journey: TransitJourney,
        firstBoardingOverlay: TransitRealtimeOverlay,
        maxTravelMinutes: Int,
        observedFirstAlightingAt: Instant? = null,
    ): TransitJourney {
        val first = journey.rideLegs.firstOrNull()?.timetable ?: return journey
        // 하차역의 동일 편 관측이 있으면 승차역 지연을 남은 구간에 전파한 값보다 우선한다.
        if (observedFirstAlightingAt != null && observedFirstAlightingAt <= firstBoardingOverlay.boardingAt) return journey
        val delay = observedFirstAlightingAt?.let { Duration.between(first.arrivalAt, it) }
            ?: first.shiftFromObservedBoardingArrival(firstBoardingOverlay.boardingAt)
        if (delay.isNegative || delay.isZero || journey.rideLegs.size < 2 ||
            journey.rideLegs.any { it.mode != TransitLegMode.SUBWAY || it.timetable?.provider != first.provider }) return journey
        // 한 공급자로 전체 환승을 복구한다. 실패 시 다른 공급자의 구간을 섞지 않는다.
        return reconnectWithTimetable(journey, first.provider, delay, maxTravelMinutes) ?: journey
    }

    /**
     * 첫 열차가 실제로 더 일찍 올 때도 같은 도보·여유를 지키며 이후 시간표를 다시 연결한다.
     * 기존 환승이 가능하다는 사실만으로 더 이른 환승을 놓치지 않도록 한다.
     * 첫 열차의 하차역에서 같은 편의 신선한 도착 예측을 확인한 경우만 검토한다.
     */
    fun reconnectEarlierTransfers(
        journey: TransitJourney,
        firstBoardingOverlay: TransitRealtimeOverlay,
        observation: TransitFirstBoardingObservation?,
        evaluatedAt: Instant,
        maxTravelMinutes: Int,
        observedFirstAlightingAt: Instant? = null,
    ): TransitJourney {
        val firstLeg = journey.rideLegs.firstOrNull() ?: return journey
        val first = firstLeg.timetable ?: return journey
        val shift = first.shiftFromObservedBoardingArrival(firstBoardingOverlay.boardingAt)
        val observedArrival = observedFirstAlightingAt ?: return journey
        if (!shift.isNegative || journey.rideLegs.size < 2 ||
            observedArrival <= firstBoardingOverlay.boardingAt || observedArrival >= first.arrivalAt ||
            journey.departureAt < evaluatedAt ||
            journey.rideLegs.any { it.mode != TransitLegMode.SUBWAY || it.timetable?.provider != first.provider }) return journey
        val verified = observation ?: return journey
        val accessSeconds = journey.legs.takeWhile { !it.isRide }.sumOf { it.movementDurationSeconds }
        val readyAt = journey.departureAt.plusSeconds(accessSeconds + boardingBufferSeconds)
        if (verified.provider != "seoul-openapi" || verified.stationName != firstLeg.from?.name ||
            verified.lineName != first.line.displayName ||
            verified.trainNumber !in setOfNotNull(first.trainNumber, first.realtimeTrainNumber) ||
            verified.departureAt != journey.departureAt || verified.evaluatedAt != evaluatedAt ||
            verified.expectedBoardingAt != firstBoardingOverlay.boardingAt || verified.readyAt < readyAt ||
            !verified.isFreshAt(evaluatedAt, 120)) return journey
        // 동일 열차를 유지하거나 더 늦어지면 원래 여정을 보존한다. 부분 성공도 채택하지 않는다.
        val arrival = maxOf(first.arrivalAt.plus(shift), observedArrival)
        return reconnectWithTimetable(journey, first.provider, Duration.between(first.arrivalAt, arrival), maxTravelMinutes) {
            it.arrivalAt < journey.arrivalAt
        }
            ?: journey
    }

    /** 관측한 승차 구간까지 보존하고, 그 하차 시각 뒤의 환승만 다시 연결한다. */
    internal fun reconnectAfterObservedRide(
        journey: TransitJourney,
        rideIndex: Int,
        observedArrivalAt: Instant,
        maxTravelMinutes: Int,
    ): TransitJourney {
        val rideIndexes = journey.legs.indices.filter { journey.legs[it].isRide }
        if (rideIndex !in rideIndexes.dropLast(1)) return journey
        val anchor = journey.legs[rideIndex].timetable ?: return journey
        // 현재 계획과 도보·여유를 대조해 환승이 불가능해진 호출이다. 시간표 정시 회복도 포함한다.
        if (journey.rideLegs.any {
                it.mode != TransitLegMode.SUBWAY || it.timetable?.provider != anchor.provider
            }) return journey
        val shift = Duration.between(anchor.arrivalAt, observedArrivalAt)
        return reconnectWithTimetable(journey, anchor.provider, shift, maxTravelMinutes,
            anchorRideIndex = rideIndex) ?: journey
    }

    private fun reconnectWithTimetable(
        journey: TransitJourney,
        providerId: String,
        firstShift: Duration,
        maxTravelMinutes: Int,
        anchorRideIndex: Int = journey.legs.indexOfFirst { it.isRide },
        accept: (TransitJourney) -> Boolean = { true },
    ): TransitJourney? {
        if (providerId == SEOUL_DATED_TIMETABLE_PROVIDER_ID) {
            val dated = datedTimetableProvider ?: return null
            return runCatching {
                // 출발 후 Worker 호출에도 남은 모든 환승이 공유하는 조회 예산을 둔다.
                calculationDeadline.within {
                    reconnectWith(journey, firstShift, maxTravelMinutes, anchorRideIndex) { query, leg ->
                        listOfNotNull(dated.findRide(query, leg.orderedStopNames))
                    }
                }
            }.getOrNull()?.takeIf(accept)
        }
        return providers.firstNotNullOfOrNull { provider ->
            runCatching {
                reconnectWith(journey, firstShift, maxTravelMinutes, anchorRideIndex) { query, _ -> provider.findRides(query) }
            }.getOrNull()?.takeIf(accept)
        }
    }

    private fun reconnectWith(
        journey: TransitJourney,
        firstDelay: Duration,
        maxTravelMinutes: Int,
        anchorRideIndex: Int,
        findRides: (TransitTimetableQuery, TransitJourneyLeg) -> List<TransitLegTimetable>,
    ): TransitJourney? {
        val anchor = requireNotNull(journey.legs[anchorRideIndex].timetable)
        var cursor = anchor.arrivalAt.plus(firstDelay)
        val latestArrival = journey.departureAt.plusSeconds(maxTravelMinutes.toLong() * 60)
        val legs = journey.legs.mapIndexed { index, leg ->
            if (index <= anchorRideIndex) return@mapIndexed leg
            val start = cursor
            if (!leg.isRide) {
                if (leg.mode != TransitLegMode.WALK) return null
                cursor = cursor.plusSeconds(leg.movementDurationSeconds)
                leg.copy(scheduledDepartureAt = start, scheduledArrivalAt = cursor)
            } else {
                val original = leg.timetable ?: return null
                val query = TransitTimetableQuery(
                    original.line, leg.from?.name ?: return null, leg.to?.name ?: return null,
                    leg.nextStopName, original.serviceClass,
                    cursor.plusSeconds(maxOf(transferBufferSeconds, transferMarginSeconds)), original.directionCode,
                    // 지연으로 자정을 넘어도 검증한 원래 열차의 운행일을 유지한다.
                    serviceDate = original.serviceDate,
                )
                val ride = findRides(query, leg).filter {
                    // 같은 경로인지 이름만이 아니라 원본 시간표의 승하차 역코드로도 확인한다.
                    it.provider == original.provider && it.line == original.line &&
                        it.fromStationCode == original.fromStationCode && it.toStationCode == original.toStationCode &&
                        it.serviceDate == original.serviceDate && it.serviceClass == original.serviceClass &&
                        it.directionCode == original.directionCode && !it.boardingAt.isBefore(query.notBefore) &&
                        !it.arrivalAt.isAfter(latestArrival)
                }.minWithOrNull(compareBy<TransitLegTimetable> { it.boardingAt }.thenBy { it.arrivalAt }) ?: return null
                cursor = ride.arrivalAt
                val seconds = Duration.between(start, cursor).seconds
                leg.copy(
                    durationSeconds = seconds, durationMinutes = ceilMinutes(seconds),
                    waitingMinutes = ceilMinutes(Duration.between(start, ride.boardingAt).seconds),
                    scheduledDepartureAt = start, scheduledArrivalAt = cursor,
                    directionCode = ride.directionCode, directionName = null, timetable = ride,
                )
            }
        }
        val seconds = Duration.between(journey.departureAt, cursor).seconds
        if (seconds !in 1..maxTravelMinutes.toLong() * 60) return null
        return journey.copy(arrivalAt = cursor, totalMinutes = ceilMinutes(seconds), legs = legs)
    }

    private fun enrichWith(
        journey: TransitJourney,
        evaluatedAt: Instant,
        maxTravelMinutes: Int,
        firstBoardingResolver: ((TransitLegTimetable) -> TransitFirstBoardingObservation?)?,
        findRides: (TransitTimetableQuery, TransitJourneyLeg) -> List<TransitLegTimetable>,
    ): TransitJourney? {
        var cursor = journey.departureAt
        var journeyServiceDate: LocalDate? = null
        val legs = journey.legs.map { leg ->
            val start = cursor
            if (!leg.isRide) {
                if (leg.mode != TransitLegMode.WALK) return null
                cursor = cursor.plusSeconds(leg.movementDurationSeconds)
                leg.copy(scheduledDepartureAt = start, scheduledArrivalAt = cursor)
            } else {
                val line = leg.line ?: return null
                val canonical = line.seoulSubwayLine ?: return null
                val isFirstRide = journeyServiceDate == null
                val margin = if (isFirstRide) boardingBufferSeconds else maxOf(transferBufferSeconds, transferMarginSeconds)
                val notBefore = cursor.plusSeconds(margin)
                val serviceDate = SeoulSubwayServiceDay.dateAt(notBefore)
                // 한 번 연결한 운행일은 후속 환승에도 유지한다.
                if (!isFirstRide && serviceDate != journeyServiceDate) return null
                // 실시간으로 동일 열차를 검증할 수 있을 때만 5분 이내의 지난 시간표를 후보로 조회한다.
                // 다음 환승에는 현재 도착정보를 적용하지 않으며 기존 여유시간을 그대로 지킨다.
                val canRecoverDelayed = isFirstRide && firstBoardingResolver != null &&
                    notBefore <= evaluatedAt.plusSeconds(20 * 60)
                // 확대 조회가 마감된 역 시간표로 넘어가 정상 후보까지 잃지 않도록 한다.
                val queryNotBefore = if (canRecoverDelayed) maxOf(
                    notBefore.minusSeconds(300), SeoulSubwayServiceDay.startsAt(serviceDate),
                    evaluatedAt.atZone(SeoulSubwayServiceDay.zone).toLocalDate()
                        .atStartOfDay(SeoulSubwayServiceDay.zone).toInstant(),
                ) else notBefore
                val query = TransitTimetableQuery(canonical, leg.from?.name ?: return null, leg.to?.name ?: return null,
                    leg.nextStopName, line.serviceClass, queryNotBefore, leg.directionCode,
                    serviceDate = serviceDate)
                val rides = findRides(query, leg).filter { ride ->
                    ride.line == canonical && ride.serviceClass == line.serviceClass && ride.serviceDate == serviceDate &&
                        !ride.boardingAt.isBefore(queryNotBefore) &&
                        (leg.directionCode == null || leg.directionCode == ride.directionCode) &&
                        ride.arrivalAt <= journey.departureAt.plusSeconds(maxTravelMinutes.toLong() * 60)
                }
                val recovered = if (canRecoverDelayed) rides.asSequence()
                    .filter { it.boardingAt < notBefore }
                    .mapNotNull { ride -> firstBoardingResolver?.invoke(ride)?.takeIf { observation ->
                        observation.evaluatedAt == evaluatedAt && observation.departureAt == journey.departureAt &&
                            observation.isFreshAt(evaluatedAt, 120) && observation.readyAt >= notBefore &&
                            observation.expectedBoardingAt >= notBefore && observation.expectedBoardingAt >= ride.boardingAt
                    }?.let { ride to it } }
                    .minByOrNull { it.second.expectedBoardingAt } else null
                val ride = recovered?.first ?: rides.filter { it.boardingAt >= notBefore }
                    .minWithOrNull(compareBy<TransitLegTimetable> { it.boardingAt }.thenBy { it.arrivalAt }) ?: return null
                val observation = recovered?.second
                journeyServiceDate = ride.serviceDate
                cursor = observation?.let {
                    rideArrivalPredictor.predictFromObservedBoardingArrival(ride, it.expectedBoardingAt).arrivalAt
                } ?: ride.arrivalAt
                val seconds = Duration.between(start, cursor).seconds
                val waitSeconds = Duration.between(start, observation?.expectedBoardingAt ?: ride.boardingAt).seconds
                leg.copy(
                    durationSeconds = seconds, durationMinutes = ceilMinutes(seconds),
                    waitingMinutes = ceilMinutes(waitSeconds), scheduledDepartureAt = start, scheduledArrivalAt = cursor,
                    directionCode = ride.directionCode, directionName = null,
                    timingBasis = TransitLegTimingBasis.TIMETABLE, timetable = ride,
                    firstBoardingObservation = observation,
                )
            }
        }
        val totalSeconds = Duration.between(journey.departureAt, cursor).seconds
        if (totalSeconds !in 1..maxTravelMinutes.toLong() * 60) return null
        return journey.copy(arrivalAt = cursor, totalMinutes = ceilMinutes(totalSeconds), legs = legs)
    }

    private fun ceilMinutes(seconds: Long) = ((seconds + 59) / 60).toInt()
}
