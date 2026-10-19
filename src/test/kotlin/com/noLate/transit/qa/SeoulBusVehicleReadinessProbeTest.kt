package com.noLate.transit.qa

import com.noLate.transit.domain.SeoulBusVehicleObservationBatch
import com.noLate.transit.domain.SeoulBusVehicleObservationStatus
import com.noLate.transit.domain.SeoulBusVehicleSourceAgeSummary
import com.noLate.transit.infrastructure.SeoulBusVehicleTransportException
import com.noLate.transit.infrastructure.TransitProviderApplicationException
import com.noLate.transit.infrastructure.TransitWireProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SeoulBusVehicleReadinessProbeTest {
    @Test fun `sampling makes at most six fixed calls spaced ten seconds within one minute`() {
        var nanos = 0L
        val results = mutableListOf<Map<String, Any>>()
        val starts = mutableListOf<Long>()
        sampleSeoulBusVehicles(true, "synthetic", true, { nanos }, { nanos += it * 1_000_000 }, results::add) { _, query ->
            assertEquals("100100447", query.routeId)
            assertEquals(44, query.firstStopOrder)
            assertEquals(48, query.lastStopOrder)
            starts.add(nanos)
            nanos += 200_000_000
            SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.NO_USABLE_OBSERVATIONS, emptyList(), 1,
                sourceAgeSummary = SeoulBusVehicleSourceAgeSummary(1, 30_000, 30_000))
        }
        assertEquals((0L..5L).map { it * 10_000_000_000 }, starts)
        assertEquals(6, results.size)
        assertEquals(50_000L, results.last()["elapsedMillis"])
        assertEquals(200L, results.last()["requestDurationMillis"])
        assertEquals(false, results.last()["navigationReady"])
        assertEquals(mapOf<String, Any>("count" to 1, "minAgeMillis" to 30_000L, "maxAgeMillis" to 30_000L), results.first()["sourceAgeSummary"])
    }

    @Test fun `sampling stops on first provider failure without retry or sleep`() {
        var calls = 0
        val results = mutableListOf<Map<String, Any>>()
        sampleSeoulBusVehicles(true, "synthetic", true, { 0L }, { error("must not sleep") }, results::add) { _, _ ->
            calls++
            error("sensitive-marker")
        }
        assertEquals(1, calls)
        assertEquals(1, results.size)
        assertEquals("PROVIDER_CHECK_FAILED", results.single()["status"])
        assertFalse(results.toString().contains("sensitive-marker"))
    }

    @Test fun `sampling without opt-in or key performs zero network calls and no sleeps`() {
        listOf(false to "synthetic", true to null).forEach { (enabled, key) ->
            val results = mutableListOf<Map<String, Any>>()
            sampleSeoulBusVehicles(enabled, key, true, { 0L }, { error("must not sleep") }, results::add) { _, _ -> error("must not call") }
            assertEquals(1, results.size)
            assertEquals(false, results.single()["networkAttempted"])
        }
    }

    @Test fun `sampling deadline aborts late starts and does not catch up in a burst`() {
        var nanos = 0L
        var calls = 0
        sampleSeoulBusVehicles(true, "synthetic", true, { nanos }, { nanos += it * 1_000_000 }, {}) { _, _ ->
            calls++
            nanos += 61_000_000_000
            SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.NO_VEHICLES, emptyList())
        }
        assertEquals(1, calls)
    }
    @Test fun `HTTP test mode stays opt-in and still requires explicit probe and key`() {
        val blocked = seoulBusVehicleReadiness(false, "synthetic", true) { _, _ -> error("must not call") }
        assertEquals(false, blocked["networkAttempted"])
        assertEquals("HTTP_EXPLICIT_TEST", blocked["transport"])
        val default = seoulBusVehicleReadiness(true, null) { _, _ -> error("must not call") }
        assertEquals("HTTPS", default["transport"])
        var calls = 0
        val allowed = seoulBusVehicleReadiness(true, "synthetic", true) { _, query ->
            calls++
            assertEquals("100100447", query.routeId)
            SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.NO_VEHICLES, emptyList())
        }
        assertEquals(1, calls)
        assertEquals(false, allowed["navigationReady"])
    }
    @Test fun `no opt-in or credential means zero calls`() {
        listOf(false to "synthetic", true to null, true to " ").forEach { (enabled, key) ->
            val result = seoulBusVehicleReadiness(enabled, key) { _, _ -> error("must not call") }
            assertEquals(false, result["networkAttempted"])
            assertEquals(false, result["navigationReady"])
        }
    }

    @Test fun `only fixed official route and stop window requested and failure redacted`() {
        var calls = 0
        val result = seoulBusVehicleReadiness(true, "sensitive-marker") { key, query ->
            calls++
            assertEquals("sensitive-marker", key)
            assertEquals("100100447", query.routeId)
            assertEquals(44, query.firstStopOrder)
            assertEquals(48, query.lastStopOrder)
            error("sensitive-marker provider-body")
        }
        assertEquals(1, calls)
        assertEquals("PROVIDER_CHECK_FAILED", result["status"])
        assertFalse(result.toString().contains("sensitive-marker"))
        assertFalse(result.toString().contains("provider-body"))
    }

    @Test fun `empty valid response never grants navigation readiness`() {
        val result = seoulBusVehicleReadiness(true, "synthetic") { _, _ ->
            SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.NO_VEHICLES, emptyList())
        }
        assertEquals("NO_VEHICLES", result["status"])
        assertEquals(false, result["freshSourceObservationAvailable"])
        assertEquals(false, result["navigationReady"])
    }

    @Test fun `failure categories expose bounded codes not raw exception content`() {
        val transport = seoulBusVehicleReadiness(true, "synthetic") { _, _ -> throw SeoulBusVehicleTransportException() }
        assertEquals("TRANSPORT_UNAVAILABLE", transport["failureCategory"])
        val rejected = seoulBusVehicleReadiness(true, "synthetic") { _, _ ->
            throw TransitProviderApplicationException(TransitWireProvider.SEOUL_BUS, "30")
        }
        assertEquals("PROVIDER_APPLICATION_REJECTED", rejected["failureCategory"])
        assertEquals("30", rejected["providerCode"])
        val hostile = seoulBusVehicleReadiness(true, "synthetic") { _, _ ->
            throw TransitProviderApplicationException(TransitWireProvider.SEOUL_BUS, "SENSITIVEKEYMARKER")
        }
        assertEquals("UNKNOWN", hostile["providerCode"])
        assertFalse(hostile.toString().contains("SENSITIVEKEYMARKER"))
    }
}
