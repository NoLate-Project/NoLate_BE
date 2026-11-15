package com.noLate.journey.automatic

import com.noLate.journey.application.JourneySubwayTrainStationMatcher
import com.noLate.journey.application.SavedSubwayWaitingArrival
import com.noLate.transit.domain.SeoulSubwayObservationLine
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class SavedSubwayWaitingArrivalTest {
    private val now = Instant.parse("2026-10-13T10:00:00Z")
    private val pattern = JourneySubwayTrainStationMatcher.Pattern(SeoulSubwayObservationLine.LINE_2, 0, "0215", 0,
        listOf("0222", "0221", "0220", "0215"), 0, 2,
        listOf("강남", "역삼", "선릉", "성수"))
    private fun row(source: String = "2026-10-13T09:59:20Z", direction: String = "내선",
        terminal: String = "성수", station: String = "강남", line: String = "2호선",
        expected: String = "2026-10-13T10:01:40Z") = TransitArrivalDto(
        provider = "seoul-openapi", kind = "SUBWAY", lineName = line, stationName = station,
        direction = direction, destinationName = terminal, trainNumber = "2243", realtime = true,
        sourceUpdatedAt = source, observedAt = "2026-10-13T09:59:25Z", expectedAt = expected,
        freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP)

    @Test fun `only the fresh exact saved service produces a two minute platform arrival hint`() {
        val selected = SavedSubwayWaitingArrival.select(listOf(pattern), listOf(row()), now)
        assertThat(selected).containsEntry("identityEvidence", "OFFICIAL_SAVED_SUBWAY_SERVICE_PATTERN")
            .containsEntry("boardingStationName", "강남")
            .containsEntry("terminalStationName", "성수")
            .containsEntry("expectedAt", "2026-10-13T10:01:40Z")
            .containsEntry("validUntil", "2026-10-13T10:01:20Z")
            .containsEntry("confirmedBoarding", false)
    }

    @Test fun `wrong direction terminal station or line cannot announce an approaching train`() {
        assertThat(SavedSubwayWaitingArrival.select(listOf(pattern), listOf(row(direction = "외선")), now)).isNull()
        assertThat(SavedSubwayWaitingArrival.select(listOf(pattern), listOf(row(terminal = "신도림")), now)).isNull()
        assertThat(SavedSubwayWaitingArrival.select(listOf(pattern), listOf(row(station = "역삼")), now)).isNull()
        assertThat(SavedSubwayWaitingArrival.select(listOf(pattern), listOf(row(line = "3호선")), now)).isNull()
    }

    @Test fun `stale or unverified service data cannot announce an arrival`() {
        assertThat(SavedSubwayWaitingArrival.select(listOf(pattern), listOf(row(source = "2026-10-13T09:57:00Z")), now)).isNull()
        assertThat(SavedSubwayWaitingArrival.select(listOf(pattern), listOf(row().copy(
            freshnessEvidence = TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY)), now)).isNull()
    }

    @Test fun `an exact train can be selected among multiple official terminal services`() {
        val alternative = JourneySubwayTrainStationMatcher.Pattern(SeoulSubwayObservationLine.LINE_2, 0, "0230", 0,
            listOf("0222", "0221", "0220", "0230"), 0, 2,
            listOf("강남", "역삼", "선릉", "신도림"))
        val selected = SavedSubwayWaitingArrival.select(listOf(pattern, alternative),
            listOf(row(terminal = "신도림", expected = "2026-10-13T10:01:50Z"), row()), now)
        assertThat(selected).containsEntry("terminalStationName", "성수")
            .containsEntry("expectedAt", "2026-10-13T10:01:40Z")
    }
}
