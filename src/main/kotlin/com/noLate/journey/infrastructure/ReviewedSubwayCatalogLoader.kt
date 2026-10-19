package com.noLate.journey.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.journey.application.IndependentSavedSubwayServiceCatalog
import com.noLate.transit.domain.SeoulSubwayObservationLine
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/** Server-owned reviewed data, never an HTTP upload or a routing-response-derived catalog.
 * Source hashes document the review inputs; this loader does not certify their authenticity. */
internal object ReviewedSubwayCatalogLoader {
    fun load(file: String, mapper: ObjectMapper, clock: Clock = Clock.systemUTC()): IndependentSavedSubwayServiceCatalog {
        try {
            val path=Path.of(file)
            require(path.isAbsolute && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
            val bytes=Files.newInputStream(path).use { it.readNBytes(1_048_577) }
            require(bytes.size in 1..1_048_576)
            return decode(mapper.readTree(bytes),clock)
        } catch (_: Exception) { throw IllegalArgumentException("SUBWAY_CATALOG_INVALID") }
    }
    internal fun decode(root: JsonNode, clock: Clock): IndependentSavedSubwayServiceCatalog {
        require(root.isObject && root.path("schemaVersion").isInt && root.path("schemaVersion").intValue()==1)
        val from=Instant.parse(root.text("validFrom"));val until=Instant.parse(root.text("validUntil"))
        val reviewed=Instant.parse(root.text("reviewedAt"))
        require(reviewed<=clock.instant() && reviewed<=from && from<=clock.instant() && clock.instant()<until)
        val sources=root.path("sources")
        require(sources.isArray && sources.size() in 3..16)
        val kinds=sources.map { row ->
            val uri=java.net.URI(row.text("url"))
            require(uri.scheme=="https" && uri.host in setOf("lab.odsay.com","api.odsay.com","data.seoul.go.kr","www.data.go.kr","www.seoulmetro.co.kr")
                && uri.userInfo==null && uri.query==null && uri.fragment==null && uri.port == -1)
            require(row.text("sha256").matches(Regex("[a-f0-9]{64}")))
            row.text("kind")
        }.toSet()
        require(kinds.containsAll(setOf("ODSAY_STATION_TOPOLOGY","SEOUL_STATION_MASTER","SERVICE_PATTERN")))
        val stations=root.path("stations");val services=root.path("services")
        require(stations.isArray && stations.size() in 2..10_000 && services.isArray && services.size() in 1..256)
        return IndependentSavedSubwayServiceCatalog(stations.map { row ->
            IndependentSavedSubwayServiceCatalog.Station(row.text("routingCity"),row.text("routingLine"),row.text("routingStationId"),
                SeoulSubwayObservationLine.valueOf(row.text("line")),row.text("stationId"))
        },services.map { row ->
            val calls=row.path("stationCalls");require(calls.isArray && calls.size() in 2..500 && calls.all { it.isTextual })
            IndependentSavedSubwayServiceCatalog.Service(row.text("routingCity"),row.text("routingLine"),row.text("routingWayCode"),
                SeoulSubwayObservationLine.valueOf(row.text("line")),row.integer("direction"),row.text("terminalStationId"),row.integer("service"),calls.map { it.textValue() })
        },from,until,clock)
    }
    private fun JsonNode.text(key:String):String {
        val n=path(key);require(n.isTextual && n.textValue().length in 1..256);return n.textValue()
    }
    private fun JsonNode.integer(key:String):Int {
        val n=path(key);require(n.isIntegralNumber && n.canConvertToInt());return n.intValue()
    }
}
