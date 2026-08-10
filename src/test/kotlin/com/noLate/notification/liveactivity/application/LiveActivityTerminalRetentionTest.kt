package com.noLate.notification.liveactivity.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
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

class LiveActivityTerminalRetentionTest {
    private val now = Instant.parse("2026-08-06T00:10:00Z")
    private val memberRepository = mock<MemberRepository>()
    private val deviceRepository = mock<NotificationDeviceTokenRepository>()
    private val startRepository = mock<LiveActivityStartTokenRepository>()
    private val activityRepository = mock<ScheduleLiveActivityRepository>()
    private val deliveryRepository = mock<LiveActivityDeliveryRepository>()
    private val member = mock<Member> { on { id } doReturn 7L }

    @Test
    fun `expiry scan never treats a late-token END tombstone as remotely ended`() {
        whenever(activityRepository.findExpiredCandidateIds(any(), any(), any()))
            .thenReturn(emptyList())

        service().endExpiredActivities()

        val states = argumentCaptor<Collection<ScheduleLiveActivityState>>()
        verify(activityRepository).findExpiredCandidateIds(states.capture(), any(), any())
        assertThat(states.firstValue).doesNotContain(ScheduleLiveActivityState.END_PENDING)
        assertThat(states.firstValue).containsExactlyInAnyOrder(
            ScheduleLiveActivityState.START_PENDING,
            ScheduleLiveActivityState.START_SENT,
            ScheduleLiveActivityState.ACTIVE,
            ScheduleLiveActivityState.TOKEN_PENDING,
        )
    }

    @Test
    fun `terminal credentials and PII are purged after retention when all deliveries are terminal`() {
        val activity = terminalActivity()
        stubCandidate(activity)
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(emptyList())

        service().cleanupRetainedTerminalActivities()

        verify(deliveryRepository).deleteAllByLiveActivityId(31)
        verify(activityRepository).delete(activity)
    }

    @Test
    fun `non-replayable ambiguous UPDATE no longer retains terminal PII forever`() {
        val activity = terminalActivity()
        val uncertain = LiveActivityDelivery(
            id = 51,
            eventKey = "uncertain-update",
            liveActivityId = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            fcmTokenFingerprint = "a".repeat(64),
            fcmOwnershipVersion = 0,
            deviceFingerprint = "b".repeat(64),
            activityToken = "c".repeat(64),
            activityTokenFingerprint = "d".repeat(64),
            targetActivityId = "activity-41",
            operation = LiveActivityDeliveryOperation.UPDATE,
            revision = 2,
            sourceNotificationGeneration = 3,
            apnsTimestamp = now.minusSeconds(100_000).epochSecond,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            contentStateJson = "{\"destinationName\":\"private\"}",
            expirationAt = now.minusSeconds(80_000),
            status = LiveActivityDeliveryStatus.UNCERTAIN,
            availableAt = now.minusSeconds(100_000),
        )
        stubCandidate(activity)
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(listOf(uncertain))

        service().cleanupRetainedTerminalActivities()

        verify(deliveryRepository).deleteAllByLiveActivityId(31)
        verify(activityRepository).delete(activity)
        assertThat(uncertain.status).isEqualTo(LiveActivityDeliveryStatus.UNCERTAIN)
    }

    @Test
    fun `unresolved END delivery remains protected from terminal cleanup`() {
        val activity = terminalActivity()
        val unresolvedEnd = LiveActivityDelivery(
            id = 52,
            eventKey = "unresolved-end",
            liveActivityId = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            fcmTokenFingerprint = "a".repeat(64),
            fcmOwnershipVersion = 0,
            deviceFingerprint = "b".repeat(64),
            activityToken = "c".repeat(64),
            activityTokenFingerprint = "d".repeat(64),
            targetActivityId = "activity-41",
            operation = LiveActivityDeliveryOperation.END,
            revision = 2,
            sourceNotificationGeneration = 3,
            apnsTimestamp = now.minusSeconds(100_000).epochSecond,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            contentStateJson = "{\"destinationName\":\"private\"}",
            expirationAt = now.minusSeconds(80_000),
            status = LiveActivityDeliveryStatus.UNCERTAIN,
            availableAt = now.minusSeconds(100_000),
        )
        stubCandidate(activity)
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(listOf(unresolvedEnd))

        service().cleanupRetainedTerminalActivities()

        verify(deliveryRepository, never()).deleteAllByLiveActivityId(any())
        verify(activityRepository, never()).delete(any<ScheduleLiveActivity>())
    }

    private fun stubCandidate(activity: ScheduleLiveActivity) {
        whenever(activityRepository.findTerminalRetentionCandidateIds(any(), any(), any()))
            .thenReturn(listOf(31))
        whenever(activityRepository.findById(31)).thenReturn(Optional.of(activity))
        whenever(memberRepository.findAllByIdsForUpdate(listOf(7))).thenReturn(listOf(member))
        whenever(activityRepository.findByIdForUpdate(31)).thenReturn(activity)
    }

    private fun terminalActivity() = ScheduleLiveActivity(
        id = 31,
        memberId = 7,
        scheduleId = 41,
        pushDeviceTokenId = 11,
        deviceFingerprint = "b".repeat(64),
        activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
        sourceNotificationGeneration = 3,
        sourceCheckCount = 4,
        contentRevision = 2,
        contentFingerprint = "e".repeat(64),
        attributesJson = "{\"scheduleTitle\":\"private\"}",
        contentStateJson = "{\"destinationName\":\"private\"}",
        expiresAt = now.minusSeconds(100_000),
    ).also {
        it.bindUpdateToken("activity-41", "c".repeat(64))
        it.markEnded(now.minusSeconds(90_000), "COMPLETED")
    }

    private fun service() = ScheduleLiveActivityService(
        memberRepository = memberRepository,
        deviceTokenRepository = deviceRepository,
        startTokenRepository = startRepository,
        activityRepository = activityRepository,
        deliveryRepository = deliveryRepository,
        objectMapper = jacksonObjectMapper(),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        terminalRetentionHours = 24,
    )
}
