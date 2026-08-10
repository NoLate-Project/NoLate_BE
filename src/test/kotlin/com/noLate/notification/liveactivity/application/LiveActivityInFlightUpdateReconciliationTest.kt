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
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivity
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivityState
import com.noLate.notification.liveactivity.infrastructure.LiveActivityDeliveryRepository
import com.noLate.notification.liveactivity.infrastructure.LiveActivityStartTokenRepository
import com.noLate.notification.liveactivity.infrastructure.ScheduleLiveActivityRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.ConcurrencyFailureException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class LiveActivityInFlightUpdateReconciliationTest {
    @Test
    fun `permanent UPDATE rejection preserves active identity and credential for later END`() {
        val fixture = fixture()
        val oldFingerprint = requireNotNull(fixture.activity.updateTokenFingerprint)
        val oldGeneration = fixture.activity.updateTokenGeneration

        fixture.coordinator.completePermanentFailure(
            fixture.dispatchLease,
            "PERMANENT_PROVIDER_REJECTION",
            fixture.now.plusSeconds(1),
        )

        assertThat(fixture.delivery.status).isEqualTo(LiveActivityDeliveryStatus.INVALID)
        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.ACTIVE)
        assertThat(fixture.activity.activityId).isEqualTo(ACTIVITY_ID)
        assertThat(fixture.activity.updateTokenFingerprint).isEqualTo(oldFingerprint)
        assertThat(fixture.activity.updateTokenGeneration).isEqualTo(oldGeneration)
        assertThat(fixture.activity.dispatchLeaseId).isNull()
        assertThat(fixture.fcm.dispatchLeaseId).isNull()

        assertThat(
            fixture.activity.markEndPending(
                fixture.now.plusSeconds(2),
                "SCHEDULE_CANCELED",
            )
        ).isTrue()
        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(fixture.activity.activityId).isEqualTo(ACTIVITY_ID)
        assertThat(fixture.activity.updateTokenFingerprint).isEqualTo(oldFingerprint)
    }

    @Test
    fun `rotated token reconciles exact in-flight UPDATE and replans from the new epoch`() {
        val fixture = fixture()

        fixture.writer.registerUpdateToken(
            memberId = 7,
            deviceFingerprint = fixture.deviceFingerprint,
            scheduleId = 41,
            activityId = ACTIVITY_ID,
            updateToken = NEW_UPDATE_TOKEN,
            updateTokenFingerprint = OpaquePushIdentifier.fingerprint(NEW_UPDATE_TOKEN),
            generation = 3,
            presentedSessionGeneration = 9,
        )

        assertThat(fixture.delivery.status).isEqualTo(LiveActivityDeliveryStatus.SUPERSEDED)
        assertThat(fixture.delivery.errorCode).isEqualTo("CLIENT_ROTATED_UPDATE_TOKEN")
        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.ACTIVE)
        assertThat(fixture.activity.updateTokenFingerprint)
            .isEqualTo(OpaquePushIdentifier.fingerprint(NEW_UPDATE_TOKEN))
        assertThat(fixture.activity.updateTokenGeneration).isEqualTo(2)
        assertThat(fixture.activity.dispatchLeaseId).isNull()
        assertThat(fixture.fcm.dispatchLeaseId).isNull()
        verify(fixture.projectionService).enqueueCurrentUpdate(31)

        fixture.coordinator.completeInvalidActivityRoute(
            fixture.dispatchLease,
            fixture.now.plusSeconds(1),
        )

        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.ACTIVE)
        assertThat(fixture.activity.updateTokenFingerprint)
            .isEqualTo(OpaquePushIdentifier.fingerprint(NEW_UPDATE_TOKEN))
        assertThat(fixture.delivery.status).isEqualTo(LiveActivityDeliveryStatus.SUPERSEDED)
    }

    @Test
    fun `late old-token rejection cannot invalidate an independently rotated credential epoch`() {
        val fixture = fixture()
        fixture.activity.bindUpdateToken(ACTIVITY_ID, NEW_UPDATE_TOKEN)

        fixture.coordinator.completeInvalidActivityRoute(
            fixture.dispatchLease,
            fixture.now.plusSeconds(1),
        )

        assertThat(fixture.delivery.status).isEqualTo(LiveActivityDeliveryStatus.INVALID)
        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.ACTIVE)
        assertThat(fixture.activity.updateTokenFingerprint)
            .isEqualTo(OpaquePushIdentifier.fingerprint(NEW_UPDATE_TOKEN))
        assertThat(fixture.activity.updateTokenGeneration).isEqualTo(2)
        assertThat(fixture.activity.dispatchLeaseId).isNull()
        assertThat(fixture.fcm.dispatchLeaseId).isNull()
    }

    @Test
    fun `current BadDeviceToken clears only credential and a later rotation recovers the activity`() {
        val fixture = fixture()

        fixture.coordinator.completeInvalidActivityRoute(
            fixture.dispatchLease,
            fixture.now.plusSeconds(1),
        )

        assertThat(fixture.delivery.status).isEqualTo(LiveActivityDeliveryStatus.INVALID)
        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.TOKEN_PENDING)
        assertThat(fixture.activity.activityId).isEqualTo(ACTIVITY_ID)
        assertThat(fixture.activity.updateToken).isNull()
        assertThat(fixture.activity.updateTokenFingerprint).isNull()
        assertThat(fixture.activity.updateTokenGeneration).isEqualTo(2)

        fixture.writer.registerUpdateToken(
            memberId = 7,
            deviceFingerprint = fixture.deviceFingerprint,
            scheduleId = 41,
            activityId = ACTIVITY_ID,
            updateToken = NEW_UPDATE_TOKEN,
            updateTokenFingerprint = OpaquePushIdentifier.fingerprint(NEW_UPDATE_TOKEN),
            generation = 3,
            presentedSessionGeneration = 9,
        )

        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.ACTIVE)
        assertThat(fixture.activity.updateTokenFingerprint)
            .isEqualTo(OpaquePushIdentifier.fingerprint(NEW_UPDATE_TOKEN))
        assertThat(fixture.activity.updateTokenGeneration).isEqualTo(3)
        verify(fixture.projectionService).enqueueCurrentUpdate(31)
    }

    @Test
    fun `rotated token reconciles exact in-flight END and late old completion is a no-op`() {
        val fixture = fixture(operation = LiveActivityDeliveryOperation.END)

        fixture.writer.registerUpdateToken(
            memberId = 7,
            deviceFingerprint = fixture.deviceFingerprint,
            scheduleId = 41,
            activityId = ACTIVITY_ID,
            updateToken = NEW_UPDATE_TOKEN,
            updateTokenFingerprint = OpaquePushIdentifier.fingerprint(NEW_UPDATE_TOKEN),
            generation = 3,
            presentedSessionGeneration = 9,
        )

        assertThat(fixture.delivery.status).isEqualTo(LiveActivityDeliveryStatus.SUPERSEDED)
        assertThat(fixture.delivery.errorCode).isEqualTo("CLIENT_ROTATED_END_TOKEN")
        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(fixture.activity.activityId).isEqualTo(ACTIVITY_ID)
        assertThat(fixture.activity.updateTokenFingerprint)
            .isEqualTo(OpaquePushIdentifier.fingerprint(NEW_UPDATE_TOKEN))
        assertThat(fixture.activity.updateTokenGeneration).isEqualTo(2)
        assertThat(fixture.activity.dispatchLeaseId).isNull()
        assertThat(fixture.fcm.dispatchLeaseId).isNull()
        verify(fixture.projectionService).enqueueCurrentUpdate(31)

        fixture.coordinator.completeSuccess(
            fixture.dispatchLease,
            "late-old-end-success",
            fixture.now.plusSeconds(1),
        )

        assertThat(fixture.delivery.status).isEqualTo(LiveActivityDeliveryStatus.SUPERSEDED)
        assertThat(fixture.delivery.providerMessageId).isNull()
        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(fixture.activity.updateTokenFingerprint)
            .isEqualTo(OpaquePushIdentifier.fingerprint(NEW_UPDATE_TOKEN))
        assertThat(fixture.activity.updateTokenGeneration).isEqualTo(2)
    }

    @Test
    fun `mismatched in-flight END revision remains fail closed with every lease intact`() {
        val fixture = fixture(
            operation = LiveActivityDeliveryOperation.END,
            deliveryRevisionOffset = -1,
        )

        assertThatThrownBy {
            fixture.writer.registerUpdateToken(
                memberId = 7,
                deviceFingerprint = fixture.deviceFingerprint,
                scheduleId = 41,
                activityId = ACTIVITY_ID,
                updateToken = NEW_UPDATE_TOKEN,
                updateTokenFingerprint = OpaquePushIdentifier.fingerprint(NEW_UPDATE_TOKEN),
                generation = 3,
                presentedSessionGeneration = 9,
            )
        }.isInstanceOf(ConcurrencyFailureException::class.java)

        assertThat(fixture.delivery.status).isEqualTo(LiveActivityDeliveryStatus.DISPATCHING)
        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(fixture.activity.updateTokenFingerprint)
            .isEqualTo(OpaquePushIdentifier.fingerprint(OLD_UPDATE_TOKEN))
        assertThat(fixture.activity.updateTokenGeneration).isEqualTo(1)
        assertThat(fixture.activity.dispatchLeaseId).isEqualTo(LEASE_ID)
        assertThat(fixture.fcm.dispatchLeaseId).isEqualTo(LEASE_ID)
    }

    @Test
    fun `unchanged token cannot reconcile an in-flight END`() {
        val fixture = fixture(operation = LiveActivityDeliveryOperation.END)

        assertThatThrownBy {
            fixture.writer.registerUpdateToken(
                memberId = 7,
                deviceFingerprint = fixture.deviceFingerprint,
                scheduleId = 41,
                activityId = ACTIVITY_ID,
                updateToken = OLD_UPDATE_TOKEN,
                updateTokenFingerprint = OpaquePushIdentifier.fingerprint(OLD_UPDATE_TOKEN),
                generation = 3,
                presentedSessionGeneration = 9,
            )
        }.isInstanceOf(ConcurrencyFailureException::class.java)

        assertThat(fixture.delivery.status).isEqualTo(LiveActivityDeliveryStatus.DISPATCHING)
        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(fixture.activity.updateTokenFingerprint)
            .isEqualTo(OpaquePushIdentifier.fingerprint(OLD_UPDATE_TOKEN))
        assertThat(fixture.activity.updateTokenGeneration).isEqualTo(1)
        assertThat(fixture.activity.dispatchLeaseId).isEqualTo(LEASE_ID)
        assertThat(fixture.fcm.dispatchLeaseId).isEqualTo(LEASE_ID)
    }

    private fun fixture(
        operation: LiveActivityDeliveryOperation = LiveActivityDeliveryOperation.UPDATE,
        deliveryRevisionOffset: Long = 0,
    ): Fixture {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val projectionService = mock<ScheduleLiveActivityService>()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
            on { sessionGeneration } doReturn 9L
        }
        val deviceFingerprint = "a".repeat(64)
        val fcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "fcm-token",
            deviceFingerprint = deviceFingerprint,
        )
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 2,
            contentFingerprint = "d".repeat(64),
            attributesJson = "{}",
            contentStateJson = "{}",
            expiresAt = now.plusSeconds(3_600),
        )
        activity.bindUpdateToken(ACTIVITY_ID, OLD_UPDATE_TOKEN)
        if (operation == LiveActivityDeliveryOperation.END) {
            activity.markEndPending(now, "SCHEDULE_CANCELED")
        }
        val oldFingerprint = requireNotNull(activity.updateTokenFingerprint)
        val delivery = LiveActivityDelivery(
            id = 51,
            eventKey = "in-flight-update",
            liveActivityId = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            fcmTokenFingerprint = fcm.tokenFingerprint,
            fcmOwnershipVersion = fcm.ownershipVersion,
            deviceFingerprint = deviceFingerprint,
            activityToken = OLD_UPDATE_TOKEN,
            activityTokenFingerprint = oldFingerprint,
            updateTokenGeneration = activity.updateTokenGeneration,
            targetActivityId = ACTIVITY_ID,
            operation = operation,
            revision = activity.contentRevision + deliveryRevisionOffset,
            sourceNotificationGeneration = 3,
            apnsTimestamp = now.epochSecond,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            contentStateJson = "{}",
            expirationAt = now.plusSeconds(3_600),
            availableAt = now,
        )
        val leaseUntil = now.plusSeconds(600)
        assertThat(activity.acquireDispatchLease(LEASE_ID, now, leaseUntil)).isTrue()
        assertThat(fcm.acquireDispatchLease(LEASE_ID, now, leaseUntil)).isTrue()
        delivery.beginDispatch(now, LEASE_ID, leaseUntil)

        val newFingerprint = OpaquePushIdentifier.fingerprint(NEW_UPDATE_TOKEN)
        whenever(activityRepository.findUpdateTokenOwnerMemberIds(newFingerprint))
            .thenReturn(emptyList())
        whenever(memberRepository.findAllByIdsForUpdate(listOf(7))).thenReturn(listOf(member))
        whenever(
            activityRepository.findByScheduleIdAndMemberIdAndDeviceFingerprint(
                41,
                7,
                deviceFingerprint,
            )
        ).thenReturn(activity)
        whenever(activityRepository.findAllByIdsForUpdate(listOf(31))).thenReturn(listOf(activity))
        whenever(deviceRepository.findByIdForUpdate(11)).thenReturn(fcm)
        whenever(
            deviceRepository.findAllByMemberIdAndDeviceFingerprintForUpdate(7, deviceFingerprint)
        ).thenReturn(listOf(fcm))
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(listOf(delivery))
        whenever(activityRepository.saveAndFlush(activity)).thenReturn(activity)

        whenever(memberRepository.findByIdForUpdate(7)).thenReturn(member)
        whenever(deliveryRepository.findById(51)).thenReturn(Optional.of(delivery))
        whenever(activityRepository.findByIdForUpdate(31)).thenReturn(activity)
        whenever(deliveryRepository.findByIdForUpdate(51)).thenReturn(delivery)

        val writer = LiveActivityTokenWriter(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            projectionService,
            Clock.fixed(now, ZoneOffset.UTC),
        )
        val coordinator = LiveActivityDispatchCoordinator(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
        )
        val dispatchLease = LiveActivityDispatchLease(
            deliveryId = 51,
            leaseId = LEASE_ID,
            memberId = 7,
            fcmTokenId = 11,
            fcmTokenFingerprint = fcm.tokenFingerprint,
            fcmOwnershipVersion = fcm.ownershipVersion,
            fcmRegistrationToken = fcm.token,
            activityToken = OLD_UPDATE_TOKEN,
            operation = operation,
            timestamp = delivery.apnsTimestamp,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            attributesJson = null,
            contentStateJson = "{}",
            alertTitle = null,
            alertBody = null,
            staleAt = null,
            dismissalAt = null,
            expirationAt = delivery.expirationAt,
        )
        return Fixture(
            now = now,
            deviceFingerprint = deviceFingerprint,
            activity = activity,
            fcm = fcm,
            delivery = delivery,
            projectionService = projectionService,
            writer = writer,
            coordinator = coordinator,
            dispatchLease = dispatchLease,
        )
    }

    private data class Fixture(
        val now: Instant,
        val deviceFingerprint: String,
        val activity: ScheduleLiveActivity,
        val fcm: NotificationDeviceToken,
        val delivery: LiveActivityDelivery,
        val projectionService: ScheduleLiveActivityService,
        val writer: LiveActivityTokenWriter,
        val coordinator: LiveActivityDispatchCoordinator,
        val dispatchLease: LiveActivityDispatchLease,
    )

    private companion object {
        const val ACTIVITY_ID = "activity-41"
        const val LEASE_ID = "la-in-flight-update"
        val OLD_UPDATE_TOKEN = "e".repeat(64)
        val NEW_UPDATE_TOKEN = "f".repeat(64)
    }
}
