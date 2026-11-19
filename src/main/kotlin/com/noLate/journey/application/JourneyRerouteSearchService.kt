package com.noLate.journey.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.journey.domain.JourneyRerouteFixPolicy
import com.noLate.journey.domain.JourneyRerouteSearchCommand
import com.noLate.journey.domain.AutomaticJourneyStopCommand
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Candidate discovery only. A separate versioned commit must replace the active route. */
@Service
class JourneyRerouteSearchService(
    private val automatic: AutomaticJourneyService,
    private val providers: List<JourneyRerouteRouteProvider> = emptyList(),
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${journey.automatic.reroute-search-enabled:false}") private val enabled: Boolean = false,
) {
    private val lastSearch = ConcurrentHashMap<String, Instant>()
    private data class OwnedCandidate(
        val memberId: Long, val scheduleId: Long, val loginGeneration: Long,
        val sessionId: String, val generation: Long, val routeRevision: Long,
        val clientOwnerId: String, val routeInputDigest: String,
        val expiresAt: Instant, val path: JsonNode,
    )
    private val candidatesById = ConcurrentHashMap<String, OwnedCandidate>()

    fun search(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String,
        command: JourneyRerouteSearchCommand): JsonNode {
        if (!enabled) throw BusinessException(ErrorCode.FEATURE_DISABLED)
        val now = clock.instant()
        JourneyRerouteFixPolicy.requireFresh(command, now)
        val destination = automatic.requireActiveRerouteDestination(memberId, scheduleId, loginGeneration,
            sessionId, command.fence())
        val provider = providers.singleOrNull { it.providerId.equals("odsay", ignoreCase = true) }
            ?: throw BusinessException(ErrorCode.FEATURE_DISABLED)
        synchronized(lastSearch) {
            lastSearch.entries.removeIf { it.value.isBefore(now.minusSeconds(60)) }
            if (lastSearch[sessionId]?.isAfter(now.minusSeconds(20)) == true)
                throw BusinessException(ErrorCode.INVALID_STATE, "새 경로를 잠시 후 다시 확인해 주세요.")
            lastSearch[sessionId] = now
        }
        val request = TransitJourneySearchRequest(command.latitude, command.longitude,
            destination.latitude, destination.longitude, now, 1_440)
        val raw = try { provider.searchRaw(request) } catch (error: Exception) {
            lastSearch.remove(sessionId, now)
            throw error
        }
        val paths = raw.path("result").path("paths")
        if (!paths.isArray) throw BusinessException(ErrorCode.INVALID_STATE, "새 경로를 확인하지 못했습니다.")
        val candidates = paths.filter(::hasNavigableTransitEvidence).take(3)
        if (candidates.isEmpty()) throw BusinessException(ErrorCode.INVALID_STATE, "새 경로를 확인하지 못했습니다.")
        // The provider call runs without a database lock; close a stop/account-switch race
        // before making any candidate selectable by an active session.
        automatic.requireActiveRerouteDestination(memberId, scheduleId, loginGeneration, sessionId, command.fence())
        val ownedPaths = synchronized(candidatesById) {
            candidatesById.entries.removeIf { !it.value.expiresAt.isAfter(now) ||
                (it.value.memberId == memberId && it.value.sessionId == sessionId) }
            if (candidatesById.size > 512) candidatesById.clear() // Fail closed under bounded memory pressure.
            candidates.map { path ->
                val id = UUID.randomUUID().toString()
                candidatesById[id] = OwnedCandidate(memberId, scheduleId, loginGeneration, sessionId,
                    command.generation, command.routeRevision, command.clientOwnerId, command.routeInputDigest,
                    now.plusSeconds(120), path.deepCopy<JsonNode>())
                path.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply { put("candidateId", id) }
            }
        }
        val response = mapper.createObjectNode().apply {
            put("provider", "odsay")
            put("sessionId", sessionId)
            put("generation", command.generation)
            put("routeRevision", command.routeRevision)
            put("routeInputDigest", command.routeInputDigest)
            put("observedAt", command.observedAt.toString())
            set<JsonNode>("result", mapper.createObjectNode().apply {
                set<JsonNode>("paths", mapper.createArrayNode().apply { ownedPaths.forEach { add(it) } })
            })
        }
        if (mapper.writeValueAsBytes(response).size > 1_048_576)
            throw BusinessException(ErrorCode.INVALID_STATE, "새 경로 응답이 너무 큽니다.")
        return response
    }

    /** A later commit must use this server-owned path, never a path echoed from the device. */
    fun requireOwnedCandidate(memberId: Long, scheduleId: Long, loginGeneration: Long,
        sessionId: String, fence: AutomaticJourneyStopCommand, candidateId: String): JsonNode {
        if (!enabled || runCatching { UUID.fromString(candidateId) }.isFailure)
            throw BusinessException(ErrorCode.INVALID_INPUT)
        val now = clock.instant()
        val candidate = candidatesById[candidateId]
            ?: throw BusinessException(ErrorCode.INVALID_STATE, "새 경로 후보가 만료되었습니다.")
        if (!candidate.expiresAt.isAfter(now)) {
            candidatesById.remove(candidateId, candidate)
            throw BusinessException(ErrorCode.INVALID_STATE, "새 경로 후보가 만료되었습니다.")
        }
        if (candidate.memberId != memberId ||
            candidate.scheduleId != scheduleId || candidate.loginGeneration != loginGeneration ||
            candidate.sessionId != sessionId || candidate.generation != fence.generation ||
            candidate.routeRevision != fence.routeRevision || candidate.clientOwnerId != fence.clientOwnerId ||
            candidate.routeInputDigest != fence.routeInputDigest)
            throw BusinessException(ErrorCode.INVALID_STATE, "새 경로 후보가 변경되었습니다.")
        automatic.requireActiveRerouteDestination(memberId, scheduleId, loginGeneration, sessionId, fence)
        return candidate.path.deepCopy<JsonNode>()
    }

    private fun hasNavigableTransitEvidence(path: JsonNode): Boolean {
        if (path.path("pathType").asInt(-1) != 2 ||
            path.path("totalTime").asDouble(-1.0) !in 0.01..1_440.0) return false
        val legs = path.path("rps")
        if (!legs.isArray || legs.size() !in 2..64) return false
        var rides = 0
        for (leg in legs) {
            when (leg.path("trafficType").asInt(-1)) {
                3 -> if (leg.path("duration").asDouble(-1.0) < 0.0) return false
                1, 2 -> {
                    rides++
                    val graph = leg.path("graph")
                    val stops = leg.path("passStopList").path("stations")
                    val lane = leg.path("lane")
                    if (leg.path("duration").asDouble(-1.0) <= 0.0 ||
                        listOf("startX", "startY", "endX", "endY").any { !leg.path(it).isNumber } ||
                        !graph.isTextual || graph.asText().length !in 7..100_000 ||
                        !stops.isArray || stops.size() !in 2..500 ||
                        !lane.isArray || lane.isEmpty) return false
                }
                else -> return false
            }
        }
        return rides > 0
    }
}

interface JourneyRerouteRouteProvider {
    val providerId: String
    fun searchRaw(request: TransitJourneySearchRequest): JsonNode
}
