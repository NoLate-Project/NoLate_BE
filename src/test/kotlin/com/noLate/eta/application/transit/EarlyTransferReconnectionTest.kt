package com.noLate.eta.application.transit

import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.*

/** 합성 열차로 조기 관측의 환승 연결만 검증한다. 지도/실제 운행 정확도 증거가 아니다. */
class EarlyTransferReconnectionTest {
    private val now = Instant.parse("2026-10-12T13:58:00Z")
    private val departure = now.plusSeconds(120)
    private val first = table(SeoulSubwayLine.LINE_4, "FIRST_FIXTURE", 450, 3330)
    private val later = table(SeoulSubwayLine.LINE_2, "LATER_FIXTURE", 3930, 4380)
    private val earlier = table(SeoulSubwayLine.LINE_2, "EARLIER_FIXTURE", 3510, 3960)
    private val observation = TransitFirstBoardingObservation("seoul-openapi", "노원", "4호선",
        departure, departure.plusSeconds(120), now.plusSeconds(320), now, now, first.trainNumber)
    private val overlay = TransitRealtimeOverlay(70, now.plusSeconds(4310), now, observation.expectedBoardingAt)

    @Test
    fun `정상 환승도 조기 관측으로 더 이른 열차를 연결하며 도보와 여유를 유지한다`() {
        val original = journey()
        val queries = mutableListOf<TransitTimetableQuery>()
        val enricher = enricher(queries)
        assertEquals(TransitTransferStatus.FEASIBLE,
            TransitTransferFeasibilityEvaluator().evaluate(original, overlay, now).status)
        val improved = enricher.reconnectEarlierTransfers(original, overlay, observation, now, 180)

        assertEquals(1, queries.size) // 첫 정류장 실시간/전체 경로를 다시 조회하지 않는다.
        assertEquals(now.plusSeconds(3440), queries.single().notBefore) // 23:51:20 + 180초 + 60초
        assertEquals(first.serviceDate, queries.single().serviceDate)
        assertSame(original.rideLegs.first(), improved.rideLegs.first())
        assertEquals(earlier.trainNumber, improved.rideLegs.last().timetable?.trainNumber)
        assertEquals(now.plusSeconds(4020), improved.arrivalAt)
        assertEquals(65, improved.totalMinutes)
        assertEquals(listOf(60L, 180L, 60L), improved.legs.filter { !it.isRide }.map { it.movementDurationSeconds })
        val evaluated = TransitTransferFeasibilityEvaluator().evaluate(improved, overlay, now)
        assertEquals(TransitTransferStatus.FEASIBLE, evaluated.status)
        assertEquals(improved.arrivalAt, evaluated.predictedArrivalAt) // 조기 보정을 두 번 빼지 않는다.
        assertEquals(65, evaluated.travelMinutes)
        assertEquals(now.plusSeconds(4440), original.arrivalAt)
        assertEquals(later.trainNumber, original.rideLegs.last().timetable?.trainNumber)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "provider", "station", "line", "train", "stale", "future-source",
        "evaluation", "departure", "boarding", "ready"])
    fun `원천 차량과 조회 기준 또는 승차 여유를 확인하지 못하면 조기 재연결하지 않는다`(bad: String) {
        val invalid = when (bad) {
            "missing" -> null
            "provider" -> observation.copy(provider = "other")
            "station" -> observation.copy(stationName = "다른역")
            "line" -> observation.copy(lineName = "2호선")
            "train" -> observation.copy(trainNumber = "OTHER")
            "stale" -> observation.copy(sourceUpdatedAt = now.minusSeconds(121))
            "future-source" -> observation.copy(sourceUpdatedAt = now.plusSeconds(61))
            "evaluation" -> observation.copy(evaluatedAt = now.minusSeconds(1))
            "departure" -> observation.copy(departureAt = departure.plusSeconds(1))
            "boarding" -> observation.copy(expectedBoardingAt = observation.expectedBoardingAt.plusSeconds(1))
            else -> observation.copy(readyAt = observation.readyAt.minusSeconds(1))
        }
        val original = journey()
        val queries = mutableListOf<TransitTimetableQuery>()
        assertSame(original, enricher(queries).reconnectEarlierTransfers(original, overlay, invalid, now, 180))
        assertTrue(queries.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["provider", "from", "to", "line", "direction", "class", "date", "margin"])
    fun `조기 후보도 동일 경로 운행일 종별과 60초 여유를 모두 지켜야 한다`(bad: String) {
        val invalid = when (bad) {
            "provider" -> earlier.copy(provider = "other")
            "from" -> earlier.copy(fromStationCode = "OTHER")
            "to" -> earlier.copy(toStationCode = "OTHER")
            "line" -> earlier.copy(line = SeoulSubwayLine.LINE_3)
            "direction" -> earlier.copy(directionCode = "UP")
            "class" -> earlier.copy(serviceClass = TransitServiceClass.EXPRESS)
            "date" -> earlier.copy(serviceDate = earlier.serviceDate.plusDays(1))
            else -> earlier.copy(boardingAt = now.plusSeconds(3439))
        }
        val original = journey()
        assertSame(original, enricher(mutableListOf(), listOf(invalid))
            .reconnectEarlierTransfers(original, overlay, observation, now, 180))
    }

    @Test
    fun `같은 도착 예측 예외 미완전 환승이나 시간 예산 초과는 원본을 보존한다`() {
        val original = journey()
        assertSame(original, enricher(mutableListOf(), listOf(later))
            .reconnectEarlierTransfers(original, overlay, observation, now, 180))
        assertSame(original, enricher(mutableListOf())
            .reconnectEarlierTransfers(original, overlay, observation, now, 64))
        val failure = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> = error("synthetic failure")
        }
        assertSame(original, TransitJourneyTimetableEnricher(listOf(failure))
            .reconnectEarlierTransfers(original, overlay, observation, now, 180))
        val third = table(SeoulSubwayLine.LINE_3, "THIRD_FIXTURE", 4600, 4800)
        val multiple = original.copy(arrivalAt = third.arrivalAt, totalMinutes = 78,
            legs = original.legs + leg(5, third, 4440, "신림", "최종역"))
        assertSame(multiple, enricher(mutableListOf())
            .reconnectEarlierTransfers(multiple, overlay, observation, now, 180))
    }

    private fun enricher(queries: MutableList<TransitTimetableQuery>, rides: List<TransitLegTimetable> = listOf(earlier, later)) =
        TransitJourneyTimetableEnricher(listOf(object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                queries += query
                return if (query.line == SeoulSubwayLine.LINE_2) rides else emptyList()
            }
        }))

    private fun journey() = TransitJourney("odsay", departure, departure, now.plusSeconds(4440), 72,
        listOf(TransitJourneyLeg(0, TransitLegMode.WALK, 1), leg(1, first, 180, "노원", "사당"),
            TransitJourneyLeg(2, TransitLegMode.WALK, 3), leg(3, later, 3510, "사당", "신림"),
            TransitJourneyLeg(4, TransitLegMode.WALK, 1)), now)

    private fun leg(sequence: Int, ride: TransitLegTimetable, start: Long, from: String, to: String): TransitJourneyLeg {
        val seconds = Duration.between(now.plusSeconds(start), ride.arrivalAt).seconds
        return TransitJourneyLeg(sequence, TransitLegMode.SUBWAY, ((seconds + 59) / 60).toInt(),
            scheduledDepartureAt = now.plusSeconds(start), scheduledArrivalAt = ride.arrivalAt,
            from = TransitStop(name = from), to = TransitStop(name = to),
            line = TransitLine(seoulSubwayLine = ride.line, serviceClass = ride.serviceClass),
            directionCode = ride.directionCode, timingBasis = TransitLegTimingBasis.TIMETABLE,
            durationSeconds = seconds, timetable = ride)
    }

    private fun table(line: SeoulSubwayLine, train: String, board: Long, arrive: Long) = TransitLegTimetable(
        "fixture-timetable", line, LocalDate.of(2026, 10, 12), train, "DOWN", TransitServiceClass.LOCAL,
        "FIXTURE_FROM", "FIXTURE_TO", "종착역", now.plusSeconds(board), now.plusSeconds(arrive), now)
}
