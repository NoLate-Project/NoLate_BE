package com.noLate.transit.qa

import com.noLate.transit.domain.SeoulBusRoutePathPoint
import com.noLate.transit.domain.SeoulBusRouteStop
import java.nio.file.Files
import kotlin.test.*

class SeoulBusCatalogDiagnosticSnapshotTest {
    private val stops = (1..2).map { SeoulBusRouteStop("100100447", it, "101000007", "02007",
        "private-name", "private-direction", "200000001", 37.55, 126.97) }
    private val shape = listOf(SeoulBusRoutePathPoint(0, 37.55, 126.97), SeoulBusRoutePathPoint(1, 37.56, 126.97))
    @Test fun `diagnostic omits names identifiers and cannot certify navigation`() {
        val result = catalogDiagnostic(stops, shape)
        assertEquals(true, result["diagnosticOnly"]); assertEquals(false, result["navigationReady"])
        for (secret in listOf("private", "101000007", "02007", "200000001", "100100447")) assertFalse(result.toString().contains(secret))
        val rows = result["stops"] as List<*>
        assertEquals(0, (rows[0] as Map<*, *>)["stationOrdinal"])
        assertEquals(0, (rows[1] as Map<*, *>)["stationOrdinal"])
    }
    @Test fun `writing outside exact diagnostic directory is rejected without creating files`() {
        assertFailsWith<IllegalArgumentException> { writeCatalogDiagnostic(java.nio.file.Path.of("/private/tmp/not-permitted.json"), stops, shape) }
    }
    @Test fun `offline replay has zero network and refuses navigation claims or extra secret fields`() {
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val bytes = mapper.writeValueAsBytes(catalogDiagnostic(stops, shape))
        val result = replayCatalogDiagnostic(bytes)
        assertEquals(0, result["networkAttempts"])
        assertEquals(false, result["navigationReady"])
        for (changes in listOf(mapOf("navigationReady" to true), mapOf("diagnosticOnly" to false),
            mapOf("schemaVersion" to 2), mapOf("serviceKey" to "must-not-be-accepted"))) {
            assertFailsWith<IllegalArgumentException> {
                replayCatalogDiagnostic(mapper.writeValueAsBytes(catalogDiagnostic(stops, shape) + changes))
            }
        }
        assertFailsWith<IllegalArgumentException> { replayCatalogDiagnostic(ByteArray(2_000_001)) }
    }
    @Test fun `offline replay refuses coerced sequence and original provider fields`() {
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        for (replacement in listOf("\"sequence\":\"1\"", "\"sequence\":1.5", "\"sequence\":1,\"stationId\":\"101000007\"")) {
            val original = mapper.writeValueAsString(catalogDiagnostic(stops, shape))
            assertFailsWith<IllegalArgumentException> {
                replayCatalogDiagnostic(original.replace("\"sequence\":1", replacement).toByteArray())
            }
        }
    }
    @Test fun `owner only snapshot cannot overwrite existing artifact`() {
        val directory = Files.createTempDirectory(catalogDiagnosticRoot(), "nolate-seoul-catalog-test-")
        val target = directory.resolve("catalog-diagnostic.json")
        try {
            writeCatalogDiagnostic(target, stops, shape)
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { writeCatalogDiagnostic(target, stops, shape) }
        } finally { Files.deleteIfExists(target); Files.deleteIfExists(directory) }
    }
}
