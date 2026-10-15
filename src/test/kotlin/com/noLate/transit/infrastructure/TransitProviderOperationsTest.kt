package com.noLate.transit.infrastructure

import com.noLate.eta.resilience.EtaMonotonicTicker
import com.noLate.eta.resilience.EtaProviderBulkheadRejectedException
import com.noLate.transit.domain.TransitCityCodeNamespace
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClientResponseException
import java.time.Instant

class TransitProviderOperationsTest {
    @Test
    fun `공급자 오류 코드는 고정 slot에서 보존하고 원래 예외를 그대로 반환한다`() {
        val metrics = TransitProviderWireMetrics(SimpleMeterRegistry())
        val before = Instant.now()
        val failure = TransitProviderApplicationException(TransitWireProvider.SEOUL_SUBWAY, "ERROR-337")
        val thrown = assertThrows(TransitProviderApplicationException::class.java) {
            metrics.observe(TransitWireProvider.SEOUL_SUBWAY, TransitWireOperation.ARRIVAL, List<Any>::isEmpty) {
                throw failure
            }
        }
        assertSame(failure, thrown)
        val diagnostic = metrics.failureDiagnostics().single()
        assertEquals(TransitWireProvider.SEOUL_SUBWAY, diagnostic.provider)
        assertEquals(TransitWireOperation.ARRIVAL, diagnostic.operation)
        assertEquals(TransitWireOutcome.APPLICATION_ERROR, diagnostic.outcome)
        assertEquals("ERROR-337", diagnostic.code)
        assertEquals(1L, diagnostic.failureCount)
        assertTrue(!diagnostic.observedAt.isBefore(before) && !diagnostic.observedAt.isAfter(Instant.now()))
    }

    @Test
    fun `임의 오류 문자열은 키나 URL 대신 UNKNOWN이며 metric tag를 늘리지 않는다`() {
        val registry = SimpleMeterRegistry()
        val metrics = TransitProviderWireMetrics(registry)
        val meterCount = registry.meters.size
        listOf("fixture-secret-key", "https://example.test/key/fixture-secret", "ERROR-337fixture-secret",
            "ERROR-337\nfixture-secret", "A".repeat(10_000), "").forEach { raw ->
            val failure = TransitProviderApplicationException(TransitWireProvider.SEOUL_SUBWAY, raw)
            assertEquals("UNKNOWN", failure.code)
            assertTrue(!failure.message.orEmpty().contains("fixture-secret"))
            assertThrows(TransitProviderApplicationException::class.java) {
                metrics.observe(TransitWireProvider.SEOUL_SUBWAY, TransitWireOperation.ARRIVAL, List<Any>::isEmpty) {
                    throw failure
                }
            }
        }
        assertEquals(meterCount, registry.meters.size)
        val diagnostic = metrics.failureDiagnostics().single()
        assertEquals("UNKNOWN", diagnostic.code)
        assertEquals(6L, diagnostic.failureCount)
        assertTrue(registry.meters.flatMap { it.id.tags }.none { it.key == "code" })
    }

    @Test
    fun `정상 회복은 과거 마지막 오류를 현재 실패로 바꾸거나 오류 수를 증가시키지 않는다`() {
        val metrics = TransitProviderWireMetrics(SimpleMeterRegistry())
        assertThrows(TransitProviderApplicationException::class.java) {
            metrics.observe(TransitWireProvider.SEOUL_SUBWAY, TransitWireOperation.ARRIVAL, List<Any>::isEmpty) {
                throw TransitProviderApplicationException(TransitWireProvider.SEOUL_SUBWAY, "ERROR-337")
            }
        }
        val old = metrics.failureDiagnostics()
        assertEquals(listOf("ok"), metrics.observe(TransitWireProvider.SEOUL_SUBWAY,
            TransitWireOperation.ARRIVAL, List<String>::isEmpty) { listOf("ok") })
        assertEquals(old, metrics.failureDiagnostics())
    }

    @Test
    fun `같은 slot의 마지막 코드만 교체하고 공급자 operation별 오류 수는 분리한다`() {
        val metrics = TransitProviderWireMetrics(SimpleMeterRegistry())
        for ((provider, operation, code) in listOf(
            Triple(TransitWireProvider.SEOUL_SUBWAY, TransitWireOperation.ARRIVAL, "ERROR-337"),
            Triple(TransitWireProvider.SEOUL_SUBWAY, TransitWireOperation.ARRIVAL, "ERROR-300"),
            Triple(TransitWireProvider.SEOUL_TIMETABLE, TransitWireOperation.TIMETABLE, "ERROR-337"),
        )) {
            assertThrows(TransitProviderApplicationException::class.java) {
                metrics.observe(provider, operation, List<Any>::isEmpty) {
                    throw TransitProviderApplicationException(provider, code)
                }
            }
        }
        val diagnostics = metrics.failureDiagnostics()
        assertEquals(2, diagnostics.size)
        assertEquals("ERROR-300", diagnostics[0].code)
        assertEquals(2L, diagnostics[0].failureCount)
        assertEquals("ERROR-337", diagnostics[1].code)
        assertEquals(1L, diagnostics[1].failureCount)
    }

    @Test
    fun `래핑된 application 오류와 HTTP 429도 원문 없이 코드만 진단한다`() {
        val metrics = TransitProviderWireMetrics(SimpleMeterRegistry())
        val wrapped = IllegalStateException("fixture-secret-url", TransitProviderApplicationException(
            TransitWireProvider.SEOUL_SUBWAY, " error-337 "))
        assertThrows(IllegalStateException::class.java) {
            metrics.observe(TransitWireProvider.SEOUL_SUBWAY, TransitWireOperation.ARRIVAL, List<Any>::isEmpty) { throw wrapped }
        }
        assertEquals("ERROR-337", metrics.failureDiagnostics().single().code)
        assertEquals(TransitWireOutcome.APPLICATION_ERROR, metrics.failureDiagnostics().single().outcome)
        assertThrows(RestClientResponseException::class.java) {
            metrics.observe(TransitWireProvider.SEOUL_SUBWAY, TransitWireOperation.ARRIVAL, List<Any>::isEmpty) {
                throw RestClientResponseException("fixture-secret-url", 429, "", null, null, null)
            }
        }
        assertEquals("HTTP_429", metrics.failureDiagnostics().single().code)
        assertEquals(TransitWireOutcome.RATE_LIMITED, metrics.failureDiagnostics().single().outcome)
    }

    @Test
    fun `wire telemetry는 HTTP 429를 고정 rate limited outcome으로 기록한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = TransitProviderWireMetrics(registry)

        assertThrows(RestClientResponseException::class.java) {
            metrics.observe(
                provider = TransitWireProvider.TAGO_BUS,
                operation = TransitWireOperation.ARRIVAL,
                isEmpty = List<Any>::isEmpty,
            ) {
                throw RestClientResponseException("redacted", 429, "", null, null, null)
            }
        }

        assertEquals(
            1L,
            registry.get("nolate.eta.transit.provider.wire.duration")
                .tag("provider", "tago_bus")
                .tag("operation", "arrival")
                .tag("outcome", "rate_limited")
                .timer()
                .count(),
        )
    }

    @Test
    fun `unsupported mapping telemetry는 입력 코드 대신 고정 namespace만 태그한다`() {
        val registry = SimpleMeterRegistry()
        val metrics = TransitProviderWireMetrics(registry)

        metrics.recordUnsupportedMapping(TransitCityCodeNamespace.ODSAY_CID)

        assertEquals(
            1.0,
            registry.get("nolate.eta.transit.mapping.unsupported")
                .tag("namespace", "odsay_cid")
                .counter()
                .count(),
        )
    }

    @Test
    fun `process wire quota는 초당 burst를 fail fast하고 다음 window에서 회복한다`() {
        var nowNanos = 0L
        val limiter = TransitProviderWireRateLimiter(
            seoulSubwayPerSecond = 1,
            seoulBusPerSecond = 1,
            tagoBusPerSecond = 1,
            ticker = EtaMonotonicTicker { nowNanos },
        )

        limiter.requirePermit(TransitWireProvider.TAGO_BUS)
        assertThrows(EtaProviderBulkheadRejectedException::class.java) {
            limiter.requirePermit(TransitWireProvider.TAGO_BUS)
        }

        nowNanos = 1_000_000_000L
        assertDoesNotThrow { limiter.requirePermit(TransitWireProvider.TAGO_BUS) }
    }

    @Test
    fun `credential endpoint는 HTTPS 또는 loopback 또는 명시적 insecure opt in만 허용한다`() {
        assertDoesNotThrow {
            validateTransitProviderEndpoint(
                TransitWireProvider.SEOUL_BUS,
                "https://provider.example/api",
                credentialConfigured = true,
                allowInsecureHttp = false,
            )
        }
        assertDoesNotThrow {
            validateTransitProviderEndpoint(
                TransitWireProvider.SEOUL_BUS,
                "http://127.0.0.1:8080/api",
                credentialConfigured = true,
                allowInsecureHttp = false,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            validateTransitProviderEndpoint(
                TransitWireProvider.SEOUL_BUS,
                "http://provider.example/api",
                credentialConfigured = true,
                allowInsecureHttp = false,
            )
        }
        assertDoesNotThrow {
            validateTransitProviderEndpoint(
                TransitWireProvider.SEOUL_BUS,
                "http://provider.example/api",
                credentialConfigured = true,
                allowInsecureHttp = true,
            )
        }
    }
}
