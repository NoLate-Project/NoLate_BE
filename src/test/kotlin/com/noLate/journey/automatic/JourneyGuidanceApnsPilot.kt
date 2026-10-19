package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.journey.domain.AutomaticJourneyState
import com.noLate.journey.domain.AutomaticJourneyStopCommand
import com.noLate.notification.liveactivity.application.LiveActivityPushClient
import com.noLate.notification.liveactivity.application.LiveActivityPushEvent
import com.noLate.notification.liveactivity.application.LiveActivityPushRequest
import java.time.Instant
import com.google.firebase.messaging.FirebaseMessagingException
import com.noLate.notification.liveactivity.application.InvalidLiveActivityRouteException
import com.noLate.notification.liveactivity.application.InvalidLiveActivityFcmTokenException
import com.noLate.notification.liveactivity.application.ConfirmedLiveActivityDeliveryException
import com.noLate.notification.liveactivity.application.RetryableLiveActivityDeliveryException

/** TEST SOURCE ONLY. Synchronous isolated sender; no durable production ownership or retry claims. */
class JourneyGuidanceApnsPilot(private val fixture: AutomaticJourneyFixture, private val sender: LiveActivityPushClient? = null) {
    // Intentionally not a data class: default toString must not include either token.
    private class Lane(val activity: String, val fcm: String, val update: String, var timestamp: Long,
        val binding: AutomaticJourneyStopCommand, val loginGeneration: Long, var content: Map<String, Any?>) {
        var ended = false
        var endAccepted = false
        var automaticEndBlocked = false
    }
    private val lanes = mutableMapOf<String, Lane>()
    var acceptedCount = 0; private set
    var providerAttemptCount = 0; private set
    var providerRejectedCount = 0; private set
    var invalidationAcceptedCount = 0; private set
    var endAcceptedCount = 0; private set
    var automaticEndAcceptedCount = 0; private set
    var automaticEndBlockedCount = 0; private set
    var automaticEndUnknownCount = 0; private set
    var automaticEndFailedCount = 0; private set
    var lastFailureCode = "NONE"; private set
    private val schemaAcceptances = (1..4).associate { it.toString() to 0 }.toMutableMap()
    /** Provider receipts only, not device delivery. Fixed keys; no payload, token or GPS diagnostics. */
    @Synchronized fun providerSchemaAcceptedCounts(): Map<String, Int> = schemaAcceptances.toMap()
    fun reset() { lanes.clear(); acceptedCount = 0; providerAttemptCount = 0; providerRejectedCount = 0; lastFailureCode = "NONE"
        schemaAcceptances.keys.forEach { schemaAcceptances[it] = 0 }
        invalidationAcceptedCount = 0; endAcceptedCount = 0
        automaticEndAcceptedCount = 0; automaticEndBlockedCount = 0; automaticEndUnknownCount = 0; automaticEndFailedCount = 0 }
    @Synchronized fun hasLane(sessionId: String): Boolean = lanes.containsKey(sessionId)
    /** Authenticated loopback QA only: binding metadata, never provider tokens or content/GPS. */
    @Synchronized fun testBinding(loginGeneration: Long): Map<String, Any> {
        require(lanes.size == 1)
        val (sessionId, lane) = lanes.entries.single().let { it.key to it.value }
        require(lane.loginGeneration == loginGeneration)
        val current = requireNotNull(fixture.service().current(7, 9001, loginGeneration, lane.binding.clientOwnerId))
        require(current.sessionId == sessionId && current.generation == lane.binding.generation
            && current.routeRevision == lane.binding.routeRevision && current.routeInputDigest == lane.binding.routeInputDigest)
        return mapOf("sessionId" to sessionId, "scheduleId" to "9001", "generation" to lane.binding.generation,
            "routeRevision" to lane.binding.routeRevision, "clientOwnerId" to lane.binding.clientOwnerId, "routeInputDigest" to lane.binding.routeInputDigest)
    }
    @Synchronized fun update(sessionId: String, loginGeneration: Long, command: JsonNode): Map<String, Any> {
        lastFailureCode = "BINDING_REJECTED"
        val binding = binding(command)
        val bound = fixture.service().validate(7, 9001, loginGeneration, sessionId, binding)
        require(bound.state == AutomaticJourneyState.ACTIVE)
        val now = fixture.clock.instant()
        lastFailureCode = "TIMESTAMP_REJECTED"
        val timestamp = integer(command, "timestamp")
        require(timestamp in (now.epochSecond - 15)..now.epochSecond) // Never relabel a stale retry as new.
        val activity = text(command, "activityId"); require(activity.matches(Regex("[A-Za-z0-9-]{1,128}")))
        val fcm = text(command, "fcmRegistrationToken"); require(fcm.length in 1..4096 && fcm.none { it.isWhitespace() || it.isISOControl() })
        val update = text(command, "liveActivityToken"); require(update.matches(Regex("[a-f0-9]{64,4096}")))
        lastFailureCode = "LANE_REJECTED"
        lanes[sessionId]?.let {
            // A new Activity or app registration needs a new explicitly fenced trial, not retargeting.
            require(!it.ended && it.binding == binding && it.loginGeneration == loginGeneration
                && it.activity == activity && it.fcm == fcm && it.update == update && timestamp > it.timestamp)
        }
        require(lanes.containsKey(sessionId) || lanes.size < 16) // Bounded test-only pinned ownership.
        val content = command.path("contentState"); require(content.isObject)
        lastFailureCode = "CONTENT_REJECTED"
        val allowed = setOf("schemaVersion", "sessionId", "scheduleId", "generation", "routeRevision", "routeInputDigest", "sequence",
            "status", "phase", "quality", "instruction", "activeLegIndex", "walkProgress", "rideProgress", "alighting", "observedAt", "validUntil", "boardingConfirmed", "arrivalStatus", "arrival")
        require(content.fieldNames().asSequence().all { it in allowed }) // No GPS/token/provider debugging fields.
        require(text(content, "sessionId") == sessionId && text(content, "scheduleId") == "9001")
        require(integer(content, "generation") == bound.generation && integer(content, "routeRevision") == bound.routeRevision
            && text(content, "routeInputDigest") == bound.routeInputDigest)
        require(integer(content, "sequence") in 1..9_007_199_254_740_991L && text(content, "status") == "ACTIVE")
        require(text(content, "phase") in setOf("WALKING_TO_STOP", "APPROACHING_STOP", "WAITING", "BOARDING_CANDIDATE", "UNAVAILABLE", "RIDING", "FINAL_WALK"))
        require(text(content, "quality") in setOf("GOOD", "UNCERTAIN", "UNAVAILABLE"))
        val instructions = mapOf("WALKING_TO_STOP" to "승차 정류장으로 이동 중", "APPROACHING_STOP" to "승차 정류장에 접근 중",
            "WAITING" to "정류장 대기 · 탑승 미확정", "BOARDING_CANDIDATE" to "탑승 여부 확인 필요 · 미확정", "UNAVAILABLE" to "현재 위치 확인 필요", "RIDING" to "버스로 이동 중", "FINAL_WALK" to "목적지로 도보 이동 중")
        require(text(content, "instruction") == instructions[text(content, "phase")] && integer(content, "activeLegIndex") in 0..(if (text(content,"phase") == "FINAL_WALK") 2 else 1))
        require(content.path("boardingConfirmed").isBoolean)
        if (text(content, "phase") == "RIDING") {
            val progress = content.path("rideProgress")
            require(integer(content, "schemaVersion") in 2L..3L && content.path("boardingConfirmed").asBoolean()
                && text(content, "quality") == "GOOD" && integer(content, "activeLegIndex") == 1L
                && content.path("walkProgress").let { it.isMissingNode || it.isNull }
                && content.path("arrival").let { it.isMissingNode || it.isNull }
                && progress.isNumber && progress.asDouble().isFinite() && progress.asDouble() in 0.0..1.0)
            com.noLate.journey.application.JourneyActivityContract.validateAlighting(content)
        } else if (text(content,"phase") == "FINAL_WALK") {
            com.noLate.journey.application.JourneyActivityContract.validateFinalWalk(content)
        } else require(integer(content, "schemaVersion") == 1L && !content.path("boardingConfirmed").asBoolean()
            && content.path("rideProgress").let { it.isMissingNode || it.isNull }
            && content.path("alighting").let { it.isMissingNode || it.isNull })
        lastFailureCode = "EXPIRY_REJECTED"
        val seconds = now.toEpochMilli() / 1000.0
        val expiry = content.path("validUntil"); require(expiry.isNumber && expiry.asDouble().isFinite()
            && expiry.asDouble() > 0 && expiry.asDouble() <= bound.validUntil.toEpochMilli() / 1000.0)
        val invalidation = text(content, "phase") == "UNAVAILABLE" && text(content, "quality") in setOf("UNCERTAIN", "UNAVAILABLE")
            && content.path("walkProgress").let { it.isMissingNode || it.isNull }
            && content.path("arrival").let { it.isMissingNode || it.isNull }
        require(expiry.asDouble() > seconds || invalidation) // No new expiry/observation invented for removal.
        content.get("walkProgress")?.takeUnless { it.isNull }?.let { require(it.isNumber && it.asDouble().isFinite() && it.asDouble() in 0.0..1.0) }
        lastFailureCode = "OBSERVATION_REJECTED"
        val observed = content.path("observedAt"); require(observed.isNumber && observed.asDouble().isFinite()
            && observed.asDouble() <= seconds + 1 && expiry.asDouble() <= observed.asDouble() + 15
            && observed.asDouble() >= (if (invalidation) seconds - 600 else timestamp.toDouble() - 15))
        content.get("arrivalStatus")?.takeUnless { it.isNull }?.let { require(it.isTextual && it.asText().matches(Regex("[A-Z_]{1,64}"))) }
        lastFailureCode = "ARRIVAL_REJECTED"
        content.get("arrival")?.takeUnless { it.isNull }?.let { arrival ->
            require(text(content, "quality") == "GOOD" && text(content, "phase") in setOf("WAITING", "APPROACHING_STOP") && arrival.isObject)
            require(arrival.fieldNames().asSequence().toSet() == setOf("providerRouteId", "directionLabel", "expectedAt", "sourceUpdatedAt", "validUntil"))
            require(text(arrival, "providerRouteId").matches(Regex("[1-9][0-9]{0,19}")) && text(arrival, "directionLabel").length in 1..100)
            val source = arrival.path("sourceUpdatedAt"); val expected = arrival.path("expectedAt"); val until = arrival.path("validUntil")
            require(listOf(source, expected, until).all { it.isNumber && it.asDouble().isFinite() })
            require(source.asDouble() in (seconds - 30)..seconds && expected.asDouble() > seconds
                // Match the saved-arrival contract and native parser's 120-second source TTL.
                // This is independent of the 30-second authenticated session lease and 15-second GPS TTL.
                && expected.asDouble() <= source.asDouble() + 3600
                && until.asDouble() > seconds && until.asDouble() <= minOf(expected.asDouble(), source.asDouble() + 120))
        }
        if (text(content, "quality") != "GOOD") require(content.path("walkProgress").isMissingNode || content.path("walkProgress").isNull)
        // Swift Codable state shape is preserved exactly; never invent new progress on the server.
        @Suppress("UNCHECKED_CAST")
        val state = fixture.mapper.convertValue(content, Map::class.java) as Map<String, Any?>
        val request = LiveActivityPushRequest(fcm, update, LiveActivityPushEvent.UPDATE, timestamp, state,
            staleAt = Instant.ofEpochSecond(expiry.asDouble().toLong()), expirationAt = minOf(now.plusSeconds(15), bound.validUntil))
        lastFailureCode = "PAYLOAD_BUDGET_REJECTED"
        require(fixture.mapper.writeValueAsBytes(mapOf("aps" to mapOf("timestamp" to timestamp, "event" to "update", "content-state" to state))).size <= 3800)
        // Reserve BEFORE provider I/O: ambiguous acceptance is never blindly resent.
        lanes[sessionId] = Lane(activity, fcm, update, timestamp, binding, loginGeneration, state)
        send(request)
        acceptedCount++
        if (sender != null) {
            val schema = integer(content, "schemaVersion").toString()
            schemaAcceptances[schema] = schemaAcceptances.getValue(schema) + 1
        }
        if (invalidation && expiry.asDouble() <= seconds) invalidationAcceptedCount++
        lastFailureCode = "NONE"
        return mapOf("sessionId" to sessionId, "timestamp" to timestamp, "providerAccepted" to (sender != null), "deviceVerified" to false)
    }
    /** End only a server-pinned lane. Stop is authoritative BEFORE I/O; later UPDATE cannot revive it.
     *  No persistence/blind retry on ambiguous END acceptance. A new trial is explicitly required. */
    @Synchronized fun end(sessionId: String, loginGeneration: Long, command: JsonNode): Map<String, Any> {
        lastFailureCode = "END_BINDING_REJECTED"
        require(command.isObject && command.fieldNames().asSequence().toSet() == setOf("generation", "routeRevision", "clientOwnerId", "routeInputDigest"))
        val binding = binding(command)
        return endPinned(sessionId, loginGeneration, binding, automaticExpiry = false)
    }

    /** Test-source server timer only. Requires an authoritative owned EXPIRED receipt, not client time.
     * No bearer refresh/app request/retargeting. Reserve before I/O; uncertain acceptance is not retried. */
    @Synchronized fun endExpiredSessions(): Int {
        var accepted = 0
        for ((sessionId, lane) in lanes.toMap()) {
            if (lane.ended || lane.automaticEndBlocked) continue
            val receipt = try { fixture.service().reconcile(7, 9001, lane.loginGeneration, sessionId, lane.binding) }
            catch (_: Exception) {
                lane.automaticEndBlocked = true; automaticEndBlockedCount++; lastFailureCode = "END_AUTHORITY_REJECTED"
                continue
            }
            if (receipt.state != AutomaticJourneyState.EXPIRED) continue
            val now = fixture.clock.instant()
            val endedAt = receipt.endedAt
            // Retain the terminal reason/time and ensure both the receipt and timestamp are usable.
            if (endedAt == null || endedAt > now || receipt.checkedAt > now || receipt.checkedAt < endedAt) {
                lane.automaticEndBlocked = true; automaticEndBlockedCount++; lastFailureCode = "END_TERMINAL_TIME_REJECTED"
                continue
            }
            if (now.epochSecond <= lane.timestamp) continue // Same-second order: defer, never future-date.
            try {
                endPinned(sessionId, lane.loginGeneration, lane.binding, automaticExpiry = true)
                accepted++; automaticEndAcceptedCount++
            } catch (_: Exception) {
                // Reservation survives unknown/confirmed provider rejection; no infinite timer retries.
                if (lane.ended) {
                    automaticEndFailedCount++
                    if (lastFailureCode == "PROVIDER_ACCEPTANCE_UNKNOWN") automaticEndUnknownCount++
                }
                else { lane.automaticEndBlocked = true; automaticEndBlockedCount++; lastFailureCode = "END_AUTHORITY_REJECTED" }
            }
        }
        return accepted
    }

    private fun endPinned(sessionId: String, loginGeneration: Long, binding: AutomaticJourneyStopCommand, automaticExpiry: Boolean): Map<String, Any> {
        val lane = requireNotNull(lanes[sessionId])
        require(lane.binding == binding && lane.loginGeneration == loginGeneration)
        // Historical terminal ownership is checked exactly; a newer session is never stopped.
        val current = fixture.service().reconcile(7, 9001, loginGeneration, sessionId, binding)
        require(current.sessionId == sessionId && current.generation == binding.generation && current.routeRevision == binding.routeRevision
            && current.routeInputDigest == binding.routeInputDigest && current.state in setOf(AutomaticJourneyState.ACTIVE, AutomaticJourneyState.STOPPED, AutomaticJourneyState.EXPIRED))
        if (automaticExpiry) require(current.state == AutomaticJourneyState.EXPIRED && current.endedAt != null)
        if (lane.ended) {
            require(lane.endAccepted) // Ambiguous provider failure is not successful delivery or a retry ticket.
            lastFailureCode = "NONE"
            return endReceipt(sessionId, lane, duplicate = true)
        }
        // Ending is cleanup, not an observation: do not require an unexpired GPS/route lease.
        // reconcile() and stop() still enforce account/login ownership and the pinned binding.
        val now = fixture.clock.instant()
        lastFailureCode = "END_TIMESTAMP_REJECTED"
        require(now.epochSecond > lane.timestamp) // Same-second END must wait; never send future timestamps.
        if (!automaticExpiry) fixture.service().stop(7, 9001, loginGeneration, sessionId, binding)
        lane.ended = true; lane.timestamp = now.epochSecond
        val terminalState = if (automaticExpiry) "EXPIRED" else "STOPPED"
        val terminal = lane.content.toMutableMap().apply {
            put("status", terminalState); put("phase", terminalState); put("quality", "UNAVAILABLE"); put("instruction", "길안내 종료")
            put("schemaVersion", 1); put("boardingConfirmed", false); put("rideProgress", null); put("alighting", null)
            put("walkProgress", null); put("arrival", null); put("arrivalStatus", "ENDED")
            // Terminal content doesn't pretend to have a new GPS fix.
            put("validUntil", now.epochSecond.toDouble())
        }
        lane.content = terminal
        send(LiveActivityPushRequest(lane.fcm, lane.update, LiveActivityPushEvent.END, lane.timestamp, terminal,
            staleAt = now, dismissalAt = now.minusSeconds(1), expirationAt = now.plusSeconds(15)))
        lane.endAccepted = true; endAcceptedCount++; acceptedCount++; lastFailureCode = "NONE"
        return endReceipt(sessionId, lane, duplicate = false)
    }
    private fun endReceipt(sessionId: String, lane: Lane, duplicate: Boolean): Map<String, Any> = mapOf(
        "sessionId" to sessionId, "state" to lane.content["status"]!!, "timestamp" to lane.timestamp, "duplicate" to duplicate,
        "providerAccepted" to (sender != null), "deviceVerified" to false)
    private fun binding(command: JsonNode) = AutomaticJourneyStopCommand(integer(command, "generation"), integer(command, "routeRevision"),
        text(command, "clientOwnerId"), text(command, "routeInputDigest"))
    private fun send(request: LiveActivityPushRequest) {
        if (sender != null) {
            providerAttemptCount++
            try { sender.send(request) }
            catch (failure: Exception) {
                providerRejectedCount++
                // Fixed codes only. Never return exception messages, request bodies or credential IDs.
                lastFailureCode = when (failure) {
                    is InvalidLiveActivityRouteException -> if (generateSequence(failure as Throwable?) { it.cause }
                        .any { it.message?.contains("BadEnvironmentKeyInToken", ignoreCase = true) == true })
                        "APNS_ENVIRONMENT_MISMATCH" else "APNS_ACTIVITY_TOKEN_INVALID"
                    is InvalidLiveActivityFcmTokenException -> "FCM_INSTALLATION_INVALID"
                    is ConfirmedLiveActivityDeliveryException -> when ((failure.cause as? FirebaseMessagingException)?.messagingErrorCode?.name) {
                        "THIRD_PARTY_AUTH_ERROR" -> "APNS_AUTH_ERROR"
                        "SENDER_ID_MISMATCH" -> "FCM_PROJECT_MISMATCH"
                        "INVALID_ARGUMENT" -> "PROVIDER_INVALID_ARGUMENT"
                        else -> "PROVIDER_CONFIRMED_REJECTION"
                    }
                    is RetryableLiveActivityDeliveryException -> "PROVIDER_QUOTA_REJECTED"
                    is FirebaseMessagingException -> when (failure.messagingErrorCode?.name) {
                        "THIRD_PARTY_AUTH_ERROR" -> "APNS_AUTH_ERROR"
                        "SENDER_ID_MISMATCH" -> "FCM_PROJECT_MISMATCH"
                        "INVALID_ARGUMENT" -> "PROVIDER_INVALID_ARGUMENT"
                        else -> "PROVIDER_ACCEPTANCE_UNKNOWN"
                    }
                    else -> "PROVIDER_ACCEPTANCE_UNKNOWN"
                }
                throw failure
            }
        }
    }
    private fun integer(node: JsonNode, key: String): Long = node.path(key).let { require(it.isIntegralNumber && it.canConvertToLong()); it.asLong() }
    private fun text(node: JsonNode, key: String): String = node.path(key).let { require(it.isTextual); it.asText() }
}
