package com.noLate.journey.automatic

import com.noLate.journey.application.*
import com.noLate.notification.domain.*
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.time.Instant

class JourneyActivityRouteAuthorityTest {
    private val now = Instant.parse("2026-10-07T00:00:00Z")
    @Test fun `only registered exact owned IOS installation can bind and lease immutable route epoch`() {
        val repo = mock<NotificationDeviceTokenRepository>()
        val token = NotificationDeviceToken(id = 8, memberId = 7, token = "synthetic", platform = PushPlatform.IOS, deviceId = "synthetic-device", ownershipVersion = 3)
        whenever(repo.findAllByMemberIdAndTokenFingerprintForUpdate(7, token.tokenFingerprint)).thenReturn(listOf(token))
        whenever(repo.findByIdForUpdate(8)).thenReturn(token)
        val authority = RegisteredJourneyActivityRouteAuthority(repo)
        val proof = authority.bind(7, "synthetic")
        assertThat(authority.acquire(7, proof, "lease-1", now)).isTrue()
        assertThat(authority.acquire(7, proof, "lease-2", now)).isFalse()
        authority.release(proof, "wrong-lease"); assertThat(token.dispatchLeaseId).isEqualTo("lease-1")
        authority.release(proof, "lease-1"); assertThat(token.dispatchLeaseId).isNull()
        token.ownershipVersion++
        assertThatThrownBy { authority.acquire(7, proof, "lease-3", now) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(token.retirementRequested).isFalse() // Invalid Activity route must not retire app FCM.
    }
    @Test fun `missing foreign retired platform device and fingerprint mutations cannot bind or send`() {
        for (variant in listOf("missing", "foreign", "retired", "platform", "device", "token")) {
            val repo = mock<NotificationDeviceTokenRepository>()
            val row = NotificationDeviceToken(id = 8, memberId = 7, token = "synthetic", platform = PushPlatform.IOS, deviceId = "synthetic-device")
            val proof = JourneyActivityRouteProof(8, 0, row.deviceFingerprint!!, row.tokenFingerprint)
            when (variant) {
                "foreign" -> row.memberId = 9
                "retired" -> row.retirementRequested = true
                "platform" -> row.platform = PushPlatform.ANDROID
                "device" -> row.deviceFingerprint = null
                "token" -> row.token = "changed-credential"
            }
            whenever(repo.findByIdForUpdate(8)).thenReturn(if (variant == "missing") null else row)
            assertThatThrownBy { RegisteredJourneyActivityRouteAuthority(repo).acquire(7, proof, "lease", now) }.isInstanceOf(IllegalArgumentException::class.java)
            verify(repo, never()).saveAndFlush(any<NotificationDeviceToken>())
        }
    }
}
