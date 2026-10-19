package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.notification.liveactivity.application.LiveActivityPushClient
import com.noLate.notification.liveactivity.application.LiveActivityPushEvent
import com.noLate.notification.liveactivity.application.LiveActivityPushRequest
import com.noLate.notification.liveactivity.application.LiveActivityPushResult
import com.noLate.notification.liveactivity.application.InvalidLiveActivityRouteException
import com.noLate.notification.liveactivity.application.InvalidLiveActivityFcmTokenException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock

class JourneyGuidanceApnsPilotTest {
    private fun fixture(clock: Clock? = null): AutomaticJourneyFixture = (clock?.let { AutomaticJourneyFixture(it) } ?: AutomaticJourneyFixture()).also { f ->
        f.service().prepare(7, 9001, 2, f.prepareCommand()); f.service().departure(7, 9001, 2, f.sessionId, f.event())
    }
    private fun body(f: AutomaticJourneyFixture, stamp: Long = f.clock.instant().epochSecond): ObjectNode = f.mapper.valueToTree(mapOf(
        "generation" to 1, "routeRevision" to 1, "clientOwnerId" to f.owner, "routeInputDigest" to f.digest,
        "activityId" to "test-activity-1", "fcmRegistrationToken" to "synthetic-test-registration", "liveActivityToken" to "a".repeat(64), "timestamp" to stamp,
        "contentState" to mapOf("schemaVersion" to 1, "sessionId" to f.sessionId, "scheduleId" to "9001", "generation" to 1, "routeRevision" to 1,
            "routeInputDigest" to f.digest, "sequence" to 1, "status" to "ACTIVE", "phase" to "WALKING_TO_STOP", "quality" to "GOOD",
            "instruction" to "승차 정류장으로 이동 중", "activeLegIndex" to 0, "walkProgress" to 0.2, "observedAt" to f.clock.instant().epochSecond.toDouble(),
            "validUntil" to f.clock.instant().epochSecond.toDouble() + 15, "boardingConfirmed" to false)))
    private fun endBody(f: AutomaticJourneyFixture): ObjectNode = f.mapper.valueToTree(f.stopCommand())
    private fun progressBody(f: AutomaticJourneyFixture, schema: Int): ObjectNode = body(f).also {
        val c = it.path("contentState") as ObjectNode
        c.put("schemaVersion", schema)
        when (schema) {
            2, 3 -> {
                c.put("phase", "RIDING").put("instruction", "버스로 이동 중").put("activeLegIndex", 1)
                    .put("boardingConfirmed", true).putNull("walkProgress").put("rideProgress", 0.5)
                if (schema == 3) c.putObject("alighting").put("kind", "ONE_STOP_BEFORE").put("remainingMeters", 100)
            }
            4 -> c.put("phase", "FINAL_WALK").put("instruction", "목적지로 도보 이동 중").put("activeLegIndex", 2)
                .put("boardingConfirmed", false).put("walkProgress", 0.6)
        }
    }
    @Test fun `provider schema matrix records only successful updates and terminal strips v4 progress`() {
        val clock = AutomaticJourneyMutableClock(); val f = fixture(clock)
        val calls = mutableListOf<LiveActivityPushRequest>(); val pilot = JourneyGuidanceApnsPilot(f, recording(calls))
        for (schema in 1..4) {
            pilot.update(f.sessionId, 2, progressBody(f, schema)); clock.now = clock.now.plusSeconds(2)
        }
        assertThat(pilot.providerSchemaAcceptedCounts()).containsExactlyInAnyOrderEntriesOf(mapOf("1" to 1, "2" to 1, "3" to 1, "4" to 1))
        pilot.end(f.sessionId, 2, endBody(f))
        assertThat(calls.last().contentState).containsEntry("schemaVersion", 1).containsEntry("walkProgress", null)
            .containsEntry("rideProgress", null).containsEntry("alighting", null).containsEntry("boardingConfirmed", false)
        assertThat(pilot.providerSchemaAcceptedCounts().values.sum()).isEqualTo(4)
        pilot.reset(); assertThat(pilot.providerSchemaAcceptedCounts().values.sum()).isZero()
    }
    @Test fun `fixture acceptance rejected content and provider failures never count as schema delivery`() {
        val f = fixture(); val noProvider = JourneyGuidanceApnsPilot(f)
        noProvider.update(f.sessionId, 2, progressBody(f, 4))
        assertThat(noProvider.providerSchemaAcceptedCounts().values.sum()).isZero()
        val calls = mutableListOf<LiveActivityPushRequest>(); val pilot = JourneyGuidanceApnsPilot(f, recording(calls))
        val invalid = progressBody(f, 4).also { (it.path("contentState") as ObjectNode).put("rideProgress", 0.2) }
        assertThatThrownBy { pilot.update(f.sessionId, 2, invalid) }.isInstanceOf(Exception::class.java)
        assertThat(calls).isEmpty(); assertThat(pilot.providerSchemaAcceptedCounts().values.sum()).isZero()
        val failing = JourneyGuidanceApnsPilot(f, object : LiveActivityPushClient {
            override fun send(request: LiveActivityPushRequest): LiveActivityPushResult = error("private-provider-failure")
        })
        assertThatThrownBy { failing.update(f.sessionId, 2, progressBody(f, 3)) }.isInstanceOf(Exception::class.java)
        assertThat(failing.providerSchemaAcceptedCounts().values.sum()).isZero()
    }
    private fun recording(calls: MutableList<LiveActivityPushRequest>) = object : LiveActivityPushClient {
        override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
            calls += request; return LiveActivityPushResult("synthetic-provider-acceptance")
        }
    }
    @Test fun `fixture acknowledgment cannot claim provider or device delivery`() {
        val f = fixture(); val pilot = JourneyGuidanceApnsPilot(f)
        val result = pilot.update(f.sessionId, 2, body(f))
        assertThat(result).containsEntry("providerAccepted", false).containsEntry("deviceVerified", false)
        assertThat(pilot.acceptedCount).isEqualTo(1)
        assertThat(pilot.providerAttemptCount).isZero()
        assertThat(pilot.lastFailureCode).isEqualTo("NONE")
    }
    @Test fun `same snapshot uses existing LiveActivityPushClient without start fields or token logs`() {
        val f = fixture(); val calls = mutableListOf<LiveActivityPushRequest>()
        val sender = object : LiveActivityPushClient { override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
            calls += request; return LiveActivityPushResult("synthetic-provider-acceptance")
        } }
        val pilot = JourneyGuidanceApnsPilot(f, sender)
        val result = pilot.update(f.sessionId, 2, body(f))
        assertThat(pilot.providerAttemptCount).isEqualTo(1)
        assertThat(pilot.providerRejectedCount).isZero()
        assertThat(result).containsEntry("providerAccepted", true).containsEntry("deviceVerified", false)
        val request = calls.single()
        assertThat(request.event).isEqualTo(LiveActivityPushEvent.UPDATE)
        assertThat(request.attributesType).isNull(); assertThat(request.alertTitle).isNull()
        assertThat(request.contentState).containsEntry("walkProgress", 0.2).containsEntry("boardingConfirmed", false)
        assertThat(request.toString()).doesNotContain("synthetic-test-registration", "a".repeat(64))
    }
    @Test fun `past same second or changed token cannot replay or retarget`() {
        val f = fixture(); val pilot = JourneyGuidanceApnsPilot(f)
        pilot.update(f.sessionId, 2, body(f, f.clock.instant().epochSecond - 5))
        for (changed in listOf(body(f, f.clock.instant().epochSecond - 5), body(f).put("activityId", "test-activity-2"),
            body(f).put("liveActivityToken", "b".repeat(64)), body(f).put("fcmRegistrationToken", "different-registration"))) {
            assertThatThrownBy { pilot.update(f.sessionId, 2, changed) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(pilot.acceptedCount).isEqualTo(1)
    }
    @Test fun `ambiguous provider failure reserves timestamp rather than blind replay`() {
        val f = fixture(); var calls = 0
        val pilot = JourneyGuidanceApnsPilot(f, object : LiveActivityPushClient {
            override fun send(request: LiveActivityPushRequest): LiveActivityPushResult { calls++; error("synthetic ambiguous provider error") }
        })
        assertThatThrownBy { pilot.update(f.sessionId, 2, body(f)) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { pilot.update(f.sessionId, 2, body(f)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(calls).isEqualTo(1); assertThat(pilot.acceptedCount).isZero()
        assertThat(pilot.providerAttemptCount).isEqualTo(1)
        assertThat(pilot.providerRejectedCount).isEqualTo(1)
    }
    @Test fun `future stale mismatched and GPS bearing payloads fail before provider`() {
        val f = fixture(); val now = f.clock.instant().epochSecond
        val pilot = JourneyGuidanceApnsPilot(f)
        val extra = body(f).also { (it.path("contentState") as ObjectNode).put("latitude", 37.0) }
        val expired = body(f).also { (it.path("contentState") as ObjectNode).put("validUntil", now.toDouble()) }
        val nestedPrivate = body(f).also { (it.path("contentState") as ObjectNode).putObject("arrival").put("latitude", 37.0) }
        for (changed in listOf(body(f, now + 1), body(f, now - 16), body(f).put("generation", 2), extra, expired, nestedPrivate)) {
            assertThatThrownBy { pilot.update(f.sessionId, 2, changed) }.isInstanceOf(Exception::class.java)
        }
        assertThat(pilot.acceptedCount).isZero()
        assertThat(pilot.providerAttemptCount).isZero()
        assertThat(pilot.lastFailureCode).doesNotContain("latitude", "synthetic-test-registration")
    }
    @Test fun `revocation and stopped session cannot send despite token possession`() {
        val f = fixture(); val pilot = JourneyGuidanceApnsPilot(f)
        f.actor.sessionGeneration = 3
        assertThatThrownBy { pilot.update(f.sessionId, 2, body(f)) }.isInstanceOf(Exception::class.java)
        f.actor.sessionGeneration = 2; f.service().stop(7, 9001, 2, f.sessionId, f.stopCommand())
        assertThatThrownBy { pilot.update(f.sessionId, 2, body(f)) }.isInstanceOf(Exception::class.java)
        assertThat(pilot.acceptedCount).isZero()
    }
    @Test fun `fresh saved arrival uses native 120 second TTL rather than session lease TTL`() {
        val f = fixture(); val now = f.clock.instant().epochSecond.toDouble()
        fun arrival(until: Double): ObjectNode = body(f).also {
            val content = it.path("contentState") as ObjectNode
            content.put("phase", "APPROACHING_STOP").put("instruction", "승차 정류장에 접근 중")
                .put("arrivalStatus", "WAITING_INFORMATION")
            content.putObject("arrival").put("providerRouteId", "999999").put("directionLabel", "합성 동쪽 방면")
                .put("sourceUpdatedAt", now).put("expectedAt", now + 240).put("validUntil", until)
        }
        val calls = mutableListOf<LiveActivityPushRequest>()
        val pilot = JourneyGuidanceApnsPilot(f, object : LiveActivityPushClient {
            override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
                calls += request; return LiveActivityPushResult("synthetic-provider-acceptance")
            }
        })
        assertThatThrownBy { pilot.update(f.sessionId, 2, arrival(now + 121)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(pilot.providerAttemptCount).isZero(); assertThat(pilot.lastFailureCode).isEqualTo("ARRIVAL_REJECTED")
        assertThat(pilot.update(f.sessionId, 2, arrival(now + 120))).containsEntry("providerAccepted", true)
        assertThat(calls).hasSize(1); assertThat(pilot.lastFailureCode).isEqualTo("NONE")
    }
    @Test fun `provider diagnostics classify environment and token errors without private exception text`() {
        val cases = listOf(
            InvalidLiveActivityRouteException(IllegalStateException("private-token BadEnvironmentKeyInToken")) to "APNS_ENVIRONMENT_MISMATCH",
            InvalidLiveActivityRouteException(IllegalStateException("private-token BadDeviceToken")) to "APNS_ACTIVITY_TOKEN_INVALID",
            InvalidLiveActivityFcmTokenException(IllegalStateException("private-token")) to "FCM_INSTALLATION_INVALID",
            IllegalStateException("private-token ambiguous") to "PROVIDER_ACCEPTANCE_UNKNOWN",
        )
        for ((failure, code) in cases) {
            val f = fixture()
            val pilot = JourneyGuidanceApnsPilot(f, object : LiveActivityPushClient {
                override fun send(request: LiveActivityPushRequest): LiveActivityPushResult = throw failure
            })
            assertThatThrownBy { pilot.update(f.sessionId, 2, body(f)) }.isSameAs(failure)
            assertThat(pilot.lastFailureCode).isEqualTo(code).doesNotContain("private-token")
            assertThat(pilot.providerAttemptCount).isEqualTo(1)
            assertThat(pilot.providerRejectedCount).isEqualTo(1)
            assertThat(pilot.acceptedCount).isZero()
        }
    }
    @Test fun `expired removal preserves old observation and clears every precise field`() {
        val clock = AutomaticJourneyMutableClock(); val f = fixture(clock)
        val calls = mutableListOf<LiveActivityPushRequest>(); val pilot = JourneyGuidanceApnsPilot(f, recording(calls))
        pilot.update(f.sessionId, 2, body(f))
        val removal = body(f)
        (removal.path("contentState") as ObjectNode).put("phase", "UNAVAILABLE").put("quality", "UNCERTAIN")
            .put("instruction", "현재 위치 확인 필요").putNull("walkProgress")
        clock.now = clock.now.plusSeconds(20); removal.put("timestamp", clock.instant().epochSecond)
        assertThat(pilot.update(f.sessionId, 2, removal)).containsEntry("providerAccepted", true)
        assertThat(pilot.invalidationAcceptedCount).isEqualTo(1)
        assertThat(calls.last().staleAt).isBefore(clock.now)
        assertThat(calls.last().contentState).containsEntry("observedAt", clock.now.minusSeconds(20).epochSecond.toDouble())
            .containsEntry("walkProgress", null)
        assertThat(calls.last().contentState["arrival"]).isNull()
    }
    @Test fun `expired removal cannot retain marker arrival or refresh old GPS expiry`() {
        for (variant in listOf("progress", "arrival", "quality", "expiry")) {
            val clock = AutomaticJourneyMutableClock(); val f = fixture(clock)
            val pilot = JourneyGuidanceApnsPilot(f)
            val removal = body(f); val content = removal.path("contentState") as ObjectNode
            content.put("phase", "UNAVAILABLE").put("quality", "UNCERTAIN").put("instruction", "현재 위치 확인 필요").putNull("walkProgress")
            when (variant) {
                "progress" -> content.put("walkProgress", 0.4)
                "arrival" -> content.putObject("arrival").put("expectedAt", clock.instant().epochSecond + 120)
                "quality" -> content.put("quality", "GOOD")
                "expiry" -> content.put("validUntil", clock.instant().epochSecond + 100)
            }
            clock.now = clock.now.plusSeconds(20); removal.put("timestamp", clock.instant().epochSecond)
            assertThatThrownBy { pilot.update(f.sessionId, 2, removal) }.isInstanceOf(Exception::class.java)
            assertThat(pilot.acceptedCount).isZero(); assertThat(pilot.providerAttemptCount).isZero()
        }
    }
    @Test fun `end closes authoritative session sends terminal content and blocks delayed update`() {
        val clock = AutomaticJourneyMutableClock(); val f = fixture(clock)
        val calls = mutableListOf<LiveActivityPushRequest>(); val pilot = JourneyGuidanceApnsPilot(f, recording(calls))
        pilot.update(f.sessionId, 2, body(f)); clock.now = clock.now.plusSeconds(2)
        assertThat(pilot.end(f.sessionId, 2, endBody(f))).containsEntry("state", "STOPPED").containsEntry("deviceVerified", false)
        assertThat(f.state()["lastState"]).isEqualTo("STOPPED")
        val end = calls.last()
        assertThat(end.event).isEqualTo(LiveActivityPushEvent.END)
        assertThat(end.timestamp).isGreaterThan(calls.first().timestamp)
        assertThat(end.dismissalAt).isBefore(clock.now)
        assertThat(end.contentState).containsEntry("status", "STOPPED").containsEntry("walkProgress", null).containsEntry("arrival", null)
        assertThat(end.attributesType).isNull(); assertThat(end.alertTitle).isNull()
        clock.now = clock.now.plusSeconds(5)
        assertThatThrownBy { pilot.update(f.sessionId, 2, body(f)) }.isInstanceOf(Exception::class.java)
        assertThat(pilot.end(f.sessionId, 2, endBody(f))).containsEntry("duplicate", true)
        assertThat(calls).hasSize(2); assertThat(pilot.endAcceptedCount).isEqualTo(1)
    }
    @Test fun `end requires pinned owner generation current identity and cannot retarget`() {
        val clock = AutomaticJourneyMutableClock(); val f = fixture(clock)
        val pilot = JourneyGuidanceApnsPilot(f)
        assertThatThrownBy { pilot.end(f.sessionId, 2, endBody(f)) }.isInstanceOf(Exception::class.java)
        pilot.update(f.sessionId, 2, body(f)); clock.now = clock.now.plusSeconds(2)
        for (changed in listOf(endBody(f).put("generation", 2), endBody(f).put("routeRevision", 2),
            endBody(f).put("clientOwnerId", "00000000-0000-4000-8000-000000000001"), endBody(f).put("routeInputDigest", "b".repeat(64)),
            endBody(f).put("liveActivityToken", "b".repeat(64)))) {
            assertThatThrownBy { pilot.end(f.sessionId, 2, changed) }.isInstanceOf(Exception::class.java)
        }
        assertThatThrownBy { pilot.end("00000000-0000-4000-8000-000000000001", 2, endBody(f)) }.isInstanceOf(Exception::class.java)
        f.actor.sessionGeneration = 3
        assertThatThrownBy { pilot.end(f.sessionId, 2, endBody(f)) }.isInstanceOf(Exception::class.java)
        assertThat(pilot.endAcceptedCount).isZero(); assertThat(f.state()["lastState"]).isEqualTo("ACTIVE")
    }
    @Test fun `same second end waits without future timestamp or early server stop`() {
        val clock = AutomaticJourneyMutableClock(); val f = fixture(clock); val pilot = JourneyGuidanceApnsPilot(f)
        pilot.update(f.sessionId, 2, body(f))
        assertThatThrownBy { pilot.end(f.sessionId, 2, endBody(f)) }.isInstanceOf(Exception::class.java)
        assertThat(f.state()["lastState"]).isEqualTo("ACTIVE"); assertThat(pilot.lastFailureCode).isEqualTo("END_TIMESTAMP_REJECTED")
        clock.now = clock.now.plusSeconds(1)
        assertThat(pilot.end(f.sessionId, 2, endBody(f))).containsEntry("providerAccepted", false)
    }
    @Test fun `ambiguous end acceptance cannot retry end or revive update`() {
        val clock = AutomaticJourneyMutableClock(); val f = fixture(clock); val calls = mutableListOf<LiveActivityPushRequest>()
        val pilot = JourneyGuidanceApnsPilot(f, object : LiveActivityPushClient {
            override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
                calls += request
                if (request.event == LiveActivityPushEvent.END) error("private-provider-ambiguity")
                return LiveActivityPushResult("synthetic-provider-acceptance")
            }
        })
        pilot.update(f.sessionId, 2, body(f)); clock.now = clock.now.plusSeconds(2)
        assertThatThrownBy { pilot.end(f.sessionId, 2, endBody(f)) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(pilot.lastFailureCode).isEqualTo("PROVIDER_ACCEPTANCE_UNKNOWN")
        assertThat(f.state()["lastState"]).isEqualTo("STOPPED")
        clock.now = clock.now.plusSeconds(5)
        assertThatThrownBy { pilot.end(f.sessionId, 2, endBody(f)) }.isInstanceOf(Exception::class.java)
        assertThatThrownBy { pilot.update(f.sessionId, 2, body(f)) }.isInstanceOf(Exception::class.java)
        assertThat(calls).hasSize(2); assertThat(pilot.endAcceptedCount).isZero(); assertThat(pilot.providerRejectedCount).isEqualTo(1)
    }
    @Test fun `local stop receipt still permits one remote end for the same pinned lane`() {
        val clock = AutomaticJourneyMutableClock(); val f = fixture(clock); val pilot = JourneyGuidanceApnsPilot(f)
        pilot.update(f.sessionId, 2, body(f)); f.service().stop(7, 9001, 2, f.sessionId, f.stopCommand())
        clock.now = clock.now.plusSeconds(2)
        assertThat(pilot.end(f.sessionId, 2, endBody(f))).containsEntry("state", "STOPPED")
        assertThat(pilot.endAcceptedCount).isEqualTo(1)
    }
    @Test fun `expired trial still permits pinned cleanup but never more progress`() {
        val clock = AutomaticJourneyMutableClock(); val f = fixture(clock); val pilot = JourneyGuidanceApnsPilot(f)
        pilot.update(f.sessionId, 2, body(f)); clock.now = clock.now.plusSeconds(601)
        assertThatThrownBy { pilot.update(f.sessionId, 2, body(f)) }.isInstanceOf(Exception::class.java)
        assertThat(pilot.end(f.sessionId, 2, endBody(f))).containsEntry("state", "STOPPED")
        assertThat(pilot.endAcceptedCount).isEqualTo(1)
    }
    @Test fun `authenticated pinned binding diagnostics never return provider tokens and reject revoked login`() {
        val f = fixture(); val pilot = JourneyGuidanceApnsPilot(f)
        assertThatThrownBy { pilot.testBinding(2) }.isInstanceOf(Exception::class.java)
        pilot.update(f.sessionId, 2, body(f))
        val result = pilot.testBinding(2)
        assertThat(result.keys).containsExactlyInAnyOrder("sessionId", "scheduleId", "generation", "routeRevision", "clientOwnerId", "routeInputDigest")
        assertThat(result.toString()).doesNotContain("synthetic-test-registration", "liveActivityToken", "observedAt")
        f.actor.sessionGeneration = 3
        assertThatThrownBy { pilot.testBinding(2) }.isInstanceOf(Exception::class.java)
    }

    private fun expire(f: AutomaticJourneyFixture) = com.noLate.journey.application.AutomaticJourneyExpiry(
        f.repository, f.clock, true, true, true).expireElapsedSessions()

    @Test fun `timer needs stored expired receipt and sends one END without replacing expiry reason`() {
        val clock = AutomaticJourneyMutableClock(); val f = fixture(clock); val calls = mutableListOf<LiveActivityPushRequest>()
        val pilot = JourneyGuidanceApnsPilot(f, recording(calls))
        pilot.update(f.sessionId, 2, body(f))
        clock.now = clock.now.plusSeconds(600)
        assertThat(pilot.endExpiredSessions()).isZero() // Client/server wall clock alone is not a receipt.
        assertThat(f.rows.getValue(f.sessionId).state).isEqualTo(com.noLate.journey.domain.AutomaticJourneyState.ACTIVE)
        assertThat(expire(f)).isEqualTo(1)
        val endedAt = f.rows.getValue(f.sessionId).endedAt
        assertThat(pilot.endExpiredSessions()).isEqualTo(1)
        repeat(5) { assertThat(pilot.endExpiredSessions()).isZero() }
        assertThat(f.rows.getValue(f.sessionId).state).isEqualTo(com.noLate.journey.domain.AutomaticJourneyState.EXPIRED)
        assertThat(f.rows.getValue(f.sessionId).endedAt).isEqualTo(endedAt)
        val end = calls.last()
        assertThat(end.event).isEqualTo(LiveActivityPushEvent.END)
        assertThat(end.contentState).containsEntry("status", "EXPIRED").containsEntry("phase", "EXPIRED")
            .containsEntry("walkProgress", null).containsEntry("arrival", null)
        assertThat(end.dismissalAt).isBefore(clock.now)
        assertThat(end.timestamp).isEqualTo(clock.now.epochSecond)
        assertThat(pilot.automaticEndAcceptedCount).isEqualTo(1)
        assertThat(pilot.end(f.sessionId, 2, endBody(f))).containsEntry("duplicate", true).containsEntry("state", "EXPIRED")
        assertThat(calls).hasSize(2)
        assertThat(f.departureCount.get()).isEqualTo(1)
        assertThatThrownBy { pilot.update(f.sessionId, 2, body(f)) }.isInstanceOf(Exception::class.java)
    }

    @Test fun `expired session with no pinned lane cannot invent a remote destination`() {
        val clock = AutomaticJourneyMutableClock(); val f = fixture(clock); val calls = mutableListOf<LiveActivityPushRequest>()
        val pilot = JourneyGuidanceApnsPilot(f, recording(calls))
        clock.now = clock.now.plusSeconds(600); expire(f)
        assertThat(pilot.endExpiredSessions()).isZero(); assertThat(calls).isEmpty()
        // Reconstructing a memory-only sender cannot claim recovered ownership after restart.
        assertThat(JourneyGuidanceApnsPilot(f, recording(calls)).endExpiredSessions()).isZero()
    }

    @Test fun `unknown END acceptance is reserved once and later ticks never retry or reactivate`() {
        val clock = AutomaticJourneyMutableClock(); val f = fixture(clock); val calls = mutableListOf<LiveActivityPushRequest>()
        val pilot = JourneyGuidanceApnsPilot(f, object : LiveActivityPushClient {
            override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
                calls += request
                if (request.event == LiveActivityPushEvent.END) error("private-provider-ambiguity")
                return LiveActivityPushResult("synthetic-acceptance")
            }
        })
        pilot.update(f.sessionId, 2, body(f)); clock.now = clock.now.plusSeconds(600); expire(f)
        assertThat(pilot.endExpiredSessions()).isZero()
        repeat(5) { clock.now = clock.now.plusSeconds(1); assertThat(pilot.endExpiredSessions()).isZero() }
        assertThat(calls).hasSize(2)
        assertThat(pilot.automaticEndFailedCount).isEqualTo(1); assertThat(pilot.automaticEndUnknownCount).isEqualTo(1)
        assertThat(pilot.automaticEndAcceptedCount).isZero()
        assertThat(pilot.lastFailureCode).isEqualTo("PROVIDER_ACCEPTANCE_UNKNOWN")
        assertThat(f.rows.getValue(f.sessionId).state.name).isEqualTo("EXPIRED")
        assertThatThrownBy { pilot.end(f.sessionId, 2, endBody(f)) }.isInstanceOf(Exception::class.java)
    }

    @Test fun `confirmed invalid Activity route blocks END replay without retiring FCM installation`() {
        val clock = AutomaticJourneyMutableClock(); val f = fixture(clock); val calls = mutableListOf<LiveActivityPushRequest>()
        val pilot = JourneyGuidanceApnsPilot(f, object : LiveActivityPushClient {
            override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
                calls += request
                if (request.event == LiveActivityPushEvent.END) throw InvalidLiveActivityRouteException()
                return LiveActivityPushResult("synthetic-acceptance")
            }
        })
        pilot.update(f.sessionId, 2, body(f)); clock.now = clock.now.plusSeconds(600); expire(f)
        pilot.endExpiredSessions(); pilot.endExpiredSessions()
        assertThat(calls).hasSize(2); assertThat(pilot.automaticEndFailedCount).isEqualTo(1)
        assertThat(pilot.automaticEndUnknownCount).isZero()
        assertThat(pilot.lastFailureCode).isEqualTo("APNS_ACTIVITY_TOKEN_INVALID")
        assertThat(f.rows.getValue(f.sessionId).state.name).isEqualTo("EXPIRED")
    }

    @Test fun `revoked identity and malformed terminal time cannot send despite old token possession`() {
        for (revoke in listOf(true, false)) {
            val clock = AutomaticJourneyMutableClock(); val f = fixture(clock); val calls = mutableListOf<LiveActivityPushRequest>()
            val pilot = JourneyGuidanceApnsPilot(f, recording(calls))
            pilot.update(f.sessionId, 2, body(f)); clock.now = clock.now.plusSeconds(600); expire(f)
            if (revoke) f.actor.sessionGeneration = 3 else f.rows.getValue(f.sessionId).endedAt = null
            assertThat(pilot.endExpiredSessions()).isZero(); assertThat(pilot.endExpiredSessions()).isZero()
            assertThat(pilot.automaticEndBlockedCount).isEqualTo(1); assertThat(calls).hasSize(1)
        }
    }

    @Test fun `historical expiry cleanup never stops a newer active generation`() {
        val clock = AutomaticJourneyMutableClock(); val f = fixture(clock); val calls = mutableListOf<LiveActivityPushRequest>()
        val pilot = JourneyGuidanceApnsPilot(f, recording(calls))
        pilot.update(f.sessionId, 2, body(f)); clock.now = clock.now.plusSeconds(600); expire(f)
        val newId = "00000000-0000-4000-8000-000000000001"
        val s = f.service(); s.prepare(7, 9001, 2, f.prepareCommand().copy(requestId = newId))
        s.departure(7, 9001, 2, newId, f.event().copy(generation = 2, eventKey = "$newId:2:1:AUTO_DEPARTURE"))
        assertThat(pilot.endExpiredSessions()).isEqualTo(1)
        assertThat(f.rows.getValue(newId).state.name).isEqualTo("ACTIVE")
        assertThat(f.rows.getValue(f.sessionId).state.name).isEqualTo("EXPIRED")
        assertThat(calls.last().contentState["sessionId"]).isEqualTo(f.sessionId)
    }

    @Test fun `same second terminal dispatch defers instead of future timestamp`() {
        val clock = AutomaticJourneyMutableClock(java.time.Instant.parse("2026-10-04T03:00:00.500Z")); val f = fixture(clock)
        val calls = mutableListOf<LiveActivityPushRequest>(); val pilot = JourneyGuidanceApnsPilot(f, recording(calls))
        clock.now = clock.now.plusMillis(599900)
        val last = body(f).also { (it.path("contentState") as ObjectNode).put("validUntil", f.rows.getValue(f.sessionId).expiresAt.toEpochMilli() / 1000.0) }
        pilot.update(f.sessionId, 2, last)
        clock.now = clock.now.plusMillis(100); expire(f)
        assertThat(pilot.endExpiredSessions()).isZero()
        assertThat(pilot.automaticEndBlockedCount).isZero(); assertThat(calls).hasSize(1)
        clock.now = clock.now.plusMillis(500)
        assertThat(pilot.endExpiredSessions()).isEqualTo(1)
        assertThat(calls.last().timestamp).isEqualTo(clock.now.epochSecond)
    }
}
