package com.noLate.eta.application.transit

import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitLegTimingBasis
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

enum class TransitTransferStatus {
    FEASIBLE,
    MISSED,
    UNKNOWN,
}

enum class TransitJourneyTimingBasis {
    FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE,
    FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN,
    FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE,
    ROUTE_ESTIMATE_ONLY,
    TIMETABLE_ONLY,
    TIMETABLE_TRANSFER_UNKNOWN,
}

data class TransitTransferEvaluation(
    val status: TransitTransferStatus,
    val predictedArrivalAt: Instant,
    val travelMinutes: Int,
    val timingBasis: TransitJourneyTimingBasis,
    val failedTransferSequence: Int? = null,
    val missedBySeconds: Long? = null,
    /** 분 단위 시간표의 경계 불확실성을 해소하기 위해 더 일찍 재검색할 최소 시간. */
    val searchEarlierBySeconds: Long? = missedBySeconds,
) {
    val eligible: Boolean
        get() = status == TransitTransferStatus.FEASIBLE
}

/**
 * 첫 승차 실시간 지연을 환승 지점까지 전파하고 다음 시간표 차량을 실제로 탈 수 있는지 확인한다.
 *
 * 미래 환승 차량은 실시간으로 가장하지 않는다. 실제 시간표로 확인된 구간 시각만 사용하고,
 * 추정값이거나 시각이 없으면 성공으로 간주하지 않고 UNKNOWN 진단으로 남긴다. 시간표 차량을
 * 탈 수 있으면 첫 지연은 환승 대기에서 흡수되고 이후 구간은 시간표대로 운행한다고 본다.
 */
@Component
class TransitTransferFeasibilityEvaluator(
    @Value("\${eta.transit.transfer-buffer-seconds:0}")
    private val transferBufferSeconds: Long = DEFAULT_TRANSFER_BUFFER_SECONDS,
    @Value("\${eta.transit.transfer-confidence-margin-seconds:60}")
    private val transferConfidenceMarginSeconds: Long = DEFAULT_TRANSFER_CONFIDENCE_MARGIN_SECONDS,
    private val rideArrivalPredictor: TransitRideArrivalPredictor = TransitRideArrivalPredictor(),
) {
    init {
        require(transferBufferSeconds in 0..MAX_TRANSFER_BUFFER_SECONDS) {
            "대중교통 환승 여유시간은 0~${MAX_TRANSFER_BUFFER_SECONDS}초 사이여야 합니다."
        }
        require(transferConfidenceMarginSeconds in 0..MAX_TRANSFER_BUFFER_SECONDS) {
            "대중교통 환승 신뢰 여유시간은 0~${MAX_TRANSFER_BUFFER_SECONDS}초 사이여야 합니다."
        }
    }

    fun evaluate(
        journey: TransitJourney,
        firstBoardingOverlay: TransitRealtimeOverlay?,
        evaluatedAt: Instant,
    ): TransitTransferEvaluation {
        val rideIndexes = journey.legs.indices.filter { journey.legs[it].isRide }
        require(rideIndexes.isNotEmpty()) { "대중교통 여정에는 승차 구간이 필요합니다." }

        var propagatedDelaySeconds = firstBoardingOverlay
            ?.let { overlay ->
                // 환승 차량을 재연결하면 전체 여정 도착시각이 바뀐다. 첫 열차 지연은 바뀐
                // 전체 ETA 차이가 아니라 동일 열차의 원본 승차시각과 관측시각으로 계산한다.
                journey.rideLegs.first().timetable?.let { first ->
                    rideArrivalPredictor.predict(first, overlay.boardingAt).shiftSeconds
                } ?: Duration.between(journey.arrivalAt, overlay.predictedArrivalAt).seconds
            }
            ?: 0L
        // 첫 차량의 실시간 도착은 이후 운행시간의 정확성을 증명하지 않는다.
        // 환승이 없는 경로와 마지막 승차도 시간 근거·도착시각을 반드시 확인한다.
        var unknown = rideIndexes.any { journey.legs[it].timetableArrival() == null }

        val firstRideIndex = rideIndexes.first()
        val firstRide = journey.legs[firstRideIndex]
        val accessSeconds = journey.legs
            .take(firstRideIndex)
            .filterNot(TransitJourneyLeg::isRide)
            .sumOf(TransitJourneyLeg::movementDurationSeconds)
        val firstReadyAt = maxOf(evaluatedAt, journey.departureAt)
            .plusSeconds(accessSeconds)
        val firstBoardingAt = firstBoardingOverlay?.boardingAt ?: firstRide.timetableBoardingAt()
        if (firstBoardingAt == null) {
            unknown = true
        } else if (firstReadyAt.isAfter(firstBoardingAt)) {
            val missedBy = Duration.between(firstBoardingAt, firstReadyAt).seconds
            val predictedArrivalAt = firstBoardingOverlay?.predictedArrivalAt
                ?: journey.arrivalAt
            return TransitTransferEvaluation(
                status = TransitTransferStatus.MISSED,
                predictedArrivalAt = predictedArrivalAt,
                travelMinutes = journey.durationTo(predictedArrivalAt),
                timingBasis = journey.timingBasis(firstBoardingOverlay != null, unknown),
                failedTransferSequence = 0,
                missedBySeconds = missedBy,
            )
        }

        val requiredTransferMarginSeconds = maxOf(
            transferBufferSeconds,
            transferConfidenceMarginSeconds,
        )
        for ((transferIndex, pair) in rideIndexes.zipWithNext().withIndex()) {
            val (currentIndex, nextIndex) = pair
            val currentRide = journey.legs[currentIndex]
            val nextRide = journey.legs[nextIndex]
            val scheduledArrival = currentRide.timetableArrival()
            val boardingAt = nextRide.timetableBoardingAt()
            if (scheduledArrival == null || boardingAt == null) {
                unknown = true
                break
            }

            val transferWalkSeconds = journey.legs
                .subList(currentIndex + 1, nextIndex)
                .filterNot(TransitJourneyLeg::isRide)
                .sumOf(TransitJourneyLeg::movementDurationSeconds)
            val physicalReadyAt = scheduledArrival
                .plusSeconds(propagatedDelaySeconds)
                .plusSeconds(transferWalkSeconds)
            if (physicalReadyAt.isAfter(boardingAt)) {
                val missedBy = Duration.between(boardingAt, physicalReadyAt).seconds
                return TransitTransferEvaluation(
                    status = TransitTransferStatus.MISSED,
                    predictedArrivalAt = journey.arrivalAt.plusSeconds(propagatedDelaySeconds),
                    travelMinutes = journey.durationTo(journey.arrivalAt.plusSeconds(propagatedDelaySeconds)),
                    timingBasis = journey.timingBasis(firstBoardingOverlay != null, unknown),
                    failedTransferSequence = transferIndex + 1,
                    missedBySeconds = missedBy,
                )
            }

            val confidenceReadyAt = physicalReadyAt.plusSeconds(requiredTransferMarginSeconds)
            if (confidenceReadyAt.isAfter(boardingAt)) {
                // Minute-resolution timetables need a margin. An exact or sub-margin boundary is not proof
                // that doors can still be reached, but neither is it proof of a missed vehicle.
                // Keep it explicitly degraded and let safe-departure spend its bounded budget on
                // an earlier itinerary instead of returning a false-safe FEASIBLE result.
                val marginShortfall = Duration.between(boardingAt, confidenceReadyAt).seconds
                    .coerceAtLeast(1L)
                return TransitTransferEvaluation(
                    status = TransitTransferStatus.UNKNOWN,
                    predictedArrivalAt = journey.arrivalAt.plusSeconds(propagatedDelaySeconds),
                    travelMinutes = journey.durationTo(
                        journey.arrivalAt.plusSeconds(propagatedDelaySeconds)
                    ),
                    timingBasis = journey.timingBasis(firstBoardingOverlay != null, unknown = true),
                    failedTransferSequence = transferIndex + 1,
                    searchEarlierBySeconds = marginShortfall,
                )
            }

            // 다음 시간표 차량에 탑승하면 앞 구간 지연은 대기 여유에 흡수된다.
            propagatedDelaySeconds = 0L
        }

        // 단일 승차의 지연 후보는 시간축에 지연이 이미 들어 있다. 원본으로 되돌린 뒤
        // 이번에 재검증한 지연만 적용한다. 환승에서는 앞 지연이 다음 열차 대기에 흡수된다.
        val embeddedDelay = if (rideIndexes.size == 1) firstRide.firstBoardingObservation?.let {
            Duration.between(requireNotNull(firstRide.timetable).boardingAt, it.expectedBoardingAt).seconds
        } ?: 0L else 0L
        val predictedArrivalAt = journey.arrivalAt.plusSeconds(propagatedDelaySeconds - embeddedDelay)
        return TransitTransferEvaluation(
            status = if (unknown) TransitTransferStatus.UNKNOWN else TransitTransferStatus.FEASIBLE,
            predictedArrivalAt = predictedArrivalAt,
            travelMinutes = journey.durationTo(predictedArrivalAt),
            timingBasis = journey.timingBasis(firstBoardingOverlay != null, unknown),
        )
    }

    /** 실제 평가와 같은 여유 설정을 이력/표시에도 사용한다. */
    fun describeTransfer(previousArrivalAt: Instant, walkingSeconds: Long, boardingAt: Instant) =
        com.noLate.eta.domain.TransitTransferTimingEvidence(previousArrivalAt, walkingSeconds,
            transferBufferSeconds, transferConfidenceMarginSeconds, boardingAt)

    private fun TransitJourney.durationTo(arrivalAt: Instant): Int =
        ceil(Duration.between(departureAt, arrivalAt).toSeconds() / SECONDS_PER_MINUTE.toDouble())
            .toInt()
            .coerceAtLeast(1)

    private fun TransitJourneyLeg.timetableArrival(): Instant? =
        (timetable?.arrivalAt ?: scheduledArrivalAt).takeIf { timingBasis == TransitLegTimingBasis.TIMETABLE }

    private fun TransitJourneyLeg.timetableBoardingAt(): Instant? {
        if (timingBasis != TransitLegTimingBasis.TIMETABLE) return null
        timetable?.let { return it.boardingAt }
        val segmentStart = scheduledDepartureAt ?: return null
        val wait = waitingMinutes ?: return null
        return segmentStart.plusSeconds(wait.toLong() * SECONDS_PER_MINUTE)
    }

    private fun TransitJourney.timingBasis(
        hasFirstBoardingRealtime: Boolean,
        unknown: Boolean,
    ): TransitJourneyTimingBasis {
        // 추정 구간이 섞인 결과를 시간표나 환승 실패로 설명하지 않도록 근거를 보존한다.
        val hasRouteEstimate = rideLegs.any { it.timingBasis == TransitLegTimingBasis.ROUTE_ESTIMATE }
        return when {
            hasRouteEstimate && hasFirstBoardingRealtime ->
                TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE
            hasRouteEstimate -> TransitJourneyTimingBasis.ROUTE_ESTIMATE_ONLY
            hasFirstBoardingRealtime && unknown ->
                TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN
            hasFirstBoardingRealtime ->
                TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE
            unknown -> TransitJourneyTimingBasis.TIMETABLE_TRANSFER_UNKNOWN
            else -> TransitJourneyTimingBasis.TIMETABLE_ONLY
        }
    }

    private companion object {
        const val DEFAULT_TRANSFER_BUFFER_SECONDS = 0L
        const val DEFAULT_TRANSFER_CONFIDENCE_MARGIN_SECONDS = 60L
        const val MAX_TRANSFER_BUFFER_SECONDS = 600L
        const val SECONDS_PER_MINUTE = 60L
    }
}
