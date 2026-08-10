package com.noLate.notification.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.liveactivity.application.NoLateDepartureContentStateWire
import com.noLate.notification.liveactivity.application.NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.liveactivity.application.ScheduleLiveActivityService
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.CannotAcquireLockException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class NotificationTokenOwnershipTransferFenceTest {
    @Test
    fun `new Live Activity owner after member snapshot aborts before token rows are locked`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val tokenRepository = mock<NotificationDeviceTokenRepository>()
        val memberRepository = mock<MemberRepository>()
        val liveActivityService = mock<ScheduleLiveActivityService>()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
            on { sessionGeneration } doReturn 3L
        }
        val deviceId = "device"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val tokenFingerprint = OpaquePushIdentifier.fingerprint("new-fcm")
        whenever(
            tokenRepository.findRegistrationCandidateOwnerMemberIds(
                tokenFingerprint,
                deviceFingerprint,
            )
        ).thenReturn(emptyList())
        whenever(
            liveActivityService.findLiveActivityHandoffOwnerMemberIdsForRegistration(
                deviceFingerprint,
            )
        ).thenReturn(emptyList(), listOf(8))
        whenever(memberRepository.findAllByIdsForUpdate(listOf(7))).thenReturn(listOf(member))

        assertThatThrownBy {
            NotificationTokenWriter(
                tokenRepository,
                memberRepository,
                clock = Clock.fixed(now, ZoneOffset.UTC),
                scheduleLiveActivityService = liveActivityService,
            ).register(
                memberId = 7,
                deviceId = deviceId,
                deviceFingerprint = deviceFingerprint,
                platform = PushPlatform.IOS,
                token = "new-fcm",
                tokenFingerprint = tokenFingerprint,
                accessTokenIssuedAt = now,
                accessTokenSessionGeneration = 3,
            )
        }.isInstanceOf(CannotAcquireLockException::class.java)

        verify(tokenRepository, never()).findRegistrationCandidateIds(any(), any())
    }

    @Test
    fun `writer commits old owner END reservation and defers ownership replacement`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val tokenRepository = mock<NotificationDeviceTokenRepository>()
        val memberRepository = mock<MemberRepository>()
        val liveActivityService = mock<ScheduleLiveActivityService>()
        val oldMember = mock<Member> { on { id } doReturn 7L }
        val newMember = mock<Member> {
            on { id } doReturn 8L
            on { deleted } doReturn false
            on { sessionGeneration } doReturn 3L
        }
        val deviceId = "device"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val token = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "old-fcm",
            deviceFingerprint = deviceFingerprint,
        )
        val newTokenFingerprint = OpaquePushIdentifier.fingerprint("new-fcm")

        whenever(
            tokenRepository.findRegistrationCandidateOwnerMemberIds(
                newTokenFingerprint,
                deviceFingerprint,
            )
        ).thenReturn(listOf(7))
        whenever(memberRepository.findAllByIdsForUpdate(listOf(7, 8)))
            .thenReturn(listOf(oldMember, newMember))
        whenever(
            tokenRepository.findRegistrationCandidateIds(newTokenFingerprint, deviceFingerprint)
        ).thenReturn(listOf(11))
        whenever(tokenRepository.findAllById(listOf(11))).thenReturn(listOf(token))
        whenever(tokenRepository.findAllByIdsForUpdate(listOf(11))).thenReturn(listOf(token))
        whenever(
            liveActivityService.prepareEndBeforeFcmOwnershipTransfer(listOf(7), listOf(11))
        ).thenAnswer {
            token.requestRetirement(now)
            setOf(11L)
        }

        val result = NotificationTokenWriter(
            tokenRepository,
            memberRepository,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            scheduleLiveActivityService = liveActivityService,
        ).register(
            memberId = 8,
            deviceId = deviceId,
            deviceFingerprint = deviceFingerprint,
            platform = PushPlatform.IOS,
            token = "new-fcm",
            tokenFingerprint = newTokenFingerprint,
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 3,
        )

        assertThat(result.retryAfterCommit).isTrue()
        assertThat(token.memberId).isEqualTo(7)
        assertThat(token.ownershipVersion).isZero()
        assertThat(token.retirementRequested).isTrue()
        verify(tokenRepository, never()).saveAndFlush(any<NotificationDeviceToken>())
    }

    @Test
    fun `UNREGISTERED END tombstone advances the same-device FCM epoch and replans END atomically`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val tokenRepository = mock<NotificationDeviceTokenRepository>()
        val memberRepository = mock<MemberRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
            on { sessionGeneration } doReturn 3L
        }
        val deviceId = "device"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val oldFcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "old-fcm",
            deviceFingerprint = deviceFingerprint,
            ownershipVersion = 4,
        )
        assertThat(oldFcm.requestRetirement(now)).isTrue()
        val content = NoLateDepartureContentStateWire(
            revision = 2,
            travelMinutes = 40,
            firstWaitMinutes = 20,
            predictedArrivalEpochSeconds = now.plusSeconds(3_600).epochSecond,
            recommendedDepartureEpochSeconds = now.epochSecond,
            updatedAtEpochSeconds = now.epochSecond,
            actionExpiresAtEpochSeconds = now.plusSeconds(3_600).epochSecond,
            status = "cancelled",
            routeSegments = emptyList(),
        )
        val updateToken = "activity-update-token"
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
            contentStateJson = jacksonObjectMapper().writeValueAsString(content),
            expiresAt = now.plusSeconds(3_600),
        )
        activity.bindUpdateToken("activity-41", updateToken)
        activity.markEndPending(now, "EXPIRED")
        val invalidOldEnd = LiveActivityDelivery(
            id = 51,
            eventKey = "old-invalid-end",
            liveActivityId = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            fcmTokenFingerprint = oldFcm.tokenFingerprint,
            fcmOwnershipVersion = 4,
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
            contentStateJson = activity.contentStateJson,
            expirationAt = now.plusSeconds(3_600),
            status = LiveActivityDeliveryStatus.INVALID,
            availableAt = now,
        )
        val newFcm = "replacement-fcm"
        val newFingerprint = OpaquePushIdentifier.fingerprint(newFcm)

        whenever(
            tokenRepository.findRegistrationCandidateOwnerMemberIds(
                newFingerprint,
                deviceFingerprint,
            )
        ).thenReturn(listOf(7))
        whenever(memberRepository.findAllByIdsForUpdate(listOf(7))).thenReturn(listOf(member))
        whenever(
            tokenRepository.findRegistrationCandidateIds(newFingerprint, deviceFingerprint)
        ).thenReturn(listOf(11))
        whenever(tokenRepository.findAllById(listOf(11))).thenReturn(listOf(oldFcm))
        whenever(tokenRepository.findAllByIdsForUpdate(listOf(11))).thenReturn(listOf(oldFcm))
        whenever(activityRepository.findAllByMemberDeviceForUpdate(7, deviceFingerprint))
            .thenReturn(listOf(activity))
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(emptyList())
        whenever(deliveryRepository.findByEventKeyForUpdate(any())).thenReturn(null)
        whenever(deliveryRepository.save(any<LiveActivityDelivery>()))
            .thenAnswer { it.arguments[0] }
        whenever(tokenRepository.saveAndFlush(oldFcm)).thenReturn(oldFcm)

        val liveActivityService = ScheduleLiveActivityService(
            memberRepository,
            tokenRepository,
            startRepository,
            activityRepository,
            deliveryRepository,
            jacksonObjectMapper(),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        val result = NotificationTokenWriter(
            tokenRepository,
            memberRepository,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            liveActivityDeliveryRepository = deliveryRepository,
            scheduleLiveActivityService = liveActivityService,
        ).register(
            memberId = 7,
            deviceId = deviceId,
            deviceFingerprint = deviceFingerprint,
            platform = PushPlatform.IOS,
            token = newFcm,
            tokenFingerprint = newFingerprint,
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 3,
        )

        assertThat(result.retryAfterCommit).isFalse()
        assertThat(result.result).isEqualTo("updated_live_activity_end_handoff")
        assertThat(oldFcm.id).isEqualTo(11)
        assertThat(oldFcm.token).isEqualTo(newFcm)
        assertThat(oldFcm.ownershipVersion).isEqualTo(5)
        assertThat(oldFcm.retirementRequested).isFalse()
        assertThat(activity.pushDeviceTokenId).isEqualTo(11)
        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(invalidOldEnd.status).isEqualTo(LiveActivityDeliveryStatus.INVALID)
        val replacementEnd = argumentCaptor<LiveActivityDelivery>()
        verify(deliveryRepository).save(replacementEnd.capture())
        assertThat(replacementEnd.firstValue.operation)
            .isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(replacementEnd.firstValue.fcmTokenFingerprint).isEqualTo(newFingerprint)
        assertThat(replacementEnd.firstValue.fcmOwnershipVersion).isEqualTo(5)
        assertThat(replacementEnd.firstValue.pushDeviceTokenId).isEqualTo(11)
    }

    @Test
    fun `same-device END handoff never steals a retired FCM provider lease`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val tokenRepository = mock<NotificationDeviceTokenRepository>()
        val memberRepository = mock<MemberRepository>()
        val member = mock<Member> {
            on { id } doReturn 7L
            on { deleted } doReturn false
            on { sessionGeneration } doReturn 3L
        }
        val deviceId = "device"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val oldFcm = NotificationDeviceToken(
            id = 11,
            memberId = 7,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "old-fcm",
            deviceFingerprint = deviceFingerprint,
        )
        assertThat(oldFcm.acquireDispatchLease("provider-lease", now, now.plusSeconds(120)))
            .isTrue()
        assertThat(oldFcm.requestRetirement(now)).isFalse()
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
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
        val newFingerprint = OpaquePushIdentifier.fingerprint("replacement-fcm")
        val liveActivityService = mock<ScheduleLiveActivityService>()
        val plan = com.noLate.notification.liveactivity.application.SameDeviceFcmEndHandoffPlan(
            memberId = 7,
            deviceFingerprint = deviceFingerprint,
            oldPushDeviceTokenIds = setOf(11),
            replaceableExistingPushDeviceTokenIds = setOf(11),
            activities = listOf(activity),
        )

        whenever(
            tokenRepository.findRegistrationCandidateOwnerMemberIds(
                newFingerprint,
                deviceFingerprint,
            )
        ).thenReturn(listOf(7))
        whenever(memberRepository.findAllByIdsForUpdate(listOf(7))).thenReturn(listOf(member))
        whenever(
            tokenRepository.findRegistrationCandidateIds(newFingerprint, deviceFingerprint)
        ).thenReturn(listOf(11))
        whenever(tokenRepository.findAllById(listOf(11))).thenReturn(listOf(oldFcm))
        whenever(tokenRepository.findAllByIdsForUpdate(listOf(11))).thenReturn(listOf(oldFcm))
        whenever(
            liveActivityService.prepareSameDeviceFcmEndHandoff(
                7,
                deviceFingerprint,
                listOf(11),
            )
        ).thenReturn(plan)

        assertThatThrownBy {
            NotificationTokenWriter(
                tokenRepository,
                memberRepository,
                clock = Clock.fixed(now, ZoneOffset.UTC),
                scheduleLiveActivityService = liveActivityService,
            ).register(
                memberId = 7,
                deviceId = deviceId,
                deviceFingerprint = deviceFingerprint,
                platform = PushPlatform.IOS,
                token = "replacement-fcm",
                tokenFingerprint = newFingerprint,
                accessTokenIssuedAt = now,
                accessTokenSessionGeneration = 3,
            )
        }.isInstanceOf(PushTokenDispatchLeaseActiveException::class.java)

        assertThat(oldFcm.token).isEqualTo("old-fcm")
        assertThat(oldFcm.retirementRequested).isTrue()
        verify(tokenRepository, never()).saveAndFlush(any<NotificationDeviceToken>())
        verify(liveActivityService, never()).applySameDeviceFcmEndHandoff(
            any(),
            any(),
            eq(false),
        )
    }
}
