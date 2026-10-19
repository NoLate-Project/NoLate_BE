package com.noLate.transit.infrastructure

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The public-data-portal path API has its own credential and station-code namespace.
 * It is a Spring bean only behind the explicit official-live observation flag, never an
 * ODsay-to-Seoul crosswalk by itself. A parsed path is
 * candidate route data, never passenger position or authorization to start navigation. */
internal class SeoulMetroShortestPathClient(
    private val serviceKey: String,
    private val origin: URI = URI.create("https://apis.data.go.kr/B553766/path2"),
    private val transport: SeoulSubwayTrainHttpTransport = SeoulSubwayTrainHttpTransport(),
) : AutoCloseable {
    // data.go.kr exposes both encoded and decoded key variants. Decode percent escapes
    // once, preserving a literal '+', then encode once for this query string.
    private val normalizedServiceKey = if ('%' in serviceKey)
        runCatching { URLDecoder.decode(serviceKey.replace("+", "%2B"), StandardCharsets.UTF_8) }
            .getOrDefault(serviceKey) else serviceKey
    init {
        require(normalizedServiceKey.isNotBlank() && normalizedServiceKey == normalizedServiceKey.trim() &&
            normalizedServiceKey.length <= 256 && normalizedServiceKey.none(Char::isISOControl))
        require(origin.rawQuery == null && origin.rawFragment == null && origin.userInfo == null &&
            (origin.scheme == "https" && origin.host == "apis.data.go.kr" && origin.port == -1 &&
                origin.path == "/B553766/path2" || origin.scheme == "http" && origin.host == "127.0.0.1" &&
                origin.port in 1..65535 && origin.path.isNullOrEmpty()))
    }

    enum class StationValueType(val wire: String) { NAME("name"), CODE("code") }
    data class Query(val departure: String, val arrival: String, val atSeoul: LocalDateTime,
        val stationValueType: StationValueType = StationValueType.NAME) {
        init {
            require(departure.length in 1..80 && arrival.length in 1..80 && departure != arrival &&
                listOf(departure,arrival).all { it == it.trim() && it.none(Char::isISOControl) })
            if (stationValueType == StationValueType.CODE)
                require(listOf(departure,arrival).all {
                    it.matches(Regex("[0-9]{1,20}")) && it.any { digit -> digit != '0' }
                })
        }
    }

    fun fetch(query: Query): SeoulMetroShortestPath {
        fun encoded(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
        val parameters = listOf(
            "serviceKey" to normalizedServiceKey, "dataType" to "JSON", "dptreStn" to query.departure,
            "arvlStn" to query.arrival, "searchDt" to query.atSeoul.format(DATE_TIME),
            "searchType" to "duration", "schInclYn" to "N",
            "stationValueType" to query.stationValueType.wire,
        ).joinToString("&") { (key,value) -> "$key=${encoded(value)}" }
        val uri = URI.create("$origin/getShtrmPath2?$parameters")
        return try { SeoulMetroShortestPathParser.parse(transport.fetch(uri)) }
        catch (_: Exception) { throw IllegalStateException("Metro shortest path unavailable") }
    }

    override fun close() = transport.close()
    companion object { private val DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss") }
}

internal class SeoulMetroShortestPath(val sections: List<Section>, val reportedTransferCount: Int?) {
    data class Station(val code: String, val line: String, val name: String)
    data class Section(val departure: Station, val arrival: Station, val terminalCode: String?,
        val direction: String?, val transfer: String?, val express: String?)
    // A caller must independently verify this code namespace against realtimePosition.statnId.
    val trainStationCodeCompatibilityVerified = false
    val passengerBoardingConfirmed = false
    override fun toString() = "SeoulMetroShortestPath(sections=${sections.size}, unverified)"
}

internal object SeoulMetroShortestPathParser {
    private val mapper = ObjectMapper().apply {
        factory.setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
            .maxStringLength(1024).maxNumberLength(20).build())
    }
    // Official station codes are zero-padded (for example 0222), unlike the realtime
    // train API's statnId. Preserve their exact spelling; never coerce to a number.
    private val code = Regex("[0-9]{1,20}")

    fun parse(bytes: ByteArray): SeoulMetroShortestPath {
        require(bytes.size in 1..SeoulSubwayTrainObservationParser.MAX_RESPONSE_BYTES)
        val root = try {
            val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            mapper.factory.createParser(text).use { parser ->
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                val value = mapper.readTree<JsonNode>(parser)
                require(parser.nextToken() == null)
                value
            }
        } catch (_: Exception) { throw IllegalArgumentException("Invalid metro shortest path") }
        require(root != null && root.isObject)
        val resultCode = root.path("header").path("resultCode")
        // Confirmed against a credentialed 2026-10-13 response. HTTP 200 alone is not success.
        require(resultCode.isTextual && resultCode.asText() == "00")
        val body = root.path("body")
        val rows = body.path("paths")
        require(rows.isArray && rows.size() in 1..500)
        val sections = rows.map { row ->
            require(row.isObject)
            SeoulMetroShortestPath.Section(
                station(row.path("dptreStn")), station(row.path("arvlStn")),
                optionalCode(row.path("tmnlStnCd")), optionalText(row.path("upbdnbSe")),
                optionalText(row.path("trsitYn")), optionalText(row.path("etrnYn")),
            )
        }
        require(sections.all { section ->
            section.departure.code != section.arrival.code && section.transfer in setOf("Y", "N") &&
                section.express in setOf("Y", "N") &&
                if (section.transfer == "Y") section.departure.line != section.arrival.line
                else section.departure.line == section.arrival.line
        })
        require(sections.zipWithNext().all { (before, after) -> before.arrival == after.departure })
        val transfers = body.path("trsitNmtm").takeIf { it.isIntegralNumber && it.intValue() in 0..50 }?.intValue()
        require(transfers == null || transfers == sections.count { it.transfer == "Y" })
        return SeoulMetroShortestPath(sections, transfers)
    }

    private fun station(row: JsonNode): SeoulMetroShortestPath.Station {
        require(row.isObject)
        val id = row.path("stnCd").asText("")
        require(code.matches(id) && id.any { it != '0' })
        return SeoulMetroShortestPath.Station(id, requiredText(row.path("lineNm")), requiredText(row.path("stnNm")))
    }
    private fun requiredText(node: JsonNode): String = optionalText(node) ?: throw IllegalArgumentException("Invalid metro shortest path")
    private fun optionalText(node: JsonNode): String? = node.takeIf { it.isTextual }?.asText()
        ?.takeIf { it.length in 1..80 && it == it.trim() && it.none(Char::isISOControl) }
    private fun optionalCode(node: JsonNode): String? = node.takeIf { it.isTextual || it.isIntegralNumber }
        ?.asText()?.takeIf { code.matches(it) && it.any { digit -> digit != '0' } }
}
