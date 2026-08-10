package com.noLate.notification.liveactivity.application

import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.liveactivity.domain.LiveActivityDelivery
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryOperation
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryStatus
import com.noLate.notification.liveactivity.domain.LiveActivityStartToken
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivity
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivityState
import com.noLate.notification.liveactivity.infrastructure.LiveActivityDeliveryRepository
import com.noLate.notification.liveactivity.infrastructure.LiveActivityStartTokenRepository
import com.noLate.notification.liveactivity.infrastructure.ScheduleLiveActivityRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional

class LiveActivityDispatchRecoveryTest {
    @Test
    fun `stale START lease stays uncertain and is never automatically replayed`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val fcmRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
        }
        val startTokenValue = "c".repeat(64)
        val startTokenFingerprint = OpaquePushIdentifier.fingerprint(startTokenValue)
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            deviceFingerprint = "a".repeat(64),
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            startTokenId = 21,
            startTokenFingerprint = startTokenFingerprint,
            startTokenGeneration = 1,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 2,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = "{}",
            expiresAt = now.plusSeconds(600),
        )
        val fcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "fcm",
            tokenFingerprint = "d".repeat(64),
            deviceFingerprint = "a".repeat(64),
        )
        val startToken = LiveActivityStartToken(
            id = 21,
            memberId = 7,
            pushDeviceTokenId = 11,
            deviceFingerprint = "a".repeat(64),
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            pushToStartToken = startTokenValue,
            tokenFingerprint = startTokenFingerprint,
            tokenGeneration = 1,
            lastSeenAt = now.minusSeconds(600),
        )
        val delivery = LiveActivityDelivery(
            id = 51,
            eventKey = "la:31:start:r2:cccc",
            liveActivityId = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            fcmTokenFingerprint = fcm.tokenFingerprint,
            fcmOwnershipVersion = fcm.ownershipVersion,
            deviceFingerprint = "a".repeat(64),
            activityToken = startTokenValue,
            activityTokenFingerprint = startTokenFingerprint,
            startTokenId = 21,
            startTokenGeneration = 1,
            operation = LiveActivityDeliveryOperation.START,
            revision = 2,
            sourceNotificationGeneration = 3,
            apnsTimestamp = now.epochSecond,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            attributesJson = "{}",
            contentStateJson = "{}",
            alertTitle = "출발 준비",
            alertBody = "실시간 이동 현황을 확인하세요.",
            expirationAt = now.plusSeconds(600),
            availableAt = now.minusSeconds(400),
        )
        val attemptedAt = now.minusSeconds(300)
        assertThat(activity.acquireDispatchLease("lease", attemptedAt, now.minusSeconds(1))).isTrue()
        assertThat(fcm.acquireDispatchLease("lease", attemptedAt, now.minusSeconds(1))).isTrue()
        assertThat(startToken.acquireDispatchLease("lease", attemptedAt, now.minusSeconds(1))).isTrue()
        delivery.beginDispatch(attemptedAt, "lease", now.minusSeconds(1))

        whenever(deliveryRepository.findStaleDispatchCandidateIds(eq(LiveActivityDeliveryStatus.DISPATCHING), any(), any()))
            .thenReturn(listOf(51))
        whenever(deliveryRepository.findById(51)).thenReturn(Optional.of(delivery))
        whenever(memberRepository.findAllByIdsForUpdate(any())).thenReturn(listOf(member))
        whenever(activityRepository.findByIdForUpdate(31)).thenReturn(activity)
        whenever(fcmRepository.findByIdForUpdate(11)).thenReturn(fcm)
        whenever(startRepository.findByIdForUpdate(21)).thenReturn(startToken)
        whenever(deliveryRepository.findByIdForUpdate(51)).thenReturn(delivery)

        val recovered = LiveActivityDispatchCoordinator(
            memberRepository,
            fcmRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            dispatchLeaseSeconds = 120,
            maxAttempts = 3,
            retryBaseSeconds = 30,
        ).recoverStaleDispatches(now, 10)

        assertThat(recovered).isEqualTo(1)
        assertThat(delivery.status).isEqualTo(LiveActivityDeliveryStatus.UNCERTAIN)
        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.START_SENT)
        assertThat(delivery.dispatchLeaseId).isNull()
        assertThat(activity.dispatchLeaseId).isNull()
        assertThat(fcm.dispatchLeaseId).isNull()
        assertThat(startToken.dispatchLeaseId).isNull()
    }

    @Test
    fun `expired START is terminally superseded before provider claim`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val fcmRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
        }
        val startTokenValue = "c".repeat(64)
        val startTokenFingerprint = OpaquePushIdentifier.fingerprint(startTokenValue)
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            deviceFingerprint = "a".repeat(64),
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            startTokenId = 21,
            startTokenFingerprint = startTokenFingerprint,
            startTokenGeneration = 1,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 2,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = "{}",
            expiresAt = now.minusSeconds(1),
        )
        val fcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "fcm",
            tokenFingerprint = "d".repeat(64),
            deviceFingerprint = "a".repeat(64),
        )
        val startToken = LiveActivityStartToken(
            id = 21,
            memberId = 7,
            pushDeviceTokenId = 11,
            deviceFingerprint = "a".repeat(64),
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            pushToStartToken = startTokenValue,
            tokenFingerprint = startTokenFingerprint,
            tokenGeneration = 1,
            lastSeenAt = now.minusSeconds(600),
        )
        val delivery = LiveActivityDelivery(
            id = 51,
            eventKey = "la:31:start:r2:cccc",
            liveActivityId = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            fcmTokenFingerprint = fcm.tokenFingerprint,
            fcmOwnershipVersion = fcm.ownershipVersion,
            deviceFingerprint = "a".repeat(64),
            activityToken = startTokenValue,
            activityTokenFingerprint = startTokenFingerprint,
            startTokenId = 21,
            startTokenGeneration = 1,
            operation = LiveActivityDeliveryOperation.START,
            revision = 2,
            sourceNotificationGeneration = 3,
            apnsTimestamp = now.minusSeconds(60).epochSecond,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            attributesJson = "{}",
            contentStateJson = "{}",
            alertTitle = "출발 준비",
            alertBody = "실시간 이동 현황을 확인하세요.",
            expirationAt = now.minusSeconds(1),
            availableAt = now.minusSeconds(60),
        )

        whenever(deliveryRepository.findDueCandidateIds(any(), eq(now), any()))
            .thenReturn(listOf(51))
        whenever(deliveryRepository.findById(51)).thenReturn(Optional.of(delivery))
        whenever(memberRepository.findAllByIdsForUpdate(any())).thenReturn(listOf(member))
        whenever(activityRepository.findByIdForUpdate(31)).thenReturn(activity)
        whenever(fcmRepository.findByIdForUpdate(11)).thenReturn(fcm)
        whenever(startRepository.findByIdForUpdate(21)).thenReturn(startToken)
        whenever(deliveryRepository.findByIdForUpdate(51)).thenReturn(delivery)

        val lease = LiveActivityDispatchCoordinator(
            memberRepository,
            fcmRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            dispatchLeaseSeconds = 120,
            maxAttempts = 3,
            retryBaseSeconds = 30,
        ).claimNext(now)

        assertThat(lease).isNull()
        assertThat(delivery.status).isEqualTo(LiveActivityDeliveryStatus.SUPERSEDED)
        assertThat(delivery.errorCode).isEqualTo("ACTIVITY_EXPIRED_BEFORE_PROVIDER_CLAIM")
        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.ENDED)
        assertThat(activity.endReason).isEqualTo("START_DELIVERY_EXPIRED")
    }

    @Test
    fun `withdrawn member can dispatch only its frozen END through the retirement reservation`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val fcmRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn true
        }
        val updateToken = "c".repeat(64)
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            deviceFingerprint = "a".repeat(64),
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 2,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = "{}",
            expiresAt = now.plusSeconds(600),
        )
        activity.bindUpdateToken("activity-41", updateToken)
        activity.markEndPending(now, "ACCOUNT_WITHDRAWAL")
        val fcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "fcm",
            tokenFingerprint = "d".repeat(64),
            deviceFingerprint = "a".repeat(64),
        )
        val reservation = liveActivityEndRetirementReservation(7, "a".repeat(64))
        assertThat(fcm.acquireDispatchLease(reservation, now, now.plusSeconds(600))).isTrue()
        assertThat(fcm.requestRetirement(now)).isFalse()
        val delivery = LiveActivityDelivery(
            id = 51,
            eventKey = "la:31:end:r${activity.contentRevision}:cccc",
            liveActivityId = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            fcmTokenFingerprint = fcm.tokenFingerprint,
            fcmOwnershipVersion = fcm.ownershipVersion,
            deviceFingerprint = "a".repeat(64),
            activityToken = updateToken,
            activityTokenFingerprint = OpaquePushIdentifier.fingerprint(updateToken),
            updateTokenGeneration = activity.updateTokenGeneration,
            targetActivityId = "activity-41",
            operation = LiveActivityDeliveryOperation.END,
            revision = activity.contentRevision,
            sourceNotificationGeneration = 3,
            apnsTimestamp = now.epochSecond,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            contentStateJson = "{}",
            dismissalAt = now,
            expirationAt = now.plusSeconds(3_600),
            availableAt = now,
        )

        whenever(deliveryRepository.findDueCandidateIds(any(), eq(now), any()))
            .thenReturn(listOf(51))
        whenever(deliveryRepository.findById(51)).thenReturn(Optional.of(delivery))
        whenever(memberRepository.findAllByIdsForUpdate(any())).thenReturn(listOf(member))
        whenever(activityRepository.findByIdForUpdate(31)).thenReturn(activity)
        whenever(fcmRepository.findByIdForUpdate(11)).thenReturn(fcm)
        whenever(deliveryRepository.findByIdForUpdate(51)).thenReturn(delivery)

        val lease = LiveActivityDispatchCoordinator(
            memberRepository,
            fcmRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            dispatchLeaseSeconds = 120,
            maxAttempts = 3,
            retryBaseSeconds = 30,
        ).claimNext(now)

        assertThat(lease).isNotNull()
        assertThat(lease!!.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(delivery.status).isEqualTo(LiveActivityDeliveryStatus.DISPATCHING)
        assertThat(fcm.dispatchLeaseId).isEqualTo(lease.leaseId)
        assertThat(fcm.retirementRequested).isTrue()
    }

    @Test
    fun `stale END lease retries the same immutable delivery without false termination`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val fcmRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
        }
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            deviceFingerprint = "a".repeat(64),
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 2,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = "{}",
            expiresAt = now.plusSeconds(600),
        )
        activity.bindUpdateToken("activity-41", "c".repeat(64))
        activity.markEndPending(now.minusSeconds(400), "DEPARTED")
        val fcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "fcm",
            tokenFingerprint = "d".repeat(64),
            deviceFingerprint = "a".repeat(64),
        )
        val delivery = LiveActivityDelivery(
            id = 51,
            eventKey = "la:31:end:r${activity.contentRevision}:cccc",
            liveActivityId = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            fcmTokenFingerprint = fcm.tokenFingerprint,
            fcmOwnershipVersion = fcm.ownershipVersion,
            deviceFingerprint = "a".repeat(64),
            activityToken = "c".repeat(64),
            activityTokenFingerprint = "e".repeat(64),
            updateTokenGeneration = activity.updateTokenGeneration,
            targetActivityId = "activity-41",
            operation = LiveActivityDeliveryOperation.END,
            revision = activity.contentRevision,
            sourceNotificationGeneration = 3,
            apnsTimestamp = now.epochSecond,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            contentStateJson = "{}",
            expirationAt = now.plusSeconds(3_600),
            availableAt = now.minusSeconds(400),
        )
        val attemptedAt = now.minusSeconds(300)
        assertThat(activity.acquireDispatchLease("lease", attemptedAt, now.minusSeconds(1))).isTrue()
        assertThat(fcm.acquireDispatchLease("lease", attemptedAt, now.minusSeconds(1))).isTrue()
        delivery.beginDispatch(attemptedAt, "lease", now.minusSeconds(1))

        whenever(deliveryRepository.findStaleDispatchCandidateIds(eq(LiveActivityDeliveryStatus.DISPATCHING), any(), any()))
            .thenReturn(listOf(51))
        whenever(deliveryRepository.findById(51)).thenReturn(Optional.of(delivery))
        whenever(memberRepository.findAllByIdsForUpdate(any())).thenReturn(listOf(member))
        whenever(activityRepository.findByIdForUpdate(31)).thenReturn(activity)
        whenever(fcmRepository.findByIdForUpdate(11)).thenReturn(fcm)
        whenever(deliveryRepository.findByIdForUpdate(51)).thenReturn(delivery)

        val recovered = LiveActivityDispatchCoordinator(
            memberRepository,
            fcmRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            dispatchLeaseSeconds = 120,
            maxAttempts = 3,
            retryBaseSeconds = 30,
        ).recoverStaleDispatches(now, 10)

        assertThat(recovered).isEqualTo(1)
        assertThat(delivery.status).isEqualTo(LiveActivityDeliveryStatus.FAILED)
        assertThat(delivery.availableAt).isEqualTo(now.plusSeconds(30))
        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(activity.dispatchLeaseId).isNull()
        assertThat(fcm.dispatchLeaseId).isNull()
    }
}
