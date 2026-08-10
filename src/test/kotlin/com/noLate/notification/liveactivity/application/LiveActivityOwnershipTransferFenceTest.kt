package com.noLate.notification.liveactivity.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.member.domain.member.Member
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.application.service.PushTokenDispatchLeaseActiveException
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
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class LiveActivityOwnershipTransferFenceTest {
    @Test
    fun `start token foreign owner appearance aborts before any unfenced activity lock`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
            on { sessionGeneration } doReturn 9L
        }
        val foreign = LiveActivityStartToken(
            id = 21,
            memberId = 8,
            pushDeviceTokenId = 18,
            deviceFingerprint = "f".repeat(64),
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            pushToStartToken = "a".repeat(64),
            lastSeenAt = now,
        )
        whenever(startRepository.findRegistrationOwnerMemberIds(any(), any(), any()))
            .thenReturn(emptyList())
        whenever(memberRepository.findAllByIdsForUpdate(listOf(7))).thenReturn(listOf(member))
        whenever(startRepository.findRegistrationCandidateIds(any(), any(), any()))
            .thenReturn(listOf(21))
        whenever(startRepository.findAllById(listOf(21))).thenReturn(listOf(foreign))

        assertThatThrownBy {
            LiveActivityTokenWriter(
                memberRepository,
                deviceRepository,
                startRepository,
                activityRepository,
                deliveryRepository,
                mock(),
                Clock.fixed(now, ZoneOffset.UTC),
            ).registerStartToken(
                memberId = 7,
                deviceFingerprint = "a".repeat(64),
                activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
                token = "a".repeat(64),
                tokenFingerprint = com.noLate.notification.domain.OpaquePushIdentifier
                    .fingerprint("a".repeat(64)),
                presentedSessionGeneration = 9,
            )
        }.isInstanceOf(org.springframework.dao.ConcurrencyFailureException::class.java)

        verify(activityRepository, never()).findAllByMemberDeviceForUpdate(any(), any())
        verifyNoInteractions(deviceRepository)
    }

    @Test
    fun `update token foreign owner appearance aborts before collision activity lock`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
            on { sessionGeneration } doReturn 9L
        }
        val target = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            deviceFingerprint = "a".repeat(64),
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = "{}",
            expiresAt = now.plusSeconds(600),
        )
        val collision = ScheduleLiveActivity(
            id = 32,
            memberId = 8,
            scheduleId = 42,
            pushDeviceTokenId = 12,
            deviceFingerprint = "f".repeat(64),
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentFingerprint = "c".repeat(64),
            attributesJson = "{}",
            contentStateJson = "{}",
            expiresAt = now.plusSeconds(600),
        )
        val updateToken = "d".repeat(64)
        val updateFingerprint = com.noLate.notification.domain.OpaquePushIdentifier
            .fingerprint(updateToken)
        whenever(activityRepository.findUpdateTokenOwnerMemberIds(updateFingerprint))
            .thenReturn(emptyList())
        whenever(memberRepository.findAllByIdsForUpdate(listOf(7))).thenReturn(listOf(member))
        whenever(
            activityRepository.findByScheduleIdAndMemberIdAndDeviceFingerprint(
                41,
                7,
                "a".repeat(64),
            )
        ).thenReturn(target)
        whenever(activityRepository.findByUpdateTokenFingerprint(updateFingerprint))
            .thenReturn(collision)

        assertThatThrownBy {
            LiveActivityTokenWriter(
                memberRepository,
                deviceRepository,
                startRepository,
                activityRepository,
                deliveryRepository,
                mock(),
                Clock.fixed(now, ZoneOffset.UTC),
            ).registerUpdateToken(
                memberId = 7,
                deviceFingerprint = "a".repeat(64),
                scheduleId = 41,
                activityId = "activity-41",
                updateToken = updateToken,
                updateTokenFingerprint = updateFingerprint,
                generation = 3,
                presentedSessionGeneration = 9,
            )
        }.isInstanceOf(org.springframework.dao.ConcurrencyFailureException::class.java)

        verify(activityRepository, never()).findAllByIdsForUpdate(any())
        verifyNoInteractions(deviceRepository)
    }

    @Test
    fun `one-generation-late client ended ACK releases exact retired END reservation`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
            on { sessionGeneration } doReturn 10L
        }
        val deviceFingerprint = "a".repeat(64)
        val updateToken = "c".repeat(64)
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 1,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = "{}",
            expiresAt = now.plusSeconds(3_600),
        )
        activity.bindUpdateToken("activity-41", updateToken)
        activity.markEndPending(now, "FCM_OWNERSHIP_TRANSFER")
        activity.authorizeEndedReportForSession(9)
        val fcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "old-fcm",
            deviceFingerprint = deviceFingerprint,
        )
        val reservation = liveActivityEndRetirementReservation(7, deviceFingerprint)
        assertThat(fcm.acquireDispatchLease(reservation, now, now.plusSeconds(600))).isTrue()
        assertThat(fcm.requestRetirement(now)).isFalse()
        val delivery = LiveActivityDelivery(
            id = 51,
            eventKey = "end-before-client-ack",
            liveActivityId = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            fcmTokenFingerprint = fcm.tokenFingerprint,
            fcmOwnershipVersion = fcm.ownershipVersion,
            deviceFingerprint = deviceFingerprint,
            activityToken = updateToken,
            activityTokenFingerprint = com.noLate.notification.domain.OpaquePushIdentifier
                .fingerprint(updateToken),
            targetActivityId = "activity-41",
            operation = LiveActivityDeliveryOperation.END,
            revision = activity.contentRevision,
            sourceNotificationGeneration = 3,
            apnsTimestamp = now.epochSecond,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            contentStateJson = "{}",
            expirationAt = now.plusSeconds(3_600),
            availableAt = now,
        )

        whenever(memberRepository.findByIdForUpdate(7)).thenReturn(member)
        whenever(
            activityRepository.findByMemberIdAndActivityIdAndDeviceFingerprint(
                7,
                "activity-41",
                deviceFingerprint,
            )
        ).thenReturn(activity)
        whenever(activityRepository.findByIdForUpdate(31)).thenReturn(activity)
        whenever(deviceRepository.findByIdForUpdate(11)).thenReturn(fcm)
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(listOf(delivery))

        LiveActivityTokenWriter(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            mock(),
            Clock.fixed(now, ZoneOffset.UTC),
        ).reportEnded(
            memberId = 7,
            deviceFingerprint = deviceFingerprint,
            scheduleId = 41,
            activityId = "activity-41",
            presentedSessionGeneration = 9,
        )

        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.ENDED)
        assertThat(delivery.status).isEqualTo(LiveActivityDeliveryStatus.SUPERSEDED)
        assertThat(fcm.retirementRequested).isTrue()
        assertThat(fcm.dispatchLeaseId).isNull()
    }

    @Test
    fun `current old-owner late token binds through exact retired reservation and enqueues END only`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
            on { sessionGeneration } doReturn 9L
        }
        val deviceFingerprint = "a".repeat(64)
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 1,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = "{}",
            expiresAt = now.plusSeconds(3_600),
        )
        activity.markEndPending(now, "FCM_OWNERSHIP_TRANSFER")
        val fcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "old-fcm",
            deviceFingerprint = deviceFingerprint,
        )
        val reservation = liveActivityEndRetirementReservation(7, deviceFingerprint)
        assertThat(fcm.acquireDispatchLease(reservation, now, now.plusSeconds(600))).isTrue()
        assertThat(fcm.requestRetirement(now)).isFalse()
        val updateToken = "c".repeat(64)
        val updateFingerprint = com.noLate.notification.domain.OpaquePushIdentifier
            .fingerprint(updateToken)

        whenever(activityRepository.findUpdateTokenOwnerMemberIds(updateFingerprint))
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
        whenever(
            deviceRepository.findAllByMemberIdAndDeviceFingerprintForUpdate(7, deviceFingerprint)
        ).thenReturn(listOf(fcm))
        whenever(activityRepository.saveAndFlush(activity)).thenReturn(activity)
        whenever(activityRepository.findById(31)).thenReturn(Optional.of(activity))
        whenever(memberRepository.findByIdForUpdate(7)).thenReturn(member)
        whenever(activityRepository.findByIdForUpdate(31)).thenReturn(activity)
        whenever(deviceRepository.findByIdForUpdate(11)).thenReturn(fcm)
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(emptyList())
        whenever(deliveryRepository.save(any<LiveActivityDelivery>())).thenAnswer { it.arguments[0] }

        val projectionService = ScheduleLiveActivityService(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            jacksonObjectMapper(),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        LiveActivityTokenWriter(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            projectionService,
            Clock.fixed(now, ZoneOffset.UTC),
        ).registerUpdateToken(
            memberId = 7,
            deviceFingerprint = deviceFingerprint,
            scheduleId = 41,
            activityId = "activity-41",
            updateToken = updateToken,
            updateTokenFingerprint = updateFingerprint,
            generation = 3,
            presentedSessionGeneration = 9,
        )

        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(activity.activityId).isEqualTo("activity-41")
        val delivery = argumentCaptor<LiveActivityDelivery>()
        verify(deliveryRepository).save(delivery.capture())
        assertThat(delivery.firstValue.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(delivery.firstValue.activityTokenFingerprint).isEqualTo(updateFingerprint)
        assertThat(fcm.memberId).isEqualTo(7)
    }

    @Test
    fun `late END token rebinds an UNREGISTERED activity to the new same-device FCM epoch`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
            on { sessionGeneration } doReturn 9L
        }
        val deviceFingerprint = "a".repeat(64)
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 99,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 1,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = "{}",
            expiresAt = now.plusSeconds(3_600),
        )
        activity.markEndPending(now, "EXPIRED")
        val replacementFcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "replacement-fcm",
            deviceFingerprint = deviceFingerprint,
            ownershipVersion = 4,
        )
        val updateToken = "c".repeat(64)
        val updateFingerprint = com.noLate.notification.domain.OpaquePushIdentifier
            .fingerprint(updateToken)

        whenever(activityRepository.findUpdateTokenOwnerMemberIds(updateFingerprint))
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
        whenever(
            deviceRepository.findAllByMemberIdAndDeviceFingerprintForUpdate(7, deviceFingerprint)
        ).thenReturn(listOf(replacementFcm))
        whenever(activityRepository.saveAndFlush(activity)).thenReturn(activity)
        whenever(activityRepository.findById(31)).thenReturn(Optional.of(activity))
        whenever(memberRepository.findByIdForUpdate(7)).thenReturn(member)
        whenever(activityRepository.findByIdForUpdate(31)).thenReturn(activity)
        whenever(deviceRepository.findByIdForUpdate(11)).thenReturn(replacementFcm)
        whenever(deliveryRepository.findByEventKeyForUpdate(any())).thenReturn(null)
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(emptyList())
        whenever(deliveryRepository.save(any<LiveActivityDelivery>())).thenAnswer { it.arguments[0] }

        val projectionService = ScheduleLiveActivityService(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            jacksonObjectMapper(),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        LiveActivityTokenWriter(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            projectionService,
            Clock.fixed(now, ZoneOffset.UTC),
        ).registerUpdateToken(
            memberId = 7,
            deviceFingerprint = deviceFingerprint,
            scheduleId = 41,
            activityId = "activity-41",
            updateToken = updateToken,
            updateTokenFingerprint = updateFingerprint,
            generation = 3,
            presentedSessionGeneration = 9,
        )

        assertThat(activity.pushDeviceTokenId).isEqualTo(11)
        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        val delivery = argumentCaptor<LiveActivityDelivery>()
        verify(deliveryRepository).save(delivery.capture())
        assertThat(delivery.firstValue.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(delivery.firstValue.pushDeviceTokenId).isEqualTo(11)
        assertThat(delivery.firstValue.fcmOwnershipVersion).isEqualTo(4)
    }

    @Test
    fun `ACTIVE cancellation without a current FCM route preserves an END tombstone`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val objectMapper = jacksonObjectMapper()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
        }
        val content = NoLateDepartureContentStateWire(
            revision = 1,
            travelMinutes = 40,
            firstWaitMinutes = 20,
            predictedArrivalEpochSeconds = now.plusSeconds(3_600).epochSecond,
            recommendedDepartureEpochSeconds = now.epochSecond,
            updatedAtEpochSeconds = now.epochSecond,
            actionExpiresAtEpochSeconds = now.plusSeconds(3_600).epochSecond,
            status = "tracking",
            routeSegments = emptyList(),
        )
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 99,
            deviceFingerprint = "a".repeat(64),
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 1,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = objectMapper.writeValueAsString(content),
            expiresAt = now.plusSeconds(3_600),
        )
        activity.bindUpdateToken("activity-41", "old-update-token")
        whenever(memberRepository.findByIdForUpdate(7)).thenReturn(member)
        whenever(activityRepository.findAllByMemberScheduleForUpdate(7, 41))
            .thenReturn(listOf(activity))
        whenever(deviceRepository.findByIdForUpdate(99)).thenReturn(null)
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(emptyList())

        ScheduleLiveActivityService(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            objectMapper,
            Clock.fixed(now, ZoneOffset.UTC),
        ).endForMemberSchedule(7, 41, "SCHEDULE_CANCELLED")

        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(activity.endReason).isEqualTo("SCHEDULE_CANCELLED")
        assertThat(
            objectMapper.readValue(
                activity.contentStateJson,
                NoLateDepartureContentStateWire::class.java,
            ).status
        ).isEqualTo("cancelled")
        verify(deliveryRepository, never()).save(any<LiveActivityDelivery>())
    }

    @Test
    fun `TOKEN_PENDING cancellation survives missing FCM and a rotated callback emits END`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val objectMapper = jacksonObjectMapper()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
            on { sessionGeneration } doReturn 9L
        }
        val deviceFingerprint = "a".repeat(64)
        val content = NoLateDepartureContentStateWire(
            revision = 1,
            travelMinutes = 40,
            firstWaitMinutes = 20,
            predictedArrivalEpochSeconds = now.plusSeconds(3_600).epochSecond,
            recommendedDepartureEpochSeconds = now.epochSecond,
            updatedAtEpochSeconds = now.epochSecond,
            actionExpiresAtEpochSeconds = now.plusSeconds(3_600).epochSecond,
            status = "tracking",
            routeSegments = emptyList(),
        )
        val oldUpdateToken = "old-update-token"
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 99,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 1,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = objectMapper.writeValueAsString(content),
            expiresAt = now.plusSeconds(3_600),
        )
        activity.bindUpdateToken("activity-41", oldUpdateToken)
        assertThat(
            activity.invalidateUpdateCredential(
                OpaquePushIdentifier.fingerprint(oldUpdateToken),
                activity.updateTokenGeneration,
            )
        ).isTrue()
        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.TOKEN_PENDING)

        whenever(memberRepository.findByIdForUpdate(7)).thenReturn(member)
        whenever(activityRepository.findAllByMemberScheduleForUpdate(7, 41))
            .thenReturn(listOf(activity))
        whenever(deviceRepository.findByIdForUpdate(99)).thenReturn(null)
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(emptyList())
        val projectionService = ScheduleLiveActivityService(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            objectMapper,
            Clock.fixed(now, ZoneOffset.UTC),
        )

        projectionService.endForMemberSchedule(7, 41, "EXPIRED")

        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(activity.updateToken).isNull()

        val replacementFcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "replacement-fcm",
            deviceFingerprint = deviceFingerprint,
            ownershipVersion = 4,
        )
        val rotatedUpdateToken = "rotated-update-token"
        val rotatedFingerprint = OpaquePushIdentifier.fingerprint(rotatedUpdateToken)
        whenever(activityRepository.findUpdateTokenOwnerMemberIds(rotatedFingerprint))
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
        whenever(
            deviceRepository.findAllByMemberIdAndDeviceFingerprintForUpdate(7, deviceFingerprint)
        ).thenReturn(listOf(replacementFcm))
        whenever(activityRepository.saveAndFlush(activity)).thenReturn(activity)
        whenever(activityRepository.findById(31)).thenReturn(Optional.of(activity))
        whenever(activityRepository.findByIdForUpdate(31)).thenReturn(activity)
        whenever(deviceRepository.findByIdForUpdate(11)).thenReturn(replacementFcm)
        whenever(deliveryRepository.findByEventKeyForUpdate(any())).thenReturn(null)
        whenever(deliveryRepository.save(any<LiveActivityDelivery>())).thenAnswer { it.arguments[0] }

        LiveActivityTokenWriter(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            projectionService,
            Clock.fixed(now, ZoneOffset.UTC),
        ).registerUpdateToken(
            memberId = 7,
            deviceFingerprint = deviceFingerprint,
            scheduleId = 41,
            activityId = "activity-41",
            updateToken = rotatedUpdateToken,
            updateTokenFingerprint = rotatedFingerprint,
            generation = 3,
            presentedSessionGeneration = 9,
        )

        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(activity.pushDeviceTokenId).isEqualTo(11)
        assertThat(activity.updateTokenFingerprint).isEqualTo(rotatedFingerprint)
        val replacementEnd = argumentCaptor<LiveActivityDelivery>()
        verify(deliveryRepository).save(replacementEnd.capture())
        assertThat(replacementEnd.firstValue.operation)
            .isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(replacementEnd.firstValue.fcmOwnershipVersion).isEqualTo(4)
        assertThat(replacementEnd.firstValue.activityTokenFingerprint)
            .isEqualTo(rotatedFingerprint)
    }

    @Test
    fun `FCM handoff prelock reports an Activity provider lease through registration poll exception`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val deviceFingerprint = "a".repeat(64)
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 99,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = "{}",
            expiresAt = now.plusSeconds(3_600),
        )
        activity.markEndPending(now, "EXPIRED")
        assertThat(activity.acquireDispatchLease("provider-lease", now, now.plusSeconds(120)))
            .isTrue()
        whenever(activityRepository.findAllByMemberDeviceForUpdate(7, deviceFingerprint))
            .thenReturn(listOf(activity))
        whenever(deviceRepository.findAllById(listOf(99))).thenReturn(emptyList())
        val service = ScheduleLiveActivityService(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            jacksonObjectMapper(),
            Clock.fixed(now, ZoneOffset.UTC),
        )

        assertThatThrownBy {
            service.prepareSameDeviceFcmEndHandoff(7, deviceFingerprint, emptyList())
        }.isInstanceOf(PushTokenDispatchLeaseActiveException::class.java)
    }

    @Test
    fun `FCM handoff apply reports DISPATCHING END through registration poll exception`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val deviceFingerprint = "a".repeat(64)
        val updateToken = "activity-update-token"
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 99,
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
        activity.bindUpdateToken("activity-41", updateToken)
        activity.markEndPending(now, "EXPIRED")
        val replacementFcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "replacement-fcm",
            deviceFingerprint = deviceFingerprint,
            ownershipVersion = 4,
        )
        val dispatchingEnd = LiveActivityDelivery(
            id = 51,
            eventKey = "dispatching-old-end",
            liveActivityId = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 99,
            fcmTokenFingerprint = "c".repeat(64),
            fcmOwnershipVersion = 3,
            deviceFingerprint = deviceFingerprint,
            activityToken = updateToken,
            activityTokenFingerprint = requireNotNull(activity.updateTokenFingerprint),
            updateTokenGeneration = activity.updateTokenGeneration,
            targetActivityId = "activity-41",
            operation = LiveActivityDeliveryOperation.END,
            revision = activity.contentRevision,
            sourceNotificationGeneration = 3,
            apnsTimestamp = now.epochSecond,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            contentStateJson = "{}",
            expirationAt = now.plusSeconds(3_600),
            status = LiveActivityDeliveryStatus.DISPATCHING,
            availableAt = now,
            dispatchLeaseId = "provider-lease",
            dispatchLeaseUntil = now.plusSeconds(120),
        )
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(listOf(dispatchingEnd))
        val service = ScheduleLiveActivityService(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            jacksonObjectMapper(),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        val plan = SameDeviceFcmEndHandoffPlan(
            memberId = 7,
            deviceFingerprint = deviceFingerprint,
            oldPushDeviceTokenIds = setOf(99),
            replaceableExistingPushDeviceTokenIds = emptySet(),
            activities = listOf(activity),
        )

        assertThatThrownBy {
            service.applySameDeviceFcmEndHandoff(plan, replacementFcm)
        }.isInstanceOf(PushTokenDispatchLeaseActiveException::class.java)
        assertThat(activity.pushDeviceTokenId).isEqualTo(99)
    }

    @Test
    fun `terminal job fence rejects stale post-commit ETA projection before START resurrection`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val pushJobRepository = mock<SchedulePushJobRepository>()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
        }
        val job = mock<SchedulePushJob> {
            on { status } doReturn SchedulePushJobStatus.CANCELED
            on { notificationGeneration } doReturn 3L
            on { checkCount } doReturn 5
        }
        whenever(memberRepository.findByIdForUpdate(7)).thenReturn(member)
        whenever(pushJobRepository.findByScheduleIdAndMemberIdForUpdate(41, 7))
            .thenReturn(job)
        val projection = ScheduleLiveActivityEtaProjection(
            memberId = 7,
            scheduleId = 41,
            notificationGeneration = 3,
            checkCount = 4,
            evaluatedAt = now,
            scheduleTitle = "회의",
            destinationName = "강남역",
            scheduleStartAt = now.plusSeconds(3_600),
            travelMinutes = 60,
            recommendedDepartureAt = now,
            predictedArrivalAt = now.plusSeconds(3_600),
            firstBoardingWaitMinutes = 20,
            etaSource = TrafficSource.LIVE_PROVIDER,
            etaStale = false,
            routeSegments = emptyList(),
            staleAt = now.plusSeconds(600),
        )

        ScheduleLiveActivityService(
            memberRepository = memberRepository,
            deviceTokenRepository = deviceRepository,
            startTokenRepository = startRepository,
            activityRepository = activityRepository,
            deliveryRepository = deliveryRepository,
            objectMapper = jacksonObjectMapper(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            pushJobRepository = pushJobRepository,
        ).projectEta(projection)

        verifyNoInteractions(startRepository, activityRepository, deviceRepository, deliveryRepository)
    }

    @Test
    fun `rotated start credential never replays an ambiguous START`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val objectMapper = jacksonObjectMapper()
        val deviceFingerprint = "a".repeat(64)
        val oldStartFingerprint = "b".repeat(64)
        val newStartToken = "c".repeat(64)
        val newStartFingerprint = com.noLate.notification.domain.OpaquePushIdentifier
            .fingerprint(newStartToken)
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
        }
        val fcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "fcm",
            deviceFingerprint = deviceFingerprint,
        )
        val startToken = LiveActivityStartToken(
            id = 21,
            memberId = 7,
            pushDeviceTokenId = 11,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            pushToStartToken = newStartToken,
            tokenFingerprint = newStartFingerprint,
            tokenGeneration = 2,
            lastSeenAt = now,
        )
        val content = NoLateDepartureContentStateWire(
            revision = 2,
            travelMinutes = 40,
            firstWaitMinutes = 20,
            predictedArrivalEpochSeconds = now.plusSeconds(3_600).epochSecond,
            recommendedDepartureEpochSeconds = now.epochSecond,
            updatedAtEpochSeconds = now.epochSecond,
            actionExpiresAtEpochSeconds = now.plusSeconds(3_600).epochSecond,
            status = "tracking",
            routeSegments = emptyList(),
        )
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            startTokenId = 21,
            startTokenFingerprint = oldStartFingerprint,
            startTokenGeneration = 1,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 2,
            contentFingerprint = "d".repeat(64),
            attributesJson = "{}",
            contentStateJson = objectMapper.writeValueAsString(content),
            expiresAt = now.plusSeconds(3_600),
        )
        activity.markStartAccepted(2)
        val uncertain = LiveActivityDelivery(
            id = 51,
            eventKey = "old-uncertain",
            liveActivityId = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            fcmTokenFingerprint = fcm.tokenFingerprint,
            fcmOwnershipVersion = fcm.ownershipVersion,
            deviceFingerprint = deviceFingerprint,
            activityToken = "e".repeat(64),
            activityTokenFingerprint = oldStartFingerprint,
            startTokenId = 21,
            startTokenGeneration = 1,
            operation = LiveActivityDeliveryOperation.START,
            revision = 2,
            sourceNotificationGeneration = 3,
            apnsTimestamp = now.minusSeconds(60).epochSecond,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            attributesJson = "{}",
            contentStateJson = activity.contentStateJson,
            expirationAt = activity.expiresAt,
            status = LiveActivityDeliveryStatus.UNCERTAIN,
            availableAt = now.minusSeconds(60),
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
        whenever(deviceRepository.findByIdForUpdate(11)).thenReturn(fcm)
        whenever(startRepository.findByIdForUpdate(21)).thenReturn(startToken)
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(listOf(uncertain), emptyList())
        whenever(deliveryRepository.save(any<LiveActivityDelivery>())).thenAnswer { it.arguments[0] }

        ScheduleLiveActivityService(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            objectMapper,
            Clock.fixed(now, ZoneOffset.UTC),
        ).reconcileStartToken(7, deviceFingerprint)

        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.START_SENT)
        assertThat(activity.contentRevision).isEqualTo(2)
        assertThat(activity.startTokenFingerprint).isEqualTo(oldStartFingerprint)
        assertThat(uncertain.status).isEqualTo(LiveActivityDeliveryStatus.UNCERTAIN)
        verify(deliveryRepository, never()).save(any<LiveActivityDelivery>())
    }

    @Test
    fun `old owner ACTIVE activity freezes END and reserves FCM before ownership transfer`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val objectMapper = jacksonObjectMapper()
        val fcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "old-fcm",
            deviceFingerprint = "a".repeat(64),
        )
        val content = NoLateDepartureContentStateWire(
            revision = 2,
            travelMinutes = 40,
            firstWaitMinutes = 20,
            predictedArrivalEpochSeconds = now.plusSeconds(3_600).epochSecond,
            recommendedDepartureEpochSeconds = now.epochSecond,
            updatedAtEpochSeconds = now.epochSecond,
            actionExpiresAtEpochSeconds = now.plusSeconds(3_600).epochSecond,
            status = "tracking",
            routeSegments = emptyList(),
        )
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
            contentStateJson = objectMapper.writeValueAsString(content),
            expiresAt = now.plusSeconds(3_600),
        )
        activity.bindUpdateToken("activity-41", "c".repeat(64))

        whenever(
            activityRepository.findAllByMembersAndPushTokensAndStatesForUpdate(any(), any(), any())
        ).thenReturn(listOf(activity))
        whenever(deviceRepository.findByIdForUpdate(11)).thenReturn(fcm)
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(emptyList())
        whenever(deliveryRepository.save(any<LiveActivityDelivery>())).thenAnswer { it.arguments[0] }

        val service = ScheduleLiveActivityService(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            objectMapper,
            Clock.fixed(now, ZoneOffset.UTC),
        )

        val reserved = service.prepareEndBeforeFcmOwnershipTransfer(listOf(7), listOf(11))

        assertThat(reserved).containsExactly(11)
        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(fcm.memberId).isEqualTo(7)
        assertThat(fcm.retirementRequested).isTrue()
        assertThat(fcm.dispatchLeaseId)
            .isEqualTo(liveActivityEndRetirementReservation(7, "a".repeat(64)))
        val delivery = argumentCaptor<LiveActivityDelivery>()
        verify(deliveryRepository).save(delivery.capture())
        assertThat(delivery.firstValue.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(delivery.firstValue.fcmOwnershipVersion).isEqualTo(0)
        assertThat(delivery.firstValue.pushDeviceTokenId).isEqualTo(11)
    }

    @Test
    fun `route reservation is not released while an END outside the handoff plan is open`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val deviceFingerprint = "a".repeat(64)
        val updateToken = "c".repeat(64)
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 1,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = "{}",
            expiresAt = now.plusSeconds(3_600),
        )
        activity.bindUpdateToken("activity-41", updateToken)
        activity.markEndPending(now, "LOGOUT")
        val fcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = "device",
            platform = PushPlatform.IOS,
            token = "old-fcm",
            deviceFingerprint = deviceFingerprint,
        )
        val reservation = liveActivityEndRetirementReservation(7, deviceFingerprint)
        assertThat(fcm.acquireDispatchLease(reservation, now, now.plusSeconds(600))).isTrue()
        assertThat(fcm.requestRetirement(now)).isFalse()
        val outsidePlanEnd = LiveActivityDelivery(
            id = 52,
            eventKey = "legacy-generation-end",
            // Legacy generation replacement reused this aggregate id, but this immutable END
            // still targets the discarded generation/ActivityKit instance.
            liveActivityId = 31,
            memberId = 7,
            scheduleId = 42,
            pushDeviceTokenId = 11,
            fcmTokenFingerprint = fcm.tokenFingerprint,
            fcmOwnershipVersion = fcm.ownershipVersion,
            deviceFingerprint = deviceFingerprint,
            activityToken = updateToken,
            activityTokenFingerprint = OpaquePushIdentifier.fingerprint(updateToken),
            targetActivityId = "legacy-activity",
            operation = LiveActivityDeliveryOperation.END,
            revision = 2,
            sourceNotificationGeneration = 2,
            apnsTimestamp = now.epochSecond,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            contentStateJson = "{}",
            expirationAt = now.plusSeconds(3_600),
            availableAt = now,
        )
        whenever(
            deliveryRepository.findAllOpenEndByPushDeviceTokenForUpdate(any(), any(), any())
        ).thenReturn(listOf(outsidePlanEnd))
        val service = ScheduleLiveActivityService(
            memberRepository,
            deviceRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            jacksonObjectMapper(),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        val plan = SameDeviceFcmEndHandoffPlan(
            memberId = 7,
            deviceFingerprint = deviceFingerprint,
            oldPushDeviceTokenIds = setOf(11),
            replaceableExistingPushDeviceTokenIds = setOf(11),
            activities = listOf(activity),
        )

        assertThatThrownBy {
            service.releaseFcmEndReservationForHandoff(plan, fcm)
        }.isInstanceOf(PushTokenDispatchLeaseActiveException::class.java)
        assertThat(fcm.dispatchLeaseId).isEqualTo(reservation)
        assertThat(fcm.retirementRequested).isTrue()
    }
}
