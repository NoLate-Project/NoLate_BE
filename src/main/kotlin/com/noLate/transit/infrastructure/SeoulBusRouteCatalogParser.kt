package com.noLate.transit.infrastructure

import com.noLate.transit.domain.SeoulBusRoutePathPoint
import com.noLate.transit.domain.SeoulBusRouteStop
import org.w3c.dom.Element
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Only fixed schema names and the response row ordinal, never provider values/URI/credentials. */
internal enum class SeoulBusCatalogField(val wireName: String) {
    ROUTE("busRouteId"), SEQUENCE("seq"), STATION("station"), SECTION("section"),
    ARS("arsId"), STATION_NO("stationNo"), NAME("stationNm"), DIRECTION("direction"),
    LONGITUDE("gpsX"), LATITUDE("gpsY"), SHAPE_ORDER("no"), UNKNOWN("UNKNOWN");

    companion object {
        fun schemaName(name: String) = entries.firstOrNull { it.wireName == name } ?: UNKNOWN
    }
}
internal enum class SeoulBusCatalogFieldIssue { ABSENT, EMPTY, DUPLICATE, NESTED, INVALID_NUMBER }
internal enum class SeoulBusCatalogNumberShape { NUMERIC_TOO_SHORT, NUMERIC_TOO_LONG, NON_NUMERIC }
internal class SeoulBusCatalogFieldException(
    val field: SeoulBusCatalogField, val rowOrdinal: Int, val issue: SeoulBusCatalogFieldIssue,
    val numberShape: SeoulBusCatalogNumberShape? = null,
) : IllegalArgumentException(if (issue == SeoulBusCatalogFieldIssue.EMPTY) "Missing Seoul catalog field" else "Invalid Seoul catalog field") {
    init { require(rowOrdinal in 1..20_000) { "Invalid catalog diagnostic ordinal" } }
}

/** Strict official getStaionByRoute/getRoutePath contract. No coordinate-system guessing. */
internal object SeoulBusRouteCatalogParser {
    const val MAX_RESPONSE_BYTES = 2_097_152
    private val id = Regex("[1-9][0-9]{8}")

    fun stops(bytes: ByteArray, routeId: String, retainInvalidPublicNumbers: Boolean = false): List<SeoulBusRouteStop> {
        require(id.matches(routeId)) { "Invalid Seoul route identifier" }
        val result = rows(bytes, 999).map { row ->
            require(row.field("busRouteId") == routeId) { "Seoul route mismatch" }
            val sequence = row.field("seq").toIntOrNull()?.takeIf { it in 1..999 }
                ?: throw IllegalArgumentException("Invalid Seoul stop sequence")
            val station = row.field("station").also { require(id.matches(it)) { "Invalid Seoul station identifier" } }
            val section = row.field("section").let {
                if (it == "0") null else it.also { value ->
                    require(value.matches(Regex("[1-9][0-9]{8,9}"))) { "Invalid Seoul section identifier" }
                }
            }
            var invalidNumber = false
            fun number(name: String): String? = try { row.publicStopNumber(name) }
                catch (failure: SeoulBusCatalogFieldException) {
                    if (!retainInvalidPublicNumbers || failure.issue != SeoulBusCatalogFieldIssue.INVALID_NUMBER) throw failure
                    invalidNumber = true
                    null // Explicit diagnostic-only invalidity, not an inferred station number.
                }
            val ars = number("arsId")
            val stationNo = number("stationNo")
            require(ars == null || stationNo == null || stationNo == ars) { "Conflicting Seoul stop numbers" }
            SeoulBusRouteStop(routeId, sequence, station, if (invalidNumber) null else ars ?: stationNo,
                row.label("stationNm"), row.label("direction"), section,
                row.coordinate("gpsY", 33.0..39.5), row.coordinate("gpsX", 124.0..132.0), invalidNumber)
        }
        require(result.zipWithNext().all { (a, b) -> b.sequence == a.sequence + 1 }) { "Ambiguous Seoul stop sequence" }
        return result
    }

    fun path(bytes: ByteArray): List<SeoulBusRoutePathPoint> {
        val result = rows(bytes, 20_000).map { row ->
            val order = row.field("no").toIntOrNull()?.takeIf { it in 0..100_000 }
                ?: throw IllegalArgumentException("Invalid Seoul shape order")
            SeoulBusRoutePathPoint(order, row.coordinate("gpsY", 33.0..39.5), row.coordinate("gpsX", 124.0..132.0))
        }
        require(result.isEmpty() || result.size >= 2) { "Incomplete Seoul route shape" }
        require(result.zipWithNext().all { (a, b) -> b.order == a.order + 1 }) { "Ambiguous Seoul shape sequence" }
        return result
    }

    private fun rows(bytes: ByteArray, limit: Int): List<CatalogRow> {
        require(bytes.size <= MAX_RESPONSE_BYTES) { "Seoul catalog response too large" }
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
            setErrorHandler(object : DefaultHandler() {
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            })
        }
        val doc = try { builder.parse(ByteArrayInputStream(bytes)) }
        catch (_: SAXException) { throw IllegalArgumentException("Invalid Seoul catalog XML") }
        val root = doc.documentElement
        val headers = root.getElementsByTagName("msgHeader")
        val bodies = root.getElementsByTagName("msgBody")
        require(root.tagName == "ServiceResult" && headers.length == 1 && bodies.length == 1 &&
            headers.item(0).parentNode == root && bodies.item(0).parentNode == root) { "Invalid Seoul catalog envelope" }
        val codes = root.getElementsByTagName("headerCd")
        val code = if (codes.length == 1 && codes.item(0).parentNode == headers.item(0)) codes.item(0).textContent.trim() else null
        if (code != "0") throw TransitProviderApplicationException(TransitWireProvider.SEOUL_BUS, code)
        val rows = root.getElementsByTagName("itemList")
        require(rows.length <= limit) { "Too many Seoul catalog rows" }
        return (0 until rows.length).map { index ->
            val row = rows.item(index)
            require(row.parentNode == bodies.item(0) && row is Element) { "Invalid Seoul catalog row envelope" }
            CatalogRow(row as Element, index + 1)
        }
    }

    private class CatalogRow(val element: Element, val ordinal: Int) {
        fun field(name: String, optional: Boolean = false): String {
            fun reject(issue: SeoulBusCatalogFieldIssue): Nothing =
                throw SeoulBusCatalogFieldException(SeoulBusCatalogField.schemaName(name), ordinal, issue)
            val nodes = element.getElementsByTagName(name)
            if (nodes.length == 0) {
                if (optional) return ""
                reject(SeoulBusCatalogFieldIssue.ABSENT)
            }
            if (nodes.length != 1) reject(SeoulBusCatalogFieldIssue.DUPLICATE)
            val node = nodes.item(0)
            if (node.parentNode != element || (0 until node.childNodes.length).any { node.childNodes.item(it) is Element }) {
                reject(SeoulBusCatalogFieldIssue.NESTED)
            }
            // Official optional arsId may be represented by an absent element or an empty tag.
            // Both use the stationNo fallback; duplicate/nested tags remain rejected above.
            return node.textContent.trim().also { if (it.isEmpty() && !optional) reject(SeoulBusCatalogFieldIssue.EMPTY) }
        }
        fun label(name: String) = field(name).also {
            require(it.length <= 128 && it.none(Char::isISOControl)) { "Invalid Seoul catalog label" }
        }
        fun publicStopNumber(name: String): String? {
            val value = field(name, optional = true)
            if (value.isEmpty() || value == "0" || value == "00000") return null
            if (!value.matches(Regex("[0-9]{5}"))) {
                val shape = if (value.any { it !in '0'..'9' }) SeoulBusCatalogNumberShape.NON_NUMERIC
                    else if (value.length < 5) SeoulBusCatalogNumberShape.NUMERIC_TOO_SHORT else SeoulBusCatalogNumberShape.NUMERIC_TOO_LONG
                throw SeoulBusCatalogFieldException(SeoulBusCatalogField.schemaName(name), ordinal,
                    SeoulBusCatalogFieldIssue.INVALID_NUMBER, shape)
            }
            return value
        }
        fun coordinate(name: String, bounds: ClosedFloatingPointRange<Double>) =
            field(name).toDoubleOrNull()?.takeIf { it.isFinite() && it in bounds }
                ?: throw IllegalArgumentException("Invalid Seoul WGS84 coordinate")
    }
}
