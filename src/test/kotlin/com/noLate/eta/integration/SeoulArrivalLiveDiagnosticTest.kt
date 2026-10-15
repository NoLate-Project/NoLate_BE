package com.noLate.eta.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.transit.infrastructure.TransitProviderApplicationException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.noLate.transit.infrastructure.TransitProviderWireMetrics
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Properties

/** Single public-station diagnostic, not app E2E or an accuracy verdict. Never prints URLs/keys. */
@Tag("external")
@Tag("transit-timetable-live")
@EnabledIfSystemProperty(named = "nolate.eta.live-probe", matches = "true")
class SeoulArrivalLiveDiagnosticTest {
    @Test
    fun `공개 노원역 실시간 조회 실패를 인증 설정과 공급자 응답으로 구분한다`() {
        val settings = Properties().apply {
            Files.newBufferedReader(Path.of("env.local.properties")).use(::load)
        }
        fun setting(name: String, default: String = "") = System.getenv(name) ?: settings.getProperty(name, default)
        val registry = SimpleMeterRegistry()
        var failureKind: String? = null
        var boundedApplicationError: String? = null
        val arrivals = try {
            SeoulTransitArrivalClient(
                setting("SEOUL_OPENAPI_KEY"), setting("SEOUL_SUBWAY_API_KEY"), "",
                setting("SEOUL_SUBWAY_BASE_URL", "http://swopenAPI.seoul.go.kr/api/subway"),
                "https://ws.bus.go.kr/api/rest",
                allowInsecureHttp = setting("SEOUL_ALLOW_INSECURE_HTTP") == "true",
                wireMetrics = TransitProviderWireMetrics(registry),
            ).getSubwayArrivals("노원", "4호선", null, "DOWN", 10)
        } catch (error: Exception) {
            failureKind = error.javaClass.simpleName
            // This known exception deliberately retains only bounded provider/code values.
            // All other exception messages can contain credential-bearing URLs and stay private.
            if (error is TransitProviderApplicationException) boundedApplicationError = error.message
            emptyList()
        }
        val wire = registry.find("nolate.eta.transit.provider.wire.duration").timers()
            .filter { it.count() > 0 }.map { timer -> mapOf(
                "provider" to timer.id.getTag("provider"), "operation" to timer.id.getTag("operation"),
                "outcome" to timer.id.getTag("outcome"), "count" to timer.count(),
            ) }
        println(jacksonObjectMapper().writeValueAsString(mapOf(
            "classification" to "READ_ONLY_PUBLIC_STATION_DIAGNOSTIC_NOT_E2E",
            "observedAt" to Instant.now().toString(), "station" to "노원", "line" to "4호선",
            "credentialConfigured" to (setting("SEOUL_SUBWAY_API_KEY").ifBlank { setting("SEOUL_OPENAPI_KEY") }.isNotBlank()),
            "arrivalCount" to arrivals.size, "failureKind" to failureKind,
            "publicVehicles" to arrivals.map { arrival -> mapOf(
                "trainNumber" to arrival.trainNumber, "terminal" to arrival.destinationName,
                "direction" to arrival.direction, "express" to arrival.express,
                "sourceUpdatedAt" to arrival.sourceUpdatedAt, "expectedAt" to arrival.expectedAt,
                "freshnessEvidence" to arrival.freshnessEvidence.name,
            ) },
            "boundedApplicationError" to boundedApplicationError, "wire" to wire,
            "productionDatabaseModified" to false,
        )))
        registry.close()
        assertTrue(arrivals.isNotEmpty(), "실시간 차량 정보가 없어 실제 공급자 단계는 미통과입니다. 안전한 진단값을 확인하세요.")
    }
}
