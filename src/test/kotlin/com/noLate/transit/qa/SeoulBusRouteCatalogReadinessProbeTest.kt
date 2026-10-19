package com.noLate.transit.qa

import com.noLate.transit.domain.SeoulBusRoutePathPoint
import com.noLate.transit.domain.SeoulBusRouteStop
import com.noLate.transit.infrastructure.SeoulBusCatalogField
import com.noLate.transit.infrastructure.SeoulBusCatalogFieldIssue
import com.noLate.transit.infrastructure.SeoulBusCatalogFieldException
import kotlin.test.*

class SeoulBusRouteCatalogReadinessProbeTest {
    private val stop = SeoulBusRouteStop("100100447", 44, "101000007", "02007", "서울역", "상명대", null, 37.55, 126.97)
    private val points = listOf(SeoulBusRoutePathPoint(0, 37.55, 126.97), SeoulBusRoutePathPoint(1, 37.56, 126.97))

    @Test fun `opt-in and key are required before either operation`() {
        listOf(false to "synthetic", true to null, true to " ").forEach { (enabled, key) ->
            val result = seoulBusCatalogReadiness(enabled, key, true, { error("No dispatch") }, { error("No dispatch") })
            assertEquals(0, result["networkAttempts"])
            assertEquals(false, result["navigationReady"])
        }
    }
    @Test fun `fixed route makes at most two distinct calls and valid contract is not navigation admission`() {
        val calls = mutableListOf<String>()
        val result = seoulBusCatalogReadiness(true, "synthetic", true, {
            assertEquals("100100447", it); calls.add("stops"); listOf(stop)
        }, { assertEquals("100100447", it); calls.add("path"); points })
        assertEquals(listOf("stops", "path"), calls)
        assertEquals(2, result["networkAttempts"])
        assertEquals("CATALOG_CONTRACT_VALID", result["status"])
        assertEquals(false, result["navigationReady"])
        assertEquals(false, result["stopSectionCrosswalkVerified"])
        assertFalse(result.toString().contains("101000007"))
        assertFalse(result.toString().contains("37.55"))
    }
    @Test fun `empty or failed first operation does not retry or spend path call`() {
        val empty = seoulBusCatalogReadiness(true, "synthetic", stops = { emptyList() }, path = { error("No dispatch") })
        assertEquals("EMPTY_STOPS", empty["status"])
        var count = 0
        val failed = seoulBusCatalogReadiness(true, "synthetic", stops = {
            count++; throw IllegalArgumentException("sensitive-key-body")
        }, path = { error("No dispatch") })
        assertEquals(1, count); assertEquals(1, failed["networkAttempts"])
        assertEquals("UNCLASSIFIED", failed["contractFailure"])
        assertFalse(failed.toString().contains("sensitive-key-body"))
    }
    @Test fun `path failure remains bounded and no fallback retry occurs`() {
        var calls = 0
        val result = seoulBusCatalogReadiness(true, "synthetic", stops = { calls++; listOf(stop) }, path = {
            calls++; throw IllegalArgumentException("Invalid Seoul WGS84 coordinate")
        })
        assertEquals(2, calls)
        assertEquals("PATH", result["failedOperation"])
        assertEquals("WGS84_COORDINATE", result["contractFailure"])
        assertEquals("HTTPS", result["transport"])
    }
    @Test fun `HTTP unauthorized stops at first call and never exposes provider message`() {
        var calls = 0
        val result = seoulBusCatalogReadiness(true, "synthetic", true, stops = {
            calls++
            throw org.springframework.web.client.RestClientResponseException(
                "sensitive-provider-uri", 401, "Unauthorized", null, "sensitive-provider-body".toByteArray(), null)
        }, path = { error("Second operation must not dispatch") })
        assertEquals(1, calls)
        assertEquals(1, result["networkAttempts"])
        assertEquals("STOPS", result["failedOperation"])
        assertEquals("HTTP_REJECTED", result["failureCategory"])
        assertEquals(401, result["httpStatus"])
        assertEquals(false, result["navigationReady"])
        assertFalse(result.toString().contains("sensitive-provider"))
    }
    @Test fun `typed field failure exposes schema and row only and blocks second request`() {
        val result = seoulBusCatalogReadiness(true, "private-key-marker", true, stops = {
            throw SeoulBusCatalogFieldException(SeoulBusCatalogField.ARS, 1, SeoulBusCatalogFieldIssue.EMPTY)
        }, path = { error("Must not dispatch") })
        assertEquals("FIELD_MISSING", result["contractFailure"])
        assertEquals("arsId", result["fieldName"])
        assertEquals("EMPTY", result["fieldIssue"])
        assertEquals(1, result["responseRowOrdinal"])
        assertEquals(1, result["networkAttempts"])
        assertEquals(false, result["navigationReady"])
        assertFalse(result.toString().contains("private-key-marker"))
    }
    @Test fun `catalog with unavailable public numbers is explicit partial data not navigation admission`() {
        val result = seoulBusCatalogReadiness(true, "synthetic", true,
            stops = { listOf(stop.copy(arsId = null)) }, path = { points })
        assertEquals("CATALOG_WITH_UNNUMBERED_STOPS", result["status"])
        assertEquals(1, result["missingPublicStopNumberCount"])
        assertEquals(1, result["targetWindowMissingPublicStopNumberCount"])
        assertEquals(false, result["allPublicStopNumbersAvailable"])
        assertEquals(false, result["navigationReady"])
        assertEquals(false, result["stopSectionCrosswalkVerified"])
        assertFalse(result.toString().contains("101000007"))
    }
    @Test fun `invalid diagnostic rows remain distinct from unavailable numbers and never certify readiness`() {
        val result = seoulBusCatalogReadiness(true, "synthetic", true,
            stops = { listOf(stop.copy(arsId = null, publicStopNumberInvalid = true)) }, path = { points })
        assertEquals("DIAGNOSTIC_CATALOG_WITH_INVALID_STOP_NUMBERS", result["status"])
        assertEquals(1, result["invalidPublicStopNumberCount"])
        assertEquals(1, result["targetWindowInvalidPublicStopNumberCount"])
        assertEquals(false, result["navigationReady"])
        assertEquals(false, result["allPublicStopNumbersAvailable"])
    }
    @Test fun `target static geometry match emits only safe counts and never navigation or section admission`() {
        val rows = (44..48).map { stop.copy(sequence = it, latitude = 37.55 + (it - 44) * 0.0001) }
        val result = seoulBusCatalogReadiness(true, "private-key-marker", true, { rows }, { points })
        assertEquals("MATCHED", result["targetShapeMatchStatus"])
        assertEquals(5, result["targetShapeMatchedStopCount"])
        assertEquals((44..48).toList(), result["targetShapeStopSequences"])
        assertEquals(true, result["targetShapeUniquelyProjected"])
        assertEquals(false, result["navigationReady"])
        assertEquals(false, result["stopSectionCrosswalkVerified"])
        for (value in listOf("private-key-marker", "37.55", "126.97", "101000007", "02007")) assertFalse(result.toString().contains(value))
    }
    @Test fun `parallel return path remains ambiguous in real probe output with zero successful projections`() {
        val rows = (44..48).map { stop.copy(sequence = it, latitude = 37.55 + (it - 44) * 0.0001) }
        val parallel = points + listOf(SeoulBusRoutePathPoint(2, 37.56, 126.9701), SeoulBusRoutePathPoint(3, 37.55, 126.9701))
        val result = seoulBusCatalogReadiness(true, "synthetic", true, { rows }, { parallel })
        assertEquals("STOP_SHAPE_AMBIGUOUS", result["targetShapeMatchStatus"])
        assertEquals(0, result["targetShapeMatchedStopCount"])
        assertEquals(false, result["targetShapeUniquelyProjected"])
        assertEquals(false, result["navigationReady"])
    }
}
