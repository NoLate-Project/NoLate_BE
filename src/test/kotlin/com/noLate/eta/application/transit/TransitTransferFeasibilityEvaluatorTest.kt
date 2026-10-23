package com.noLate.eta.application.transit

import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLegTimingBasis
import com.noLate.eta.domain.TransitServiceClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.time.LocalDate

class TransitTransferFeasibilityEvaluatorTest {
    private val departureAt = Instant.parse("2026-08-01T08:00:00Z")
    private val evaluator = TransitTransferFeasibilityEvaluator()

    @Test
    fun `첫 승차가 미관측이어도 동일 환승편의 하차 예측은 독립적으로 계산한다`() {
        val journey = observedTransferJourney()
        val observation = TransitRideRealtimeObservation(
            3, TransitRideObservationPoint.ALIGHTING,
            departureAt.plusSeconds(45 * 60), departureAt,
        )

        val result = evaluator.evaluateWithObservedTransfers(
            journey, null, departureAt, listOf(observation),
        )!!

        assertEquals(TransitTransferStatus.FEASIBLE, result.status)
        assertEquals(departureAt.plusSeconds(47 * 60), result.predictedArrivalAt)
        assertEquals(TransitJourneyTimingBasis.FIRST_BOARDING_TIMETABLE_TRANSFER_OBSERVED, result.timingBasis)
        assertEquals(journey.rideLegs.first().timetable?.boardingAt,
            result.observedRideTimings[1]?.boardingAt)
        assertEquals(departureAt.plusSeconds(45 * 60), result.observedRideTimings[3]?.arrivalAt)
    }

    @Test
    fun `환승 열차 하차역 예측을 해당 구간과 최종 도착에만 반영한다`() {
        val journey = observedTransferJourney()
        val observation = TransitRideRealtimeObservation(
            3, TransitRideObservationPoint.ALIGHTING,
            departureAt.plusSeconds(45 * 60), departureAt,
        )

        val result = evaluator.evaluateWithObservedTransfers(
            journey, firstOverlay(journey), departureAt, listOf(observation),
        )!!

        assertEquals(TransitTransferStatus.FEASIBLE, result.status)
        assertEquals(departureAt.plusSeconds(47 * 60), result.predictedArrivalAt)
        assertEquals(47, result.travelMinutes)
        assertEquals(TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_OBSERVED, result.timingBasis)
        assertEquals(departureAt.plusSeconds(21 * 60), result.observedRideTimings[1]?.arrivalAt)
        assertEquals(departureAt.plusSeconds(45 * 60), result.observedRideTimings[3]?.arrivalAt)
    }

    @Test
    fun `환승 열차 승차역 지연은 그 열차의 시간표 운행시간만 이동한다`() {
        val journey = observedTransferJourney()
        val observation = TransitRideRealtimeObservation(
            3, TransitRideObservationPoint.BOARDING,
            departureAt.plusSeconds(32 * 60), departureAt,
        )

        val result = evaluator.evaluateWithObservedTransfers(
            journey, firstOverlay(journey), departureAt, listOf(observation),
        )!!

        assertEquals(TransitTransferStatus.FEASIBLE, result.status)
        assertEquals(departureAt.plusSeconds(44 * 60), result.predictedArrivalAt)
        assertEquals(departureAt.plusSeconds(42 * 60), result.observedRideTimings[3]?.arrivalAt)
    }

    @Test
    fun `구간 시간표가 없으면 일부 실시간 관측으로 전체 ETA를 만들지 않는다`() {
        val journey = observedTransferJourney().let { it.copy(legs = it.legs.mapIndexed { index, leg ->
            if (index == 3) leg.copy(timetable = null) else leg
        }) }

        assertNull(evaluator.evaluateWithObservedTransfers(
            journey, firstOverlay(journey), departureAt,
            listOf(TransitRideRealtimeObservation(3, TransitRideObservationPoint.ALIGHTING,
                departureAt.plusSeconds(45 * 60), departureAt)),
        ))
    }

    @Test
    fun `첫 승차 접근 111초를 2분으로 올림해서 탈 수 있는 차량을 놓쳤다고 하지 않는다`() {
        val original = singleRideJourney(TransitLegTimingBasis.ROUTE_ESTIMATE)
        val journey = original.copy(legs = listOf(
            TransitJourneyLeg(sequence = 0, mode = TransitLegMode.WALK, durationMinutes = 2, durationSeconds = 111),
        ) + original.legs)
        val overlay = TransitRealtimeOverlay(
            travelMinutes = journey.totalMinutes,
            predictedArrivalAt = journey.arrivalAt,
            observedAt = departureAt,
            boardingAt = departureAt.plusSeconds(115),
        )

        val result = evaluator.evaluate(journey, overlay, departureAt)

        // 차량은 접근 가능하지만 실제 운행시각이 없는 나머지 여정은 여전히 미확정이다.
        assertEquals(TransitTransferStatus.UNKNOWN, result.status)
        assertEquals(null, result.failedTransferSequence)
        assertFalse(result.eligible)
    }

    @Test
    fun `환승 도보 243초는 분 올림 없이 계산하고 기존 60초 신뢰 여유는 유지한다`() {
        val original = transferJourney(nextDepartureAt = departureAt.plusSeconds(20 * 60 + 243 + 60))
        val journey = original.copy(legs = original.legs.map { leg ->
            if (leg.mode == TransitLegMode.WALK) leg.copy(durationSeconds = 243) else leg
        })

        val result = evaluator.evaluate(journey, null, departureAt)

        assertEquals(TransitTransferStatus.FEASIBLE, result.status)
    }

    @Test
    fun `초 단위 도보라도 환승 여유가 59초뿐이면 성공으로 승격하지 않는다`() {
        val original = transferJourney(nextDepartureAt = departureAt.plusSeconds(20 * 60 + 243 + 59))
        val journey = original.copy(legs = original.legs.map { leg ->
            if (leg.mode == TransitLegMode.WALK) leg.copy(durationSeconds = 243) else leg
        })

        val result = evaluator.evaluate(journey, null, departureAt)

        assertEquals(TransitTransferStatus.UNKNOWN, result.status)
        assertEquals(1L, result.searchEarlierBySeconds)
    }

    @Test
    fun `원본 초 값이 없는 공급자의 환승 도보는 기존 분 단위를 유지한다`() {
        val journey = transferJourney(nextDepartureAt = departureAt.plusSeconds(20 * 60 + 243 + 60))

        val result = evaluator.evaluate(journey, null, departureAt)

        assertEquals(TransitTransferStatus.UNKNOWN, result.status)
        assertEquals(57L, result.searchEarlierBySeconds)
    }

    @ParameterizedTest
    @EnumSource(TransitLegTimingBasis::class, names = ["ROUTE_ESTIMATE", "UNKNOWN"])
    fun `단일 승차도 구간 시각 근거가 없으면 첫 차량 실시간만으로 확정하지 않는다`(
        timingBasis: TransitLegTimingBasis,
    ) {
        val journey = singleRideJourney(timingBasis)
        val overlay = TransitRealtimeOverlay(
            travelMinutes = 45,
            predictedArrivalAt = journey.arrivalAt.plusSeconds(5 * 60),
            observedAt = departureAt,
            boardingAt = departureAt.plusSeconds(10 * 60),
        )

        val result = evaluator.evaluate(journey, overlay, departureAt)

        // 환승 반복문이 실행되지 않아도 첫 차량 도착만으로 전체 ETA를 확정하면 안 된다.
        assertEquals(TransitTransferStatus.UNKNOWN, result.status)
        assertFalse(result.eligible)
        assertEquals(
            if (timingBasis == TransitLegTimingBasis.ROUTE_ESTIMATE) {
                TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE
            } else {
                TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN
            },
            result.timingBasis,
        )
    }

    @Test
    fun `마지막 승차의 도착시각이 없으면 앞 환승이 가능해도 전체 ETA는 미확정이다`() {
        val original = transferJourney(nextDepartureAt = departureAt.plusSeconds(35 * 60))
        val journey = original.copy(
            legs = original.legs.map { leg ->
                if (leg.sequence == 2) leg.copy(scheduledArrivalAt = null) else leg
            },
        )

        val result = evaluator.evaluate(journey, firstBoardingOverlay = null, evaluatedAt = departureAt)

        assertEquals(TransitTransferStatus.UNKNOWN, result.status)
        assertFalse(result.eligible)
    }

    @Test
    fun `구간이 추정값이어도 첫 실시간 차량을 놓친 사실은 MISSED로 보존한다`() {
        val journey = singleRideJourney(TransitLegTimingBasis.ROUTE_ESTIMATE)
        val overlay = TransitRealtimeOverlay(
            travelMinutes = 45,
            predictedArrivalAt = journey.arrivalAt.plusSeconds(5 * 60),
            observedAt = departureAt,
            boardingAt = departureAt.plusSeconds(10 * 60),
        )

        val result = evaluator.evaluate(journey, overlay, departureAt.plusSeconds(11 * 60))

        assertEquals(TransitTransferStatus.MISSED, result.status)
        assertEquals(0, result.failedTransferSequence)
        assertEquals(60L, result.missedBySeconds)
        assertEquals(TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE, result.timingBasis)
        assertFalse(result.eligible)
    }

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

    private fun firstOverlay(journey: TransitJourney) = TransitRealtimeOverlay(
        travelMinutes = journey.totalMinutes,
        predictedArrivalAt = journey.arrivalAt,
        observedAt = departureAt,
        boardingAt = departureAt.plusSeconds(6 * 60),
    )

    private fun observedTransferJourney(): TransitJourney {
        fun table(line: SeoulSubwayLine, train: String, from: String, to: String,
                  boardingMinute: Long, arrivalMinute: Long) = TransitLegTimetable(
            provider = "seoul-timetable", line = line,
            serviceDate = LocalDate.parse("2026-08-01"), trainNumber = train,
            directionCode = "DOWN", serviceClass = TransitServiceClass.LOCAL,
            fromStationCode = from, toStationCode = to, terminalName = "종착",
            boardingAt = departureAt.plusSeconds(boardingMinute * 60),
            arrivalAt = departureAt.plusSeconds(arrivalMinute * 60), fetchedAt = departureAt,
        )
        return TransitJourney(
            provider = "odsay", requestedDepartureAt = departureAt, departureAt = departureAt,
            arrivalAt = departureAt.plusSeconds(42 * 60), totalMinutes = 42, fetchedAt = departureAt,
            legs = listOf(
                TransitJourneyLeg(0, TransitLegMode.WALK, 5),
                TransitJourneyLeg(1, TransitLegMode.SUBWAY, 15,
                    scheduledDepartureAt = departureAt.plusSeconds(5 * 60),
                    scheduledArrivalAt = departureAt.plusSeconds(20 * 60),
                    timingBasis = TransitLegTimingBasis.TIMETABLE,
                    timetable = table(SeoulSubwayLine.LINE_4, "4703", "0411", "0433", 5, 20)),
                TransitJourneyLeg(2, TransitLegMode.WALK, 5),
                TransitJourneyLeg(3, TransitLegMode.SUBWAY, 10,
                    scheduledDepartureAt = departureAt.plusSeconds(30 * 60),
                    scheduledArrivalAt = departureAt.plusSeconds(40 * 60),
                    timingBasis = TransitLegTimingBasis.TIMETABLE,
                    timetable = table(SeoulSubwayLine.LINE_2, "2480", "0226", "0230", 30, 40)),
                TransitJourneyLeg(4, TransitLegMode.WALK, 2),
            ),
        )
    }

    private fun singleRideJourney(timingBasis: TransitLegTimingBasis): TransitJourney = TransitJourney(
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
                durationMinutes = 40,
                waitingMinutes = 3,
                scheduledDepartureAt = departureAt,
                scheduledArrivalAt = departureAt.plusSeconds(40 * 60),
                timingBasis = timingBasis,
            ),
        ),
    )

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

    @Test
    fun `ROUTE_ESTIMATE 시각은 존재해도 환승 시간표 증거로 사용하지 않는다`() {
        val journey = transferJourney(
            nextDepartureAt = departureAt.plusSeconds(35 * 60)
        ).let { original ->
            original.copy(
                legs = original.legs.map { leg ->
                    if (leg.isRide) {
                        leg.copy(
                            timingBasis = TransitLegTimingBasis.ROUTE_ESTIMATE
                        )
                    } else {
                        leg
                    }
                }
            )
        }
        val overlay = TransitRealtimeOverlay(
            travelMinutes = 45,
            predictedArrivalAt = journey.arrivalAt.plusSeconds(5 * 60),
            observedAt = departureAt,
            boardingAt = departureAt.plusSeconds(10 * 60),
        )

        val result = evaluator.evaluate(
            journey = journey,
            firstBoardingOverlay = overlay,
            evaluatedAt = departureAt,
        )

        // 시각값이 있어도 실제 시간표가 아니므로 환승 가능을 확정하지 않는다.
        assertEquals(TransitTransferStatus.UNKNOWN, result.status)
        assertEquals(
            TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE,
            result.timingBasis,
        )
        assertFalse(result.eligible)
    }
}
