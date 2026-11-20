package com.noLate.journey.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.journey.domain.AutomaticJourneyStopCommand
import java.time.Clock
import java.time.Instant
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Cross-checks one owned reroute candidate against the official Seoul bus stop/shape catalog.
 * No route or session is changed here; a later atomic commit must recheck this exact fence. */
class JourneyRerouteTransitProofService(
    private val search: JourneyRerouteSearchService,
    private val walking: JourneyRerouteWalkingProofService,
    private val officialBus: JourneyBusDeviceAdmissionVerifier,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Proof(val candidateId: String, val routeFingerprint: String, val validUntil: Instant,
        val busAdmission: JourneyBusDeviceAdmission, val snapshot: JourneyRouteSnapshot)

    fun verify(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String,
        fence: AutomaticJourneyStopCommand, candidateId: String): Proof {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw WalkingContractRejected("AMBIENT_TRANSACTION")
        val candidate = search.requireOwnedCandidateContext(memberId, scheduleId, loginGeneration,
            sessionId, fence, candidateId)
        val walks = walking.requireProof(memberId, scheduleId, loginGeneration, sessionId, fence, candidateId)
        if (walks.candidateId != candidateId || !walks.validUntil.isAfter(clock.instant()))
            throw BusinessException(ErrorCode.INVALID_STATE)
        val snapshot = JourneyRerouteCandidateSnapshotFactory(mapper).bus(candidate, walks)
        val official = officialBus.verify(snapshot)
        val latest = search.requireOwnedCandidateContext(memberId, scheduleId, loginGeneration,
            sessionId, fence, candidateId)
        walking.requireProof(memberId, scheduleId, loginGeneration, sessionId, fence, candidateId)
        if (latest.path != candidate.path || latest.origin != candidate.origin ||
            latest.destination != candidate.destination || !walks.validUntil.isAfter(clock.instant()))
            throw BusinessException(ErrorCode.INVALID_STATE)
        return Proof(candidateId, snapshot.fingerprint, walks.validUntil, official, snapshot)
    }
}
