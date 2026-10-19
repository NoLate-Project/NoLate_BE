package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.SerializationFeature
import com.noLate.global.error.GlobalExceptionHandler
import com.noLate.global.security.MemberPrincipal
import com.noLate.journey.controller.AutomaticJourneyController
import com.noLate.journey.controller.JourneyBusArrivalController
import com.noLate.journey.application.JourneyBusArrivalService
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.*
import org.mockito.kotlin.*
import com.sun.net.httpserver.HttpServer
import org.springframework.core.MethodParameter
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Actual controller/service/domain with explicit synthetic authentication, only in test source. */
fun automaticJourneyFixtureMvc(fixture: AutomaticJourneyFixture, authenticated: Boolean = true,
    arrivalSource: () -> List<TransitArrivalDto> = { emptyList() },
    principalForRequest: ((NativeWebRequest) -> MemberPrincipal?)? = null,
    activityDelivery: com.noLate.journey.application.JourneyActivityDeliveryService? = null,
    rideProvider: com.noLate.transit.infrastructure.SeoulTransitArrivalClient? = null): MockMvc {
    fixture.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    val principal = MemberPrincipal(7, "fixture@example.invalid", "Synthetic fixture", accessTokenSessionGeneration = 2)
    val resolver = object : HandlerMethodArgumentResolver {
        override fun supportsParameter(parameter: MethodParameter) = parameter.parameterType == MemberPrincipal::class.java
        override fun resolveArgument(parameter: MethodParameter, container: ModelAndViewContainer?, request: NativeWebRequest, factory: WebDataBinderFactory?): Any? =
            if (principalForRequest != null) principalForRequest(request) else principal.takeIf { authenticated }
    }
    val automatic = fixture.service(profiles = "journey-isolated-fixture")
    val arrivals = mock<TransitArrivalService>()
    whenever(arrivals.getBusArrivals(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any(), anyOrNull()))
        .thenAnswer { arrivalSource() }
    val controllers = mutableListOf<Any>(AutomaticJourneyController(automatic),
        JourneyBusArrivalController(JourneyBusArrivalService(automatic, arrivals, fixture.clock)))
    activityDelivery?.let { controllers.add(com.noLate.journey.controller.JourneyActivityController(it)) }
    rideProvider?.let { controllers.add(com.noLate.journey.controller.JourneyBusRideController(
        com.noLate.journey.application.JourneyBusRideService(automatic, it, fixture.clock, true))) }
    return MockMvcBuilders.standaloneSetup(*controllers.toTypedArray())
        .setCustomArgumentResolvers(resolver)
        .setControllerAdvice(GlobalExceptionHandler())
        .setMessageConverters(MappingJackson2HttpMessageConverter(fixture.mapper))
        .build()
}

/** Never calls SpringApplication.run: no .env/env.properties/application context or real DB. */
fun main() {
    val fixture = AutomaticJourneyFixture(Clock.systemUTC())
    fixture.useMappedBusPlan()
    var mappingMode = "MAPPED"
    var transitMode = "FRESH"
    var arrivalRequests = 0
    var validationRequests = 0
    var validationMode = "NORMAL"
    val mvc = automaticJourneyFixtureMvc(fixture, arrivalSource = {
        arrivalRequests++
        val source = fixture.clock.instant().minusSeconds(if (transitMode == "STALE") 121 else 0)
        if (transitMode == "EMPTY") emptyList() else listOf(TransitArrivalDto(
            provider = "seoul-bus", kind = "BUS", routeName = "합성 버스", stationName = "합성 승차",
            arsId = "99999", providerRouteId = if (transitMode == "WRONG_ROUTE") "999998" else "999999",
            direction = if (transitMode == "WRONG_DIRECTION") "합성 서쪽 방면" else "합성 동쪽 방면",
            vehicleId = "999997", waitSeconds = 120, sourceUpdatedAt = source.toString(),
            observedAt = fixture.clock.instant().toString(), expectedAt = source.plusSeconds(120).toString(),
            freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
        ))
    })
    val executor = Executors.newSingleThreadExecutor()
    val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 15543), 16)
    var failureMode = "NORMAL"
    server.executor = executor
    server.createContext("/") { exchange ->
        var status = 200
        var bytes: ByteArray
        try {
            // The harness cannot accept user tokens or proxy arbitrary schedule/member resources.
            if (exchange.requestHeaders.containsKey("Authorization")) {
                status = 400
                bytes = fixture.mapper.writeValueAsBytes(mapOf("errorCode" to "FIXTURE_AUTH_ONLY"))
            } else {
                val requestBytes = exchange.requestBody.readNBytes(16_385)
                val path = exchange.requestURI.path
                if (requestBytes.size > 16_384) {
                    status = 413; bytes = fixture.mapper.writeValueAsBytes(mapOf("errorCode" to "FIXTURE_INPUT_LIMIT"))
                } else if (path == "/__fixture/health" && exchange.requestMethod == "GET") {
                    bytes = fixture.mapper.writeValueAsBytes(mapOf("fixtureOnly" to true, "service" to "automatic-journey", "scheduleId" to "9001"))
                } else if (path == "/__fixture/state" && exchange.requestMethod == "GET") {
                    bytes = fixture.mapper.writeValueAsBytes(fixture.state() + mapOf("arrivalRequests" to arrivalRequests, "validationRequests" to validationRequests,
                        "validationMode" to validationMode, "transitMode" to transitMode, "mappingMode" to mappingMode))
                } else if (path == "/__fixture/saved-route" && exchange.requestMethod == "GET" && mappingMode in setOf("SAVED_GUIDANCE", "SAVED_GUIDANCE_MAPPED")) {
                    bytes = fixture.mapper.writeValueAsBytes(mapOf("fixtureOnly" to true, "plan" to fixture.checkedPlan()))
                } else if (path == "/__fixture/reset" && exchange.requestMethod == "POST") {
                    fixture.reset(); failureMode = "NORMAL"; transitMode = "FRESH"; arrivalRequests = 0
                    validationRequests = 0; validationMode = "NORMAL"
                    fixture.useMappedBusPlan(); mappingMode = "MAPPED"
                    bytes = fixture.mapper.writeValueAsBytes(fixture.state())
                } else if (path == "/__fixture/mapping-control" && exchange.requestMethod == "POST") {
                    val requested = fixture.mapper.readTree(requestBytes).path("mode").asText()
                    if (requested !in setOf("MAPPED", "UNSUPPORTED", "CHANGED_ROUTE", "SAVED_GUIDANCE", "SAVED_GUIDANCE_MAPPED")) {
                        status = 400; bytes = fixture.mapper.writeValueAsBytes(mapOf("errorCode" to "INVALID_FIXTURE_MODE"))
                    } else {
                        if (requested.startsWith("SAVED_GUIDANCE")) fixture.useSavedGuidancePlan(mapped = requested == "SAVED_GUIDANCE_MAPPED") else fixture.useMappedBusPlan(requested)
                        mappingMode = requested
                        bytes = fixture.mapper.writeValueAsBytes(mapOf("fixtureOnly" to true, "mappingMode" to mappingMode))
                    }
                } else if (path == "/__fixture/lifetime-control" && exchange.requestMethod == "POST") {
                    val requested = fixture.mapper.readTree(requestBytes).path("mode").asText()
                    if (requested !in setOf("NORMAL", "UNAVAILABLE", "ACCOUNT_REVOKED", "ACCESS_REVOKED", "SERVER_STOPPED")) {
                        status = 400; bytes = fixture.mapper.writeValueAsBytes(mapOf("errorCode" to "INVALID_FIXTURE_MODE"))
                    } else {
                        validationMode = requested
                        if (requested == "ACCOUNT_REVOKED") fixture.actor.sessionGeneration = 3
                        if (requested == "ACCESS_REVOKED") fixture.accessAllowed = false
                        if (requested == "SERVER_STOPPED") fixture.rows.values.forEach { it.stop(fixture.clock.instant()) }
                        bytes = fixture.mapper.writeValueAsBytes(mapOf("fixtureOnly" to true, "validationMode" to validationMode))
                    }
                } else if (path == "/__fixture/transit-control" && exchange.requestMethod == "POST") {
                    val requested = fixture.mapper.readTree(requestBytes).path("mode").asText()
                    if (requested !in setOf("FRESH", "STALE", "WRONG_ROUTE", "WRONG_DIRECTION", "EMPTY")) {
                        status = 400; bytes = fixture.mapper.writeValueAsBytes(mapOf("errorCode" to "INVALID_FIXTURE_MODE"))
                    } else {
                        transitMode = requested
                        bytes = fixture.mapper.writeValueAsBytes(mapOf("fixtureOnly" to true, "transitMode" to transitMode))
                    }
                } else if (path == "/__fixture/control" && exchange.requestMethod == "POST") {
                    val requested = fixture.mapper.readTree(requestBytes).path("mode").asText()
                    if (requested !in setOf("NORMAL", "FAIL_BEFORE_COMMIT_ONCE", "LOSE_RESPONSE_AFTER_COMMIT_ONCE")) {
                        status = 400; bytes = fixture.mapper.writeValueAsBytes(mapOf("errorCode" to "INVALID_FIXTURE_MODE"))
                    } else {
                        failureMode = requested
                        bytes = fixture.mapper.writeValueAsBytes(mapOf("fixtureOnly" to true, "mode" to failureMode))
                    }
                } else if (!path.startsWith("/api/schedules/9001/automatic-journey")) {
                    status = 404; bytes = fixture.mapper.writeValueAsBytes(mapOf("errorCode" to "FIXTURE_RESOURCE_ONLY"))
                } else if (path.endsWith("/validate") && validationMode == "UNAVAILABLE") {
                    validationRequests++
                    status = 503; bytes = fixture.mapper.writeValueAsBytes(mapOf("success" to false, "errorCode" to "FIXTURE_UNAVAILABLE"))
                } else if (path.endsWith("/departure") && failureMode == "FAIL_BEFORE_COMMIT_ONCE") {
                    failureMode = "NORMAL"; status = 503
                    bytes = fixture.mapper.writeValueAsBytes(mapOf("success" to false, "errorCode" to "FIXTURE_RETRY"))
                } else {
                    val request = MockMvcRequestBuilders.request(HttpMethod.valueOf(exchange.requestMethod), exchange.requestURI)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBytes)
                    val result = mvc.perform(request).andReturn().response
                    if (path.endsWith("/validate")) validationRequests++
                    status = result.status; bytes = result.contentAsByteArray
                    if (path.endsWith("/departure") && status in 200..299 && failureMode == "LOSE_RESPONSE_AFTER_COMMIT_ONCE") {
                        failureMode = "NORMAL"; status = 503
                        bytes = fixture.mapper.writeValueAsBytes(mapOf("success" to false, "errorCode" to "FIXTURE_RESPONSE_LOST"))
                    }
                }
            }
        } catch (_: Exception) {
            status = 400
            bytes = fixture.mapper.writeValueAsBytes(mapOf("success" to false, "errorCode" to "FIXTURE_REQUEST_REJECTED"))
        }
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(0); executor.shutdownNow() })
    server.start()
    println("Automatic journey FIXTURE ONLY ready at http://127.0.0.1:15543 (schedule 9001; no application environment or real providers)")
    CountDownLatch(1).await()
}
