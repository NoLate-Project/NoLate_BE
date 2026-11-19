package com.noLate.journey.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.journey.domain.AutomaticJourneyStopCommand
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Verifies the two walks of a server-owned WALK-BUS-WALK candidate. It never
 * changes the saved plan, current session, native route or Live Activity. */
class JourneyRerouteWalkingProofService(
    private val search: JourneyRerouteSearchService,
    private val provider: ServerPedestrianProvider,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    data class Proof(val candidateId: String, val issuedAt: Instant, val validUntil: Instant,
        val walkingPaths: Map<Int, List<ServerWalkingCoordinate>>)
    private data class OwnedProof(val memberId: Long, val scheduleId: Long, val loginGeneration: Long,
        val sessionId: String, val fence: AutomaticJourneyStopCommand, val proof: Proof)
    private val proofs = ConcurrentHashMap<String, OwnedProof>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val permits = Semaphore(2)
    private val validator = ServerOwnedWalkingSnapshotFactory(mapper, provider, clock)

    override fun close() { (provider as? AutoCloseable)?.close() }

    fun verify(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String,
        fence: AutomaticJourneyStopCommand, candidateId: String): Proof {
        val candidate = search.requireOwnedCandidateContext(memberId, scheduleId, loginGeneration,
            sessionId, fence, candidateId)
        val now = clock.instant()
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw WalkingContractRejected("AMBIENT_TRANSACTION")
        if (now.isBefore(candidate.issuedAt) || !now.isBefore(candidate.issuedAt.plusSeconds(30)))
            throw WalkingContractRejected("STALE_ORIGIN")
        proofs[candidateId]?.takeIf { it.memberId == memberId && it.scheduleId == scheduleId &&
            it.loginGeneration == loginGeneration && it.sessionId == sessionId && it.fence == fence &&
            it.proof.validUntil.isAfter(now) }?.let { return it.proof.copy(
                walkingPaths = it.proof.walkingPaths.mapValues { entry -> entry.value.toList() }) }
        if (!permits.tryAcquire()) throw WalkingContractRejected("PROVIDER_BUSY")
        if (!inFlight.add(candidateId)) {
            permits.release()
            throw WalkingContractRejected("PROVIDER_BUSY")
        }
        try {
            val legs = candidate.path.path("rps")
            if (!legs.isArray || legs.size() != 3 || legs[0].path("trafficType").asInt(-1) != 3 ||
                legs[1].path("trafficType").asInt(-1) != 2 || legs[2].path("trafficType").asInt(-1) != 3)
                throw WalkingContractRejected("UNSUPPORTED_REROUTE_CANDIDATE")
            val ride = legs[1]
            val boarding = coordinate(ride, "startY", "startX")
            val alighting = coordinate(ride, "endY", "endX")
            val requests = mapOf(0 to ServerWalkingRequest(candidate.origin, boarding),
                2 to ServerWalkingRequest(alighting, candidate.destination))
            if (requests.values.any { it.origin == it.destination }) throw WalkingContractRejected("ENDPOINT_MISMATCH")
            val issuedAt = clock.instant()
            val paths = linkedMapOf<Int, List<ServerWalkingCoordinate>>()
            for ((index, request) in requests) {
                val body = try { provider.fetch(request) }
                    catch (failure: WalkingContractRejected) { throw failure }
                    catch (_: Exception) { throw WalkingContractRejected("PROVIDER_UNAVAILABLE") }
                paths[index] = validator.parse(body, request,
                    if (index == 0) 30.0 else 75.0, if (index == 0) 75.0 else 30.0)
                if (paths.values.sumOf { it.size } > 12_000 || !clock.instant().isBefore(issuedAt.plusSeconds(30)))
                    throw WalkingContractRejected("FETCH_DEADLINE")
            }
            val current = search.requireOwnedCandidateContext(memberId, scheduleId, loginGeneration,
                sessionId, fence, candidateId)
            if (current.path != candidate.path || current.origin != candidate.origin ||
                current.destination != candidate.destination) throw BusinessException(ErrorCode.INVALID_STATE)
            val validUntil = minOf(current.expiresAt, current.issuedAt.plusSeconds(30))
            if (!validUntil.isAfter(clock.instant())) throw WalkingContractRejected("EXPIRED")
            val proof = Proof(candidateId, issuedAt, validUntil, paths.mapValues { it.value.toList() })
            synchronized(proofs) {
                proofs.entries.removeIf { !it.value.proof.validUntil.isAfter(clock.instant()) }
                if (proofs.size > 512) proofs.clear()
                proofs[candidateId] = OwnedProof(memberId, scheduleId, loginGeneration, sessionId, fence, proof)
            }
            return proof
        } finally {
            inFlight.remove(candidateId)
            permits.release()
        }
    }

    fun requireProof(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String,
        fence: AutomaticJourneyStopCommand, candidateId: String): Proof {
        search.requireOwnedCandidateContext(memberId, scheduleId, loginGeneration, sessionId, fence, candidateId)
        val owned = proofs[candidateId]
        if (owned == null || owned.memberId != memberId || owned.scheduleId != scheduleId ||
            owned.loginGeneration != loginGeneration || owned.sessionId != sessionId || owned.fence != fence ||
            !owned.proof.validUntil.isAfter(clock.instant())) throw BusinessException(ErrorCode.INVALID_STATE)
        return owned.proof.copy(walkingPaths = owned.proof.walkingPaths.mapValues { it.value.toList() })
    }

    private fun coordinate(leg: JsonNode, lat: String, lng: String): ServerWalkingCoordinate {
        val y = leg.path(lat); val x = leg.path(lng)
        if (!y.isNumber || !x.isNumber || !y.asDouble().isFinite() || !x.asDouble().isFinite() ||
            y.asDouble() !in -90.0..90.0 || x.asDouble() !in -180.0..180.0)
            throw WalkingContractRejected("INVALID_COORDINATE")
        return ServerWalkingCoordinate(y.asDouble(), x.asDouble())
    }
}
