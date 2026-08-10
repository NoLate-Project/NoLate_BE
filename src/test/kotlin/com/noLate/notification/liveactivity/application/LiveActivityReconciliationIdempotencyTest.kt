package com.noLate.notification.liveactivity.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import com.noLate.notification.liveactivity.infrastructure.LiveActivityDeliveryRepository
import com.noLate.notification.liveactivity.infrastructure.LiveActivityStartTokenRepository
import com.noLate.notification.liveactivity.infrastructure.ScheduleLiveActivityRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class LiveActivityReconciliationIdempotencyTest {
    private val now = Instant.parse("2026-08-06T00:10:00Z")
    private val memberRepository = mock<MemberRepository>()
    private val deviceRepository = mock<NotificationDeviceTokenRepository>()
    private val startRepository = mock<LiveActivityStartTokenRepository>()
    private val activityRepository = mock<ScheduleLiveActivityRepository>()
    private val deliveryRepository = mock<LiveActivityDeliveryRepository>()
    private val member = mock<Member> {
        on { id } doReturn 7L
        on { deleted } doReturn false
    }
    private val deviceFingerprint = "a".repeat(64)

    @Test
    fun `identical START reconciliation preserves failed attempt count and backoff`() {
        val fcm = fcm()
        val rawStartToken = "c".repeat(64)
        val startFingerprint = OpaquePushIdentifier.fingerprint(rawStartToken)
        val startToken = LiveActivityStartToken(
            id = 21,
            memberId = 7,
            pushDeviceTokenId = 11,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            pushToStartToken = rawStartToken,
            tokenFingerprint = startFingerprint,
            lastSeenAt = now,
        )
        val activity = activity().also {
            it.startTokenId = 21
            it.startTokenFingerprint = startFingerprint
            it.startTokenGeneration = 1
        }
        val delivery = delivery(
            activity,
            fcm,
            rawStartToken,
            startFingerprint,
            LiveActivityDeliveryOperation.START,
        )
        delivery.beginDispatch(now.minusSeconds(60), "lease", now.minusSeconds(1))
        val retryAt = now.plusSeconds(600)
        delivery.markConfirmedFailure(now.minusSeconds(30), "THROTTLED", null, retryAt)

        whenever(memberRepository.findByIdForUpdate(7)).thenReturn(member)
        whenever(
            startRepository.findAllByMemberIdAndActivityTypeAndActiveTrueAndRetirementRequestedFalseOrderByIdAsc(
                7,
                NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            )
        ).thenReturn(listOf(startToken))
        whenever(activityRepository.findAllByMemberDeviceForUpdate(7, deviceFingerprint))
            .thenReturn(listOf(activity))
        whenever(deviceRepository.findByIdForUpdate(11)).thenReturn(fcm)
        whenever(startRepository.findByIdForUpdate(21)).thenReturn(startToken)
        whenever(deliveryRepository.findByEventKeyForUpdate(delivery.eventKey)).thenReturn(delivery)

        service().reconcileStartToken(7, deviceFingerprint)

        assertThat(delivery.status).isEqualTo(LiveActivityDeliveryStatus.FAILED)
        assertThat(delivery.attemptCount).isEqualTo(1)
        assertThat(delivery.availableAt).isEqualTo(retryAt)
        verify(deliveryRepository, never()).save(any<LiveActivityDelivery>())
    }

    @Test
    fun `identical UPDATE callback preserves failed attempt count and backoff`() {
        val fcm = fcm()
        val rawUpdateToken = "d".repeat(64)
        val updateFingerprint = OpaquePushIdentifier.fingerprint(rawUpdateToken)
        val activity = activity().also { it.bindUpdateToken("activity-41", rawUpdateToken) }
        val delivery = delivery(
            activity,
            fcm,
            rawUpdateToken,
            updateFingerprint,
            LiveActivityDeliveryOperation.UPDATE,
        )
        delivery.beginDispatch(now.minusSeconds(60), "lease", now.minusSeconds(1))
        val retryAt = now.plusSeconds(900)
        delivery.markConfirmedFailure(now.minusSeconds(30), "THROTTLED", null, retryAt)

        whenever(activityRepository.findById(31)).thenReturn(Optional.of(activity))
        whenever(memberRepository.findByIdForUpdate(7)).thenReturn(member)
        whenever(activityRepository.findByIdForUpdate(31)).thenReturn(activity)
        whenever(deviceRepository.findByIdForUpdate(11)).thenReturn(fcm)
        whenever(deliveryRepository.findByEventKeyForUpdate(delivery.eventKey)).thenReturn(delivery)

        service().enqueueCurrentUpdate(31)

        assertThat(delivery.status).isEqualTo(LiveActivityDeliveryStatus.FAILED)
        assertThat(delivery.attemptCount).isEqualTo(1)
        assertThat(delivery.availableAt).isEqualTo(retryAt)
        verify(deliveryRepository, never()).save(any<LiveActivityDelivery>())
    }

    @Test
    fun `same-row FCM ownership epoch change materializes one fresh START route`() {
        val oldFcm = fcm()
        val currentFcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "refreshed-fcm",
            deviceFingerprint = deviceFingerprint,
            ownershipVersion = 1,
        )
        val rawStartToken = "c".repeat(64)
        val startFingerprint = OpaquePushIdentifier.fingerprint(rawStartToken)
        val startToken = LiveActivityStartToken(
            id = 21,
            memberId = 7,
            pushDeviceTokenId = 11,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            pushToStartToken = rawStartToken,
            tokenFingerprint = startFingerprint,
            lastSeenAt = now,
        )
        val activity = activity().also {
            it.startTokenId = 21
            it.startTokenFingerprint = startFingerprint
            it.startTokenGeneration = 1
        }
        val oldRoute = delivery(
            activity,
            oldFcm,
            rawStartToken,
            startFingerprint,
            LiveActivityDeliveryOperation.START,
        )

        whenever(memberRepository.findByIdForUpdate(7)).thenReturn(member)
        whenever(
            startRepository.findAllByMemberIdAndActivityTypeAndActiveTrueAndRetirementRequestedFalseOrderByIdAsc(
                7,
                NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            )
        ).thenReturn(listOf(startToken))
        whenever(activityRepository.findAllByMemberDeviceForUpdate(7, deviceFingerprint))
            .thenReturn(listOf(activity))
        whenever(deviceRepository.findByIdForUpdate(11)).thenReturn(currentFcm)
        whenever(startRepository.findByIdForUpdate(21)).thenReturn(startToken)
        whenever(deliveryRepository.findByEventKeyForUpdate(any())).thenReturn(null)
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(listOf(oldRoute))
        whenever(deliveryRepository.save(any<LiveActivityDelivery>())).thenAnswer { it.arguments[0] }

        service().reconcileStartToken(7, deviceFingerprint)

        assertThat(oldRoute.status).isEqualTo(LiveActivityDeliveryStatus.SUPERSEDED)
        val fresh = argumentCaptor<LiveActivityDelivery>()
        verify(deliveryRepository).save(fresh.capture())
        assertThat(fresh.firstValue.fcmOwnershipVersion).isEqualTo(1)
        assertThat(fresh.firstValue.fcmTokenFingerprint).isEqualTo(currentFcm.tokenFingerprint)
        assertThat(fresh.firstValue.eventKey).isNotEqualTo(oldRoute.eventKey)
    }

    private fun service() = ScheduleLiveActivityService(
        memberRepository,
        deviceRepository,
        startRepository,
        activityRepository,
        deliveryRepository,
        jacksonObjectMapper(),
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun fcm() = NotificationDeviceToken(
        id = 11,
        memberId = 7,
        deviceId = "device",
        platform = PushPlatform.IOS,
        token = "fcm",
        deviceFingerprint = deviceFingerprint,
    )

    private fun activity() = ScheduleLiveActivity(
        id = 31,
        memberId = 7,
        scheduleId = 41,
        pushDeviceTokenId = 11,
        deviceFingerprint = deviceFingerprint,
        activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
        sourceNotificationGeneration = 3,
        sourceCheckCount = 4,
        contentRevision = 2,
        contentFingerprint = "b".repeat(64),
        attributesJson = "{}",
        contentStateJson = "{}",
        expiresAt = now.plusSeconds(3_600),
    )

    private fun delivery(
        activity: ScheduleLiveActivity,
        fcm: NotificationDeviceToken,
        rawActivityToken: String,
        activityTokenFingerprint: String,
        operation: LiveActivityDeliveryOperation,
    ) = LiveActivityDelivery(
        id = 51,
        eventKey = liveActivityDeliveryEventKey(
            liveActivityId = 31,
            operation = operation,
            revision = activity.contentRevision,
            activityTokenFingerprint = activityTokenFingerprint,
            activityTokenGeneration = if (operation == LiveActivityDeliveryOperation.START) {
                requireNotNull(activity.startTokenGeneration)
            } else {
                activity.updateTokenGeneration
            },
            pushDeviceTokenId = 11,
            fcmOwnershipVersion = fcm.ownershipVersion,
        ),
        liveActivityId = 31,
        memberId = 7,
        scheduleId = 41,
        pushDeviceTokenId = 11,
        fcmTokenFingerprint = fcm.tokenFingerprint,
        fcmOwnershipVersion = fcm.ownershipVersion,
        deviceFingerprint = deviceFingerprint,
        activityToken = rawActivityToken,
        activityTokenFingerprint = activityTokenFingerprint,
        startTokenId = activity.startTokenId.takeIf {
            operation == LiveActivityDeliveryOperation.START
        },
        startTokenGeneration = activity.startTokenGeneration.takeIf {
            operation == LiveActivityDeliveryOperation.START
        },
        updateTokenGeneration = activity.updateTokenGeneration.takeIf {
            operation != LiveActivityDeliveryOperation.START
        },
        targetActivityId = activity.activityId,
        operation = operation,
        revision = activity.contentRevision,
        sourceNotificationGeneration = activity.sourceNotificationGeneration,
        apnsTimestamp = now.epochSecond,
        activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
        attributesJson = "{}".takeIf { operation == LiveActivityDeliveryOperation.START },
        contentStateJson = "{}",
        expirationAt = activity.expiresAt,
        availableAt = now.minusSeconds(60),
    )
}
