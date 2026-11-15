package com.noLate.journey.application

import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import java.time.Instant

/** A platform arrival hint, never passenger location or boarding evidence. */
internal object SavedSubwayWaitingArrival {
    fun select(patterns: List<JourneySubwayTrainStationMatcher.Pattern>, rows: List<TransitArrivalDto>, now: Instant): Map<String, Any?>? {
        val identities = patterns.mapNotNull { pattern ->
            val names = pattern.officialStationNames ?: return@mapNotNull null
            if (names.size != pattern.stationCalls.size || pattern.boardingIndex !in names.indices) return@mapNotNull null
            Identity(pattern.line.requestName, names[pattern.boardingIndex], names.last(), pattern.direction)
        }.distinct().takeIf { it.isNotEmpty() && it.size <= patterns.size && patterns.all { it.officialStationNames != null } }
            ?: return null
        val selected = rows.asSequence().mapNotNull { row ->
            if (row.provider != "seoul-openapi" || row.kind != "SUBWAY" || !row.realtime ||
                row.freshnessEvidence != TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP ||
                row.trainNumber?.matches(Regex("[0-9]{1,8}")) != true) return@mapNotNull null
            val identity = identities.singleOrNull { identity ->
                val tokens = if (identity.direction == 0) listOf("상행", "내선") else listOf("하행", "외선")
                row.lineName == identity.line && name(row.stationName) == name(identity.boarding) &&
                    name(row.destinationName) == name(identity.terminal) &&
                    tokens.any { row.direction?.contains(it) == true }
            } ?: return@mapNotNull null
            val source = instant(row.sourceUpdatedAt) ?: return@mapNotNull null
            val received = instant(row.observedAt) ?: return@mapNotNull null
            val expected = instant(row.expectedAt) ?: return@mapNotNull null
            if (source.isAfter(now.plusSeconds(5)) || source.isBefore(now.minusSeconds(120)) ||
                received.isBefore(source.minusSeconds(5)) || received.isAfter(now.plusSeconds(5)) ||
                !expected.isAfter(now) || expected.isAfter(source.plusSeconds(3600))) return@mapNotNull null
            val until = minOf(source.plusSeconds(120), expected)
            if (!until.isAfter(now)) return@mapNotNull null
            Candidate(identity, row, source, received, expected, until)
        }.minByOrNull { it.expected } ?: return null
        return mapOf(
            "identityEvidence" to "OFFICIAL_SAVED_SUBWAY_SERVICE_PATTERN",
            "provider" to "SEOUL_SUBWAY", "kind" to "SUBWAY", "lineName" to selected.identity.line,
            "boardingStationName" to selected.identity.boarding, "terminalStationName" to selected.identity.terminal,
            "direction" to selected.row.direction, "trainNumber" to selected.row.trainNumber,
            "sourceUpdatedAt" to selected.source.toString(), "receivedAt" to selected.received.toString(),
            "expectedAt" to selected.expected.toString(), "validUntil" to selected.until.toString(),
            "confirmedBoarding" to false,
        )
    }

    private data class Identity(val line: String, val boarding: String, val terminal: String, val direction: Int)
    private data class Candidate(val identity: Identity, val row: TransitArrivalDto, val source: Instant, val received: Instant,
        val expected: Instant, val until: Instant)
    private fun instant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
    private fun name(value: String?): String? = value?.replace("\\s+".toRegex(), "")?.removeSuffix("역")
        ?.takeIf { it.isNotBlank() }
}
