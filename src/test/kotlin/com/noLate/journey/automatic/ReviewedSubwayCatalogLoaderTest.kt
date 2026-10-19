package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.journey.infrastructure.ReviewedSubwayCatalogLoader
import com.noLate.journey.application.JourneyRouteSnapshot
import org.junit.jupiter.api.Test
import java.time.*
import kotlin.test.*

class ReviewedSubwayCatalogLoaderTest {
    private val mapper=jacksonObjectMapper()
    private val now=Instant.parse("2026-10-13T00:00:00Z")
    private val clock=Clock.fixed(now,ZoneOffset.UTC)
    private fun data():ObjectNode=mapper.valueToTree(mapOf("schemaVersion" to 1,"reviewedAt" to now.minusSeconds(2).toString(),
        "validFrom" to now.minusSeconds(1).toString(),"validUntil" to now.plusSeconds(3600).toString(),
        "sources" to listOf("ODSAY_STATION_TOPOLOGY","SEOUL_STATION_MASTER","SERVICE_PATTERN").map{
            mapOf("kind" to it,"url" to "https://data.seoul.go.kr/synthetic-test-not-official-data","sha256" to "a".repeat(64))},
        "stations" to (0..2).map{mapOf("routingCity" to "1000","routingLine" to "2","routingStationId" to (222+it).toString(),
            "line" to "LINE_2","stationId" to (9990000001L+it).toString())},
        "services" to listOf(mapOf("routingCity" to "1000","routingLine" to "2","routingWayCode" to "1","line" to "LINE_2",
            "direction" to 0,"terminalStationId" to "9990000003","service" to 0,"stationCalls" to SubwayNavigationFixture.ids))))
    @Test fun `bounded reviewed file connects to existing route resolver without deriving crosswalk from a request`() {
        val catalog=ReviewedSubwayCatalogLoader.decode(data(),clock)
        val f=AutomaticJourneyFixture(clock);f.plan=f.plan.copy(route=syntheticSavedSubwayRoute(f.mapper))
        assertEquals(1,catalog.resolveAll(JourneyRouteSnapshot.from(f.plan,f.mapper),1).size)
    }
    @Test fun `expired future or missing provenance rejects instead of installing an empty or inferred catalog`() {
        for(root in listOf(data().put("validUntil",now.toString()),data().put("reviewedAt",now.plusSeconds(1).toString()),
            data().also{it.remove("sources")},data().also{(it.path("sources")[0] as ObjectNode).put("url","http://data.seoul.go.kr/test")}))
            assertFailsWith<IllegalArgumentException>{ReviewedSubwayCatalogLoader.decode(root,clock)}
    }
    @Test fun `path failure does not expose local filename or raw source`() {
        val failure=assertFailsWith<IllegalArgumentException>{ReviewedSubwayCatalogLoader.load("/private/tmp/private-subway-missing.json",mapper,clock)}
        assertEquals("SUBWAY_CATALOG_INVALID",failure.message)
        assertNull(failure.cause)
    }
}
