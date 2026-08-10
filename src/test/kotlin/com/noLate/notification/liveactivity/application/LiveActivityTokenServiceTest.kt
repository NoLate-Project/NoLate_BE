package com.noLate.notification.liveactivity.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.notification.liveactivity.controller.RegisterLiveActivityStartTokenRequest
import com.noLate.notification.liveactivity.controller.RegisterLiveActivityUpdateTokenRequest
import com.noLate.notification.liveactivity.domain.LiveActivityAppearance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class LiveActivityTokenServiceTest {
    private val writer = mock<LiveActivityTokenWriter>()
    private val service = LiveActivityTokenService(writer)

    @Test
    fun `start token accepts schema one and forwards normalized device appearance`() {
        service.registerStartToken(
            memberId = 7,
            deviceId = "device",
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            pushToStartToken = "a".repeat(64),
            schemaVersion = 1,
            presentedSessionGeneration = 9,
            appearance = " DARK ",
        )

        verify(writer).registerStartToken(
            memberId = eq(7),
            deviceFingerprint = any(),
            activityType = eq(NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE),
            token = eq("a".repeat(64)),
            tokenFingerprint = any(),
            presentedSessionGeneration = eq(9),
            appearance = eq(LiveActivityAppearance.DARK),
        )
    }

    @Test
    fun `legacy start token omission forwards no appearance`() {
        service.registerStartToken(
            memberId = 7,
            deviceId = "device",
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            pushToStartToken = "a".repeat(64),
            schemaVersion = 1,
            presentedSessionGeneration = 9,
        )

        verify(writer).registerStartToken(
            memberId = eq(7),
            deviceFingerprint = any(),
            activityType = eq(NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE),
            token = eq("a".repeat(64)),
            tokenFingerprint = any(),
            presentedSessionGeneration = eq(9),
            appearance = eq(null),
        )
    }

    @Test
    fun `unknown start token appearance is rejected before persistence`() {
        assertThatThrownBy {
            service.registerStartToken(
                memberId = 7,
                deviceId = "device",
                activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
                pushToStartToken = "a".repeat(64),
                schemaVersion = 1,
                presentedSessionGeneration = 9,
                appearance = "automatic",
            )
        }.isInstanceOf(BusinessException::class.java)

        verifyNoInteractions(writer)
    }

    @Test
    fun `start token retirement fences session before ending any live activity`() {
        whenever(
            writer.retireStartToken(
                memberId = eq(7),
                deviceFingerprint = any(),
                activityType = eq(NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE),
                presentedSessionGeneration = eq(9),
            )
        ).thenThrow(BusinessException(ErrorCode.INVALID_TOKEN))

        assertThatThrownBy {
            service.retireStartToken(
                memberId = 7,
                deviceId = "device",
                activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
                presentedSessionGeneration = 9,
            )
        }.isInstanceOf(BusinessException::class.java)
    }

    @Test
    fun `update token requires lowercase even-length hex and explicit generation`() {
        assertThatThrownBy {
            service.registerUpdateToken(
                memberId = 7,
                deviceId = "device",
                scheduleId = 41,
                activityId = "activity-41",
                updateToken = "A".repeat(64),
                generation = 3,
                schemaVersion = 1,
                presentedSessionGeneration = 9,
            )
        }.isInstanceOf(BusinessException::class.java)

        verifyNoInteractions(writer)
    }

    @Test
    fun `valid update callback forwards schedule generation and never returns credential`() {
        whenever(
            writer.registerUpdateToken(
                memberId = any(),
                deviceFingerprint = any(),
                scheduleId = any(),
                activityId = any(),
                updateToken = any(),
                updateTokenFingerprint = any(),
                generation = any(),
                presentedSessionGeneration = any(),
            )
        ).thenReturn(91)

        service.registerUpdateToken(
            memberId = 7,
            deviceId = "device",
            scheduleId = 41,
            activityId = "activity-41",
            updateToken = "a".repeat(64),
            generation = 3,
            schemaVersion = 1,
            presentedSessionGeneration = 9,
        )

        verify(writer).registerUpdateToken(
            memberId = eq(7),
            deviceFingerprint = any(),
            scheduleId = eq(41),
            activityId = eq("activity-41"),
            updateToken = eq("a".repeat(64)),
            updateTokenFingerprint = any(),
            generation = eq(3),
            presentedSessionGeneration = eq(9),
        )
    }

    @Test
    fun `provider request string representation redacts both routes`() {
        val request = LiveActivityPushRequest(
            fcmRegistrationToken = "ordinary-secret",
            liveActivityToken = "activity-secret",
            event = LiveActivityPushEvent.UPDATE,
            timestamp = 1,
            contentState = mapOf("revision" to 1),
            expirationAt = java.time.Instant.ofEpochSecond(2),
        )

        assertThat(request.toString())
            .doesNotContain("ordinary-secret", "activity-secret")
            .contains("event=UPDATE", "revision")
    }

    @Test
    fun `controller request string representations redact ActivityKit credentials`() {
        val startSecret = "a".repeat(64)
        val updateSecret = "b".repeat(64)
        val deviceSecret = "raw-device-identity"

        assertThat(
            RegisterLiveActivityStartTokenRequest(
                deviceId = deviceSecret,
                pushToStartToken = startSecret,
                appearance = "dark",
            ).toString()
        ).doesNotContain(startSecret, deviceSecret)
            .contains("appearancePresent=true")
        assertThat(
            RegisterLiveActivityUpdateTokenRequest(
                deviceId = deviceSecret,
                scheduleId = 41,
                updateToken = updateSecret,
                generation = 3,
            ).toString()
        ).doesNotContain(updateSecret, deviceSecret)
    }
}
