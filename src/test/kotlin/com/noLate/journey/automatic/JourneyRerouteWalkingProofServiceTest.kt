package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.journey.application.*
import com.noLate.journey.domain.JourneyRerouteSearchCommand
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean

class JourneyRerouteWalkingProofServiceTest {
    private val mapper = ObjectMapper()
    private val now = Instant.parse("2026-11-19T01:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val session = "d8f53664-36bd-4db4-8fde-b256f8c5ea5c"
    private val command = JourneyRerouteSearchCommand(3, 7,
        "80e3d778-b983-48d2-b9ad-5d2f987e41d3", "a".repeat(64),
        37.565, 126.990, 8.0, now)

    private fun fixture(): Pair<JourneyRerouteSearchService, AtomicBoolean> {
        val active = AtomicBoolean(true)
        val automatic = mock<AutomaticJourneyService>()
        whenever(automatic.requireActiveRerouteDestination(eq(7), eq(41), eq(2), eq(session), any()))
            .thenAnswer {
                if (!active.get()) throw BusinessException(ErrorCode.INVALID_STATE)
                JourneyRerouteDestination(37.5765, 126.9785)
            }
        val odsay = object : JourneyRerouteRouteProvider {
            override val providerId = "odsay"
            override fun searchRaw(request: TransitJourneySearchRequest) = mapper.readTree("""
                {"result":{"paths":[{"pathType":2,"totalTime":21,"rps":[
                {"trafficType":3,"duration":3},
                {"trafficType":2,"duration":15,"startX":126.9905,"startY":37.5655,
                "endX":126.978,"endY":37.576,"graph":"126.9905 37.5655|126.978 37.576",
                "passStopList":{"stations":[{},{}]},"lane":[{}]},
                {"trafficType":3,"duration":3}]}]}}
            """)
        }
        return JourneyRerouteSearchService(automatic, listOf(odsay), mapper, clock, true) to active
    }

    private fun geometry(request: ServerWalkingRequest): String = """
        {"type":"FeatureCollection","features":[{"type":"Feature",
        "properties":{"index":0},"geometry":{"type":"LineString","coordinates":[
        [${request.origin.lng},${request.origin.lat}],
        [${request.destination.lng},${request.destination.lat}]]}}]}
    """

    @Test fun `both TMAP walks are verified from server owned endpoints and bound to candidate`() {
        val (search, active) = fixture()
        val id = search.search(7, 41, 2, session, command)
            .path("result").path("paths")[0].path("candidateId").asText()
        val requests = mutableListOf<ServerWalkingRequest>()
        val provider = ServerPedestrianProvider { request -> requests.add(request); geometry(request) }
        val service = JourneyRerouteWalkingProofService(search, provider, mapper, clock)
        val proof = service.verify(7, 41, 2, session, command.fence(), id)
        assertThat(requests).containsExactly(
            ServerWalkingRequest(ServerWalkingCoordinate(37.565, 126.990), ServerWalkingCoordinate(37.5655, 126.9905)),
            ServerWalkingRequest(ServerWalkingCoordinate(37.576, 126.978), ServerWalkingCoordinate(37.5765, 126.9785)))
        assertThat(proof.walkingPaths.keys).containsExactly(0, 2)
        assertThat(service.requireProof(7, 41, 2, session, command.fence(), id).walkingPaths).hasSize(2)
        assertThatThrownBy { service.requireProof(8, 41, 2, session, command.fence(), id) }
            .isInstanceOf(BusinessException::class.java)
        active.set(false)
        assertThatThrownBy { service.requireProof(7, 41, 2, session, command.fence(), id) }
            .isInstanceOf(BusinessException::class.java)
    }

    @Test fun `invalid second walk never publishes proof`() {
        val (search, _) = fixture()
        val id = search.search(7, 41, 2, session, command)
            .path("result").path("paths")[0].path("candidateId").asText()
        var calls = 0
        val provider = ServerPedestrianProvider { request ->
            calls++
            if (calls == 2) geometry(request.copy(destination = ServerWalkingCoordinate(37.5769, 126.9785)))
            else geometry(request)
        }
        val service = JourneyRerouteWalkingProofService(search, provider, mapper, clock)
        assertThatThrownBy { service.verify(7, 41, 2, session, command.fence(), id) }
            .isInstanceOf(WalkingContractRejected::class.java)
            .extracting("reason").isEqualTo("ENDPOINT_MISMATCH")
        assertThatThrownBy { service.requireProof(7, 41, 2, session, command.fence(), id) }
            .isInstanceOf(BusinessException::class.java)
    }

    @Test fun `stopped session during TMAP fetch rejects proof`() {
        val (search, active) = fixture()
        val id = search.search(7, 41, 2, session, command)
            .path("result").path("paths")[0].path("candidateId").asText()
        val provider = ServerPedestrianProvider { request -> active.set(false); geometry(request) }
        val service = JourneyRerouteWalkingProofService(search, provider, mapper, clock)
        assertThatThrownBy { service.verify(7, 41, 2, session, command.fence(), id) }
            .isInstanceOf(BusinessException::class.java)
    }
}
