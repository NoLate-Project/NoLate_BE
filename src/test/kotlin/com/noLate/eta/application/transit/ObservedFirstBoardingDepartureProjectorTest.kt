package com.noLate.eta.application.transit

import com.noLate.eta.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** Recorded clock replay only, not a new live feed or map-accuracy sample. */
class ObservedFirstBoardingDepartureProjectorTest {
    private val start = Instant.parse("2026-10-13T02:20:00Z")
    private val evaluated = start.minusSeconds(60)
    private val observation = TransitFirstBoardingObservation("seoul-openapi", "노원", "4호선",
        start, start.plusSeconds(120), start.plusSeconds(215), start.minusSeconds(85), evaluated, "4585")

    @Test
    fun `같은 관측 열차의 대기만 줄이고 모든 도보 여유 환승편과 최종 도착을 보존한다`() {
        for (seconds in listOf(false, true)) {
            val original = journey()
            val resolution = resolution()
            val result = requireNotNull(ObservedFirstBoardingDepartureProjector.project(original, resolution, evaluated, seconds))
            val expected = start.plusSeconds(if (seconds) 95 else 60)
            assertEquals(expected, result.journey.departureAt)
            assertEquals(original.arrivalAt, result.journey.arrivalAt)
            assertEquals(observation.expectedBoardingAt, result.resolution.overlay?.boardingAt)
            assertEquals(observation.sourceUpdatedAt, result.resolution.firstBoardingObservation?.sourceUpdatedAt)
            assertEquals(120, java.time.Duration.between(expected, result.resolution.firstBoardingObservation?.readyAt).seconds)
            assertEquals(listOf(60L, 157L, 60L), result.journey.legs.filter { !it.isRide }.map { it.movementDurationSeconds })
            assertEquals(original.rideLegs.map { it.timetable }, result.journey.rideLegs.map { it.timetable })
            original.legs.drop(2).zip(result.journey.legs.drop(2)).forEach { (before, after) -> assertSame(before, after) }
            val before = TransitTransferFeasibilityEvaluator().evaluate(original, resolution.overlay, evaluated)
            val after = TransitTransferFeasibilityEvaluator().evaluate(result.journey, result.resolution.overlay, evaluated)
            assertEquals(TransitTransferStatus.FEASIBLE, after.status)
            assertEquals(before.predictedArrivalAt, after.predictedArrivalAt)
            assertTrue(after.travelMinutes < before.travelMinutes)
            assertEquals(start, original.departureAt) // No mutation of the provider/request evidence.
        }
    }

    @Test
    fun `조기 관측도 같은 시간표와 후속 열차를 유지한 채 안전한 출발만 옮긴다`() {
        val early = observation.copy(expectedBoardingAt = start.plusSeconds(140))
        val original = journey().let { value ->
            val first = value.legs[1]
            val table = requireNotNull(first.timetable).copy(boardingAt = start.plusSeconds(180),
                arrivalAt = start.plusSeconds(3060))
            value.copy(legs = value.legs.mapIndexed { index, leg -> if (index == 1)
                first.copy(timetable = table, firstBoardingObservation = null,
                    scheduledArrivalAt = table.arrivalAt, durationSeconds = 3000, durationMinutes = 50)
                else leg })
        }
        val resolved = resolution().copy(firstBoardingObservation = early,
            overlay = resolution().overlay!!.copy(boardingAt = early.expectedBoardingAt))
        val before = TransitTransferFeasibilityEvaluator().evaluate(original, resolved.overlay, evaluated)
        val result = requireNotNull(ObservedFirstBoardingDepartureProjector.project(original, resolved, evaluated, true))
        assertEquals(start.plusSeconds(20), result.journey.departureAt)
        assertNull(result.journey.rideLegs.first().firstBoardingObservation)
        assertEquals(original.rideLegs.map { it.timetable }, result.journey.rideLegs.map { it.timetable })
        val after = TransitTransferFeasibilityEvaluator().evaluate(result.journey, result.resolution.overlay, evaluated)
        assertTrue(after.eligible)
        assertEquals(before.predictedArrivalAt, after.predictedArrivalAt)
        assertEquals(early.expectedBoardingAt, result.resolution.firstBoardingObservation?.readyAt)
    }

    @Test
    fun `단일 열차의 embedded 지연도 중복 적용하거나 제거하지 않는다`() {
        val original = journey().let { it.copy(legs = it.legs.take(2) + it.legs.last(),
            arrivalAt = start.plusSeconds(3155), totalMinutes = 53) }
        val resolution = resolution().copy(overlay = resolution().overlay!!.copy(predictedArrivalAt = original.arrivalAt, travelMinutes = 53))
        val result = requireNotNull(ObservedFirstBoardingDepartureProjector.project(original, resolution, evaluated, true))
        assertEquals(original.arrivalAt, TransitTransferFeasibilityEvaluator()
            .evaluate(result.journey, result.resolution.overlay, evaluated).predictedArrivalAt)
    }

    @Test
    fun `차량 관측 요청 또는 시간표 일치를 증명하지 못하면 출발을 옮기지 않는다`() {
        val original = journey()
        for (bad in listOf(observation.copy(trainNumber = "OTHER"), observation.copy(stationName = "OTHER"),
            observation.copy(provider = "OTHER"), observation.copy(evaluatedAt = evaluated.minusSeconds(1)),
            observation.copy(readyAt = start.plusSeconds(59)))) {
            assertNull(ObservedFirstBoardingDepartureProjector.project(original, resolution().copy(firstBoardingObservation = bad), evaluated, true))
        }
        assertNull(ObservedFirstBoardingDepartureProjector.project(original, resolution().copy(firstBoardingObservation = null), evaluated, true))
    }

    private fun resolution() = TransitRealtimeOverlayResolution(
        overlay = TransitRealtimeOverlay(68, start.plusSeconds(4050), evaluated, observation.expectedBoardingAt, 3),
        firstBoardingObservation = observation)

    private fun journey(): TransitJourney {
        fun table(line: SeoulSubwayLine, train: String, from: String, to: String, board: Long, arrive: Long) =
            TransitLegTimetable("seoul-timetable", line, LocalDate.parse("2026-10-13"), train, "DOWN", TransitServiceClass.LOCAL,
                from, to, "종착", start.plusSeconds(board), start.plusSeconds(arrive), evaluated)
        val first = table(SeoulSubwayLine.LINE_4, "4585", "0411", "0433", 0, 2880)
        val second = table(SeoulSubwayLine.LINE_2, "2240", "0226", "0230", 3540, 3990)
        return TransitJourney("tmap", start, start, start.plusSeconds(4050), 68, listOf(
            TransitJourneyLeg(0, TransitLegMode.WALK, 1, durationSeconds = 60, scheduledDepartureAt = start, scheduledArrivalAt = start.plusSeconds(60)),
            TransitJourneyLeg(1, TransitLegMode.SUBWAY, 51, durationSeconds = 3035, waitingMinutes = 3,
                scheduledDepartureAt = start.plusSeconds(60), scheduledArrivalAt = start.plusSeconds(3095),
                from = TransitStop(name = "노원"), to = TransitStop(name = "사당"), timetable = first,
                timingBasis = TransitLegTimingBasis.TIMETABLE, firstBoardingObservation = observation),
            TransitJourneyLeg(2, TransitLegMode.WALK, 3, durationSeconds = 157, scheduledDepartureAt = start.plusSeconds(3095), scheduledArrivalAt = start.plusSeconds(3252)),
            TransitJourneyLeg(3, TransitLegMode.SUBWAY, 13, durationSeconds = 738, waitingMinutes = 5,
                scheduledDepartureAt = start.plusSeconds(3252), scheduledArrivalAt = second.arrivalAt,
                from = TransitStop(name = "사당"), to = TransitStop(name = "신림"), timetable = second, timingBasis = TransitLegTimingBasis.TIMETABLE),
            TransitJourneyLeg(4, TransitLegMode.WALK, 1, durationSeconds = 60, scheduledDepartureAt = second.arrivalAt, scheduledArrivalAt = start.plusSeconds(4050)),
        ), evaluated)
    }
}
