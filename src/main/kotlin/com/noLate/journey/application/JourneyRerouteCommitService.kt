package com.noLate.journey.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.journey.domain.AutomaticJourneyState
import com.noLate.journey.domain.AutomaticJourneyStopCommand
import com.noLate.journey.infrastructure.AutomaticJourneyPreparationRepository
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import org.springframework.stereotype.Service
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.util.UUID

data class JourneyRerouteCommitCommand(
    val generation: Long, val routeRevision: Long, val clientOwnerId: String,
    val routeInputDigest: String, val newRouteInputJSON: String,
) {
    fun fence() = AutomaticJourneyStopCommand(generation, routeRevision, clientOwnerId, routeInputDigest)
}

data class JourneyRerouteCommitReceipt(
    val sessionId: String, val scheduleId: String, val generation: Long, val routeRevision: Long,
    val clientOwnerId: String, val routeInputDigest: String, val routeFingerprint: String,
    val routeInputJSON: String, val state: AutomaticJourneyState,
    val officialBusAdmission: JourneyBusDeviceAdmission,
    val preparedAt: java.time.Instant, val expiresAt: java.time.Instant,
    val rerouteValidated: Boolean = true,
)

/** Provider I/O stays outside the DB transaction. The final fence, proof and route swap share one commit. */
@Service
@ConditionalOnProperty(prefix = "journey.automatic", name = ["reroute-walking-enabled", "reroute-transit-enabled"], havingValue = "true")
class JourneyRerouteCommitService(
    private val transit: JourneyRerouteTransitProofService,
    private val persistence: JourneyRerouteCommitPersistence,
) {
    fun commit(member: Long, schedule: Long, login: Long, session: String, candidate: String,
        command: JourneyRerouteCommitCommand): JourneyRerouteCommitReceipt {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw BusinessException(ErrorCode.INVALID_STATE, "경로 검증은 트랜잭션 밖에서 수행해야 합니다.")
        val proof = transit.verify(member, schedule, login, session, command.fence(), candidate)
        return persistence.commit(member, schedule, login, session, candidate, command, proof)
    }
}

@Service
@ConditionalOnProperty(prefix = "journey.automatic", name = ["reroute-walking-enabled", "reroute-transit-enabled"], havingValue = "true")
class JourneyRerouteCommitPersistence(
    private val repository: AutomaticJourneyPreparationRepository,
    private val members: MemberRepository,
    private val travelPlans: ScheduleTravelPlanService,
    private val search: JourneyRerouteSearchService,
    private val walking: JourneyRerouteWalkingProofService,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun commit(member: Long, schedule: Long, login: Long, session: String, candidateId: String,
        command: JourneyRerouteCommitCommand, proof: JourneyRerouteTransitProofService.Proof): JourneyRerouteCommitReceipt {
        val actor = members.findByIdForUpdate(member)
        if (actor == null || actor.deleted || actor.sessionGeneration != login)
            throw BusinessException(ErrorCode.INVALID_TOKEN)
        val value = repository.findBySessionId(session) ?: invalid()
        if (value.memberId != member || value.scheduleId != schedule || value.loginSessionGeneration != login ||
            value.state != AutomaticJourneyState.ACTIVE || value.expiresAt <= clock.instant() ||
            repository.findFirstByMemberIdAndScheduleIdOrderByGenerationDesc(member, schedule)?.sessionId != session)
            invalid()
        value.fence(command.generation, command.routeRevision, command.clientOwnerId, command.routeInputDigest)
        val candidate = search.requireOwnedCandidateContext(member, schedule, login, session, command.fence(), candidateId)
        val walk = walking.requireProof(member, schedule, login, session, command.fence(), candidateId)
        if (proof.candidateId != candidateId || !proof.validUntil.isAfter(clock.instant()) ||
            !walk.validUntil.isAfter(clock.instant()) || proof.snapshot.fingerprint != proof.routeFingerprint ||
            JourneyRerouteCandidateSnapshotFactory(mapper).bus(candidate, walk).json != proof.snapshot.json)
            invalid()
        val saved = JourneyRouteSnapshot.from(travelPlans.getMyJourneyTravelPlanForStart(member, schedule), mapper)
        val source = value.sourceRouteFingerprint ?: value.routeFingerprint
        if (saved.fingerprint != source) invalid()
        val revision = value.routeRevision + 1
        if (revision > 9_007_199_254_740_991L) invalid()
        JourneyRerouteInputFence.validate(command.newRouteInputJSON, revision,
            proof.snapshot, candidate, mapper)
        val digest = OpaquePushIdentifier.fingerprint(command.newRouteInputJSON)
        if (value.generation >= 9_007_199_254_740_991L || value.departedAt == null) invalid()
        val now = clock.instant()
        val next = com.noLate.journey.domain.AutomaticJourneyPreparation(
            sessionId = UUID.randomUUID().toString(), memberId = member, scheduleId = schedule,
            loginSessionGeneration = login, clientOwnerId = value.clientOwnerId,
            routeInputDigest = digest, routeFingerprint = proof.routeFingerprint,
            generation = value.generation + 1, routeRevision = revision,
            parentSessionId = value.sessionId,
            sourceRouteFingerprint = source, rerouteSnapshotJson = proof.snapshot.json,
            rerouteInputJson = command.newRouteInputJSON,
            rerouteBusAdmissionJson = mapper.writeValueAsString(proof.busAdmission),
            execution = value.execution, state = AutomaticJourneyState.ACTIVE,
            sequence = 1, preparedAt = now, expiresAt = value.expiresAt,
            departedAt = value.departedAt,
        )
        value.stop(now)
        repository.saveAndFlush(value)
        repository.saveAndFlush(next)
        return JourneyRerouteCommitReceipt(next.sessionId, schedule.toString(), next.generation, next.routeRevision,
            next.clientOwnerId, digest, next.routeFingerprint, command.newRouteInputJSON,
            next.state, proof.busAdmission, next.preparedAt, next.expiresAt)
    }

    private fun invalid(): Nothing = throw BusinessException(ErrorCode.INVALID_STATE, "새 경로 검증이 만료되었거나 세션이 변경되었습니다.")
}

/** The client may choose JSON key order for its digest, but not change the provider route or its geometry. */
internal object JourneyRerouteInputFence {
    fun validate(json: String, revision: Long, snapshot: JourneyRouteSnapshot,
        candidate: JourneyRerouteSearchService.CandidateContext, mapper: ObjectMapper): JsonNode {
        if (json.toByteArray(Charsets.UTF_8).size !in 1..1_048_576) invalid()
        val input = runCatching { mapper.readTree(json) }.getOrNull() ?: invalid()
        if (!input.isObject || input.path("schemaVersion").asInt(-1) != 1 ||
            input.path("routeRevision").asLong(-1) != revision ||
            input.path("context").asText() != "SAVED_ROUTE" ||
            input.path("provider").asText() != "odsay" || input.path("source").asText() != "api" ||
            input.path("reliability").asText() != "provider_estimate" ||
            input.path("plausibility").asText() != "normal" ||
            input.path("serviceState").asText() != "UNKNOWN" || input.has("walkingSnapshot")) invalid()
        if (!coordinate(input.path("origin"), candidate.origin) ||
            !coordinate(input.path("destination"), candidate.destination)) invalid()
        val legs = input.path("legs")
        val raw = mapper.readTree(snapshot.json).path("transitLegs")
        if (!legs.isArray || legs.size() != 3 || !raw.isArray || raw.size() != 3) invalid()
        for (index in 0..2) {
            val leg = legs[index]
            val expected = raw[index]
            if (leg.path("index").asInt(-1) != index || leg.path("kind").asText() != expected.path("kind").asText() ||
                leg.path("exact").asBoolean(false) != true ||
                leg.path("geometryInputComplete").asBoolean(false) != true ||
                leg.path("geometrySource").asText() != expected.path("pathGeometrySource").asText()) invalid()
            val points = leg.path("coordinates")
            val shape = expected.path("pathCoords")
            if (!points.isArray || !shape.isArray || points.size() != shape.size() || points.size() !in 2..2000) invalid()
            for (p in 0 until points.size()) if (!coordinate(points[p], shape[p])) invalid()
            if (!coordinate(leg.path("start"), expected.path("startCoord")) ||
                !coordinate(leg.path("end"), expected.path("endCoord"))) invalid()
            val evidence = expected.path("navigationEvidence")
            if (leg.path("stopEvidenceProvider").asText() != evidence.path("provider").asText() ||
                leg.path("stopEvidenceVersion").asInt(-1) != 1) invalid()
            if (index == 1) {
                if (optionalText(leg.path("localRouteId")) != optionalText(expected.path("localRouteId")) ||
                    optionalText(leg.path("directionName")) != optionalText(evidence.path("directionName")) ||
                    leg.path("declaredStationCount").asInt(-1) != evidence.path("declaredStationCount").asInt(-1)) invalid()
                val stops = leg.path("stops")
                val expectedStops = evidence.path("stopOccurrences")
                if (!stops.isArray || !expectedStops.isArray || stops.size() != expectedStops.size() ||
                    stops.size() !in 2..64) invalid()
                for (s in 0 until stops.size()) {
                    if (stops[s].path("sourceIndex").asInt(-1) != expectedStops[s].path("sourceIndex").asInt(-2) ||
                        stops[s].path("providerSequence").asInt(-1) != expectedStops[s].path("providerSequence").asInt(-2) ||
                        optionalText(stops[s].path("name")) != optionalText(expectedStops[s].path("name")) ||
                        optionalText(stops[s].path("code")) != optionalText(expectedStops[s].path("code")) ||
                        !coordinate(stops[s].path("coordinate"), expectedStops[s].path("coord"))) invalid()
                }
            }
        }
        return input
    }

    private fun coordinate(actual: JsonNode, expected: ServerWalkingCoordinate): Boolean =
        actual.path("latitude").isNumber && actual.path("longitude").isNumber &&
            actual.path("latitude").asDouble() == expected.lat && actual.path("longitude").asDouble() == expected.lng
    private fun coordinate(actual: JsonNode, expected: JsonNode): Boolean =
        actual.path("latitude").isNumber && actual.path("longitude").isNumber &&
            expected.path("lat").isNumber && expected.path("lng").isNumber &&
            actual.path("latitude").asDouble() == expected.path("lat").asDouble() &&
            actual.path("longitude").asDouble() == expected.path("lng").asDouble()
    private fun optionalText(value: JsonNode): String? = if (value.isTextual) value.asText() else null
    private fun invalid(): Nothing = throw BusinessException(ErrorCode.INVALID_INPUT, "새 경로 입력이 서버에서 확인한 경로와 다릅니다.")
}
