package com.noLate.notification.liveactivity.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class LiveActivityStartTokenAppearanceTest {
    @Test
    fun `appearance-only registration preserves credential generation and missing value preserves appearance`() {
        val token = "a".repeat(64)
        val startToken = LiveActivityStartToken(
            memberId = 7,
            pushDeviceTokenId = 11,
            deviceFingerprint = "b".repeat(64),
            activityType = "NoLateDepartureAttributes",
            pushToStartToken = token,
            tokenGeneration = 4,
            lastSeenAt = Instant.EPOCH,
            appearance = LiveActivityAppearance.DARK,
        )

        val credentialChanged = startToken.rotate(
            memberId = 7,
            pushDeviceTokenId = 11,
            deviceFingerprint = "b".repeat(64),
            token = token,
            tokenFingerprint = startToken.tokenFingerprint,
            seenAt = Instant.EPOCH.plusSeconds(1),
            appearance = LiveActivityAppearance.LIGHT,
        )

        assertThat(credentialChanged).isFalse()
        assertThat(startToken.tokenGeneration).isEqualTo(4)
        assertThat(startToken.appearance).isEqualTo(LiveActivityAppearance.LIGHT)

        startToken.rotate(
            memberId = 7,
            pushDeviceTokenId = 11,
            deviceFingerprint = "b".repeat(64),
            token = token,
            tokenFingerprint = startToken.tokenFingerprint,
            seenAt = Instant.EPOCH.plusSeconds(2),
        )

        assertThat(startToken.tokenGeneration).isEqualTo(4)
        assertThat(startToken.appearance).isEqualTo(LiveActivityAppearance.LIGHT)
    }
}
