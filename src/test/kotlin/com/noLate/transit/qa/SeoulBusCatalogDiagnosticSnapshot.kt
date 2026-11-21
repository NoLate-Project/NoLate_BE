package com.noLate.transit.qa

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.transit.domain.SeoulBusRoutePathPoint
import com.noLate.transit.domain.SeoulBusRouteStop
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** Public route geometry only, never a human trip, raw response, credentials, URL or live vehicle.
 * Names and provider identifiers are replaced. This diagnostic cannot be used for navigation.
 */
internal fun catalogDiagnostic(stops: List<SeoulBusRouteStop>, shape: List<SeoulBusRoutePathPoint>): Map<String, Any> {
    require(stops.size in 2..999 && shape.size in 2..20_000)
    val directions = stops.map { it.direction }.distinct()
    val stations = stops.map { it.stationId }.distinct()
    return mapOf("schemaVersion" to 1, "diagnosticOnly" to true, "navigationReady" to false,
        "stops" to stops.map {
            mapOf("sequence" to it.sequence, "stationOrdinal" to stations.indexOf(it.stationId),
                "directionOrdinal" to directions.indexOf(it.direction), "hasArs" to (it.arsId != null),
                "invalidArs" to it.publicStopNumberInvalid, "hasSection" to (it.sectionId != null),
                "latitude" to it.latitude, "longitude" to it.longitude)
        }, "shape" to shape.map { mapOf("order" to it.order, "latitude" to it.latitude, "longitude" to it.longitude) })
}

internal fun writeCatalogDiagnostic(target: Path, stops: List<SeoulBusRouteStop>, shape: List<SeoulBusRoutePathPoint>) {
    val absolute = target.toAbsolutePath().normalize()
    val parent = absolute.parent
    val temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
    require(absolute.fileName.toString() == "catalog-diagnostic.json"
        && parent.parent == temporaryRoot && parent.fileName.toString().startsWith("nolate-seoul-catalog-")
        && parent.toRealPath() == parent && !Files.isSymbolicLink(parent)
        && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
    val bytes = jacksonObjectMapper().writeValueAsBytes(catalogDiagnostic(stops, shape))
    require(bytes.size <= 2_000_000)
    Files.newByteChannel(absolute, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }
}
