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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.ConcurrencyFailureException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class LiveActivityInFlightStartReconciliationTest {
    @Test
    fun `authenticated update token reconciles in-flight START and late provider success is a no-op`() {
        val fixture = fixture()

        fixture.writer.registerUpdateToken(
            memberId = 7,
            deviceFingerprint = fixture.deviceFingerprint,
            scheduleId = 41,
            activityId = "activity-41",
            updateToken = fixture.updateToken,
            updateTokenFingerprint = fixture.updateTokenFingerprint,
            generation = 3,
            presentedSessionGeneration = 9,
        )

        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.ACTIVE)
        assertThat(fixture.activity.activityId).isEqualTo("activity-41")
        assertThat(fixture.activity.updateTokenFingerprint)
            .isEqualTo(fixture.updateTokenFingerprint)
        assertThat(fixture.delivery.status).isEqualTo(LiveActivityDeliveryStatus.SUPERSEDED)
        assertThat(fixture.delivery.errorCode).isEqualTo("CLIENT_CONFIRMED_START")
        assertThat(fixture.activity.dispatchLeaseId).isNull()
        assertThat(fixture.fcm.dispatchLeaseId).isNull()
        assertThat(fixture.startToken.dispatchLeaseId).isNull()
        verify(fixture.projectionService).enqueueCurrentUpdate(31)

        fixture.coordinator.completeSuccess(
            fixture.dispatchLease,
            messageId = "late-provider-success",
            now = fixture.now.plusSeconds(1),
        )

        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.ACTIVE)
        assertThat(fixture.delivery.status).isEqualTo(LiveActivityDeliveryStatus.SUPERSEDED)
        assertThat(fixture.delivery.providerMessageId).isNull()
    }

    @Test
    fun `in-flight START callback binds without reviving an existing END_PENDING tombstone`() {
        val fixture = fixture(endPending = true)

        fixture.writer.registerUpdateToken(
            memberId = 7,
            deviceFingerprint = fixture.deviceFingerprint,
            scheduleId = 41,
            activityId = "activity-41",
            updateToken = fixture.updateToken,
            updateTokenFingerprint = fixture.updateTokenFingerprint,
            generation = 3,
            presentedSessionGeneration = 9,
        )

        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(fixture.activity.activityId).isEqualTo("activity-41")
        assertThat(fixture.delivery.status).isEqualTo(LiveActivityDeliveryStatus.SUPERSEDED)
        assertThat(fixture.activity.dispatchLeaseId).isNull()
        assertThat(fixture.fcm.dispatchLeaseId).isNull()
        assertThat(fixture.startToken.dispatchLeaseId).isNull()
        verify(fixture.projectionService).enqueueCurrentUpdate(31)
    }

    @Test
    fun `mismatched in-flight START generation remains fail closed with every lease intact`() {
        val fixture = fixture(deliveryGeneration = 2)

        assertThatThrownBy {
            fixture.writer.registerUpdateToken(
                memberId = 7,
                deviceFingerprint = fixture.deviceFingerprint,
                scheduleId = 41,
                activityId = "activity-41",
                updateToken = fixture.updateToken,
                updateTokenFingerprint = fixture.updateTokenFingerprint,
                generation = 3,
                presentedSessionGeneration = 9,
            )
        }.isInstanceOf(ConcurrencyFailureException::class.java)

        assertThat(fixture.activity.state).isEqualTo(ScheduleLiveActivityState.START_PENDING)
        assertThat(fixture.delivery.status).isEqualTo(LiveActivityDeliveryStatus.DISPATCHING)
        assertThat(fixture.activity.dispatchLeaseId).isEqualTo(LEASE_ID)
        assertThat(fixture.fcm.dispatchLeaseId).isEqualTo(LEASE_ID)
        assertThat(fixture.startToken.dispatchLeaseId).isEqualTo(LEASE_ID)
        verify(fixture.projectionService, never()).enqueueCurrentUpdate(any())
    }

    @Test
    fun `confirmed nonacceptance terminalizes an exact canceled tokenless in-flight START`() {
        val scenarios: List<Triple<String, LiveActivityDeliveryStatus, (Fixture) -> Unit>> = listOf(
            Triple("permanent rejection", LiveActivityDeliveryStatus.INVALID) { fixture ->
                fixture.coordinator.completePermanentFailure(
                    fixture.dispatchLease,
                    "PERMANENT_PROVIDER_REJECTION",
                    fixture.now.plusSeconds(1),
                )
            },
            Triple("invalid ActivityKit route", LiveActivityDeliveryStatus.INVALID) { fixture ->
                fixture.coordinator.completeInvalidActivityRoute(
                    fixture.dispatchLease,
                    fixture.now.plusSeconds(1),
                )
            },
            Triple("invalid FCM route", LiveActivityDeliveryStatus.INVALID) { fixture ->
                fixture.coordinator.completeInvalidFcmRoute(
                    fixture.dispatchLease,
                    fixture.now.plusSeconds(1),
                )
            },
            Triple("retryable explicit nonacceptance", LiveActivityDeliveryStatus.SUPERSEDED) { fixture ->
                fixture.coordinator.completeRetryableFailure(
                    fixture.dispatchLease,
                    "QUOTA_EXCEEDED",
                    fixture.now.plusSeconds(1),
                )
            },
        )

        scenarios.forEach { (description, expectedDeliveryStatus, complete) ->
            val fixture = fixture(endPending = true)

            complete(fixture)

            assertThat(fixture.activity.state)
                .describedAs(description)
                .isEqualTo(ScheduleLiveActivityState.ENDED)
            assertThat(fixture.delivery.status)
                .describedAs(description)
                .isEqualTo(expectedDeliveryStatus)
            assertThat(fixture.activity.activityId).describedAs(description).isNull()
            assertThat(fixture.activity.updateToken).describedAs(description).isNull()
            assertThat(fixture.activity.dispatchLeaseId).describedAs(description).isNull()
            assertThat(fixture.fcm.dispatchLeaseId).describedAs(description).isNull()
            assertThat(fixture.startToken.dispatchLeaseId).describedAs(description).isNull()
        }
    }

    @Test
    fun `provider success or ambiguity retains an exact canceled START tombstone`() {
        val scenarios: List<Triple<String, LiveActivityDeliveryStatus, (Fixture) -> Unit>> = listOf(
            Triple("provider success", LiveActivityDeliveryStatus.SUCCESS) { fixture ->
                fixture.coordinator.completeSuccess(
                    fixture.dispatchLease,
                    "provider-message-id",
                    fixture.now.plusSeconds(1),
                )
            },
            Triple(
                "ambiguous provider boundary",
                LiveActivityDeliveryStatus.UNCERTAIN,
            ) { fixture ->
                fixture.coordinator.completeAmbiguous(
                    fixture.dispatchLease,
                    "PROVIDER_BOUNDARY_UNKNOWN",
                    fixture.now.plusSeconds(1),
                )
            },
        )

        scenarios.forEach { (description, expectedDeliveryStatus, complete) ->
            val fixture = fixture(endPending = true)

            complete(fixture)

            assertThat(fixture.delivery.status)
                .describedAs(description)
                .isEqualTo(expectedDeliveryStatus)
            assertThat(fixture.activity.state)
                .describedAs(description)
                .isEqualTo(ScheduleLiveActivityState.END_PENDING)
            assertThat(fixture.activity.activityId).describedAs(description).isNull()
            assertThat(fixture.activity.updateToken).describedAs(description).isNull()
            assertThat(fixture.activity.dispatchLeaseId).describedAs(description).isNull()
            assertThat(fixture.fcm.dispatchLeaseId).describedAs(description).isNull()
            assertThat(fixture.startToken.dispatchLeaseId).describedAs(description).isNull()
        }
    }

    private fun fixture(
        endPending: Boolean = false,
        deliveryGeneration: Long = 3,
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
        val startTokenValue = "b".repeat(64)
        val startFingerprint = OpaquePushIdentifier.fingerprint(startTokenValue)
        val updateToken = "c".repeat(64)
        val updateTokenFingerprint = OpaquePushIdentifier.fingerprint(updateToken)
        val fcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "fcm-token",
            deviceFingerprint = deviceFingerprint,
        )
        val startToken = LiveActivityStartToken(
            id = 21,
            memberId = 7,
            pushDeviceTokenId = 11,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            pushToStartToken = startTokenValue,
            tokenFingerprint = startFingerprint,
            tokenGeneration = 4,
            lastSeenAt = now,
        )
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            startTokenId = 21,
            startTokenFingerprint = startFingerprint,
            startTokenGeneration = 4,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 1,
            contentFingerprint = "d".repeat(64),
            attributesJson = "{}",
            contentStateJson = "{}",
            expiresAt = now.plusSeconds(3_600),
        )
        val delivery = LiveActivityDelivery(
            id = 51,
            eventKey = "in-flight-start",
            liveActivityId = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            fcmTokenFingerprint = fcm.tokenFingerprint,
            fcmOwnershipVersion = fcm.ownershipVersion,
            deviceFingerprint = deviceFingerprint,
            activityToken = startTokenValue,
            activityTokenFingerprint = startFingerprint,
            startTokenId = 21,
            startTokenGeneration = 4,
            operation = LiveActivityDeliveryOperation.START,
            revision = 1,
            sourceNotificationGeneration = deliveryGeneration,
            apnsTimestamp = now.epochSecond,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            attributesJson = "{}",
            contentStateJson = "{}",
            expirationAt = now.plusSeconds(3_600),
            availableAt = now,
        )
        val leaseUntil = now.plusSeconds(600)
        assertThat(activity.acquireDispatchLease(LEASE_ID, now, leaseUntil)).isTrue()
        assertThat(fcm.acquireDispatchLease(LEASE_ID, now, leaseUntil)).isTrue()
        assertThat(startToken.acquireDispatchLease(LEASE_ID, now, leaseUntil)).isTrue()
        delivery.beginDispatch(now, LEASE_ID, leaseUntil)
        if (endPending) activity.markEndPending(now, "SCHEDULE_CANCELED")

        whenever(activityRepository.findUpdateTokenOwnerMemberIds(updateTokenFingerprint))
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
        whenever(startRepository.findByIdForUpdate(21)).thenReturn(startToken)
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
            activityToken = startTokenValue,
            operation = LiveActivityDeliveryOperation.START,
            timestamp = delivery.apnsTimestamp,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            attributesJson = "{}",
            contentStateJson = "{}",
            alertTitle = "출발 준비",
            alertBody = "이동 현황을 확인하세요.",
            staleAt = null,
            dismissalAt = null,
            expirationAt = delivery.expirationAt,
        )
        return Fixture(
            now = now,
            memberRepository = memberRepository,
            deviceRepository = deviceRepository,
            startRepository = startRepository,
            activityRepository = activityRepository,
            deliveryRepository = deliveryRepository,
            projectionService = projectionService,
            deviceFingerprint = deviceFingerprint,
            updateToken = updateToken,
            updateTokenFingerprint = updateTokenFingerprint,
            activity = activity,
            fcm = fcm,
            startToken = startToken,
            delivery = delivery,
            writer = writer,
            coordinator = coordinator,
            dispatchLease = dispatchLease,
        )
    }

    private data class Fixture(
        val now: Instant,
        val memberRepository: MemberRepository,
        val deviceRepository: NotificationDeviceTokenRepository,
        val startRepository: LiveActivityStartTokenRepository,
        val activityRepository: ScheduleLiveActivityRepository,
        val deliveryRepository: LiveActivityDeliveryRepository,
        val projectionService: ScheduleLiveActivityService,
        val deviceFingerprint: String,
        val updateToken: String,
        val updateTokenFingerprint: String,
        val activity: ScheduleLiveActivity,
        val fcm: NotificationDeviceToken,
        val startToken: LiveActivityStartToken,
        val delivery: LiveActivityDelivery,
        val writer: LiveActivityTokenWriter,
        val coordinator: LiveActivityDispatchCoordinator,
        val dispatchLease: LiveActivityDispatchLease,
    )

    private companion object {
        const val LEASE_ID = "la-in-flight-start"
    }
}
