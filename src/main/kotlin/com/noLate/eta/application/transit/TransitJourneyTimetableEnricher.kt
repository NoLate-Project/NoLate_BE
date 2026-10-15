package com.noLate.eta.application.transit

import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.domain.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** 전 구간을 확인한 경우에만 추정 여정을 시간표 여정으로 교체한다. 부분 성공은 ETA가 아니다. */
@Component
class TransitJourneyTimetableEnricher(
    private val providers: List<TransitTimetableProvider>,
    @Value("\${eta.transit.boarding-buffer-seconds:60}") private val boardingBufferSeconds: Long = 60,
    @Value("\${eta.transit.transfer-confidence-margin-seconds:60}") private val transferMarginSeconds: Long = 60,
    @Value("\${eta.transit.transfer-buffer-seconds:0}") private val transferBufferSeconds: Long = 0,
    private val rideArrivalPredictor: TransitRideArrivalPredictor = TransitRideArrivalPredictor(),
) {
    init {
        require(boardingBufferSeconds in 0..600 && transferMarginSeconds in 0..600 && transferBufferSeconds in 0..600)
    }

    fun enrich(
        journey: TransitJourney,
        evaluatedAt: Instant,
        maxTravelMinutes: Int,
        firstBoardingResolver: ((TransitLegTimetable) -> TransitFirstBoardingObservation?)? = null,
    ): TransitJourney {
        // Tmap은 정수 초 출발, 분 단위 공급자는 정수 분 출발을 쓴다.
        // 소수초 입력을 초 모델에 암묵적으로 절삭하거나 과거 출발을 허용하지 않는다.
        if (providers.isEmpty() || journey.departureAt.nano != 0 || journey.departureAt.isBefore(evaluatedAt) ||
            journey.rideLegs.any { it.mode != TransitLegMode.SUBWAY } ||
            journey.rideLegs.all { it.timingBasis == TransitLegTimingBasis.TIMETABLE }) return journey
        // 공급자를 혼합해 부분 성공을 조립하지 않는다. 한 공급자가 전체 여정을 증명해야 한다.
        return providers.firstNotNullOfOrNull { provider ->
            runCatching { enrichWith(provider, journey, evaluatedAt, maxTravelMinutes, firstBoardingResolver) }.getOrNull()
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
    ): TransitJourney {
        val first = journey.rideLegs.firstOrNull()?.timetable ?: return journey
        val delay = Duration.between(first.boardingAt, firstBoardingOverlay.boardingAt)
        if (delay.isNegative || delay.isZero || providers.isEmpty() || journey.rideLegs.size < 2 ||
            journey.rideLegs.any { it.mode != TransitLegMode.SUBWAY || it.timetable?.provider != first.provider }) return journey
        // 한 공급자로 전체 환승을 복구한다. 실패 시 다른 공급자의 구간을 섞지 않는다.
        return providers.firstNotNullOfOrNull { provider ->
            runCatching { reconnectWith(provider, journey, delay, maxTravelMinutes) }.getOrNull()
        } ?: journey
    }

    /**
     * 첫 열차가 실제로 더 일찍 올 때도 같은 도보·여유를 지키며 이후 시간표를 다시 연결한다.
     * 기존 환승이 가능하다는 사실만으로 더 이른 환승을 놓치지 않도록 한다.
     * 원천 관측이 없거나 오래됐으면 시간표만으로 이 최적화를 수행하지 않는다.
     */
    fun reconnectEarlierTransfers(
        journey: TransitJourney,
        firstBoardingOverlay: TransitRealtimeOverlay,
        observation: TransitFirstBoardingObservation?,
        evaluatedAt: Instant,
        maxTravelMinutes: Int,
    ): TransitJourney {
        val firstLeg = journey.rideLegs.firstOrNull() ?: return journey
        val first = firstLeg.timetable ?: return journey
        val shift = Duration.between(first.boardingAt, firstBoardingOverlay.boardingAt)
        if (!shift.isNegative || providers.isEmpty() || journey.rideLegs.size < 2 ||
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
        return providers.firstNotNullOfOrNull { provider ->
            runCatching { reconnectWith(provider, journey, shift, maxTravelMinutes) }.getOrNull()
                // 동일 열차를 유지하거나 더 늦어지면 원래 여정을 보존한다. 부분 성공도 채택하지 않는다.
                ?.takeIf { it.arrivalAt < journey.arrivalAt }
        } ?: journey
    }

    private fun reconnectWith(
        provider: TransitTimetableProvider,
        journey: TransitJourney,
        firstDelay: Duration,
        maxTravelMinutes: Int,
    ): TransitJourney? {
        val firstRideIndex = journey.legs.indexOfFirst { it.isRide }
        val first = requireNotNull(journey.legs[firstRideIndex].timetable)
        var cursor = rideArrivalPredictor.predict(first, first.boardingAt.plus(firstDelay)).arrivalAt
        val latestArrival = journey.departureAt.plusSeconds(maxTravelMinutes.toLong() * 60)
        val legs = journey.legs.mapIndexed { index, leg ->
            if (index <= firstRideIndex) return@mapIndexed leg
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
                val ride = provider.findRides(query).filter {
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
        provider: TransitTimetableProvider,
        journey: TransitJourney,
        evaluatedAt: Instant,
        maxTravelMinutes: Int,
        firstBoardingResolver: ((TransitLegTimetable) -> TransitFirstBoardingObservation?)?,
    ): TransitJourney? {
        var cursor = journey.departureAt
        val serviceDate = journey.departureAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        var rideCount = 0
        val legs = journey.legs.map { leg ->
            val start = cursor
            if (!leg.isRide) {
                if (leg.mode != TransitLegMode.WALK) return null
                cursor = cursor.plusSeconds(leg.movementDurationSeconds)
                leg.copy(scheduledDepartureAt = start, scheduledArrivalAt = cursor)
            } else {
                val line = leg.line ?: return null
                val canonical = line.seoulSubwayLine ?: return null
                val isFirstRide = rideCount++ == 0
                val margin = if (isFirstRide) boardingBufferSeconds else maxOf(transferBufferSeconds, transferMarginSeconds)
                val notBefore = cursor.plusSeconds(margin)
                // 실시간으로 동일 열차를 검증할 수 있을 때만 5분 이내의 지난 시간표를 후보로 조회한다.
                // 다음 환승에는 현재 도착정보를 적용하지 않으며 기존 여유시간을 그대로 지킨다.
                val canRecoverDelayed = isFirstRide && firstBoardingResolver != null &&
                    notBefore <= evaluatedAt.plusSeconds(20 * 60)
                val queryNotBefore = if (canRecoverDelayed) notBefore.minusSeconds(300) else notBefore
                val query = TransitTimetableQuery(canonical, leg.from?.name ?: return null, leg.to?.name ?: return null,
                    leg.nextStopName, line.serviceClass, queryNotBefore, leg.directionCode,
                    serviceDate = serviceDate)
                val rides = provider.findRides(query).filter { ride ->
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
                val delay = observation?.let { Duration.between(ride.boardingAt, it.expectedBoardingAt) } ?: Duration.ZERO
                cursor = rideArrivalPredictor.predict(ride, ride.boardingAt.plus(delay)).arrivalAt
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
