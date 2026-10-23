package com.noLate.journey.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.transit.infrastructure.SeoulMetroShortestPath
import com.noLate.transit.infrastructure.SeoulMetroShortestPathClient
import com.noLate.transit.infrastructure.SeoulMetroStationCoordinateCatalog
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** Narrow on-demand alternative to a reviewed static catalog. Only a complete saved segment
 * and terminal-bound official path can yield a train pattern. Fresh train rows are checked
 * against each official code/name at match time, never against a sparse arrivals feed. The
 * cache is route-fingerprint scoped and short-lived; no IDs are written to the database. */
internal class LiveOfficialSubwayServicePatternCatalog(
    private val path: (SeoulMetroShortestPathClient.Query) -> SeoulMetroShortestPath,
    private val coordinates: () -> SeoulMetroStationCoordinateCatalog,
    private val clock: Clock = Clock.systemUTC(),
) : SavedSubwayServicePatternCatalog {
    private data class Key(val routeFingerprint: String, val legIndex: Int)
    private data class Entry(val at: java.time.Instant, val pattern: JourneySubwayTrainStationMatcher.Pattern)
    private val cache = ConcurrentHashMap<Key, Entry>()
    private val mapper = jacksonObjectMapper()

    override fun resolve(route: JourneyRouteSnapshot, legIndex: Int): JourneySubwayTrainStationMatcher.Pattern? =
        resolveAll(route, legIndex).singleOrNull()

    override fun resolveAll(route: JourneyRouteSnapshot, legIndex: Int): List<JourneySubwayTrainStationMatcher.Pattern> {
        val raw = JourneySubwaySavedLegEvidence.resolve(route, legIndex).evidence ?: return emptyList()
        val key = Key(route.fingerprint, legIndex)
        val now = clock.instant()
        cache[key]?.takeIf { now >= it.at && now < it.at.plus(CACHE_TTL) }?.let { return listOf(it.pattern) }
        val leg = try { mapper.readTree(route.legsJson).path(legIndex) }
            catch (_: Exception) { return emptyList() }
        val start = leg.path("startName").takeIf { it.isTextual }?.asText() ?: return emptyList()
        val end = leg.path("endName").takeIf { it.isTextual }?.asText() ?: return emptyList()
        val line = SavedSubwayOfficialPathComparison.canonicalLine(raw.routingLine,
            leg.path("lineName").takeIf { it.isTextual }?.asText())
        if (start == end || line == null)
            return emptyList()
        // The path endpoint can return no paths overnight. Noon is used only to obtain
        // today's service topology; current train position/freshness is never time-shifted.
        val atSeoul = LocalDateTime.ofInstant(now, ZoneId.of("Asia/Seoul")).toLocalDate().atTime(12, 0)
        val segment = path(SeoulMetroShortestPathClient.Query(start, end, atSeoul))
        if (SavedSubwayOfficialPathComparison.compare(route, legIndex, segment) == null) return emptyList()
        val savedCalls = listOf(segment.sections.first().departure) + segment.sections.map { it.arrival }
        if (savedCalls.size != raw.stations.size) return emptyList()
        val stationCoordinates = coordinates()
        if (!savedCalls.zip(raw.stations).all { (official, saved) ->
                stationCoordinates.corroborates(official, saved.latitude, saved.longitude)
            }) return emptyList()
        val terminal = segment.sections.map { it.terminalCode }.distinct().singleOrNull() ?: return emptyList()
        val full = path(SeoulMetroShortestPathClient.Query(segment.sections.first().departure.code,
            terminal, atSeoul, SeoulMetroShortestPathClient.StationValueType.CODE))
        val pattern = OfficialSubwaySavedLegPattern.resolve(route, legIndex, segment, full)
            ?: return emptyList()
        if (cache.size >= 128) cache.clear()
        cache[key] = Entry(clock.instant(), pattern)
        return listOf(pattern)
    }

    companion object { private val CACHE_TTL = Duration.ofMinutes(2) }
}
