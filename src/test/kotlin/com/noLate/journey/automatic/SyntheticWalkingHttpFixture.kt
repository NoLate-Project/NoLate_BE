package com.noLate.journey.automatic

import com.noLate.journey.application.*
import com.noLate.journey.infrastructure.TmapServerPedestrianProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Loopback sockets only, artificial credential/coordinates, no external API or production app. */
internal class SyntheticWalkingHttpFixture(val mode: String = "complete", private val duringFetch: () -> Unit = {}) : AutoCloseable {
    val calls = AtomicInteger(); val traps = AtomicInteger()
    @Volatile var validRequest = true
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 4)
    private val executor = Executors.newFixedThreadPool(2)
    val endpoint: URI = URI.create("http://127.0.0.1:${server.address.port}/tmap/routes/pedestrian?version=1&format=json")
    val provider = TmapServerPedestrianProvider("synthetic-walking-key", endpoint,
        if (mode in setOf("timeout", "slow-body")) Duration.ofMillis(350) else Duration.ofSeconds(2))
    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                if (exchange.requestURI.path == "/trap") { traps.incrementAndGet(); exchange.sendResponseHeaders(200, -1); return@createContext }
                calls.incrementAndGet()
                val raw = exchange.requestBody.readNBytes(4097).toString(UTF_8)
                val fields = raw.split('&').associate { row -> val parts = row.split('=', limit = 2)
                    URLDecoder.decode(parts[0], UTF_8) to URLDecoder.decode(parts.getOrElse(1) { "" }, UTF_8) }
                validRequest = validRequest && exchange.requestMethod == "POST" && exchange.requestURI.query == "version=1&format=json" &&
                    exchange.requestHeaders.getFirst("appKey") == "synthetic-walking-key" && !raw.contains("synthetic-walking-key") &&
                    fields["reqCoordType"] == "WGS84GEO" && fields["resCoordType"] == "WGS84GEO" && fields["sort"] == "index" &&
                    fields["searchOption"] == "0" && fields["startName"] == "출발" && fields["endName"] == "도착" &&
                    exchange.requestHeaders.getFirst("Cookie") == null
                duringFetch()
                val request = ServerWalkingRequest(ServerWalkingCoordinate(fields.getValue("startY").toDouble(), fields.getValue("startX").toDouble()),
                    ServerWalkingCoordinate(fields.getValue("endY").toDouble(), fields.getValue("endX").toDouble()))
                when (mode) {
                    "redirect" -> { exchange.responseHeaders.set("Location", endpoint.resolve("/trap").toString()); exchange.sendResponseHeaders(307, -1) }
                    "status" -> { exchange.sendResponseHeaders(429, 4); exchange.responseBody.write("oops".toByteArray()) }
                    "partial" -> { val body = syntheticPedestrianBody(request).toByteArray(); exchange.sendResponseHeaders(206, body.size.toLong()); exchange.responseBody.write(body) }
                    "large" -> { exchange.sendResponseHeaders(200, 1_048_577); exchange.responseBody.write(ByteArray(1_048_577)) }
                    "chunked-large" -> { exchange.sendResponseHeaders(200, 0); repeat(257) { exchange.responseBody.write(ByteArray(4096)) } }
                    "timeout" -> { Thread.sleep(1000); exchange.sendResponseHeaders(200, -1) }
                    "slow-body" -> { exchange.sendResponseHeaders(200, 0); exchange.responseBody.write("{".toByteArray()); exchange.responseBody.flush(); Thread.sleep(1000); exchange.responseBody.write("}".toByteArray()) }
                    "empty" -> exchange.sendResponseHeaders(200, -1)
                    "invalid-utf8" -> { exchange.sendResponseHeaders(200, 2); exchange.responseBody.write(byteArrayOf(0xC3.toByte(), 0x28)) }
                    else -> { val body = syntheticPedestrianBody(request).toByteArray(); exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body) }
                }
            } catch (_: Exception) { /* Cancelled stream/timeout is expected, never print request/body. */ }
            finally { exchange.close() }
        }
        server.start()
    }
    override fun close() { provider.close(); server.stop(0); executor.shutdownNow() }
}

internal fun walkingHttpContractFixture(case: String): Map<String, Any> {
    val mode = when (case) {
        "http-complete", "http-edited", "http-revoked" -> "complete"
        "http-redirect" -> "redirect"
        "http-large" -> "chunked-large"
        "http-timeout" -> "slow-body"
        else -> error("unknown fixture")
    }
    val fixture = AutomaticJourneyFixture().also { it.useSavedGuidancePlan(mapped = true) }
    var reads = 0; var revoked = false
    val authority = object : WalkingRouteAuthority {
        override fun <T> withOwnedRoute(member: Long, schedule: Long, login: Long, action: (OwnedWalkingRoute) -> T): T {
            reads++
            if (revoked) throw WalkingContractRejected("OWNER_CHANGED")
            return action(OwnedWalkingRoute(fixture.plan.copy(route = fixture.plan.route?.deepCopy()), 1))
        }
    }
    SyntheticWalkingHttpFixture(mode) {
        if (case == "http-edited") (fixture.plan.route as com.fasterxml.jackson.databind.node.ObjectNode).put("id", "changed-during-http")
        if (case == "http-revoked") revoked = true
    }.use { socket ->
        var status = "BOUND"; var reason = "TRANSIT_ADMISSION_PENDING"; var walks = 0; var points = 0
        try {
            val result = SavedJourneyWalkingCheckService(fixture.mapper, socket.provider, authority, fixture.clock, true).check(7, 9001, 2)
            walks = result.walkCount; points = result.pointCount
        } catch (failure: WalkingContractRejected) { status = "BLOCKED"; reason = failure.reason }
        check(socket.validRequest && socket.traps.get() == 0)
        return mapOf("fixtureOnly" to true, "case" to case, "status" to status, "reason" to reason, "walkCount" to walks,
            "pointCount" to points, "providerCalls" to socket.calls.get(), "navigationReady" to false,
            "transitProvider" to "odsay", "walkProvider" to "tmap", "authorityReads" to reads, "redirectTrapCalls" to socket.traps.get())
    }
}
