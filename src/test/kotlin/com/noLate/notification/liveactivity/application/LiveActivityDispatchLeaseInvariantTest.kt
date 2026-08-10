package com.noLate.notification.liveactivity.application

import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.infrastructure.FirebasePushTimingInvariant
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.liveactivity.infrastructure.LiveActivityDeliveryRepository
import com.noLate.notification.liveactivity.infrastructure.LiveActivityStartTokenRepository
import com.noLate.notification.liveactivity.infrastructure.ScheduleLiveActivityRepository
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class LiveActivityDispatchLeaseInvariantTest {
    private val firebaseTiming = FirebasePushTimingInvariant.validate(
        connectTimeoutMillis = 5_000,
        readTimeoutMillis = 30_000,
        writeTimeoutMillis = 5_000,
        providerMaxCallSeconds = 60,
        dispatchLeaseSeconds = 600,
        registrationWaitMillis = 70_000,
    )

    @Test
    fun `live activity lease equal to firebase worst case budget is rejected`() {
        assertThatThrownBy { coordinator(dispatchLeaseSeconds = 440) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be strictly longer")
    }

    @Test
    fun `live activity lease strictly containing firebase worst case budget is accepted`() {
        assertThatCode { coordinator(dispatchLeaseSeconds = 441) }
            .doesNotThrowAnyException()
    }

    private fun coordinator(dispatchLeaseSeconds: Long) = LiveActivityDispatchCoordinator(
        memberRepository = mock<MemberRepository>(),
        deviceTokenRepository = mock<NotificationDeviceTokenRepository>(),
        startTokenRepository = mock<LiveActivityStartTokenRepository>(),
        activityRepository = mock<ScheduleLiveActivityRepository>(),
        deliveryRepository = mock<LiveActivityDeliveryRepository>(),
        dispatchLeaseSeconds = dispatchLeaseSeconds,
        maxAttempts = 3,
        retryBaseSeconds = 30,
        firebasePushTimingInvariant = firebaseTiming,
    )
}
