package com.noLate.transit.infrastructure

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.transit.domain.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** OA-12601 contract boundary. Names are retained for later exact corroboration;
 * this parser performs no route matching, time extrapolation, station-order inference or GPS. */
internal object SeoulSubwayTrainObservationParser {
    const val MAX_RESPONSE_BYTES = 1_048_576
    private val mapper = ObjectMapper().apply {
        factory.setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
            .maxStringLength(1024).maxNumberLength(20).build())
    }
    private val decimalId = Regex("[0-9]{1,20}")
    private val trainId = Regex("[0-9]{1,8}")
    private val seoul = ZoneId.of("Asia/Seoul")
    private val date = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    private val compactDate = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)
    private val dateTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

    fun parse(bytes: ByteArray, query: SeoulSubwayTrainQuery, receivedAt: Instant): SeoulSubwayTrainObservationBatch {
        require(bytes.size in 1..MAX_RESPONSE_BYTES) { "Invalid subway observation size" }
        val root = try {
            val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            mapper.factory.createParser(text).use { parser ->
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                val node = mapper.readTree<JsonNode>(parser)
                require(parser.nextToken() == null)
                node
            }
        } catch (_: Exception) { throw IllegalArgumentException("Invalid subway observation JSON") }
        require(root != null && root.isObject) { "Invalid subway observation envelope" }
        val error = root.path("errorMessage")
        require(error.isObject) { "Missing subway observation status" }
        val code = error.field("code")
        if (code !in setOf("INFO-000", "INFO-200")) throw TransitProviderApplicationException(
            TransitWireProvider.SEOUL_SUBWAY,
            code?.takeIf { it in setOf("INFO-100", "ERROR-300", "ERROR-301", "ERROR-310", "ERROR-331",
                "ERROR-332", "ERROR-333", "ERROR-334", "ERROR-335", "ERROR-336", "ERROR-500", "ERROR-600", "ERROR-601") }
                ?: "UNSUPPORTED_CODE",
        )
        val rows = root.path("realtimePositionList")
        if (code == "INFO-200") {
            require((rows.isMissingNode || rows.isArray && rows.isEmpty) &&
                listOf(root.get("list_total_count"), error.get("total")).all { it == null || it.count() == 0 }) {
                "Contradictory subway no-data envelope"
            }
            return SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.NO_TRAINS)
        }
        require(rows.isArray && rows.size() <= query.limit) { "Invalid subway observation rows" }
        val counts = listOfNotNull(root.get("list_total_count"), error.get("total")).map {
            it.count() ?: throw IllegalArgumentException("Invalid subway observation total")
        }
        require(counts.isNotEmpty() && counts.distinct().size == 1 && counts[0] >= rows.size()) { "Invalid subway observation total" }
        if (counts[0] != rows.size()) return SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.INCOMPLETE_PAGE)
        val duplicates = rows.mapNotNull { it.field("trainNo") }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val rejected = mutableMapOf<SeoulSubwayTrainRejection, Int>()
        fun reject(reason: SeoulSubwayTrainRejection): SeoulSubwayTrainObservation? {
            rejected[reason] = (rejected[reason] ?: 0) + 1
            return null
        }
        val observations = rows.mapNotNull { row ->
            val number = row.field("trainNo")
            if (number in duplicates) reject(SeoulSubwayTrainRejection.DUPLICATE_TRAIN)
            else parseRow(row, query, receivedAt, ::reject)
        }
        return SeoulSubwayTrainObservationBatch(when {
            rows.isEmpty -> SeoulSubwayTrainStatus.NO_TRAINS
            observations.isEmpty() -> SeoulSubwayTrainStatus.NO_USABLE_OBSERVATIONS
            else -> SeoulSubwayTrainStatus.AVAILABLE
        }, observations, rejected.toMap())
    }

    private fun parseRow(row: JsonNode, query: SeoulSubwayTrainQuery, receivedAt: Instant,
        reject: (SeoulSubwayTrainRejection) -> SeoulSubwayTrainObservation?): SeoulSubwayTrainObservation? {
        if (row.field("subwayId") != query.line.providerId || row.field("subwayNm") != query.line.requestName)
            return reject(SeoulSubwayTrainRejection.LINE_MISMATCH)
        val number = row.field("trainNo")?.takeIf { trainId.matches(it) && it.any { c -> c != '0' } }
            ?: return reject(SeoulSubwayTrainRejection.TRAIN_ID_INVALID)
        val station = row.field("statnId")?.takeIf { decimalId.matches(it) && it.any { c -> c != '0' } }
        val destination = row.field("statnTid")?.takeIf { decimalId.matches(it) && it.any { c -> c != '0' } }
        if (station == null || destination == null) return reject(SeoulSubwayTrainRejection.STATION_ID_INVALID)
        val direction = row.field("updnLine")?.takeIf { it in setOf("0", "1") }?.toInt()
            ?: return reject(SeoulSubwayTrainRejection.DIRECTION_INVALID)
        val state = row.field("trainSttus")?.takeIf { it in setOf("0", "1", "2", "3") }?.toInt()
            ?: return reject(SeoulSubwayTrainRejection.STATE_INVALID)
        val service = row.field("directAt")?.takeIf { it in setOf("0", "1", "7") }?.toInt()
            ?: return reject(SeoulSubwayTrainRejection.SERVICE_INVALID)
        val last = row.field("lstcarAt")?.takeIf { it in setOf("0", "1") }
            ?: return reject(SeoulSubwayTrainRejection.LAST_TRAIN_INVALID)
        val dayRaw = row.field("lastRecptnDt") ?: return reject(SeoulSubwayTrainRejection.TIMESTAMP_UNSUPPORTED)
        val clockRaw = row.field("recptnDt") ?: return reject(SeoulSubwayTrainRejection.TIMESTAMP_UNSUPPORTED)
        // Explicit strict forms only. Their real-provider availability still requires an approved
        // response contract check. Unsupported forms never use receipt time or today's date.
        val dayFormat = when {
            Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(dayRaw) -> date
            Regex("[0-9]{8}").matches(dayRaw) -> compactDate
            else -> return reject(SeoulSubwayTrainRejection.TIMESTAMP_UNSUPPORTED)
        }
        val day = runCatching { LocalDate.parse(dayRaw, dayFormat) }.getOrNull()
            ?: return reject(SeoulSubwayTrainRejection.TIMESTAMP_INVALID)
        val local = when {
            Regex("[0-9]{2}:[0-9]{2}:[0-9]{2}").matches(clockRaw) -> runCatching { day.atTime(LocalTime.parse(clockRaw, time)) }.getOrNull()
            Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}").matches(clockRaw) -> runCatching { LocalDateTime.parse(clockRaw, dateTime) }.getOrNull()
            else -> return reject(SeoulSubwayTrainRejection.TIMESTAMP_UNSUPPORTED)
        } ?: return reject(SeoulSubwayTrainRejection.TIMESTAMP_INVALID)
        if (local.toLocalDate() != day) return reject(SeoulSubwayTrainRejection.TIMESTAMP_DATE_MISMATCH)
        val source = local.atZone(seoul).toInstant()
        if (source > receivedAt) return reject(SeoulSubwayTrainRejection.TIMESTAMP_FUTURE)
        val until = source.plusSeconds(30) // Application policy, not a claimed provider SLA.
        if (receivedAt >= until) return reject(SeoulSubwayTrainRejection.TIMESTAMP_STALE)
        return SeoulSubwayTrainObservation(number, query.line, station, destination, direction, state, service,
            last == "1", source, receivedAt, until,
            row.field("statnNm")?.takeIf(::validStationName),
            row.field("statnTnm")?.takeIf(::validStationName))
    }

    private fun validStationName(value: String) = value.length in 1..80 && value == value.trim() &&
        value.none(Char::isISOControl)

    private fun JsonNode.field(name: String): String? = get(name)?.takeIf { it.isTextual || it.isIntegralNumber }
        ?.asText()?.takeIf { it.length in 1..128 && it.none(Char::isISOControl) }
    private fun JsonNode.count(): Int? = takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()?.takeIf { it in 0..100_000 }
}
