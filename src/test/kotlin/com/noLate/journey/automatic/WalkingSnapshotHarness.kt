package com.noLate.journey.automatic

import com.noLate.global.error.GlobalExceptionHandler
import com.noLate.global.security.MemberPrincipal
import com.noLate.journey.controller.JourneyWalkingSnapshotController
import com.sun.net.httpserver.HttpServer
import org.springframework.core.MethodParameter
import org.springframework.http.HttpMethod
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.*
import java.net.InetSocketAddress
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.*
import org.mockito.kotlin.*

internal fun walkingSnapshotMvc(f: WalkingSnapshotDatabaseFixture, ride: MixedWalkingRideFixture? = null,
    activity: com.noLate.journey.application.JourneyActivityDeliveryService? = null,
    subway:com.noLate.journey.application.JourneySubwayObservationService?=null): org.springframework.test.web.servlet.MockMvc {
    val automatic=f.automaticService()
    val arrivals=mock<com.noLate.transit.application.TransitArrivalService>()
    whenever(arrivals.getBusArrivals(anyOrNull(),anyOrNull(),anyOrNull(),anyOrNull(),anyOrNull(),any(),any(),anyOrNull()))
        .thenAnswer { call ->
            val base=authenticatedHarnessArrival(f.fixture.clock.instant(),true)
            if(f.fixture.plan.route?.path("id")?.asText()=="synthetic-transfer-buses")listOf(1,3)
                .filter{TransferRideFixture.ars(it,0)==call.getArgument<String?>(0)}
                .map{base.copy(arsId=TransferRideFixture.ars(it,0),providerRouteId=TransferRideFixture.route(it))}
            else listOf(base)
        }
    val controllers=mutableListOf<Any>(JourneyWalkingSnapshotController(f.service),
        com.noLate.journey.controller.AutomaticJourneyController(automatic),
        com.noLate.journey.controller.JourneyBusArrivalController(com.noLate.journey.application.JourneyBusArrivalService(automatic,arrivals,f.fixture.clock)))
    ride?.let { controllers.add(com.noLate.journey.controller.JourneyBusRideController(
        com.noLate.journey.application.JourneyBusRideService(automatic,it.provider,f.fixture.clock,true))) }
    activity?.let { controllers.add(com.noLate.journey.controller.JourneyActivityController(it)) }
    subway?.let { controllers.add(com.noLate.journey.controller.JourneySubwayObservationController(it)) }
    return MockMvcBuilders
    .standaloneSetup(*controllers.toTypedArray()).setControllerAdvice(GlobalExceptionHandler())
    .setMessageConverters(MappingJackson2HttpMessageConverter(f.fixture.mapper.copy()
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
    .setCustomArgumentResolvers(object : HandlerMethodArgumentResolver {
        override fun supportsParameter(parameter: MethodParameter) = parameter.parameterType == MemberPrincipal::class.java
        override fun resolveArgument(parameter: MethodParameter, container: ModelAndViewContainer?, request: NativeWebRequest,
            factory: WebDataBinderFactory?): Any? = JourneyFixtureAuthentication.principal(request.getHeader("Authorization"))
    }).build()
}

/** Opt-in owner-only /private/tmp H2 file. No Spring app or production credentials/resources. */
fun main() {
    val directory = Path.of(requireNotNull(System.getenv("NOLATE_WALKING_SNAPSHOT_DIR")))
    require(directory.isAbsolute && directory.parent == Path.of("/private/tmp") &&
        directory.fileName.toString().startsWith("nolate-walking-e2e-") && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(directory) && Files.getPosixFilePermissions(directory) == PosixFilePermissions.fromString("rwx------"))
    val transfer=System.getenv("NOLATE_WALKING_TRANSFER_FIXTURE")=="true"
    val extended=System.getenv("NOLATE_WALKING_EXTENDED_FIXTURE")=="true"
    val offlineActivity=System.getenv("NOLATE_WALKING_ACTIVITY_FIXTURE")=="true"
    val subwayFixture=System.getenv("NOLATE_WALKING_SUBWAY_FIXTURE")=="true"
    val officialSubwayFixture=System.getenv("NOLATE_WALKING_SUBWAY_OFFICIAL_LIVE")=="true"
    val realActivity=System.getenv("NOLATE_WALKING_REAL_APNS")=="true"
    // One real-provider fixture may exercise either the v8 subway card or the v7
    // five-leg transfer card, never both or the offline-only sender simultaneously.
    require(!realActivity || (subwayFixture && !transfer && !extended && !offlineActivity) ||
        (transfer && extended && !subwayFixture && !officialSubwayFixture && !offlineActivity))
    require(!subwayFixture || !transfer && !extended && !offlineActivity)
    require(!officialSubwayFixture || !subwayFixture && !realActivity && !transfer && !extended && !offlineActivity)
    require(!extended || transfer)
    require(!offlineActivity || transfer && extended)
    val clock = WalkingSnapshotClock()
    val provider = SyntheticWalkingHttpFixture()
    val f = WalkingSnapshotDatabaseFixture("jdbc:h2:file:${directory.resolve("walking")};MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE", clock, provider.provider,connectorWalking=true,durablePreparations=true,transferFixture=transfer,retainedNavigation=transfer||subwayFixture||officialSubwayFixture,extendedNavigation=extended,subwayFixture=subwayFixture,officialSubwayFixture=officialSubwayFixture)
    val ride=MixedWalkingRideFixture(clock,transfer)
    val activity = if(offlineActivity)MixedWalkingActivityFixture(f) else null
    val remote = if(realActivity)SubwayRealActivityFixture.fromEnvironment(f) else null
    val activityDelivery = remote?.delivery ?: activity?.delivery
    val subway=if(subwayFixture)SubwayNavigationFixture(clock)else null
    val officialSubway=if(officialSubwayFixture) OfficialSubwayNavigationFixture(
        requireNotNull(System.getenv("DATA_GO_KR_SUBWAY_PATH_API_KEY")),
        requireNotNull(System.getenv("SEOUL_SUBWAY_API_KEY")),clock) else null
    val automatic=f.automaticService()
    val mvc = walkingSnapshotMvc(f,ride,activityDelivery,
        subway?.service(f,automatic) ?: officialSubway?.service(f,automatic))
    val officialResponses = ArrayDeque<Map<String,Any?>>()
    val automaticOutcomes = ArrayDeque<Map<String,Any?>>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 15546), 8)
    val executor = Executors.newScheduledThreadPool(1); server.executor = executor
    var clockOffsetSeconds=0L
    server.createContext("/") { exchange ->
        try {
            clock.now=java.time.Instant.now().plusSeconds(clockOffsetSeconds).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            val path = exchange.requestURI.path
            val limit=if(path.startsWith("/api/schedules/9001/automatic-journey/")) 1_100_000 else 4096
            val bytes = exchange.requestBody.readNBytes(limit+1)
            val principal = JourneyFixtureAuthentication.principal(exchange.requestHeaders.getFirst("Authorization"))
            var code = 200
            val output = when {
                bytes.size > limit -> { code = 413; "{}" }
                path == "/__fixture/context" && exchange.requestMethod == "GET" -> f.fixture.mapper.writeValueAsString(mapOf(
                    "fixtureOnly" to true, "apiOrigin" to "http://127.0.0.1:15546", "memberId" to 7,
                    "routeRevision" to 1, "routeFingerprint" to com.noLate.journey.application.JourneyRouteSnapshot.from(f.fixture.plan, f.fixture.mapper).fingerprint,
                    "guidancePushRealProvider" to realActivity,"realActivityFixture" to realActivity,
                    "allowedInstallationDigest" to remote?.installationDigest,"syntheticTransferFixture" to transfer,
                    "syntheticSubwayFixture" to subwayFixture,
                    "officialLiveSubwayFixture" to officialSubwayFixture,
                    "offlineActivityFixture" to offlineActivity,
                    "extendedNavigationFixture" to extended, "accessTokenLifetimeSeconds" to if(extended)9000 else 600,
                    "accessToken" to (if(extended)JourneyFixtureAuthentication.extendedJwt else JourneyFixtureAuthentication.jwt).createAccessToken(7, "Synthetic fixture", 2)))
                principal == null || principal.accessTokenSessionGeneration != 2L -> { code = 401; "{}" }
                path == "/__fixture/state" && exchange.requestMethod == "GET" -> f.fixture.mapper.writeValueAsString(mapOf(
                    "fixtureOnly" to true, "providerCalls" to provider.calls.get(), "rows" to f.tx { f.repository.count() },
                    "syntheticMixedWalking" to true,"syntheticRideEnabled" to true,"guidancePushRealProvider" to realActivity,
                    "syntheticTransferFixture" to transfer,"syntheticVehicleRequests" to ride.requests,
                    "syntheticSubwayRequests" to (subway?.requests ?: 0),
                    "officialLiveSubwayFixture" to officialSubwayFixture,
                    "officialTrainRequests" to (officialSubway?.requests?.get() ?: 0),
                    "officialProviderSummary" to (officialSubway?.lastProviderSummary?.get() ?: emptyMap<String,Any>()),
                    "officialResponses" to synchronized(officialResponses) { officialResponses.toList() },
                    "automaticOutcomes" to synchronized(automaticOutcomes) { automaticOutcomes.toList() },
                    "officialSession" to if(officialSubwayFixture)f.tx { f.preparations.findAll()
                        .maxByOrNull { it.generation }?.let { row -> mapOf("sessionId" to row.sessionId,
                            "generation" to row.generation,"routeRevision" to row.routeRevision,
                            "clientOwnerId" to row.clientOwnerId,"routeInputDigest" to row.routeInputDigest) }} else null) + f.fixture.state() +
                    (remote?.state() ?: activity?.state() ?: emptyMap<String,Any>()))
                path == "/__fixture/ride-control" && exchange.requestMethod == "POST" -> {
                    when(f.fixture.mapper.readTree(bytes).path("mode").asText()) {
                        "FRESH" -> f.fixture.mapper.writeValueAsString(mapOf("fixtureOnly" to true,"mode" to "FRESH","startAt" to ride.start().toEpochMilli()/1000.0))
                        "SECOND_FRESH" -> if(transfer)f.fixture.mapper.writeValueAsString(mapOf("fixtureOnly" to true,"mode" to "SECOND_FRESH","startAt" to ride.startSecond().toEpochMilli()/1000.0)) else {code=400;"{}"}
                        "EMPTY" -> { ride.clear();"{}" }
                        else -> { code=400;"{}" }
                    }
                }
                path == "/__fixture/subway-control" && exchange.requestMethod == "POST" && subway!=null -> {
                    when(f.fixture.mapper.readTree(bytes).path("mode").asText()){
                        "FRESH"->f.fixture.mapper.writeValueAsString(mapOf("fixtureOnly" to true,"startAt" to subway.start().toEpochMilli()/1000.0))
                        "EMPTY"->{subway.clear();"{}"}
                        else->{code=400;"{}"}
                    }
                }
                path == "/__fixture/saved-route" && exchange.requestMethod == "GET" ->
                    f.fixture.mapper.writeValueAsString(mapOf("fixtureOnly" to true,"plan" to f.fixture.plan))
                path == "/__fixture/change" && exchange.requestMethod == "POST" -> {
                    when (f.fixture.mapper.readTree(bytes).path("mode").asText()) {
                        "revision" -> f.revision++
                        "logout" -> f.login++
                        "expire" -> { clockOffsetSeconds=301;clock.now=clock.now.plusSeconds(301) }
                        "normal" -> { f.revision = 1; f.login = 2;clockOffsetSeconds=0; clock.now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS) }
                        else -> code = 400
                    }; "{}"
                }
                path.startsWith("/api/schedules/9001/walking-snapshot") || path.startsWith("/api/schedules/9001/automatic-journey") -> {
                    val result = mvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf(exchange.requestMethod), path)
                        .header("Authorization", exchange.requestHeaders.getFirst("Authorization"))
                        .contentType("application/json").content(bytes)).andReturn().response
                    if (officialSubwayFixture && path.endsWith("/subway-observation/input")) {
                        val body = runCatching { f.fixture.mapper.readTree(result.contentAsString).path("data") }.getOrNull()
                        val entry = mapOf("at" to clock.now.toString(), "http" to result.status,
                            "sessionId" to path.substringAfter("/automatic-journey/").substringBefore('/'),
                            "status" to body?.path("status")?.asText(null),
                            "matchingTrainCount" to body?.path("matchingTrainCount")?.asInt(0),
                            "nativeInputPresent" to (body?.path("nativeInput")?.isObject == true))
                        synchronized(officialResponses) {
                            officialResponses.addLast(entry)
                            while (officialResponses.size > 80) officialResponses.removeFirst()
                        }
                    }
                    code = result.status; result.contentAsString
                }
                else -> { code = 404; "{}" }
            }.toByteArray()
            if (path.startsWith("/api/schedules/9001/automatic-journey")) {
                val responseCode = runCatching { f.fixture.mapper.readTree(output)
                    .path("code").asText(null) }.getOrNull()
                val action = if (path.endsWith("/automatic-journey")) "prepare" else
                    path.substringAfter("/automatic-journey/").substringAfter('/', "prepare")
                synchronized(automaticOutcomes) {
                    automaticOutcomes.addLast(mapOf("action" to action, "http" to code,
                        "code" to responseCode, "requestBytes" to bytes.size,
                        "authenticated" to (principal != null)))
                    while (automaticOutcomes.size > 30) automaticOutcomes.removeFirst()
                }
            }
            exchange.responseHeaders.set("Content-Type", "application/json"); exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(code, output.size.toLong()); exchange.responseBody.write(output)
        } catch (failure: Exception) {
            if (exchange.requestURI.path.startsWith("/api/schedules/9001/automatic-journey"))
                println("HARNESS_AUTOMATIC_EXCEPTION type=${failure.javaClass.simpleName}")
            runCatching { exchange.sendResponseHeaders(500, -1) }
        }
        finally { exchange.close() }
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(0); executor.shutdownNow(); remote?.close(); officialSubway?.close(); f.close(); provider.close() })
    activityDelivery?.let { delivery -> executor.scheduleWithFixedDelay({
        clock.now=java.time.Instant.now().plusSeconds(clockOffsetSeconds).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        runCatching { f.tx { com.noLate.journey.application.AutomaticJourneyExpiry(f.preparations,clock,true,true,true).expireElapsedSessions() } }
        runCatching { delivery.dispatchTerminal() }
    },1,1,TimeUnit.SECONDS) }
    server.start(); println("WALKING_SNAPSHOT_FIXTURE_READY loopback=15546 realProvider=$realActivity")
    CountDownLatch(1).await()
}
