package com.noLate.journey.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.transit.infrastructure.SeoulMetroShortestPath

/** Compares two complete, independently supplied station sequences. This is not an
 * ODsay→Seoul ID conversion and never authorizes a train or passenger position. */
internal object SavedSubwayOfficialPathComparison {
    data class Candidate(val line: String, val stationNames: List<String>, val officialStationCodes: List<String>) {
        val realtimeStationIdsVerified = false
        val passengerBoardingConfirmed = false
        override fun toString() = "OfficialSubwayPathCandidate(stations=${stationNames.size}, unverified)"
    }

    /** ODsay displays Seoul metro lines with an optional 수도권 prefix. The raw ODsay
     * line code must independently agree; a display name alone cannot select a line. */
    fun canonicalLine(rawLine: String, displayedLine: String?): String? {
        if (rawLine.length != 1 || rawLine[0] !in '1'..'9') return null
        val official = "${rawLine}호선"
        return official.takeIf { displayedLine == official || displayedLine == "수도권 $official" }
    }

    fun compare(snapshot: JourneyRouteSnapshot, legIndex: Int, path: SeoulMetroShortestPath): Candidate? {
        val evidence = JourneySubwaySavedLegEvidence.resolve(snapshot, legIndex).evidence ?: return null
        if (path.sections.size != evidence.stations.size - 1 || path.reportedTransferCount != 0) return null
        val leg = try { jacksonObjectMapper().readTree(snapshot.legsJson).path(legIndex) }
            catch (_: Exception) { return null }
        val line = canonicalLine(evidence.routingLine,
            leg.path("lineName").takeIf { it.isTextual }?.asText()) ?: return null
        val stops = leg.path("navigationEvidence").path("stopOccurrences")
        if (!stops.isArray || stops.size() != evidence.stations.size) return null
        val savedNames = stops.map { it.path("name").takeIf { name -> name.isTextual }?.asText() ?: return null }
        if (savedNames.any { it.isBlank() || it != it.trim() || it.length > 80 } ||
            savedNames.distinct().size != savedNames.size ||
            savedNames.first() != leg.path("startName").asText() ||
            savedNames.last() != leg.path("endName").asText()) return null
        val sections = path.sections
        if (sections.any { it.transfer != "N" || it.departure.line != line || it.arrival.line != line }) return null
        val official = listOf(sections.first().departure) + sections.map { it.arrival }
        val officialNames = official.map { it.name }
        val codes = official.map { it.code }
        if (officialNames != savedNames || codes.distinct().size != codes.size) return null
        return Candidate(line, officialNames, codes)
    }
}
