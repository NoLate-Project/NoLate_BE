package com.noLate.journey.automatic

import com.noLate.notification.liveactivity.application.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant

internal class BoundedJourneyPushTestClientTest {
    private val token = "synthetic-installation-not-a-token"
    private val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun request(event: LiveActivityPushEvent, target: String = token) = LiveActivityPushRequest(
        target, "synthetic-activity-not-a-token", event, 1, emptyMap(), expirationAt = Instant.ofEpochSecond(2))

    @Test fun `reserves independent update and end limits and never sends start`() {
        var calls = 0
        val delegate = object : LiveActivityPushClient { override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
            calls++; return LiveActivityPushResult("synthetic-receipt")
        } }
        val client = BoundedJourneyPushTestClient(delegate, digest, 2, 1, 1200) { 100 }
        assertThrows(IllegalStateException::class.java) { client.send(request(LiveActivityPushEvent.START)) }
        repeat(2) { client.send(request(LiveActivityPushEvent.UPDATE)) }
        assertThrows(IllegalArgumentException::class.java) { client.send(request(LiveActivityPushEvent.UPDATE)) }
        client.send(request(LiveActivityPushEvent.END))
        assertThrows(IllegalArgumentException::class.java) { client.send(request(LiveActivityPushEvent.END)) }
        assertEquals(3, calls)
    }

    @Test fun `ambiguous failure consumes allowance without blind retry or retargeting`() {
        var calls = 0
        val delegate = object : LiveActivityPushClient { override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
            calls++; error("SYNTHETIC_AMBIGUOUS_FAILURE")
        } }
        val client = BoundedJourneyPushTestClient(delegate, digest, 1, 1, 1200) { 100 }
        assertThrows(IllegalArgumentException::class.java) { client.send(request(LiveActivityPushEvent.UPDATE, "another-synthetic-installation")) }
        assertThrows(IllegalStateException::class.java) { client.send(request(LiveActivityPushEvent.UPDATE)) }
        assertThrows(IllegalArgumentException::class.java) { client.send(request(LiveActivityPushEvent.UPDATE)) }
        assertEquals(1, calls)
    }

    @Test fun `monotonic time boundary and rollback fail closed before provider I O`() {
        var nanos = 100L
        var calls = 0
        val delegate = object : LiveActivityPushClient { override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
            calls++; return LiveActivityPushResult("synthetic-receipt")
        } }
        val client = BoundedJourneyPushTestClient(delegate, digest, 80, 2, 1200) { nanos }
        nanos = 99
        assertThrows(IllegalArgumentException::class.java) { client.send(request(LiveActivityPushEvent.UPDATE)) }
        nanos = 100 + 1200_000_000_000L
        assertThrows(IllegalArgumentException::class.java) { client.send(request(LiveActivityPushEvent.END)) }
        assertEquals(0, calls)
        assertThrows(IllegalArgumentException::class.java) { BoundedJourneyPushTestClient(delegate, digest, 81, 2, 1200) }
        assertThrows(IllegalArgumentException::class.java) { BoundedJourneyPushTestClient(delegate, digest, 80, 3, 1200) }
        assertThrows(IllegalArgumentException::class.java) { BoundedJourneyPushTestClient(delegate, digest, 80, 2, 1201) }
    }
}
