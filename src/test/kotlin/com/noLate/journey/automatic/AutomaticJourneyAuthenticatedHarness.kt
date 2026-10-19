package com.noLate.journey.automatic

import com.noLate.global.security.JwtTokenProvider
import com.noLate.global.security.MemberPrincipal
import com.sun.net.httpserver.HttpServer
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.net.InetSocketAddress
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import com.noLate.notification.liveactivity.application.LiveActivityPushClient
import com.noLate.journey.application.AutomaticJourneyExpiry
import java.util.concurrent.TimeUnit
import com.noLate.journey.application.JourneyActivityDeliveryService
import com.noLate.journey.application.JourneyActivityCredentials
import com.noLate.journey.application.JourneyActivityContract
import com.noLate.journey.domain.JourneyActivityEndState
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.mockito.kotlin.*

/** Test-source-only issuer, deliberately unrelated to the user's or production credentials. */
object JourneyFixtureAuthentication {
    val jwt = JwtTokenProvider("isolated-journey-fixture-key-not-for-production-20261005", 600, 600, "journey-isolated-auth-fixture")
    // Explicit long-fixture opt-in only. Models the production access-token horizon, not
    // background refresh. Both tokens still have signed, fixed expiries and the same identity.
    val extendedJwt = JwtTokenProvider("isolated-journey-fixture-key-not-for-production-20261005", 9000, 600, "journey-isolated-auth-fixture")
    fun principal(header: String?): MemberPrincipal? = try {
        val token = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
        if (token == null || token.length > 8192 || !jwt.validateToken(token) || !jwt.isAccessToken(token)
            || jwt.getMemberIdFromToken(token) != 7L) null
        else MemberPrincipal(7, "fixture@example.invalid", "Synthetic fixture", jwt.getIssuedAt(token), jwt.getSessionGeneration(token))
    } catch (_: Exception) { null }
}

internal fun resetAuthenticatedHarnessPlan(fixture: AutomaticJourneyFixture, rideEnabled: Boolean) {
    fixture.reset() // Durable fixtures reject reset before mutating saved evidence.
    if (rideEnabled) fixture.useRideGuidancePlan() else fixture.useSavedGuidancePlan(mapped = true)
}

/** Separate authenticated listener: existing 15543 fixture still rejects EVERY Authorization header. */
fun authenticatedHarnessMain(pushClient: LiveActivityPushClient? = null) {
    val shortTtl = System.getenv("NOLATE_JOURNEY_TEST_SHORT_TTL") == "1"
    val expiryEndEnabled = System.getenv("NOLATE_JOURNEY_TEST_EXPIRY_END") == "1"
    val durableConfiguration = DurableJourneyFixtureDatabase.fromEnvironment()
    val database = durableConfiguration?.first
    val fixture = AutomaticJourneyFixture(Clock.systemUTC(), if (shortTtl) 60 else 600, database)
    val expiry = AutomaticJourneyExpiry(fixture.repository, fixture.clock, true, true, true)
    var expiryRuns = 0
    var expiryCount = 0
    fixture.useSavedGuidancePlan(mapped = true)
    val rideEnabled = System.getenv("NOLATE_JOURNEY_TEST_RIDE") == "1"
    val alightingEnabled = System.getenv("NOLATE_JOURNEY_TEST_ALIGHT_APPROACH") == "1"
    val separationEnabled = alightingEnabled && System.getenv("NOLATE_JOURNEY_TEST_SEPARATION") == "1"
    val catalogBendEnabled = separationEnabled && System.getenv("NOLATE_JOURNEY_TEST_CATALOG_BEND") == "1"
    var rideStartedAt: java.time.Instant? = null
    var rideMode = "EMPTY"
    var rideRequests = 0
    val rideProvider = if (rideEnabled) mock<com.noLate.transit.infrastructure.SeoulTransitArrivalClient>().also { provider ->
        fixture.useRideGuidancePlan()
        whenever(provider.getBusRouteDiagnosticStops("999999999")).thenReturn(
            if (catalogBendEnabled) rideCatalogBendFixtureStops() else if (separationEnabled) rideDepartureFixtureStops() else rideFixtureStops())
        whenever(provider.getBusRoutePath("999999999")).thenReturn(
            if (catalogBendEnabled) rideCatalogBendFixtureShape() else if (separationEnabled) rideDepartureFixtureShape() else rideFixtureShape())
        whenever(provider.getBusVehicleObservations(any())).thenAnswer {
            rideRequests++
            val now = fixture.clock.instant()
            val source = java.time.Instant.ofEpochSecond(now.epochSecond / 2 * 2)
            val start = rideStartedAt
            val meters = if (start == null) 0.0 else java.time.Duration.between(start, source).toMillis().coerceAtLeast(0) / 1000.0 * 10
            val target = if (alightingEnabled) 6_371_000 * Math.PI / 180 * kotlin.math.cos(Math.toRadians(37.5665)) * 0.01 else 600.0
            // Artificial independent vehicle pauses 30 s at the target, then departs.
            // It never reads the passenger's injected GPS.
            val vehicleMeters = if (separationEnabled && meters > target + 300) (meters - 300).coerceAtMost(target * 1.25)
                else meters.coerceAtMost(target)
            val vehicles = if (rideMode == "FRESH" && start != null) listOf(rideFixtureVehicle(now, source, vehicleMeters)) else emptyList()
            com.noLate.transit.domain.SeoulBusVehicleObservationBatch(
                if (vehicles.isEmpty()) com.noLate.transit.domain.SeoulBusVehicleObservationStatus.NO_VEHICLES else com.noLate.transit.domain.SeoulBusVehicleObservationStatus.AVAILABLE, vehicles)
        }
    } else null
    var validationRequests = 0
    var reconciliationRequests = 0
    var reconciliationUnavailable = false
    var arrivalRequests = 0
    var authenticatedRequests = 0
    var rejectedRequests = 0
    var redirectEnabled = false
    var trapRequests = 0
    var trapAuthorizationSeen = false
    var refreshRequests = 0
    var refreshUnavailable = false
    var consumedRefreshTokens = mutableSetOf<String>()
    val guidancePush = JourneyGuidanceApnsPilot(fixture, pushClient)
    var durableAttempts = 0; var durableAccepted = 0; var durableRejected = 0
    val durableClient = pushClient?.let { client -> object : LiveActivityPushClient {
        override fun send(request: com.noLate.notification.liveactivity.application.LiveActivityPushRequest): com.noLate.notification.liveactivity.application.LiveActivityPushResult {
            durableAttempts++
            try { return client.send(request).also { durableAccepted++ } }
            catch (failure: Exception) { durableRejected++; throw failure }
        }
    } }
    val providers = StaticListableBeanFactory()
    if (durableClient != null) providers.addBean("isolatedPushClient", durableClient)
    val durable = database?.let { JourneyActivityDeliveryService(it.lanes, fixture.service(), fixture.members,
        JourneyActivityCredentials(requireNotNull(durableConfiguration).second), fixture.mapper, it.manager,
        providers.getBeanProvider(LiveActivityPushClient::class.java), FixtureJourneyActivityRouteAuthority(), fixture.clock, true) }
    fun hasLane(session: String) = if (database == null) guidancePush.hasLane(session) else database.tx { database.lanes.findBySessionId(session) != null }
    fun end(session: String, login: Long, body: com.fasterxml.jackson.databind.JsonNode): Map<String, Any> =
        durable?.end(7, 9001, login, session, JourneyActivityContract.binding(body)) ?: guidancePush.end(session, login, body)
    val mvc = automaticJourneyFixtureMvc(fixture, activityDelivery = durable, rideProvider = rideProvider, principalForRequest = { JourneyFixtureAuthentication.principal(it.getHeader("Authorization")) }, arrivalSource = {
        arrivalRequests++
        val now = fixture.clock.instant()
        listOf(authenticatedHarnessArrival(now, rideEnabled))
    })
    val executor = Executors.newSingleThreadScheduledExecutor()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 15544), 16)
    server.executor = executor
    val trap = HttpServer.create(InetSocketAddress("127.0.0.1", 15545), 4)
    trap.executor = executor
    trap.createContext("/") { exchange ->
        trapRequests++; trapAuthorizationSeen = trapAuthorizationSeen || exchange.requestHeaders.containsKey("Authorization")
        val output = "{}".toByteArray()
        exchange.sendResponseHeaders(200, output.size.toLong()); exchange.responseBody.use { it.write(output) }; exchange.close()
    }
    server.createContext("/") { exchange ->
        var status = 200
        val result: Any = try {
            val path = exchange.requestURI.path
            // 32 canonical legs exceed the old 16 KiB QA envelope. Still bounded, test-only.
            val bytes = exchange.requestBody.readNBytes(65537)
            when {
                bytes.size > 65536 -> { status = 413; mapOf("errorCode" to "FIXTURE_INPUT_LIMIT") }
                path == "/__fixture/context" && exchange.requestMethod == "GET" && !exchange.requestHeaders.containsKey("Authorization") -> mapOf(
                    "fixtureOnly" to true, "plan" to fixture.checkedPlan(), "apiOrigin" to "http://127.0.0.1:15544",
                    "memberId" to 7, "guidancePushRealProvider" to (pushClient != null),
                    "refreshToken" to JourneyFixtureAuthentication.jwt.createRefreshToken(7, "Synthetic fixture", 2),
                    "accessToken" to (if (exchange.requestURI.query == "short=1")
                        JwtTokenProvider("isolated-journey-fixture-key-not-for-production-20261005", 45, 600, "journey-isolated-auth-fixture")
                        else JourneyFixtureAuthentication.jwt).createAccessToken(7, "Synthetic fixture", 2))
                path == "/__fixture/walking-contract" && exchange.requestMethod == "GET" -> {
                    val principal = JourneyFixtureAuthentication.principal(exchange.requestHeaders.getFirst("Authorization"))
                    if (principal?.accessTokenSessionGeneration != fixture.actor.sessionGeneration || fixture.actor.deleted) {
                        status = 401; mapOf("errorCode" to "FIXTURE_AUTH_REQUIRED")
                    } else {
                        val case = exchange.requestURI.query?.removePrefix("case=") ?: "complete"
                        if (case.startsWith("http-")) walkingHttpContractFixture(case) else walkingContractFixture(case)
                    }
                }
                path == "/__fixture/ride-control" && exchange.requestMethod == "POST" && rideEnabled -> {
                    val mode = fixture.mapper.readTree(bytes).path("mode").asText()
                    require(mode in setOf("EMPTY", "FRESH"))
                    rideMode = mode
                    if (mode == "FRESH") rideStartedAt = java.time.Instant.ofEpochSecond(fixture.clock.instant().epochSecond + 3)
                    mapOf("fixtureOnly" to true, "mode" to mode, "startAt" to (rideStartedAt?.toEpochMilli()?.div(1000.0) ?: 0.0))
                }
                path == "/__fixture/state" && exchange.requestMethod == "GET" -> fixture.state() + mapOf(
                    "syntheticRideEnabled" to rideEnabled, "syntheticAlightingApproachEnabled" to alightingEnabled,
                    "syntheticSeparationEnabled" to separationEnabled,
                    "syntheticCatalogBendEnabled" to catalogBendEnabled,
                    "syntheticVehicleRequests" to rideRequests, "syntheticRideMode" to rideMode,
                    "isolatedTtlSeconds" to fixture.isolatedTtlSeconds, "expiryRuns" to expiryRuns, "expiryCount" to expiryCount,
                    "expiryEndEnabled" to expiryEndEnabled,
                    "automaticEndAcceptedCount" to guidancePush.automaticEndAcceptedCount,
                    "automaticEndBlockedCount" to guidancePush.automaticEndBlockedCount,
                    "automaticEndUnknownCount" to guidancePush.automaticEndUnknownCount,
                    "automaticEndFailedCount" to guidancePush.automaticEndFailedCount,
                    "authenticatedRequests" to authenticatedRequests, "rejectedRequests" to rejectedRequests,
                    "validationRequests" to validationRequests, "reconciliationRequests" to reconciliationRequests, "arrivalRequests" to arrivalRequests,
                    "trapRequests" to trapRequests, "trapAuthorizationSeen" to trapAuthorizationSeen, "refreshRequests" to refreshRequests,
                    "guidancePushAcceptedCount" to (if (durable != null) durableAccepted else guidancePush.acceptedCount), "guidancePushRealProvider" to (pushClient != null),
                        "guidancePushProviderAttemptCount" to (if (durable != null) durableAttempts else guidancePush.providerAttemptCount),
                        "guidancePushProviderRejectedCount" to (if (durable != null) durableRejected else guidancePush.providerRejectedCount),
                        "guidancePushInvalidationAcceptedCount" to guidancePush.invalidationAcceptedCount,
                        "guidancePushEndAcceptedCount" to guidancePush.endAcceptedCount,
                        "guidancePushProviderSchemaAcceptedCounts" to guidancePush.providerSchemaAcceptedCounts(),
                        "guidancePushFailureCode" to guidancePush.lastFailureCode) + (database?.tx {
                        mapOf("durableFixture" to true, "durableLaneCount" to database.lanes.count(),
                            "restoredPreparationCount" to database.preparations.count(),
                            "durableReadyCount" to database.lanes.countByEndState(JourneyActivityEndState.READY),
                            "durableInFlightCount" to database.lanes.countByEndState(JourneyActivityEndState.IN_FLIGHT),
                            "durableEndAcceptedCount" to database.lanes.countByEndState(JourneyActivityEndState.ACCEPTED),
                            "durableUnknownCount" to database.lanes.countByEndState(JourneyActivityEndState.UNKNOWN),
                            "durableRejectedCount" to database.lanes.countByEndState(JourneyActivityEndState.REJECTED),
                            "durableBlockedCount" to database.lanes.countByEndState(JourneyActivityEndState.BLOCKED))
                    } ?: mapOf("durableFixture" to false))
                path == "/__fixture/push-binding" && exchange.requestMethod == "GET" -> {
                    val principal = JourneyFixtureAuthentication.principal(exchange.requestHeaders.getFirst("Authorization"))
                    if (principal == null) { rejectedRequests++; status = 401; mapOf("success" to false) }
                    else mapOf("fixtureOnly" to true, "data" to guidancePush.testBinding(requireNotNull(principal.accessTokenSessionGeneration)))
                }
                path == "/__fixture/reset" && exchange.requestMethod == "POST" -> {
                    resetAuthenticatedHarnessPlan(fixture, rideEnabled)
                    rideStartedAt = null; rideMode = "EMPTY"; rideRequests = 0
                    validationRequests = 0; arrivalRequests = 0; authenticatedRequests = 0; rejectedRequests = 0
                    reconciliationRequests = 0; reconciliationUnavailable = false
                    redirectEnabled = false; trapRequests = 0; trapAuthorizationSeen = false
                    refreshRequests = 0; consumedRefreshTokens.clear()
                    refreshUnavailable = false
                    guidancePush.reset()
                    fixture.state()
                }
                path == "/__fixture/display-route" && exchange.requestMethod == "POST" -> {
                    val variant = fixture.mapper.readTree(bytes).path("variant").asText()
                    if (variant !in setOf("long-name", "32-legs", "seoul-7016-preview") || fixture.state()["lastState"] !in setOf("NONE", "STOPPED", "EXPIRED", "CANCELLED")) {
                        status = 409; mapOf("errorCode" to "FIXTURE_DISPLAY_ROUTE_REJECTED")
                    } else {
                        if (variant == "seoul-7016-preview") fixture.useSeoul7016Preview() else fixture.useSavedGuidanceDisplayPlan(variant)
                        mapOf("fixtureOnly" to true, "variant" to variant, "legCount" to fixture.plan.route!!.path("transitLegs").size())
                    }
                }
                path == "/__fixture/reconciliation-control" && exchange.requestMethod == "POST" -> {
                    reconciliationUnavailable = fixture.mapper.readTree(bytes).path("unavailable").asBoolean()
                    mapOf("fixtureOnly" to true, "reconciliationUnavailable" to reconciliationUnavailable)
                }
                path == "/__fixture/revoke" && exchange.requestMethod == "POST" -> {
                    fixture.actor.sessionGeneration = 3; mapOf("fixtureOnly" to true, "revoked" to true)
                }
                path == "/__fixture/redirect-control" && exchange.requestMethod == "POST" -> {
                    redirectEnabled = fixture.mapper.readTree(bytes).path("enabled").asBoolean()
                    mapOf("fixtureOnly" to true, "redirectEnabled" to redirectEnabled)
                }
                path == "/__fixture/refresh-control" && exchange.requestMethod == "POST" -> {
                    refreshUnavailable = fixture.mapper.readTree(bytes).path("unavailable").asBoolean()
                    mapOf("fixtureOnly" to true, "refreshUnavailable" to refreshUnavailable)
                }
                // Exchange double only: JWT signature/type/generation and one-use rotation are real;
                // this is not the production MemberUseCase/DB refresh integration.
                path == "/api/member/auth/refresh" && exchange.requestMethod == "POST" -> {
                    refreshRequests++
                    val jwt = JourneyFixtureAuthentication.jwt
                    val presented = fixture.mapper.readTree(bytes).path("refreshToken").asText("")
                    if (refreshUnavailable) {
                        status = 503; mapOf("success" to false)
                    } else if (presented.length > 8192 || !jwt.validateToken(presented) || !jwt.isRefreshToken(presented)
                        || jwt.getMemberIdFromToken(presented) != 7L || jwt.getSessionGeneration(presented) != fixture.actor.sessionGeneration
                        || !consumedRefreshTokens.add(presented)) {
                        status = 401; mapOf("success" to false)
                    } else mapOf("success" to true, "data" to mapOf(
                        "accessToken" to jwt.createAccessToken(7, "Synthetic fixture", 2),
                        "refreshToken" to jwt.createRefreshToken(7, "Synthetic fixture", 2)))
                }
                !path.startsWith("/api/schedules/9001/automatic-journey/") -> { status = 404; mapOf("errorCode" to "FIXTURE_RESOURCE_ONLY") }
                else -> {
                    val auth = exchange.requestHeaders.getFirst("Authorization")
                    if (JourneyFixtureAuthentication.principal(auth) == null) { rejectedRequests++; status = 401; mapOf("success" to false) }
                    else if (path.endsWith("/reconcile") && reconciliationUnavailable) {
                        reconciliationRequests++; status = 503; mapOf("success" to false)
                    } else if (durable == null && path.endsWith("/live-activity/end") && exchange.requestMethod == "POST") {
                        val principal = requireNotNull(JourneyFixtureAuthentication.principal(auth))
                        val sessionId = path.removeSuffix("/live-activity/end").substringAfterLast('/')
                        mapOf("success" to true, "data" to end(sessionId, requireNotNull(principal.accessTokenSessionGeneration), fixture.mapper.readTree(bytes)))
                    } else if (path.endsWith("/stop") && exchange.requestMethod == "POST"
                        && hasLane(path.removeSuffix("/stop").substringAfterLast('/'))) {
                        // Native still ends immediately locally. The authenticated stop receipt also
                        // closes the pinned remote lane; non-pilot fixture sessions keep the usual MVC path.
                        val principal = requireNotNull(JourneyFixtureAuthentication.principal(auth))
                        val sessionId = path.removeSuffix("/stop").substringAfterLast('/')
                        mapOf("success" to true, "data" to end(sessionId, requireNotNull(principal.accessTokenSessionGeneration), fixture.mapper.readTree(bytes)))
                    } else if (durable == null && path.endsWith("/live-activity") && exchange.requestMethod == "POST") {
                        val principal = requireNotNull(JourneyFixtureAuthentication.principal(auth))
                        val sessionId = path.removeSuffix("/live-activity").substringAfterLast('/')
                        mapOf("success" to true, "data" to (durable?.update(7, 9001, requireNotNull(principal.accessTokenSessionGeneration), sessionId, fixture.mapper.readTree(bytes))
                            ?: guidancePush.update(sessionId, requireNotNull(principal.accessTokenSessionGeneration), fixture.mapper.readTree(bytes))))
                    } else if (redirectEnabled && path.endsWith("/prepare-saved")) {
                        status = 307; exchange.responseHeaders.set("Location", "http://127.0.0.1:15545/__fixture/trap")
                        mapOf("fixtureOnly" to true)
                    } else {
                        authenticatedRequests++
                        if (path.endsWith("/validate")) validationRequests++
                        if (path.endsWith("/reconcile")) reconciliationRequests++
                        val request = MockMvcRequestBuilders.request(HttpMethod.valueOf(exchange.requestMethod), exchange.requestURI)
                            .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(bytes)
                        val response = mvc.perform(request).andReturn().response
                        status = response.status
                        fixture.mapper.readTree(response.contentAsByteArray)
                    }
                }
            }
        } catch (_: Exception) { status = 400; mapOf("errorCode" to "FIXTURE_REQUEST_REJECTED") }
        val output = fixture.mapper.writeValueAsBytes(result)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, output.size.toLong()); exchange.responseBody.use { it.write(output) }; exchange.close()
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(0); trap.stop(0); executor.shutdownNow(); database?.close() })
    server.start(); trap.start()
    executor.scheduleWithFixedDelay({
        expiryRuns++; expiryCount += expiry.expireElapsedSessions()
        if (expiryEndEnabled) {
            if (durable != null) durable.dispatchTerminal() else guidancePush.endExpiredSessions()
        }
    }, 1, 1, TimeUnit.SECONDS)
    println("Authenticated journey TEST ISSUER at 127.0.0.1:15544; real push provider=${pushClient != null}; no production application/DB/transit")
    CountDownLatch(1).await()
}

@Suppress("UNUSED_PARAMETER")
fun main(args: Array<String>) { authenticatedHarnessMain() }
