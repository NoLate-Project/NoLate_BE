package com.noLate.eta.application.transit

import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.LocalDate

/**
 * 10/12 18:20 노원→사당→신림 격리 E2E의 선택된 시간축을 재생한다.
 * 새 공급자 호출이나 지도 정확도 검증이 아니다. 네이버의 미확인 열차 시각을 만들지 않는다.
 */
class RecordedNowonBoardingTimelineTest {
    private val departure = at("18:20:00")
    private val evaluatedAt = at("18:19:03")

    @ParameterizedTest
    @ValueSource(longs = [0, 60])
    fun `접근 도보와 승차 여유는 선택 경계에만 적용하고 최종 도착에 다시 더하지 않는다`(buffer: Long) {
        val queries = mutableListOf<TransitTimetableQuery>()
        val input = inputJourney()
        val enriched = TransitJourneyTimetableEnricher(
            listOf(recordedProvider(queries)), boardingBufferSeconds = buffer,
        ).enrich(input, evaluatedAt, 180)

        assertEquals(at("18:21:00").plusSeconds(buffer), queries[0].notBefore)
        // 18:20:30 열차는 여유를 0으로 바꾸어도 접근 도보가 끝나기 전이다.
        // 탈락한 열차의 미확인 하차시각은 이 회귀 데이터에 넣지 않는다.
        assertTrue(at("18:20:30").isBefore(queries[0].notBefore))
        assertEquals(at("19:15:30"), queries[1].notBefore) // 하차 + 환승 도보 180초 + 여유 60초
        assertEquals(listOf("K4669", "2388"), enriched.rideLegs.map { it.timetable?.trainNumber })
        assertEquals(listOf(60L, 180L, 60L), enriched.legs.filter { !it.isRide }.map { it.movementDurationSeconds })
        assertEquals(at("19:26:00"), enriched.arrivalAt)
        assertEquals(66, enriched.totalMinutes)

        val transfer = TransitTransferFeasibilityEvaluator().evaluate(enriched, null, evaluatedAt)
        assertEquals(TransitTransferStatus.FEASIBLE, transfer.status)
        assertEquals(enriched.arrivalAt, transfer.predictedArrivalAt)
        assertEquals(66, transfer.travelMinutes)
        assertTrue(input.rideLegs.all { it.timetable == null }) // 입력 추정 경로는 변경하지 않는다.
    }

    @ParameterizedTest
    @ValueSource(longs = [0, 5])
    fun `첫 열차 보정은 환승 대기에 흡수되며 도보 대기나 여유를 재누적하지 않는다`(delaySeconds: Long) {
        val enriched = TransitJourneyTimetableEnricher(listOf(recordedProvider(mutableListOf())))
            .enrich(inputJourney(), evaluatedAt, 180)
        // 5초 지연은 뒤이은 실제 E2E 관측과 같다. 여기서는 검증 완료 overlay의 전파만 검사한다.
        val overlay = TransitRealtimeOverlay(
            travelMinutes = if (delaySeconds == 0L) 66 else 67,
            predictedArrivalAt = enriched.arrivalAt.plusSeconds(delaySeconds),
            observedAt = evaluatedAt, boardingAt = at("18:23:30").plusSeconds(delaySeconds),
            firstBoardingWaitMinutes = 3,
        )
        val result = TransitTransferFeasibilityEvaluator().evaluate(enriched, overlay, evaluatedAt)

        assertEquals(TransitTransferStatus.FEASIBLE, result.status)
        assertEquals(at("19:26:00"), result.predictedArrivalAt)
        assertEquals(66, result.travelMinutes)
        assertEquals(TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE, result.timingBasis)
    }

    private fun recordedProvider(queries: MutableList<TransitTimetableQuery>) = object : TransitTimetableProvider {
        override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
            queries += query
            return listOf(if (query.line == SeoulSubwayLine.LINE_4) {
                table(query.line, "K4669", "18:23:30", "19:11:30", "오이도")
            } else {
                table(query.line, "2388", "19:17:30", "19:25:00", "내선순환")
            })
        }
    }

    private fun table(line: SeoulSubwayLine, train: String, board: String, alight: String, terminal: String) =
        TransitLegTimetable(
            provider = "seoul-timetable", line = line, serviceDate = LocalDate.of(2026, 10, 12),
            trainNumber = train, directionCode = "DOWN", serviceClass = TransitServiceClass.LOCAL,
            // 이 테스트는 역코드 adapter가 아니라 시각 계산을 검사한다. 운영 역코드를 추정하지 않는다.
            fromStationCode = "FIXTURE_FROM", toStationCode = "FIXTURE_TO", terminalName = terminal,
            boardingAt = at(board), arrivalAt = at(alight), fetchedAt = evaluatedAt,
        )

    private fun inputJourney(): TransitJourney {
        fun walk(sequence: Int, minutes: Int) = TransitJourneyLeg(sequence, TransitLegMode.WALK, minutes)
        fun subway(sequence: Int, line: SeoulSubwayLine, from: String, to: String, minutes: Int) =
            TransitJourneyLeg(
                sequence, TransitLegMode.SUBWAY, minutes, from = TransitStop(name = from), to = TransitStop(name = to),
                line = TransitLine(name = line.displayName, serviceClass = TransitServiceClass.LOCAL, seoulSubwayLine = line),
                directionCode = "DOWN", timingBasis = TransitLegTimingBasis.ROUTE_ESTIMATE,
            )
        return TransitJourney(
            provider = "odsay", requestedDepartureAt = departure, departureAt = departure,
            // 입력 승차 소요시간은 합성이며 시간표 보강에서 교체된다. 실제 ODsay 원문으로 가장하지 않는다.
            arrivalAt = departure.plusSeconds(60 * 60), totalMinutes = 60, fetchedAt = evaluatedAt,
            legs = listOf(walk(0, 1), subway(1, SeoulSubwayLine.LINE_4, "노원", "사당", 48),
                walk(2, 3), subway(3, SeoulSubwayLine.LINE_2, "사당", "신림", 7), walk(4, 1)),
        )
    }

    private fun at(time: String): Instant = java.time.OffsetDateTime.parse("2026-10-12T${time}+09:00").toInstant()
}
