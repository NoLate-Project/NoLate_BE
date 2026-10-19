package com.noLate.journey.automatic

import com.noLate.journey.application.*
import com.noLate.transit.domain.SeoulSubwayObservationLine
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal val subwayObservationFixtureCases = setOf("complete", "multiple", "direction", "stale", "partial",
    "edited", "revoked", "catalog-missing", "raw-missing", "redirect", "catalog-branches", "catalog-express",
    "catalog-ambiguous", "catalog-unmapped", "catalog-expired", "catalog-loop-shadow",
    "automatic-complete","automatic-stopped","automatic-replaced","automatic-revoked","automatic-edited","automatic-generation")

/** HTTP -> actual bounded client/parser -> saved-route fences -> matcher. All rows/catalog/actors
 * are synthetic. This does not attest an official service catalog, current passenger, or GPS. */
internal fun subwayObservationCheckFixture(case: String): Map<String, Any?> {
    require(case in subwayObservationFixtureCases)
    val f = AutomaticJourneyFixture(Clock.systemUTC())
    f.plan = f.plan.copy(route = syntheticSavedSubwayRoute(f.mapper))
    val automatic=f.service()
    if(case.startsWith("automatic-"))automatic.prepare(7,9001,2,f.prepareCommand())
    if (case == "raw-missing") {
        (requireNotNull(f.plan.route).path("transitLegs").path(1).path("navigationEvidence") as
            com.fasterxml.jackson.databind.node.ObjectNode).remove("subwayIdentifiers")
    }
    if(case=="catalog-express") {
        val leg=requireNotNull(f.plan.route).path("transitLegs").path(1) as com.fasterxml.jackson.databind.node.ObjectNode
        leg.put("stationCount",1)
        val evidence=leg.path("navigationEvidence") as com.fasterxml.jackson.databind.node.ObjectNode;evidence.put("declaredStationCount",1)
        val rows=evidence.path("stopOccurrences") as com.fasterxml.jackson.databind.node.ArrayNode;rows.remove(1)
        (rows[1] as com.fasterxml.jackson.databind.node.ObjectNode).put("sourceIndex",1)
    }
    if(case=="catalog-unmapped") (requireNotNull(f.plan.route).path("transitLegs").path(1)
        .path("navigationEvidence").path("stopOccurrences")[1] as com.fasterxml.jackson.databind.node.ObjectNode)
        .put("routingStationID","888888")
    var reads = 0; var revoked = false
    val calls = AtomicInteger(); val traps = AtomicInteger(); val catalogCalls = AtomicInteger()
    val stations = listOf("9990000001", "9990000002", "9990000003", "9990000004", "9990000005")
    val catalogClock=Clock.systemUTC();val catalogNow=catalogClock.instant()
    fun pattern(calls:List<String>,service:Int=0)=IndependentSavedSubwayServiceCatalog.Service("1000","2","1",
        SeoulSubwayObservationLine.LINE_2,0,calls.last(),service,calls)
    val independent=IndependentSavedSubwayServiceCatalog(stations.mapIndexed { index,id ->
        IndependentSavedSubwayServiceCatalog.Station("1000","2",(222+index).toString(),SeoulSubwayObservationLine.LINE_2,id)
    },when(case) {
        "catalog-branches"->listOf(pattern(stations.take(3)),pattern(stations.take(4)))
        "catalog-express"->listOf(pattern(stations.take(3)),pattern(listOf(stations[0],stations[2]),1))
        "catalog-ambiguous"->listOf(pattern(stations.take(3)),pattern(listOf(stations[4])+stations.take(3)))
        "catalog-loop-shadow"->listOf(pattern(stations.take(3)),pattern(stations.take(3)+stations.take(3)))
        else->listOf(pattern(stations.take(3)))
    },catalogNow.minusSeconds(60),catalogNow.plusSeconds(if(case=="catalog-expired")-1 else 3600),catalogClock)
    val authority = object : WalkingRouteAuthority {
        override fun <T> withOwnedRoute(member: Long, schedule: Long, login: Long, action: (OwnedWalkingRoute) -> T): T {
            reads++
            check(member == 7L && schedule == 9001L && login == 2L)
            if (revoked) throw WalkingContractRejected("OWNER_CHANGED")
            return action(OwnedWalkingRoute(f.plan, 1))
        }
    }
    val socket = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 2)
    val executor = Executors.newSingleThreadExecutor(); socket.executor = executor
    socket.createContext("/") { exchange ->
        try {
            if (exchange.requestURI.path == "/trap") { traps.incrementAndGet(); exchange.sendResponseHeaders(204, -1) }
            else {
                calls.incrementAndGet()
                check(exchange.requestMethod == "GET" && exchange.requestHeaders.getFirst("Cookie") == null)
                check(exchange.requestURI.rawPath.split('/').drop(1).map { URLDecoder.decode(it, Charsets.UTF_8) } ==
                    listOf("synthetic-subway-only", "json", "realtimePosition", "0", "500", "2호선"))
                if (case == "edited") (f.plan.route as com.fasterxml.jackson.databind.node.ObjectNode).put("changed", true)
                if (case == "revoked") revoked = true
                if(case=="automatic-stopped" || case=="automatic-replaced")automatic.stop(7,9001,2,f.sessionId,f.stopCommand())
                if(case=="automatic-replaced")automatic.prepare(7,9001,2,f.prepareCommand().copy(requestId="cc67598a-6c84-4d0d-8d25-134e0c01c12e"))
                if(case=="automatic-revoked")f.actor.sessionGeneration=3
                if(case=="automatic-edited")(f.plan.route as com.fasterxml.jackson.databind.node.ObjectNode).put("changed",true)
                if (case == "redirect") {
                    exchange.responseHeaders.set("Location", "http://127.0.0.1:${socket.address.port}/trap")
                    exchange.sendResponseHeaders(302, -1)
                } else {
                    val source = Instant.now().minusSeconds(if (case == "stale") 31 else 1)
                    val day = DateTimeFormatter.ofPattern("uuuuMMdd").withZone(ZoneId.of("Asia/Seoul")).format(source)
                    val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Seoul")).format(source)
                    val row = mapOf("subwayId" to "1002", "subwayNm" to "2호선", "trainNo" to "00123",
                        "statnId" to if(case=="catalog-express")stations[2] else stations[1], "statnTid" to stations[2], "updnLine" to if (case == "direction") "1" else "0",
                        "trainSttus" to "1", "directAt" to if(case=="catalog-express") "1" else "0", "lstcarAt" to "0", "lastRecptnDt" to day, "recptnDt" to time)
                    val rows = when(case) {
                        "multiple"->listOf(row,row+("trainNo" to "00124"))
                        "catalog-branches"->listOf(row,row+mapOf("trainNo" to "00124","statnTid" to stations[3]))
                        else->listOf(row)
                    }
                    val body = f.mapper.writeValueAsBytes(mapOf("errorMessage" to mapOf("code" to "INFO-000", "total" to
                        if (case == "partial") 2 else rows.size), "realtimePositionList" to rows))
                    exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
                }
            }
        } catch (_: Exception) { runCatching { exchange.sendResponseHeaders(500, -1) } }
        finally { exchange.close() }
    }
    socket.start()
    try {
        SeoulTransitArrivalClient("", "synthetic-subway-only", "", "http://127.0.0.1:${socket.address.port}",
            "http://127.0.0.1:1").use { client ->
            val catalog = object:SavedSubwayServicePatternCatalog {
                override fun resolve(route:JourneyRouteSnapshot,legIndex:Int)=resolveAll(route,legIndex).singleOrNull()
                override fun resolveAll(route:JourneyRouteSnapshot,legIndex:Int):List<JourneySubwayTrainStationMatcher.Pattern> {
                    catalogCalls.incrementAndGet()
                    return if(case=="catalog-missing")emptyList() else independent.resolveAll(route,legIndex)
                }
                override fun validAt(now:Instant)=independent.validAt(now)
            }
            val service = SavedJourneySubwayObservationCheck(f.mapper, authority, catalog,
                { query -> client.getSubwayTrainObservations(query) }, Clock.systemUTC(), true)
            val result = try {
                if(case.startsWith("automatic-")) {
                    val owned=JourneySubwayObservationService({ member,schedule,login,session,command ->
                        reads++;automatic.requireSubwayObservationFence(member,schedule,login,session,command)
                    },service).observe(7,9001,2,f.sessionId,com.noLate.journey.domain.JourneySubwayObservationCommand(
                        if(case=="automatic-generation")2 else 1,1,f.owner,f.digest,1))
                    SavedSubwayObservationCheckResult(owned["status"] as String,owned["matchingTrainCount"] as Int)
                } else service.check(7, 9001, 2, 1, 1)
            }
                catch (failure: SubwayObservationCheckRejected) { SavedSubwayObservationCheckResult(failure.reason) }
                catch (failure: WalkingContractRejected) { SavedSubwayObservationCheckResult(failure.reason) }
                catch (_: com.noLate.global.error.BusinessException) { SavedSubwayObservationCheckResult(
                    if(case=="automatic-revoked")"OWNER_CHANGED" else "AUTOMATIC_SESSION_UNAVAILABLE") }
            check(traps.get() == 0)
            return mapOf("fixtureOnly" to true, "realProvider" to false, "gpsUsed" to false, "case" to case,
                "status" to result.status, "matchingTrainCount" to result.matchingTrainCount,
                "navigationReady" to false, "passengerBoardingConfirmed" to false,
                "passengerRemainingStops" to null, "passengerPosition" to null, "providerCalls" to calls.get(),
                "authorityReads" to reads, "redirectTrapCalls" to traps.get(), "schemaVersion" to 1)
                .also { check(catalogCalls.get() == if (case in setOf("raw-missing","automatic-generation")) 0 else 1) }
        }
    } finally { socket.stop(0); executor.shutdownNow() }
}

/** Test-source-only loopback server. No resources/dotenv, Spring production context, DB, APNs or GPS. */
fun main() {
    val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 15547), 2)
    val executor = Executors.newSingleThreadExecutor(); server.executor = executor
    server.createContext("/__fixture/subway-check") { exchange ->
        try {
            val bytes = exchange.requestBody.readNBytes(1025)
            val request = if (bytes.size <= 1024 && exchange.requestMethod == "POST") mapper.readTree(bytes) else null
            val case = request?.path("case")?.takeIf { it.isTextual }?.asText()
            val valid = case in subwayObservationFixtureCases
            val output = if (valid) mapper.writeValueAsBytes(subwayObservationCheckFixture(case!!)) else "{}".toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json"); exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(if (valid) 200 else 400, output.size.toLong()); exchange.responseBody.write(output)
        } catch (_: Exception) { runCatching { exchange.sendResponseHeaders(500, -1) } }
        finally { exchange.close() }
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(0); executor.shutdownNow() })
    server.start(); println("SUBWAY_CHECK_FIXTURE_READY loopback=15547 realProvider=false gps=false")
    CountDownLatch(1).await()
}
