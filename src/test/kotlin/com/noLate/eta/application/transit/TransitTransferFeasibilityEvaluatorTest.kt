package com.noLate.eta.application.transit

import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLegTimingBasis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant

class TransitTransferFeasibilityEvaluatorTest {
    private val departureAt = Instant.parse("2026-08-01T08:00:00Z")
    private val evaluator = TransitTransferFeasibilityEvaluator()

    @Test
    fun `첫 승차가 5분 늦으면 환승 도보 뒤의 시간표 차량을 놓친다`() {
        val journey = transferJourney(nextDepartureAt = departureAt.plusSeconds(24 * 60))
        val overlay = TransitRealtimeOverlay(
            travelMinutes = 45,
            predictedArrivalAt = journey.arrivalAt.plusSeconds(5 * 60),
            observedAt = departureAt,
            boardingAt = departureAt.plusSeconds(10 * 60),
        )

        val result = evaluator.evaluate(journey, overlay, departureAt)

        assertEquals(TransitTransferStatus.MISSED, result.status)
        assertEquals(1, result.failedTransferSequence)
        assertEquals(6 * 60, result.missedBySeconds)
        assertFalse(result.eligible)
        assertEquals(
            TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE,
            result.timingBasis,
        )
    }

    @Test
    fun `ODsay waitingTime 0 인접 환승은 분 단위 경계라 기본값에서 UNKNOWN이다`() {
        val journey = zeroSlackTransferJourney()

        val result = evaluator.evaluate(
            journey = journey,
            firstBoardingOverlay = null,
            evaluatedAt = departureAt,
        )

        assertEquals(TransitTransferStatus.UNKNOWN, result.status)
        assertEquals(journey.arrivalAt, result.predictedArrivalAt)
        assertEquals(1, result.failedTransferSequence)
        assertEquals(60, result.searchEarlierBySeconds)
        assertEquals(TransitJourneyTimingBasis.TIMETABLE_TRANSFER_UNKNOWN, result.timingBasis)
    }

    @Test
    fun `여유 0초 환승에 첫 구간 지연이 1초 전파되면 놓친다`() {
        val journey = zeroSlackTransferJourney()
        val overlay = TransitRealtimeOverlay(
            travelMinutes = 41,
            predictedArrivalAt = journey.arrivalAt.plusSeconds(1),
            observedAt = departureAt,
            boardingAt = departureAt,
        )

        val result = evaluator.evaluate(journey, overlay, departureAt)

        assertEquals(TransitTransferStatus.MISSED, result.status)
        assertEquals(1, result.failedTransferSequence)
        assertEquals(1, result.missedBySeconds)
        assertFalse(result.eligible)
    }

    @Test
    fun `분 단위 시간표에서 60초 환승 여유가 확보되면 FEASIBLE이다`() {
        val journey = zeroSlackTransferJourney().let { original ->
            original.copy(
                legs = original.legs.map { leg ->
                    if (leg.sequence == 2) {
                        leg.copy(
                            scheduledDepartureAt = requireNotNull(leg.scheduledDepartureAt)
                                .plusSeconds(60),
                        )
                    } else {
                        leg
                    }
                }
            )
        }

        val result = evaluator.evaluate(journey, firstBoardingOverlay = null, evaluatedAt = departureAt)

        assertEquals(TransitTransferStatus.FEASIBLE, result.status)
        assertEquals(TransitJourneyTimingBasis.TIMETABLE_ONLY, result.timingBasis)
    }

    @Test
    fun `명시적으로 신뢰 여유를 0으로 내린 경우에만 exact boundary를 허용한다`() {
        val permissive = TransitTransferFeasibilityEvaluator(
            transferBufferSeconds = 0,
            transferConfidenceMarginSeconds = 0,
        )

        val result = permissive.evaluate(
            journey = zeroSlackTransferJourney(),
            firstBoardingOverlay = null,
            evaluatedAt = departureAt,
        )

        assertEquals(TransitTransferStatus.FEASIBLE, result.status)
    }

    @Test
    fun `실시간 overlay가 없어도 첫 시간표 차량을 탈 수 있으면 허용한다`() {
        val journey = transferJourney(nextDepartureAt = departureAt.plusSeconds(35 * 60))

        val result = evaluator.evaluate(
            journey = journey,
            firstBoardingOverlay = null,
            evaluatedAt = departureAt.plusSeconds(2 * 60),
        )

        assertEquals(TransitTransferStatus.FEASIBLE, result.status)
        assertEquals(TransitJourneyTimingBasis.TIMETABLE_ONLY, result.timingBasis)
    }

    @Test
    fun `환승 시간표에 여유가 있으면 첫 지연은 대기에서 흡수되고 최종 도착은 시간표를 따른다`() {
        val journey = transferJourney(nextDepartureAt = departureAt.plusSeconds(35 * 60))
        val overlay = TransitRealtimeOverlay(
            travelMinutes = 45,
            predictedArrivalAt = journey.arrivalAt.plusSeconds(5 * 60),
            observedAt = departureAt,
            boardingAt = departureAt.plusSeconds(10 * 60),
        )

        val result = evaluator.evaluate(journey, overlay, departureAt)

        assertEquals(TransitTransferStatus.FEASIBLE, result.status)
        assertEquals(journey.arrivalAt, result.predictedArrivalAt)
        assertEquals(40, result.travelMinutes)
        assertEquals(
            TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE,
            result.timingBasis,
        )
    }

    @Test
    fun `미래 환승 출발시각이 없으면 실시간 성공으로 꾸미지 않고 UNKNOWN으로 남긴다`() {
        val journey = transferJourney(nextDepartureAt = null)
        val overlay = TransitRealtimeOverlay(
            travelMinutes = 45,
            predictedArrivalAt = journey.arrivalAt.plusSeconds(5 * 60),
            observedAt = departureAt,
            boardingAt = departureAt.plusSeconds(10 * 60),
        )

        val result = evaluator.evaluate(journey, overlay, departureAt)

        assertEquals(TransitTransferStatus.UNKNOWN, result.status)
        assertEquals(
            TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN,
            result.timingBasis,
        )
        assertFalse(result.eligible)
    }

    @Test
    fun `첫 실시간 승차 검증에 환승 버퍼를 중복 적용하지 않는다`() {
        val journey = transferJourney(nextDepartureAt = departureAt.plusSeconds(35 * 60)).let {
            it.copy(
                legs = listOf(
                    TransitJourneyLeg(
                        sequence = 0,
                        mode = TransitLegMode.WALK,
                        durationMinutes = 5,
                    ),
                ) + it.legs.map { leg -> leg.copy(sequence = leg.sequence + 1) }
            )
        }
        val overlay = TransitRealtimeOverlay(
            travelMinutes = 40,
            predictedArrivalAt = journey.arrivalAt,
            observedAt = departureAt,
            boardingAt = departureAt.plusSeconds(10 * 60),
        )

        val result = evaluator.evaluate(
            journey = journey,
            firstBoardingOverlay = overlay,
            evaluatedAt = departureAt.plusSeconds(4 * 60 + 30),
        )

        assertEquals(TransitTransferStatus.FEASIBLE, result.status)
        assertEquals(journey.arrivalAt, result.predictedArrivalAt)
    }

    @Test
    fun `다음 ride의 waitingMinutes는 실제 boarding 여유로 보존한다`() {
        val journey = transferJourney(nextDepartureAt = departureAt.plusSeconds(24 * 60)).let {
            it.copy(
                legs = it.legs.map { leg ->
                    if (leg.sequence == 2) leg.copy(waitingMinutes = 8) else leg
                }
            )
        }
        val overlay = TransitRealtimeOverlay(
            travelMinutes = 45,
            predictedArrivalAt = journey.arrivalAt.plusSeconds(5 * 60),
            observedAt = departureAt,
            boardingAt = departureAt.plusSeconds(10 * 60),
        )

        val result = evaluator.evaluate(journey, overlay, departureAt)

        assertEquals(TransitTransferStatus.FEASIBLE, result.status)
        assertEquals(journey.arrivalAt, result.predictedArrivalAt)
    }

    private fun transferJourney(nextDepartureAt: Instant?): TransitJourney = TransitJourney(
        provider = "odsay",
        requestedDepartureAt = departureAt,
        departureAt = departureAt,
        arrivalAt = departureAt.plusSeconds(40 * 60),
        totalMinutes = 40,
        fetchedAt = departureAt,
        legs = listOf(
            TransitJourneyLeg(
                sequence = 0,
                mode = TransitLegMode.BUS,
                durationMinutes = 20,
                waitingMinutes = 3,
                scheduledDepartureAt = departureAt,
                scheduledArrivalAt = departureAt.plusSeconds(20 * 60),
                timingBasis = TransitLegTimingBasis.TIMETABLE,
            ),
            TransitJourneyLeg(
                sequence = 1,
                mode = TransitLegMode.WALK,
                durationMinutes = 5,
                scheduledDepartureAt = departureAt.plusSeconds(20 * 60),
                scheduledArrivalAt = departureAt.plusSeconds(25 * 60),
                timingBasis = TransitLegTimingBasis.TIMETABLE,
            ),
            TransitJourneyLeg(
                sequence = 2,
                mode = TransitLegMode.SUBWAY,
                durationMinutes = 15,
                waitingMinutes = 0,
                scheduledDepartureAt = nextDepartureAt,
                scheduledArrivalAt = departureAt.plusSeconds(40 * 60),
                timingBasis = if (nextDepartureAt == null) {
                    TransitLegTimingBasis.UNKNOWN
                } else {
                    TransitLegTimingBasis.TIMETABLE
                },
            ),
        ),
    )

    private fun zeroSlackTransferJourney(): TransitJourney = TransitJourney(
        provider = "odsay",
        requestedDepartureAt = departureAt,
        departureAt = departureAt,
        arrivalAt = departureAt.plusSeconds(40 * 60),
        totalMinutes = 40,
        fetchedAt = departureAt,
        legs = listOf(
            TransitJourneyLeg(
                sequence = 0,
                mode = TransitLegMode.BUS,
                durationMinutes = 20,
                waitingMinutes = 0,
                scheduledDepartureAt = departureAt,
                scheduledArrivalAt = departureAt.plusSeconds(20 * 60),
                timingBasis = TransitLegTimingBasis.TIMETABLE,
            ),
            TransitJourneyLeg(
                sequence = 1,
                mode = TransitLegMode.WALK,
                durationMinutes = 4,
                scheduledDepartureAt = departureAt.plusSeconds(20 * 60),
                scheduledArrivalAt = departureAt.plusSeconds(24 * 60),
                timingBasis = TransitLegTimingBasis.TIMETABLE,
            ),
            TransitJourneyLeg(
                sequence = 2,
                mode = TransitLegMode.SUBWAY,
                durationMinutes = 16,
                waitingMinutes = 0,
                scheduledDepartureAt = departureAt.plusSeconds(24 * 60),
                scheduledArrivalAt = departureAt.plusSeconds(40 * 60),
                timingBasis = TransitLegTimingBasis.TIMETABLE,
            ),
        ),
    )
}
