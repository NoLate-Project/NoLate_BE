package com.noLate.journey.automatic

import com.noLate.journey.application.*
import com.noLate.journey.infrastructure.TmapServerPedestrianProvider
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class TmapServerPedestrianProviderTest {
    private val request = ServerWalkingRequest(ServerWalkingCoordinate(37.5665, 126.978), ServerWalkingCoordinate(37.5665, 126.9795))
    @Test fun `fixed official HTTPS destination and raw request bound server parsing with header-only artificial key`() {
        assertThat(TmapServerPedestrianProvider.ENDPOINT).isEqualTo("https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&format=json")
        SyntheticWalkingHttpFixture().use { socket ->
            val f = AutomaticJourneyFixture().also { it.useSavedGuidancePlan(mapped = true) }
            val value = ServerOwnedWalkingSnapshotFactory(f.mapper, socket.provider, f.clock).issue(f.plan, 7, 2, 1)
            assertThat(value.validatedWalks(f.plan, 7, 2, 1, f.clock.instant(), f.mapper)).hasSize(2)
            assertThat(socket.validRequest).isTrue(); assertThat(socket.calls.get()).isEqualTo(2)
        }
    }
    @Test fun `redirect status fixed and chunked limits empty and malformed UTF8 fail with no retry or trap`() {
        for ((mode, reason) in mapOf("redirect" to "PROVIDER_REDIRECT", "status" to "PROVIDER_STATUS", "partial" to "PROVIDER_STATUS", "large" to "PROVIDER_BODY_LIMIT",
            "chunked-large" to "PROVIDER_BODY_LIMIT", "empty" to "PROVIDER_EMPTY", "invalid-utf8" to "PROVIDER_UNAVAILABLE")) {
            SyntheticWalkingHttpFixture(mode).use { socket ->
                assertThatThrownBy { socket.provider.fetch(request) }.isInstanceOf(WalkingContractRejected::class.java).hasMessage(reason)
                assertThat(socket.calls.get()).isEqualTo(1); assertThat(socket.traps.get()).isZero()
            }
        }
    }
    @Test fun `total timeout includes headers and stalled streaming body and closes work`() {
        for (mode in listOf("timeout", "slow-body")) SyntheticWalkingHttpFixture(mode).use { socket ->
            val start = System.nanoTime()
            assertThatThrownBy { socket.provider.fetch(request) }.isInstanceOf(WalkingContractRejected::class.java).hasMessage("PROVIDER_TIMEOUT")
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2))
            assertThat(socket.calls.get()).isEqualTo(1)
        }
    }
    @Test fun `invalid destination key and request reject before any socket request`() {
        for (url in listOf("http://apis.openapi.sk.com/tmap/routes/pedestrian", "https://example.invalid", "http://localhost:9999"))
            assertThatThrownBy { TmapServerPedestrianProvider("synthetic", URI.create(url), Duration.ofSeconds(1)) }.isInstanceOf(IllegalArgumentException::class.java)
        SyntheticWalkingHttpFixture().use { socket ->
            for (key in listOf("", " ", "bad\r\nheader")) assertThatThrownBy {
                TmapServerPedestrianProvider(key, socket.endpoint, Duration.ofSeconds(1)).fetch(request)
            }.isInstanceOf(WalkingContractRejected::class.java)
            assertThatThrownBy { socket.provider.fetch(request.copy(origin = ServerWalkingCoordinate(Double.NaN, 0.0))) }.isInstanceOf(WalkingContractRejected::class.java)
            assertThat(socket.calls.get()).isZero()
        }
    }
    @Test fun `socket contract scenarios use real coordinator and never confer navigation readiness`() {
        for ((case, reason) in mapOf("http-complete" to "TRANSIT_ADMISSION_PENDING", "http-edited" to "SAVED_ROUTE_CHANGED",
            "http-revoked" to "OWNER_CHANGED", "http-redirect" to "PROVIDER_UNAVAILABLE", "http-large" to "PROVIDER_UNAVAILABLE", "http-timeout" to "PROVIDER_UNAVAILABLE")) {
            val result = walkingHttpContractFixture(case)
            assertThat(result["reason"]).isEqualTo(reason); assertThat(result["navigationReady"]).isEqualTo(false)
            assertThat(result["redirectTrapCalls"]).isEqualTo(0)
        }
    }
    @Test fun `two HTTP requests maximum with fail fast no queue and permit release`() {
        val entered = java.util.concurrent.CountDownLatch(2); val resume = java.util.concurrent.CountDownLatch(1)
        SyntheticWalkingHttpFixture(duringFetch = { entered.countDown(); check(resume.await(1, java.util.concurrent.TimeUnit.SECONDS)) }).use { socket ->
            val first = java.util.concurrent.CompletableFuture.supplyAsync { socket.provider.fetch(request) }
            val second = java.util.concurrent.CompletableFuture.supplyAsync { socket.provider.fetch(request) }
            try {
                check(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
                assertThatThrownBy { socket.provider.fetch(request) }.hasMessage("PROVIDER_BUSY")
                assertThat(socket.calls.get()).isEqualTo(2)
            } finally { resume.countDown() }
            first.get(2, java.util.concurrent.TimeUnit.SECONDS); second.get(2, java.util.concurrent.TimeUnit.SECONDS)
            socket.provider.fetch(request)
            assertThat(socket.calls.get()).isEqualTo(3)
        }
    }
}
