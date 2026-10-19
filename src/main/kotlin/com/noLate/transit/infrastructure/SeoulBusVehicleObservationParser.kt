package com.noLate.transit.infrastructure

import com.noLate.transit.domain.SeoulBusVehicleObservation
import com.noLate.transit.domain.SeoulBusVehicleObservationBatch
import com.noLate.transit.domain.SeoulBusVehicleObservationStatus
import com.noLate.transit.domain.SeoulBusVehicleQuery
import com.noLate.transit.domain.SeoulBusVehicleRejection
import com.noLate.transit.domain.SeoulBusVehicleSourceAgeSummary
import com.noLate.transit.domain.SeoulBusVehicleCoordinate
import org.w3c.dom.Element
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Carries no URI, credentials, provider body, or original network exception. */
internal class SeoulBusVehicleTransportException : IllegalStateException("Seoul vehicle transport unavailable")

/** Strict, bounded XML contract for the official buspos/getBusPosByRouteSt operation. */
internal object SeoulBusVehicleObservationParser {
    const val MAX_RESPONSE_BYTES = 1_048_576
    private const val MAX_VEHICLES = 500
    private val id = Regex("[1-9][0-9]{8}")
    private val sourceClock = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
        .withResolverStyle(ResolverStyle.STRICT)
    private val seoul = ZoneId.of("Asia/Seoul")
    private val ttl = Duration.ofSeconds(15)

    fun parse(xml: ByteArray, query: SeoulBusVehicleQuery, receivedAt: Instant): SeoulBusVehicleObservationBatch {
        require(xml.size <= MAX_RESPONSE_BYTES) { "Seoul vehicle response too large" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            // Default parser handlers can print untrusted payload fragments to stderr.
            setErrorHandler(object : DefaultHandler() {
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            })
        }
        val document = try { builder.parse(ByteArrayInputStream(xml)) }
        catch (_: SAXException) { throw IllegalArgumentException("Invalid Seoul vehicle XML") }
        val root = document.documentElement
        val messageHeaders = root.getElementsByTagName("msgHeader")
        val bodies = root.getElementsByTagName("msgBody")
        require(root.tagName == "ServiceResult" && messageHeaders.length == 1 && bodies.length == 1 &&
            messageHeaders.item(0).parentNode == root && bodies.item(0).parentNode == root) {
            "Invalid Seoul vehicle envelope"
        }
        // Unlike legacy arrival parsing, missing/duplicated status is never a successful response.
        val headers = document.getElementsByTagName("headerCd")
        val code = if (headers.length == 1 && headers.item(0).parentNode == messageHeaders.item(0))
            headers.item(0).textContent.trim() else null
        if (code != "0") throw TransitProviderApplicationException(TransitWireProvider.SEOUL_BUS, code)
        val items = document.getElementsByTagName("itemList")
        require(items.length <= MAX_VEHICLES) { "Too many Seoul vehicle observations" }
        require((0 until items.length).all { items.item(it).parentNode == bodies.item(0) }) {
            "Invalid Seoul vehicle item envelope"
        }
        // Conflicting/duplicate observations for a vehicle must not pick an arbitrary latest row.
        val duplicates = (0 until items.length).mapNotNull { index ->
            (items.item(index) as? Element)?.getElementsByTagName("vehId")?.item(0)?.textContent?.trim()
        }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val rejected = mutableMapOf<SeoulBusVehicleRejection, Int>()
        val ages = mutableListOf<Long>()
        fun reject(reason: SeoulBusVehicleRejection): SeoulBusVehicleObservation? {
            rejected[reason] = (rejected[reason] ?: 0) + 1
            return null
        }
        val accepted = (0 until items.length).mapNotNull { index ->
            val item = items.item(index) as Element
            val vehicle = item.getElementsByTagName("vehId").item(0)?.textContent?.trim()
            if (vehicle in duplicates) reject(SeoulBusVehicleRejection.DUPLICATE_VEHICLE)
            else item.parseObservation(query, receivedAt, ::reject) { ages.add(it) }
        }
        return SeoulBusVehicleObservationBatch(
            status = when {
                items.length == 0 -> SeoulBusVehicleObservationStatus.NO_VEHICLES
                accepted.isEmpty() -> SeoulBusVehicleObservationStatus.NO_USABLE_OBSERVATIONS
                else -> SeoulBusVehicleObservationStatus.AVAILABLE
            },
            observations = accepted,
            rejectedCount = items.length - accepted.size,
            rejectionCounts = rejected.toMap(),
            sourceAgeSummary = if (ages.isEmpty()) null else SeoulBusVehicleSourceAgeSummary(
                ages.size, ages.min(), ages.max(),
            ),
        )
    }

    private fun Element.parseObservation(
        query: SeoulBusVehicleQuery,
        receivedAt: Instant,
        reject: (SeoulBusVehicleRejection) -> SeoulBusVehicleObservation?,
        recordAge: (Long) -> Unit,
    ): SeoulBusVehicleObservation? {
        fun field(name: String): String? {
            val nodes = getElementsByTagName(name)
            if (nodes.length != 1 || nodes.item(0).parentNode != this) return null
            val node = nodes.item(0)
            if ((0 until node.childNodes.length).any { node.childNodes.item(it) is Element }) return null
            return node.textContent.trim().takeIf { it.isNotEmpty() }
        }
        val vehicle = field("vehId")?.takeIf(id::matches) ?: return reject(SeoulBusVehicleRejection.VEHICLE_ID_INVALID)
        val route = field("routeId")
        // routeId is optional in the published contract; request binding is used only when absent.
        if (getElementsByTagName("routeId").length > 0 && route != query.routeId) return reject(SeoulBusVehicleRejection.ROUTE_MISMATCH)
        val section = field("sectionId")?.takeIf(id::matches) ?: return reject(SeoulBusVehicleRejection.SECTION_INVALID)
        val sectionOrder = field("sectOrd")?.toIntOrNull()?.takeIf { it in 1..999 }
            ?: return reject(SeoulBusVehicleRejection.SECTION_INVALID)
        val offset = field("sectDist")?.toIntOrNull()?.takeIf { it in 0..99_999 }
            ?: return reject(SeoulBusVehicleRejection.SECTION_INVALID)
        val stopped = when (field("stopFlag")) {
            "0" -> false; "1" -> true; else -> return reject(SeoulBusVehicleRejection.STOP_INVALID)
        }
        val lastStation = field("lastStnId")?.takeIf(id::matches) ?: return reject(SeoulBusVehicleRejection.STOP_INVALID)
        val coordinate = if (getElementsByTagName("tmX").length == 0 && getElementsByTagName("tmY").length == 0) null else {
            // Official api.bus.go.kr getBusPosByRouteSt: tmX/tmY=WGS84, posX/posY=GRS80.
            // No axis swap, projected-coordinate fallback, or guessed transformation.
            val longitude = field("tmX")?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 124.0..132.0 }
                ?: return reject(SeoulBusVehicleRejection.COORDINATE_INVALID)
            val latitude = field("tmY")?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 33.0..39.5 }
                ?: return reject(SeoulBusVehicleRejection.COORDINATE_INVALID)
            SeoulBusVehicleCoordinate(latitude, longitude)
        }
        val rawTime = field("dataTm")?.takeIf { it.matches(Regex("[0-9]{14}")) }
            ?: return reject(SeoulBusVehicleRejection.TIMESTAMP_UNSUPPORTED)
        val source = runCatching { LocalDateTime.parse(rawTime, sourceClock).atZone(seoul).toInstant() }
            .getOrNull() ?: return reject(SeoulBusVehicleRejection.TIMESTAMP_INVALID)
        // Diagnostics include stale/future source clocks without admitting them as observations.
        recordAge(Duration.between(source, receivedAt).toMillis())
        // Receipt time cannot make old data fresh. Future source clocks are not trusted either.
        if (source > receivedAt) return reject(SeoulBusVehicleRejection.TIMESTAMP_FUTURE)
        if (!source.plus(ttl).isAfter(receivedAt)) return reject(SeoulBusVehicleRejection.TIMESTAMP_STALE)
        return SeoulBusVehicleObservation(
            vehicle, query.routeId, section, sectionOrder, offset, stopped, lastStation,
            source, receivedAt, source.plus(ttl), coordinate,
        )
    }
}
