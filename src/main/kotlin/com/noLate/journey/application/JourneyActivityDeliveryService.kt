package com.noLate.journey.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.journey.domain.*
import com.noLate.journey.infrastructure.JourneyActivityLaneRepository
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.liveactivity.application.*
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Durable ownership + one END intent. Provider I/O is ALWAYS outside the committed reservation. */
@Service
class JourneyActivityDeliveryService(
    private val lanes: JourneyActivityLaneRepository,
    private val journeys: AutomaticJourneyService,
    private val members: MemberRepository,
    private val credentials: JourneyActivityCredentials,
    private val mapper: ObjectMapper,
    manager: PlatformTransactionManager,
    private val clients: ObjectProvider<LiveActivityPushClient>,
    private val routes: JourneyActivityRouteAuthority,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${journey.activity.enabled:false}") private val enabled: Boolean = false,
) {
    private val transactions = TransactionTemplate(manager)
    private val terminal = listOf(AutomaticJourneyState.EXPIRED, AutomaticJourneyState.STOPPED)
    private val page = PageRequest.of(0, 32)

    fun update(member: Long, schedule: Long, login: Long, session: String, command: JsonNode): Map<String, Any> {
        requireEnabled()
        val client = requireNotNull(clients.ifAvailable) { "JOURNEY_ACTIVITY_PROVIDER_UNAVAILABLE" }
        val lease = UUID.randomUUID().toString()
        val reserved = tx {
            lockActor(member, login)
            val binding = JourneyActivityContract.binding(command)
            val bound = journeys.validate(member, schedule, login, session, binding)
            require(bound.state == AutomaticJourneyState.ACTIVE)
            val now = clock.instant()
            val snapshot = JourneyActivityContract.parse(command, session, schedule, bound, now, mapper)
            val saved=journeys.requireActivityObservationFence(member,schedule,login,session,binding,
                command.path("contentState").path("activeLegIndex").asInt(),command.path("contentState").path("phase").asText(),
                command.path("contentState").path("schemaVersion").asInt())
            if(command.path("contentState").path("schemaVersion").asInt()==8)
                JourneyActivityContract.validateSubwaySavedCalls(command.path("contentState"),saved,mapper)
            if(command.path("contentState").path("lastConfirmedSubway").isObject)
                JourneyActivityContract.validateLastSubwaySavedCall(command.path("contentState"),saved,mapper)
            val tokenHash = JourneyActivityCredentials.fingerprint(snapshot.token)
            val fcmHash = JourneyActivityCredentials.fingerprint(snapshot.fcm)
            val proof = routes.bind(member, snapshot.fcm)
            var lane = lanes.lockSession(session)
            if (lane == null) {
                lane = JourneyActivityLane(sessionId = session, memberId = member, scheduleId = schedule,
                    loginGeneration = login, clientOwnerId = binding.clientOwnerId, routeInputDigest = binding.routeInputDigest,
                    generation = binding.generation, routeRevision = binding.routeRevision, activityId = snapshot.activityId,
                    tokenFingerprint = tokenHash, fcmFingerprint = fcmHash,
                    pushTokenId = proof.tokenId, fcmOwnershipVersion = proof.ownershipVersion, deviceFingerprint = proof.deviceFingerprint,
                    encryptedCredentials = credentials.seal(session, snapshot.fcm, snapshot.token),
                    lastTimestamp = snapshot.timestamp, contentJson = mapper.writeValueAsString(snapshot.content),
                    retentionExpiresAt = now.plusSeconds(7 * 24 * 60 * 60))
            } else {
                require(lane.memberId == member && lane.scheduleId == schedule && lane.loginGeneration == login && lane.binding() == binding)
                require(lane.endState == JourneyActivityEndState.NONE && lane.activityId == snapshot.activityId
                    && lane.tokenFingerprint == tokenHash && lane.fcmFingerprint == fcmHash && snapshot.timestamp > lane.lastTimestamp)
                require(proof == routeProof(lane))
                lane.lastTimestamp = snapshot.timestamp; lane.contentJson = mapper.writeValueAsString(snapshot.content)
            }
            // Unique token/activity constraints prevent cross-session transfer. A rotation needs a new contract.
            lanes.saveAndFlush(lane)
            if (!routes.acquire(member, proof, lease, now)) throw JourneyActivityRouteBusy()
            proof to LiveActivityPushRequest(snapshot.fcm, snapshot.token, LiveActivityPushEvent.UPDATE, snapshot.timestamp,
                snapshot.content, staleAt = snapshot.staleAt, expirationAt = minOf(now.plusSeconds(15), bound.validUntil))
        }
        val request = reserved.second
        try { client.send(request) } finally { tx { routes.release(reserved.first, lease) } }
        return mapOf("sessionId" to session, "timestamp" to request.timestamp, "providerAccepted" to true, "deviceVerified" to false)
    }

    /** Explicit stop commits authority + durable intent together; no provider result is implied. */
    fun end(member: Long, schedule: Long, login: Long, session: String, command: AutomaticJourneyStopCommand): Map<String, Any> {
        requireEnabled()
        return tx {
            lockActor(member, login)
            val lane = requireNotNull(lanes.lockSession(session))
            require(lane.memberId == member && lane.scheduleId == schedule && lane.loginGeneration == login && lane.binding() == command)
            val receipt = journeys.reconcile(member, schedule, login, session, command)
            if (receipt.state !in terminal) journeys.stop(member, schedule, login, session, command)
            queue(lane)
            mapOf("sessionId" to session, "state" to requireNotNull(lane.terminalReason),
                "endQueued" to true, "providerAccepted" to (lane.endState == JourneyActivityEndState.ACCEPTED), "deviceVerified" to false)
        }
    }

    /** Crash before reservation: READY survives. Crash after reservation: UNKNOWN, never blind replay. */
    @Scheduled(fixedDelayString = "\${journey.activity.dispatch-delay-ms:1000}")
    fun dispatchTerminal(): Int {
        if (!enabled) return 0
        val client = clients.ifAvailable ?: return 0 // Keep READY; absence is not provider acceptance.
        val now = clock.instant()
        val stale = tx { lanes.staleClaims(JourneyActivityEndState.IN_FLIGHT, now.minusSeconds(30), page) }
        for (id in stale) tx {
            lanes.lockId(id)?.takeIf { it.endState == JourneyActivityEndState.IN_FLIGHT && it.claimedAt?.let { at -> at <= now.minusSeconds(30) } == true }?.let {
                it.endState = JourneyActivityEndState.UNKNOWN; it.failureCode = "PROCESS_INTERRUPTED_ACCEPTANCE_UNKNOWN"
                lanes.saveAndFlush(it)
            }
        }
        val ids = tx { lanes.candidates(JourneyActivityEndState.READY, JourneyActivityEndState.NONE, terminal, page) }
        var accepted = 0
        for (id in ids) {
            // Read id -> actor lock -> lane lock matches API lock order. Immutable owner is rechecked.
            val owner = tx { lanes.findById(id).orElse(null)?.let { it.memberId to it.loginGeneration } } ?: continue
            val lease = UUID.randomUUID().toString()
            val reserved = try { tx {
                lockActor(owner.first, owner.second)
                val lane = lanes.lockId(id) ?: return@tx null
                if (lane.endState !in setOf(JourneyActivityEndState.NONE, JourneyActivityEndState.READY)) return@tx null
                queue(lane)
                val time = clock.instant()
                if (lane.endState != JourneyActivityEndState.READY || time.epochSecond <= lane.lastTimestamp) return@tx null
                val tokens = credentials.open(lane.sessionId, lane.encryptedCredentials)
                require(JourneyActivityCredentials.fingerprint(tokens.first) == lane.fcmFingerprint && JourneyActivityCredentials.fingerprint(tokens.second) == lane.tokenFingerprint)
                val proof = routeProof(lane)
                if (!routes.acquire(lane.memberId, proof, lease, time)) throw JourneyActivityRouteBusy()
                lane.endState = JourneyActivityEndState.IN_FLIGHT; lane.claimedAt = time; lane.lastTimestamp = time.epochSecond
                val content = content(lane.contentJson).toMutableMap().apply {
                    put("status", lane.terminalReason); put("phase", lane.terminalReason); put("quality", "UNAVAILABLE")
                    put("schemaVersion", 1); put("boardingConfirmed", false); put("rideProgress", null); put("alighting", null); put("subway",null)
                    put("instruction", "길안내 종료"); put("walkProgress", null); put("arrival", null)
                    put("arrivalStatus", "ENDED"); put("validUntil", time.epochSecond.toDouble())
                }
                lane.contentJson = mapper.writeValueAsString(content)
                lanes.saveAndFlush(lane)
                proof to LiveActivityPushRequest(tokens.first, tokens.second, LiveActivityPushEvent.END, lane.lastTimestamp,
                    content, staleAt = time, dismissalAt = time.minusSeconds(1), expirationAt = time.plusSeconds(15))
            } } catch (failure: Exception) {
                // Never overwrite another worker's reserved/accepted lane after a transaction conflict.
                // Transient database failures leave the durable intent eligible; no I/O happened.
                if (failure is BusinessException || failure is IllegalArgumentException || failure is IllegalStateException) {
                    tx { lanes.lockId(id)?.takeIf { it.endState in setOf(JourneyActivityEndState.NONE, JourneyActivityEndState.READY) }?.let {
                        it.endState = JourneyActivityEndState.BLOCKED; it.failureCode = "END_AUTHORITY_OR_CREDENTIALS_REJECTED"; lanes.saveAndFlush(it)
                    } }
                }
                null
            } ?: continue
            val request = reserved.second
            val result = try { client.send(request); JourneyActivityEndState.ACCEPTED to "NONE" }
            catch (failure: Exception) {
                when (failure) {
                    is InvalidLiveActivityRouteException -> JourneyActivityEndState.REJECTED to "APNS_ACTIVITY_TOKEN_INVALID"
                    is InvalidLiveActivityFcmTokenException -> JourneyActivityEndState.REJECTED to "FCM_INSTALLATION_INVALID"
                    is ConfirmedLiveActivityDeliveryException, is RetryableLiveActivityDeliveryException -> JourneyActivityEndState.REJECTED to "PROVIDER_CONFIRMED_REJECTION"
                    else -> JourneyActivityEndState.UNKNOWN to "PROVIDER_ACCEPTANCE_UNKNOWN"
                }
            }
            tx { lanes.lockId(id)?.takeIf { it.endState == JourneyActivityEndState.IN_FLIGHT && it.lastTimestamp == request.timestamp }?.let {
                it.endState = result.first; it.failureCode = result.second; lanes.saveAndFlush(it)
            } }
            tx { routes.release(reserved.first, lease) }
            if (result.first == JourneyActivityEndState.ACCEPTED) accepted++
        }
        return accepted
    }

    private fun queue(lane: JourneyActivityLane) {
        val receipt = journeys.reconcile(lane.memberId, lane.scheduleId, lane.loginGeneration, lane.sessionId, lane.binding())
        val now = clock.instant(); val ended = receipt.endedAt
        require(receipt.state in terminal && ended != null && ended <= now && receipt.checkedAt <= now && receipt.checkedAt >= ended)
        if (lane.endState != JourneyActivityEndState.NONE) {
            require(lane.terminalReason == receipt.state.name && lane.terminalAt == ended)
            return
        }
        lane.terminalReason = receipt.state.name; lane.terminalAt = ended; lane.endState = JourneyActivityEndState.READY
        lanes.saveAndFlush(lane)
    }
    private fun lockActor(member: Long, login: Long) {
        val actor = members.findByIdForUpdate(member)
        if (actor == null || actor.deleted || actor.sessionGeneration != login) throw BusinessException(ErrorCode.INVALID_TOKEN)
    }
    private fun requireEnabled() { if (!enabled) throw BusinessException(ErrorCode.FEATURE_DISABLED) }
    private fun routeProof(lane: JourneyActivityLane) = JourneyActivityRouteProof(lane.pushTokenId, lane.fcmOwnershipVersion, lane.deviceFingerprint, lane.fcmFingerprint)
    @Suppress("UNCHECKED_CAST") private fun content(json: String) = mapper.readValue(json, Map::class.java) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun <T> tx(action: () -> T): T = transactions.execute { action() } as T
}
