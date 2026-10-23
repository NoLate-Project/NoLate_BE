package com.noLate.transit.qa

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.transit.domain.SeoulBusRoutePathPoint
import com.noLate.transit.domain.SeoulBusRouteStop
import com.noLate.transit.domain.SeoulBusStopShapeMatcher
import java.nio.file.Files
import java.nio.file.Path

/** Deliberately no provider client, dotenv, application context, network or live vehicle. */
internal fun replayCatalogDiagnostic(bytes: ByteArray): Map<String, Any> {
    require(bytes.size in 1..2_000_000)
    val root = jacksonObjectMapper().readTree(bytes)
    fun JsonNode.fieldsExactly(vararg names: String) {
        require(isObject && fieldNames().asSequence().toSet() == names.toSet())
    }
    fun JsonNode.integer(name: String, range: IntRange): Int {
        val value = get(name)
        require(value.isIntegralNumber && value.canConvertToInt() && value.intValue() in range)
        return value.intValue()
    }
    fun JsonNode.flag(name: String): Boolean {
        require(get(name).isBoolean)
        return get(name).booleanValue()
    }
    fun JsonNode.coordinate(name: String): Double {
        require(get(name).isNumber && get(name).doubleValue().isFinite())
        return get(name).doubleValue()
    }
    root.fieldsExactly("schemaVersion", "diagnosticOnly", "navigationReady", "stops", "shape")
    require(root.integer("schemaVersion", 1..1) == 1 && root.flag("diagnosticOnly") && !root.flag("navigationReady"))
    require(root["stops"].isArray && root["stops"].size() in 2..999 &&
        root["shape"].isArray && root["shape"].size() in 2..20_000)
    val stops = root["stops"].map { row ->
        row.fieldsExactly("sequence", "stationOrdinal", "directionOrdinal", "hasArs", "invalidArs", "hasSection", "latitude", "longitude")
        SeoulBusRouteStop("100100447", row.integer("sequence", 1..999),
            (101000000 + row.integer("stationOrdinal", 0..998)).toString(),
            if (row.flag("hasArs")) "02007" else null, "DIAGNOSTIC",
            "DIRECTION_${row.integer("directionOrdinal", 0..998)}",
            if (row.flag("hasSection")) "200000001" else null,
            row.coordinate("latitude"), row.coordinate("longitude"), row.flag("invalidArs"))
    }
    val shape = root["shape"].map { row ->
        row.fieldsExactly("order", "latitude", "longitude")
        SeoulBusRoutePathPoint(row.integer("order", 0..100_000), row.coordinate("latitude"), row.coordinate("longitude"))
    }
    val result = SeoulBusStopShapeMatcher.match("100100447", stops, "100100447", shape, 44, 48)
    return mapOf("probe" to "OFFLINE_REDACTED_CATALOG", "networkAttempts" to 0,
        "diagnosticOnly" to true, "navigationReady" to false,
        "stopSectionCrosswalkVerified" to false, "passengerVehicleAssociation" to false,
        "stopCount" to stops.size, "shapePointCount" to shape.size,
        "targetShapeMatchStatus" to result.status.name,
        "targetShapeMatchedStopCount" to result.stops.size,
        "targetShapeStopSequences" to result.stops.map { it.stopSequence },
        "targetShapeAmbiguity" to (result.ambiguity?.name ?: "NONE"),
        "targetShapeDiagnosticStopSequence" to (result.diagnosticStopSequence ?: 0))
}

fun main() {
    val result = try {
        val path = Path.of(System.getenv("NOLATE_SEOUL_CATALOG_DIAGNOSTIC_FILE") ?: error("Missing diagnostic"))
            .toAbsolutePath().normalize()
        require(path.fileName.toString() == "catalog-diagnostic.json" &&
            path.parent.parent == catalogDiagnosticRoot() && path.parent.fileName.toString().startsWith("nolate-seoul-catalog-") &&
            path.toRealPath() == path && !Files.isSymbolicLink(path) && Files.isRegularFile(path) && Files.size(path) <= 2_000_000)
        replayCatalogDiagnostic(Files.readAllBytes(path))
    } catch (_: Exception) {
        mapOf("probe" to "OFFLINE_REDACTED_CATALOG", "networkAttempts" to 0,
            "navigationReady" to false, "status" to "DIAGNOSTIC_REJECTED")
    }
    println(jacksonObjectMapper().writeValueAsString(result))
    if (result["targetShapeMatchStatus"] != "MATCHED") kotlin.system.exitProcess(2)
}
