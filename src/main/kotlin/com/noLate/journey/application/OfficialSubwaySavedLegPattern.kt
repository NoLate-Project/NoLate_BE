package com.noLate.journey.application

import com.noLate.transit.domain.SeoulSubwayObservationLine
import com.noLate.transit.infrastructure.SeoulMetroShortestPath

/** Joins one saved ODsay leg to two independent official paths.
 * Every call in the saved leg and in the train's terminal-bound service is checked. An
 * observation must still corroborate its current/terminal station code AND name; it does
 * not identify the passenger. */
internal object OfficialSubwaySavedLegPattern {
    fun resolve(snapshot: JourneyRouteSnapshot, legIndex: Int, savedSegment: SeoulMetroShortestPath,
        terminalService: SeoulMetroShortestPath,
    ): JourneySubwayTrainStationMatcher.Pattern? {
        val compared = SavedSubwayOfficialPathComparison.compare(snapshot, legIndex, savedSegment) ?: return null
        val segment = savedSegment.sections
        val service = terminalService.sections
        if (segment.isEmpty() || service.size < segment.size || savedSegment.reportedTransferCount != 0 ||
            terminalService.reportedTransferCount != 0) return null
        val line = SeoulSubwayObservationLine.entries.singleOrNull { it.requestName == compared.line } ?: return null
        val raw = JourneySubwaySavedLegEvidence.resolve(snapshot, legIndex).evidence ?: return null
        if (raw.routingLine != compared.line.first().toString()) return null
        val terminal = segment.map { it.terminalCode }.distinct().singleOrNull() ?: return null
        val direction = segment.map { it.direction }.distinct().singleOrNull() ?: return null
        val directionCode = when (direction) {
            "상행", "내선" -> 0
            "하행", "외선" -> 1
            else -> return null
        }
        // ODsay wayCode is 1=up/inner, 2=down/outer. Do not silently replace a
        // saved route's direction with the official shortest-path direction.
        if (raw.routingWayCode != (directionCode + 1).toString()) return null
        if (service.any { it.transfer != "N" || it.express != "N" || it.direction != direction ||
                it.departure.line != compared.line || it.arrival.line != compared.line ||
                it.terminalCode != terminal } ||
            segment.any { it.transfer != "N" || it.express != "N" || it.direction != direction } ||
            service.first().departure != segment.first().departure || service.last().arrival.code != terminal)
            return null
        val serviceStations = listOf(service.first().departure) + service.map { it.arrival }
        val savedStations = listOf(segment.first().departure) + segment.map { it.arrival }
        if (serviceStations.take(savedStations.size) != savedStations ||
            savedStations.map { it.code } != compared.officialStationCodes) return null
        if (serviceStations.any { !it.code.matches(Regex("[0-9]{4}")) } ||
            serviceStations.map { it.code }.distinct().size != serviceStations.size) return null
        return JourneySubwayTrainStationMatcher.Pattern(line, directionCode,
            serviceStations.last().code, 0, serviceStations.map { it.code },
            0, savedStations.lastIndex, serviceStations.map { it.name })
    }
}
