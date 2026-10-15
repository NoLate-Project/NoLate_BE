package com.noLate.eta.integration

import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.application.transit.TransitJourneyTimetableEnricher
import com.noLate.eta.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** 합성 시간표로 입력 치환/재선택만 검증한다. 실측 정확도 테스트가 아니다. */
class TransitWalkingComparisonFixtureTest {
    private val departure = Instant.parse("2026-10-05T22:30:00Z")

    @Test
    fun `도보를 치환하면 열차를 재선택하며 원본 총시간에서 단순 차감하지 않는다`() {
        val original = original()
        val queries = mutableListOf<TransitTimetableQuery>()
        val provider = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                queries += query
                return if (query.line == SeoulSubwayLine.LINE_4) listOf(ride(query.line, "4539", 420, 3300))
                else listOf(ride(query.line, "2098", 3540, 3990), ride(query.line, "2100", 3750, 4200))
            }
        }
        val enricher = TransitJourneyTimetableEnricher(listOf(provider))
        val baseline = enricher.enrich(original, departure.minusSeconds(60), 180)
        val normalized = TransitWalkingComparisonFixture.replaceWalkingSeconds(original, listOf(300, 120, 0))
        val comparison = enricher.enrich(normalized, departure.minusSeconds(60), 180)

        assertEquals("2100", baseline.rideLegs[1].timetable?.trainNumber)
        assertEquals("2098", comparison.rideLegs[1].timetable?.trainNumber)
        assertEquals(departure.plusSeconds(3480), queries.last().notBefore)
        assertEquals(departure.plusSeconds(3990), comparison.arrivalAt)
        assertEquals(listOf(342L, 2880L, 243L, 450L, 168L), original.legs.map { it.durationSeconds })
        assertEquals(listOf(300L, 2880L, 120L, 450L, 0L), normalized.legs.map { it.durationSeconds })
        assertNotEquals(baseline.arrivalAt.minusSeconds(42 + 123 + 168), comparison.arrivalAt)
    }

    @Test
    fun `불완전한 도보 프로필과 이미 시간표가 연결된 여정은 치환하지 않는다`() {
        val original = original()
        for (invalid in listOf(listOf(300L, 120), listOf(-1L, 120, 0), listOf(1801L, 120, 0))) {
            assertThrows(IllegalArgumentException::class.java) {
                TransitWalkingComparisonFixture.replaceWalkingSeconds(original, invalid)
            }
        }
        val confirmed = original.copy(legs = original.legs.map { leg ->
            if (leg.isRide) leg.copy(timingBasis = TransitLegTimingBasis.TIMETABLE) else leg
        })
        assertThrows(IllegalArgumentException::class.java) {
            TransitWalkingComparisonFixture.replaceWalkingSeconds(confirmed, listOf(300, 120, 0))
        }
    }

    private fun ride(line: SeoulSubwayLine, train: String, boarding: Long, arrival: Long) = TransitLegTimetable(
        "fixture-only", line, LocalDate.of(2026, 10, 6), train, "DOWN", TransitServiceClass.LOCAL,
        "FROM", "TO", "fixture-terminal", departure.plusSeconds(boarding), departure.plusSeconds(arrival), departure,
    )

    private fun original(): TransitJourney {
        val modes = listOf(TransitLegMode.WALK, TransitLegMode.SUBWAY, TransitLegMode.WALK,
            TransitLegMode.SUBWAY, TransitLegMode.WALK)
        val durations = listOf(342L, 2880L, 243L, 450L, 168L)
        val legs = modes.mapIndexed { index, mode ->
            TransitJourneyLeg(sequence = index, mode = mode, durationSeconds = durations[index],
                durationMinutes = ((durations[index] + 59) / 60).toInt(), timingBasis = TransitLegTimingBasis.ROUTE_ESTIMATE,
                from = TransitStop(name = "FROM"), to = TransitStop(name = "TO"),
                line = if (mode == TransitLegMode.SUBWAY) TransitLine(serviceClass = TransitServiceClass.LOCAL,
                    seoulSubwayLine = if (index == 1) SeoulSubwayLine.LINE_4 else SeoulSubwayLine.LINE_2) else null)
        }
        return TransitJourney("fixture-only", departure, departure, departure.plusSeconds(durations.sum()),
            69, legs, departure.minusSeconds(60))
    }
}
