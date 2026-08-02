package com.noLate.transit.infrastructure

import com.noLate.eta.resilience.EtaMonotonicTicker
import com.noLate.eta.resilience.EtaProviderBulkheadRejectedException
import com.noLate.transit.domain.TransitCityCodeNamespace
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClientResponseException

class TransitProviderOperationsTest {
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
