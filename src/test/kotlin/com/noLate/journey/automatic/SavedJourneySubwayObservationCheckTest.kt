package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.*
import com.noLate.transit.domain.*
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SavedJourneySubwayObservationCheckTest {
    private val clock = AutomaticJourneyMutableClock()
    private val f = AutomaticJourneyFixture(clock).also {
        it.plan = it.plan.copy(route = syntheticSavedSubwayRoute(it.mapper, transfer = true))
    }
    private var revision = 1L
    private var revoked = false
    private var reads = 0
    private var fetches = 0
    private var catalogReads = 0
    private val stationIds = listOf("1002000201", "1002000202", "1002000203")
    private val pattern get() = JourneySubwayTrainStationMatcher.Pattern(SeoulSubwayObservationLine.LINE_2,
        0, stationIds.last(), 0, stationIds, 0, 2)
    private val authority = object : WalkingRouteAuthority {
        override fun <T> withOwnedRoute(member: Long, schedule: Long, login: Long, action: (OwnedWalkingRoute) -> T): T {
            reads++
            if (revoked) throw WalkingContractRejected("OWNER_CHANGED")
            assertEquals(2, login)
            return action(OwnedWalkingRoute(f.plan.copy(memberId = member, scheduleId = schedule), revision))
        }
    }
    private fun observations() = SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.AVAILABLE, listOf(
        SeoulSubwayTrainObservation("00123", SeoulSubwayObservationLine.LINE_2, stationIds[1], stationIds.last(),
            0, 1, 0, false, clock.now.minusSeconds(2), clock.now, clock.now.plusSeconds(28))))
    private fun service(enabled: Boolean = true, provider: SubwayTrainObservationProvider = SubwayTrainObservationProvider {
        fetches++; assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); observations()
    }, catalog: SavedSubwayServicePatternCatalog = SavedSubwayServicePatternCatalog { route, index ->
        catalogReads++; assertEquals("SUBWAY", route.legs[index].kind)
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); pattern
    }) = SavedJourneySubwayObservationCheck(f.mapper, authority, catalog, provider, clock, enabled)
    private fun check(service: SavedJourneySubwayObservationCheck, index: Int = 1) = service.check(7, 9001, 2, 1, index)

    @Test fun `saved subway leg check brackets lookup and train fetch in fresh ownership fences`() {
        val result = check(service())
        assertEquals("TRAIN_REFERENCES_ONLY", result.status); assertEquals(1, result.matchingTrainCount)
        assertEquals(2, reads); assertEquals(1, fetches); assertEquals(1, catalogReads)
        assertFalse(result.navigationReady); assertFalse(result.passengerBoardingConfirmed)
        assertNull(result.passengerPosition); assertNull(result.passengerRemainingStops)
        val json = f.mapper.writeValueAsString(result)
        assertFalse(json.contains("00123")); assertFalse(json.contains(stationIds[1]))
    }

    @Test fun `later subway transfer leg can be checked without claiming passenger current leg`() {
        assertEquals(1, check(service(), 3).matchingTrainCount)
        assertFalse(check(service(), 3).navigationReady)
    }

    @Test fun `default disabled malformed identity revision and leg fail without provider`() {
        val disabled = SavedJourneySubwayObservationCheck(f.mapper, authority, { _, _ -> error("not called") },
            { error("not called") }, clock)
        assertEquals("FEATURE_DISABLED", assertFailsWith<SubwayObservationCheckRejected> { check(disabled) }.reason)
        val s = service()
        for (member in listOf(0L, -1L)) assertFailsWith<SubwayObservationCheckRejected> { s.check(member, 9001, 2, 1, 1) }
        assertFailsWith<SubwayObservationCheckRejected> { s.check(7, 0, 2, 1, 1) }
        for (login in listOf(-1L, 9_007_199_254_740_992L))
            assertFailsWith<SubwayObservationCheckRejected> { s.check(7, 9001, login, 1, 1) }
        for (value in listOf(0L, 9_007_199_254_740_992L))
            assertFailsWith<SubwayObservationCheckRejected> { s.check(7, 9001, 2, value, 1) }
        for (index in listOf(-1, 64)) assertFailsWith<SubwayObservationCheckRejected> { check(s, index) }
        assertEquals(0, reads); assertEquals(0, fetches); assertEquals(0, catalogReads)
    }

    @Test fun `walk missing and non subway saved legs do not invoke catalog or provider`() {
        for (index in listOf(0, 2, 5))
            assertEquals("UNSUPPORTED_SUBWAY_LEG", assertFailsWith<SubwayObservationCheckRejected> { check(service(), index) }.reason)
        assertEquals(0, fetches); assertEquals(0, catalogReads)
    }

    @Test fun `no independent station catalog blocks without train request and still rechecks owner`() {
        val result = check(service(catalog = { _, _ -> catalogReads++; null }))
        assertEquals("STATION_CROSSWALK_REQUIRED", result.status); assertEquals(0, fetches); assertEquals(2, reads)
        assertEquals(0, result.matchingTrainCount); assertFalse(result.navigationReady)
    }

    @Test fun `legacy display route missing raw subway IDs makes no catalog or train lookup`() {
        (f.plan.route!!.path("transitLegs")[1].path("navigationEvidence") as ObjectNode).remove("subwayIdentifiers")
        val result = check(service())
        assertEquals("RAW_SUBWAY_IDENTIFIERS_REQUIRED", result.status)
        assertEquals(0, fetches); assertEquals(0, catalogReads); assertEquals(2, reads)
    }

    @Test fun `revision route and login changes during network discard the result`() {
        for (change in listOf("revision", "route", "login")) {
            revision = 1; revoked = false
            val original = f.plan.route!!.deepCopy<ObjectNode>()
            val s = service(provider = {
                when (change) {
                    "revision" -> revision++
                    "route" -> (f.plan.route as ObjectNode).put("changed", true)
                    "login" -> revoked = true
                }
                observations()
            })
            val failure = assertFailsWith<RuntimeException> { check(s) }
            assertEquals(when (change) { "revision" -> "REVISION_CHANGED"; "route" -> "SAVED_ROUTE_CHANGED"; else -> "OWNER_CHANGED" }, failure.message)
            f.plan = f.plan.copy(route = original)
        }
    }

    @Test fun `catalog resolution cannot bypass post fetch revision fence`() {
        val s = service(catalog = { _, _ -> revision++; null })
        assertEquals("REVISION_CHANGED", assertFailsWith<SubwayObservationCheckRejected> { check(s) }.reason)
        assertEquals(0, fetches)
    }

    @Test fun `source train expires during post fetch fence rather than using receipt as now`() {
        val batch = observations()
        val result = check(service(provider = { clock.now = clock.now.plusSeconds(9); batch }))
        assertEquals(1, result.matchingTrainCount)
        // A source already 25 seconds old expires even inside the check's independent deadline.
        val old = SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.AVAILABLE, listOf(
            SeoulSubwayTrainObservation("00123", SeoulSubwayObservationLine.LINE_2, stationIds[1], stationIds.last(),
                0, 1, 0, false, clock.now.minusSeconds(25), clock.now, clock.now.plusSeconds(5))))
        assertEquals(0, check(service(provider = { clock.now = clock.now.plusSeconds(5); old })).matchingTrainCount)
    }

    @Test fun `check deadline and clock regression reject without returning stale success`() {
        val start = clock.now
        for (seconds in listOf(10L, -1L)) {
            clock.now = start
            assertEquals("CHECK_DEADLINE", assertFailsWith<SubwayObservationCheckRejected> {
                check(service(provider = { val batch = observations(); clock.now = clock.now.plusSeconds(seconds); batch }))
            }.reason)
        }
    }

    @Test fun `ambient transaction forbids ownership and external network`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try { assertEquals("AMBIENT_TRANSACTION", assertFailsWith<SubwayObservationCheckRejected> { check(service()) }.reason) }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false) }
        assertEquals(0, reads); assertEquals(0, fetches)
    }

    @Test fun `provider and catalog failures are sanitized and single flight released`() {
        val privateText = "private key payload and URL"
        val broken = service(provider = { throw IllegalStateException(privateText) })
        repeat(2) {
            val error = assertFailsWith<SubwayObservationCheckRejected> { check(broken) }
            assertEquals("PROVIDER_UNAVAILABLE", error.message); assertNull(error.cause)
        }
        val error = assertFailsWith<SubwayObservationCheckRejected> { check(service(catalog = { _, _ -> error(privateText) })) }
        assertEquals("CATALOG_UNAVAILABLE", error.message); assertNull(error.cause)
    }

    @Test fun `single member concurrent lookup does not wait or fetch twice`() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val s = service(provider = { entered.countDown(); check(resume.await(2, TimeUnit.SECONDS)); observations() })
        val running = CompletableFuture.supplyAsync { check(s) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals("PROVIDER_BUSY", assertFailsWith<SubwayObservationCheckRejected> { check(s) }.reason)
        } finally { resume.countDown() }
        assertEquals(1, running.get(3, TimeUnit.SECONDS).matchingTrainCount)
        assertEquals(1, check(s).matchingTrainCount)
    }
    @Test fun `catalog expiry before lookup or during external I O discards all candidate references`() {
        val expires=clock.now.plusSeconds(2)
        val catalog=object:SavedSubwayServicePatternCatalog {
            override fun resolve(route:JourneyRouteSnapshot,legIndex:Int)=pattern
            override fun validAt(now:java.time.Instant)=now<expires
        }
        assertEquals("CATALOG_UNAVAILABLE",assertFailsWith<SubwayObservationCheckRejected>{
            check(service(catalog=catalog,provider={fetches++;val batch=observations();clock.now=expires;batch}))
        }.reason)
        assertEquals(1,fetches)
        assertEquals("CATALOG_UNAVAILABLE",assertFailsWith<SubwayObservationCheckRejected>{check(service(catalog=catalog))}.reason)
        assertEquals(1,fetches)
    }
    @Test fun `unbounded or mixed line catalog is rejected before any provider operation`() {
        val other=JourneySubwayTrainStationMatcher.Pattern(SeoulSubwayObservationLine.LINE_4,0,stationIds.last(),0,stationIds,0,2)
        for(patterns in listOf(List(33){pattern},listOf(pattern,other))) {
            val catalog=object:SavedSubwayServicePatternCatalog {
                override fun resolve(route:JourneyRouteSnapshot,legIndex:Int)=null
                override fun resolveAll(route:JourneyRouteSnapshot,legIndex:Int)=patterns
            }
            assertEquals("CATALOG_UNAVAILABLE",assertFailsWith<SubwayObservationCheckRejected>{check(service(catalog=catalog))}.reason)
        }
        assertEquals(0,fetches)
    }
    @Test fun `catalog validity exceptions are sanitized rather than leaking source detail`() {
        val catalog=object:SavedSubwayServicePatternCatalog {
            override fun resolve(route:JourneyRouteSnapshot,legIndex:Int)=pattern
            override fun validAt(now:java.time.Instant):Boolean=error("private source path and credential")
        }
        val failure=assertFailsWith<SubwayObservationCheckRejected>{check(service(catalog=catalog))}
        assertEquals("CATALOG_UNAVAILABLE",failure.message);assertNull(failure.cause);assertEquals(0,fetches)
    }
    @Test fun `missing raw IDs invoke neither catalog resolution nor catalog validity`() {
        (f.plan.route!!.path("transitLegs")[1].path("navigationEvidence") as ObjectNode).remove("subwayIdentifiers")
        val catalog=object:SavedSubwayServicePatternCatalog {
            override fun resolve(route:JourneyRouteSnapshot,legIndex:Int):JourneySubwayTrainStationMatcher.Pattern?=error("not called")
            override fun validAt(now:java.time.Instant):Boolean=error("not called")
        }
        assertEquals("RAW_SUBWAY_IDENTIFIERS_REQUIRED",check(service(catalog=catalog)).status)
        assertEquals(0,fetches);assertEquals(2,reads)
    }
}
