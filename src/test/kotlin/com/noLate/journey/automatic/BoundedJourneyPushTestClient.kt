package com.noLate.journey.automatic

import com.noLate.notification.liveactivity.application.LiveActivityPushClient
import com.noLate.notification.liveactivity.application.LiveActivityPushEvent
import com.noLate.notification.liveactivity.application.LiveActivityPushRequest
import com.noLate.notification.liveactivity.application.LiveActivityPushResult
import java.security.MessageDigest

/** TEST SOURCE ONLY. Reserves before I/O, including ambiguous failures. No token/payload diagnostics. */
internal class BoundedJourneyPushTestClient(
    private val client: LiveActivityPushClient,
    private val permittedInstallationDigest: String,
    private val maxUpdates: Int,
    private val maxEnds: Int,
    durationSeconds: Int,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : LiveActivityPushClient {
    private val started = monotonicNanos()
    private val durationNanos = durationSeconds.toLong() * 1_000_000_000
    private var updates = 0
    private var ends = 0

    init {
        require(permittedInstallationDigest.matches(Regex("[a-f0-9]{64}"))
            && maxUpdates in 1..80 && maxEnds in 1..2 && durationSeconds in 1..1200) {
            "INVALID_ISOLATED_PUSH_BUDGET"
        }
    }

    override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
        reserve(request)
        return client.send(request)
    }

    @Synchronized private fun reserve(request: LiveActivityPushRequest) {
        val elapsed = monotonicNanos() - started
        require(elapsed >= 0 && elapsed < durationNanos) { "ISOLATED_PUSH_TIME_BUDGET_EXHAUSTED" }
        val digest = MessageDigest.getInstance("SHA-256").digest(request.fcmRegistrationToken.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        require(digest == permittedInstallationDigest) { "ISOLATED_PUSH_INSTALLATION_REJECTED" }
        when (request.event) {
            LiveActivityPushEvent.START -> error("ISOLATED_PUSH_START_FORBIDDEN")
            LiveActivityPushEvent.UPDATE -> {
                require(updates < maxUpdates) { "ISOLATED_PUSH_UPDATE_BUDGET_EXHAUSTED" }
                updates++
            }
            LiveActivityPushEvent.END -> {
                require(ends < maxEnds) { "ISOLATED_PUSH_END_BUDGET_EXHAUSTED" }
                ends++
            }
        }
    }
}
