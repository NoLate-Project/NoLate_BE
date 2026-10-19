package com.noLate.journey.application

import com.noLate.transit.domain.SeoulSubwayObservationLine
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections

/** Server-owned inputs only. Creating this object does not attest official provenance: deployment
 * still owes independently reviewed crosswalk/service data. No client upload, name/coordinate
 * join, numeric-ID ordering, implicit reversal, HTTP fetch or production bean is provided here. */
internal class IndependentSavedSubwayServiceCatalog(
    stations: Collection<Station>, services: Collection<Service>,
    private val validFrom: Instant, private val validUntil: Instant,
    private val clock: Clock = Clock.systemUTC(),
) : SavedSubwayServicePatternCatalog {
    data class Station(val routingCity: String, val routingLine: String, val routingStationId: String,
        val line: SeoulSubwayObservationLine, val stationId: String) {
        override fun toString() = "SubwayCatalogStation(REDACTED)"
    }
    class Service(val routingCity: String, val routingLine: String, val routingWayCode: String,
        val line: SeoulSubwayObservationLine, val direction: Int, val terminalStationId: String,
        val service: Int, stationCalls: List<String>) {
        // Kotlin List is read-only at compile time, not immutable across Java/cast consumers.
        val stationCalls: List<String> = Collections.unmodifiableList(ArrayList(stationCalls))
        override fun toString() = "SubwayCatalogService(REDACTED)"
    }
    private data class Key(val city: String, val line: String, val station: String)
    private val crosswalk: Map<Key, Station>
    private val patterns = services.toList()

    init {
        require(stations.size in 2..10_000 && patterns.size in 1..256) { "Invalid subway catalog bounds" }
        require(validUntil > validFrom && Duration.between(validFrom,validUntil) <= Duration.ofDays(31)) {
            "Invalid subway catalog validity window"
        }
        val rows = stations.toList()
        require(rows.all { it.routingCity == "1000" && validId(it.routingLine) &&
            validId(it.routingStationId) && validId(it.stationId) }) { "Invalid subway station crosswalk" }
        crosswalk = rows.associateBy { Key(it.routingCity,it.routingLine,it.routingStationId) }
        require(crosswalk.size == rows.size) { "Duplicate or conflicting subway station crosswalk" }
        require(rows.groupBy { it.routingCity to it.routingLine }.values.all { group -> group.map { it.line }.distinct().size == 1 }) {
            "Conflicting subway line crosswalk"
        }
        // One provider station cannot stand for two routing stations on the same routing line.
        require(rows.groupBy { listOf(it.routingCity,it.routingLine,it.line.name,it.stationId) }.values.all { it.size == 1 }) {
            "Ambiguous subway station crosswalk"
        }
        patterns.forEach { pattern ->
            require(pattern.routingCity == "1000" && validId(pattern.routingLine) &&
                pattern.routingWayCode in setOf("1","2")) { "Invalid subway routing pattern" }
            JourneySubwayTrainStationMatcher.Pattern(pattern.line,pattern.direction,pattern.terminalStationId,
                pattern.service,pattern.stationCalls,0,pattern.stationCalls.lastIndex)
            val known = rows.filter { it.routingCity == pattern.routingCity && it.routingLine == pattern.routingLine &&
                it.line == pattern.line }.map { it.stationId }.toSet()
            require(pattern.stationCalls.all { it in known }) { "Subway service has an unmapped station" }
        }
        require(patterns.groupBy { listOf(it.routingCity,it.routingLine,it.routingWayCode,it.line.name) }.values
            .all { group -> group.map { it.direction }.distinct().size == 1 }) { "Conflicting subway direction crosswalk" }
        require(patterns.map { listOf(it.routingCity,it.routingLine,it.routingWayCode,it.line.name,
            it.direction,it.terminalStationId,it.service,it.stationCalls) }.distinct().size == patterns.size) {
            "Duplicate subway service pattern"
        }
    }

    override fun validAt(now: Instant): Boolean = now >= validFrom && now < validUntil
    override fun resolve(route: JourneyRouteSnapshot, legIndex: Int): JourneySubwayTrainStationMatcher.Pattern? =
        resolveAll(route,legIndex).singleOrNull()

    override fun resolveAll(route: JourneyRouteSnapshot, legIndex: Int): List<JourneySubwayTrainStationMatcher.Pattern> {
        if (!validAt(clock.instant())) return emptyList()
        val raw = JourneySubwaySavedLegEvidence.resolve(route,legIndex).evidence ?: return emptyList()
        val mapped = raw.stations.map { crosswalk[Key(raw.routingCity,raw.routingLine,it.routingStationId)] ?: return emptyList() }
        val line = mapped.map { it.line }.distinct().singleOrNull() ?: return emptyList()
        val calls = mapped.map { it.stationId }
        val result = patterns.filter { it.routingCity == raw.routingCity && it.routingLine == raw.routingLine &&
            it.routingWayCode == raw.routingWayCode && it.line == line }.mapNotNull { pattern ->
            if (calls.size > pattern.stationCalls.size) return@mapNotNull null
            // Full ordered subsequence, not endpoint/name/nearest-station matching. Do not skip
            // intermediate calls to make a regular service fit an express routing leg.
            val windows = (0..pattern.stationCalls.size-calls.size).filter { start ->
                pattern.stationCalls.subList(start,start+calls.size) == calls
            }
            // Dropping only this pattern would let another ordinary-looking pattern mask the
            // unresolved loop occurrence. No train-side selection may repair missing evidence.
            if (windows.size > 1) return emptyList()
            val start = windows.singleOrNull() ?: return@mapNotNull null
            JourneySubwayTrainStationMatcher.Pattern(pattern.line,pattern.direction,pattern.terminalStationId,
                pattern.service,pattern.stationCalls,start,start+calls.lastIndex)
        }
        return result.takeIf { it.size <= 32 } ?: emptyList()
    }
    private fun validId(value: String) = value.matches(Regex("[0-9]{1,20}")) && value.any { it != '0' }
}
