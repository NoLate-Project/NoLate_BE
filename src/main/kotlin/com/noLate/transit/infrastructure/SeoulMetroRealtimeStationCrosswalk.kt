package com.noLate.transit.infrastructure

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.transit.domain.SeoulSubwayObservationLine
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** An ephemeral corroboration of one official path against the Seoul ALL-arrivals feed.
 * It needs exact line/name uniqueness AND matching code suffix. It is not a published
 * namespace contract, so callers must not persist it or use it alone as boarding proof. */
internal class SeoulMetroRealtimeStationCrosswalk private constructor(
    private val stations: Map<Pair<String, String>, Set<String>>,
) {
    data class Candidate(val officialCode: String, val realtimeStationId: String, val line: String, val name: String) {
        val passengerBoardingConfirmed = false
        override fun toString() = "MetroRealtimeStationCandidate(REDACTED)"
    }

    fun correlate(path: SeoulMetroShortestPath): List<Candidate>? {
        val calls = listOf(path.sections.firstOrNull()?.departure ?: return null) + path.sections.map { it.arrival }
        if (calls.distinctBy { it.line to it.code }.size != calls.size) return null
        val result = calls.map { station ->
            val line = SeoulSubwayObservationLine.entries.singleOrNull { it.requestName == station.line } ?: return null
            val ids = stations[line.providerId to station.name] ?: return null
            val id = ids.singleOrNull() ?: return null
            // A corroborating check, not a guessed conversion: both names and lines have
            // already matched exact independent provider fields above.
            if (station.code.length != 4 || !id.endsWith(station.code)) return null
            Candidate(station.code, id, station.line, station.name)
        }
        if (result.map { it.realtimeStationId }.distinct().size != result.size) return null
        return result
    }

    companion object {
        private const val MAX_RESPONSE = 5_242_880
        private val id = Regex("[0-9]{1,20}")
        private val mapper = ObjectMapper().apply {
            factory.setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
                .maxStringLength(1024).maxNumberLength(20).build())
        }

        fun parse(bytes: ByteArray): SeoulMetroRealtimeStationCrosswalk {
            require(bytes.size in 1..MAX_RESPONSE)
            val root = try {
                val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                mapper.factory.createParser(text).use { parser ->
                    parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    mapper.readTree<JsonNode>(parser).also { require(parser.nextToken() == null) }
                }
            } catch (_: Exception) { throw IllegalArgumentException("Invalid Seoul arrivals batch") }
            require(root != null && root.isObject)
            val error = root.path("errorMessage")
            require(error.path("status").isIntegralNumber && error.path("status").intValue() == 200 &&
                error.path("code").asText() == "INFO-000")
            val rows = root.path("realtimeArrivalList")
            require(rows.isArray && rows.size() in 1..5_000)
            val entries = rows.mapNotNull { row ->
                if (!row.isObject) return@mapNotNull null
                val line = row.path("subwayId").asText("")
                val stationId = row.path("statnId").asText("")
                val name = row.path("statnNm").asText("")
                if (line !in SeoulSubwayObservationLine.entries.map { it.providerId } ||
                    !id.matches(stationId) || stationId.all { it == '0' } ||
                    name.length !in 1..80 || name != name.trim() || name.any(Char::isISOControl)) null
                else (line to name) to stationId
            }
            return SeoulMetroRealtimeStationCrosswalk(entries.groupBy({ it.first }, { it.second })
                .mapValues { (_, values) -> values.toSet() })
        }
    }
}
