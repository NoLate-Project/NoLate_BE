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
 * 미래 환승 차량은 실시간으로 가장하지 않는다. ODsay가 제공한 시간표 시각만 사용하고,
 * 시각이 하나라도 없으면 성공으로 간주하지 않고 UNKNOWN 진단으로 남긴다. 시간표 차량을
 * 탈 수 있으면 첫 지연은 환승 대기에서 흡수되고 이후 구간은 시간표대로 운행한다고 본다.
 */
@Component
class TransitTransferFeasibilityEvaluator(
    @Value("\${eta.transit.transfer-buffer-seconds:0}")
    private val transferBufferSeconds: Long = DEFAULT_TRANSFER_BUFFER_SECONDS,
    @Value("\${eta.transit.transfer-confidence-margin-seconds:60}")
    private val transferConfidenceMarginSeconds: Long = DEFAULT_TRANSFER_CONFIDENCE_MARGIN_SECONDS,
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
            ?.let { Duration.between(journey.arrivalAt, it.predictedArrivalAt).seconds }
            ?: 0L
        var unknown = false

        val firstRideIndex = rideIndexes.first()
        val firstRide = journey.legs[firstRideIndex]
        val accessSeconds = journey.legs
            .take(firstRideIndex)
            .filterNot(TransitJourneyLeg::isRide)
            .sumOf { it.durationMinutes.toLong() * SECONDS_PER_MINUTE }
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
                timingBasis = journey.timingBasis(firstBoardingOverlay != null, unknown = false),
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
                .sumOf { it.durationMinutes.toLong() * SECONDS_PER_MINUTE }
            val physicalReadyAt = scheduledArrival
                .plusSeconds(propagatedDelaySeconds)
                .plusSeconds(transferWalkSeconds)
            if (physicalReadyAt.isAfter(boardingAt)) {
                val missedBy = Duration.between(boardingAt, physicalReadyAt).seconds
                return TransitTransferEvaluation(
                    status = TransitTransferStatus.MISSED,
                    predictedArrivalAt = journey.arrivalAt.plusSeconds(propagatedDelaySeconds),
                    travelMinutes = journey.durationTo(journey.arrivalAt.plusSeconds(propagatedDelaySeconds)),
                    timingBasis = journey.timingBasis(firstBoardingOverlay != null, unknown = false),
                    failedTransferSequence = transferIndex + 1,
                    missedBySeconds = missedBy,
                )
            }

            val confidenceReadyAt = physicalReadyAt.plusSeconds(requiredTransferMarginSeconds)
            if (confidenceReadyAt.isAfter(boardingAt)) {
                // ODsay exposes minute timestamps. An exact or sub-margin boundary is not proof
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

        val predictedArrivalAt = journey.arrivalAt.plusSeconds(propagatedDelaySeconds)
        return TransitTransferEvaluation(
            status = if (unknown) TransitTransferStatus.UNKNOWN else TransitTransferStatus.FEASIBLE,
            predictedArrivalAt = predictedArrivalAt,
            travelMinutes = journey.durationTo(predictedArrivalAt),
            timingBasis = journey.timingBasis(firstBoardingOverlay != null, unknown),
        )
    }

    private fun TransitJourney.durationTo(arrivalAt: Instant): Int =
        ceil(Duration.between(departureAt, arrivalAt).toSeconds() / SECONDS_PER_MINUTE.toDouble())
            .toInt()
            .coerceAtLeast(1)

    private fun TransitJourneyLeg.timetableArrival(): Instant? =
        scheduledArrivalAt.takeIf { timingBasis == TransitLegTimingBasis.TIMETABLE }

    private fun TransitJourneyLeg.timetableBoardingAt(): Instant? {
        if (timingBasis != TransitLegTimingBasis.TIMETABLE) return null
        val segmentStart = scheduledDepartureAt ?: return null
        val wait = waitingMinutes ?: return null
        return segmentStart.plusSeconds(wait.toLong() * SECONDS_PER_MINUTE)
    }

    private fun TransitJourney.timingBasis(
        hasFirstBoardingRealtime: Boolean,
        unknown: Boolean,
    ): TransitJourneyTimingBasis = when {
        hasFirstBoardingRealtime && unknown ->
            TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN
        hasFirstBoardingRealtime ->
            TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE
        unknown -> TransitJourneyTimingBasis.TIMETABLE_TRANSFER_UNKNOWN
        else -> TransitJourneyTimingBasis.TIMETABLE_ONLY
    }

    private companion object {
        const val DEFAULT_TRANSFER_BUFFER_SECONDS = 0L
        const val DEFAULT_TRANSFER_CONFIDENCE_MARGIN_SECONDS = 60L
        const val MAX_TRANSFER_BUFFER_SECONDS = 600L
        const val SECONDS_PER_MINUTE = 60L
    }
}
