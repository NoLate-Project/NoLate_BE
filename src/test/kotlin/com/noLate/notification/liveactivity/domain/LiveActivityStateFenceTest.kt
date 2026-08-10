package com.noLate.notification.liveactivity.domain

import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.liveactivity.application.liveActivityDeliveryEventKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class LiveActivityStateFenceTest {
    private val now = Instant.parse("2026-08-06T00:00:00Z")

    @Test
    fun `older generation and check never replace a newer desired state`() {
        val activity = activity(generation = 4, checkCount = 7)

        assertThat(activity.sourceIsNewer(3, 99)).isFalse()
        assertThat(activity.sourceIsNewer(4, 7)).isFalse()
        assertThat(activity.sourceIsNewer(4, 8)).isTrue()
        assertThat(activity.sourceIsNewer(5, 0)).isTrue()
    }

    @Test
    fun `generation restart clears old ActivityKit instance and advances revision`() {
        val activity = activity(generation = 4, checkCount = 7)
        activity.bindUpdateToken("activity-old", "a".repeat(64))
        val previousRevision = activity.contentRevision

        activity.resetForGeneration(
            pushDeviceTokenId = 12,
            startTokenId = 22,
            startTokenFingerprint = "b".repeat(64),
            startTokenGeneration = 3,
            notificationGeneration = 5,
            checkCount = 0,
            fingerprint = "c".repeat(64),
            attributesJson = "{\"generation\":5}",
            contentJson = "{\"revision\":${previousRevision + 1}}",
            staleAt = now.plusSeconds(60),
            expiresAt = now.plusSeconds(600),
        )

        assertThat(activity.sourceNotificationGeneration).isEqualTo(5)
        assertThat(activity.contentRevision).isEqualTo(previousRevision + 1)
        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.START_PENDING)
        assertThat(activity.activityId).isNull()
        assertThat(activity.updateToken).isNull()
        assertThat(activity.updateTokenFingerprint).isNull()
        assertThat(activity.updateTokenGeneration).isZero()
    }

    @Test
    fun `retired FCM route can only exchange its exact END reservation`() {
        val token = NotificationDeviceToken(
            id = 91,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "fcm",
            tokenFingerprint = "d".repeat(64),
            deviceFingerprint = "e".repeat(64),
        )
        assertThat(token.acquireDispatchLease("reserved", now, now.plusSeconds(600))).isTrue()
        assertThat(token.requestRetirement(now)).isFalse()

        assertThat(
            token.acquireReservedRetirementDispatchLease(
                "wrong",
                "provider",
                now.plusSeconds(600),
            )
        ).isFalse()
        assertThat(
            token.acquireReservedRetirementDispatchLease(
                "reserved",
                "provider",
                now.plusSeconds(600),
            )
        ).isTrue()
        assertThat(token.dispatchLeaseId).isEqualTo("provider")
    }

    @Test
    fun `delivery identity changes with the frozen FCM ownership epoch`() {
        val first = liveActivityDeliveryEventKey(
            liveActivityId = 31,
            operation = LiveActivityDeliveryOperation.END,
            revision = 4,
            activityTokenFingerprint = "a".repeat(64),
            activityTokenGeneration = 2,
            pushDeviceTokenId = 11,
            fcmOwnershipVersion = 2,
        )
        val rotated = liveActivityDeliveryEventKey(
            liveActivityId = 31,
            operation = LiveActivityDeliveryOperation.END,
            revision = 4,
            activityTokenFingerprint = "a".repeat(64),
            activityTokenGeneration = 2,
            pushDeviceTokenId = 11,
            fcmOwnershipVersion = 3,
        )

        assertThat(first).isNotEqualTo(rotated)
        assertThat(first.length).isLessThanOrEqualTo(160)
        assertThat(rotated.length).isLessThanOrEqualTo(160)
    }

    @Test
    fun `delivery identity changes with the ActivityKit credential generation`() {
        val first = liveActivityDeliveryEventKey(
            liveActivityId = 31,
            operation = LiveActivityDeliveryOperation.UPDATE,
            revision = 4,
            activityTokenFingerprint = "a".repeat(64),
            activityTokenGeneration = 1,
            pushDeviceTokenId = 11,
            fcmOwnershipVersion = 2,
        )
        val rotated = liveActivityDeliveryEventKey(
            liveActivityId = 31,
            operation = LiveActivityDeliveryOperation.UPDATE,
            revision = 4,
            activityTokenFingerprint = "a".repeat(64),
            activityTokenGeneration = 2,
            pushDeviceTokenId = 11,
            fcmOwnershipVersion = 2,
        )

        assertThat(first).isNotEqualTo(rotated)
        assertThat(first.length).isLessThanOrEqualTo(160)
        assertThat(rotated.length).isLessThanOrEqualTo(160)
    }

    @Test
    fun `late update token binding preserves END tombstone instead of reviving activity`() {
        val activity = activity(generation = 4, checkCount = 7)
        activity.markEndPending(now, "LOGOUT")

        activity.bindUpdateToken("activity-late", "b".repeat(64))

        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(activity.activityId).isEqualTo("activity-late")
    }

    @Test
    fun `ambiguous START remains fail closed while ETA advances desired content`() {
        val activity = activity(generation = 4, checkCount = 7)
        activity.startTokenId = 21
        activity.startTokenFingerprint = "a".repeat(64)
        activity.startTokenGeneration = 1
        activity.markStartAccepted(activity.contentRevision)
        val previousRevision = activity.contentRevision

        val refreshed = activity.refreshProjection(
            pushDeviceTokenId = 12,
            startTokenId = 22,
            startTokenFingerprint = "b".repeat(64),
            startTokenGeneration = 2,
            notificationGeneration = 4,
            checkCount = 8,
            fingerprint = "c".repeat(64),
            contentJson = "{\"revision\":${previousRevision + 1}}",
            staleAt = now.plusSeconds(120),
            expiresAt = now.plusSeconds(900),
        )

        assertThat(refreshed).isTrue()
        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.START_SENT)
        assertThat(activity.contentRevision).isEqualTo(previousRevision + 1)
        assertThat(activity.pushDeviceTokenId).isEqualTo(11)
        assertThat(activity.startTokenId).isEqualTo(21)
        assertThat(activity.startTokenFingerprint).isEqualTo("a".repeat(64))
        assertThat(activity.startTokenGeneration).isEqualTo(1)
    }

    private fun activity(generation: Long, checkCount: Int) = ScheduleLiveActivity(
        id = 31,
        memberId = 7,
        scheduleId = 41,
        pushDeviceTokenId = 11,
        deviceFingerprint = "e".repeat(64),
        activityType = "NoLateDepartureAttributes",
        sourceNotificationGeneration = generation,
        sourceCheckCount = checkCount,
        contentRevision = 2,
        contentFingerprint = "f".repeat(64),
        attributesJson = "{}",
        contentStateJson = "{}",
        expiresAt = now.plusSeconds(600),
    )
}
