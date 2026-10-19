package com.noLate.journey.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.journey.domain.*
import com.noLate.journey.infrastructure.JourneyGuidanceSessionRepository
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.liveactivity.application.*
import com.noLate.notification.liveactivity.domain.*
import com.noLate.notification.liveactivity.infrastructure.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class JourneyLiveActivityProjectionTest {
    private val now = Instant.parse("2026-10-03T01:00:00Z")
    private val mapper = jacksonObjectMapper()
    private val members = mock<MemberRepository>()
    private val devices = mock<NotificationDeviceTokenRepository>()
    private val starts = mock<LiveActivityStartTokenRepository>()
    private val activities = mock<ScheduleLiveActivityRepository>()
    private val deliveries = mock<LiveActivityDeliveryRepository>()
    private val journeys = mock<JourneyGuidanceSessionRepository>()
    private val device = "a".repeat(64)
    private val token = LiveActivityStartToken(id = 21, memberId = 7, pushDeviceTokenId = 11, deviceFingerprint = device, activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE, pushToStartToken = "b".repeat(64), lastSeenAt = now)
    private val fcm = NotificationDeviceToken(id = 11, memberId = 7, deviceId = "device", platform = PushPlatform.IOS, token = "fcm", deviceFingerprint = device)
    private val legs = listOf(JourneyLeg("WALK", null, "집", "역", null), JourneyLeg("SUBWAY", 5, "역", "회사역", "2호선"))

    @Test
    fun `journey without predeparture Activity uses durable START with inTransit state`() {
        prepare()
        whenever(activities.saveAndFlush(any<ScheduleLiveActivity>())).thenAnswer {
            (it.arguments[0] as ScheduleLiveActivity).also { saved -> ReflectionTestUtils.setField(saved, "id", 31L) }
        }
        service().projectJourney(session(), legs)
        val captured = argumentCaptor<LiveActivityDelivery>()
        verify(deliveries).save(captured.capture())
        assertThat(captured.firstValue.operation).isEqualTo(LiveActivityDeliveryOperation.START)
        val state = mapper.readTree(captured.firstValue.contentStateJson)
        assertThat(state.path("status").asText()).isEqualTo("inTransit")
        assertThat(state.path("journey").path("sessionId").asText()).isEqualTo("journey")
        assertThat(state.path("routeSegments")[0].path("legIndex").asInt(-1)).isZero()
        assertThat(state.path("predictedArrivalEpochSeconds").isNull).isTrue()
    }

    @Test
    fun `existing Activity retains identity and generation while revision progresses`() {
        prepare()
        val activity = activity().apply { bindUpdateToken("existing-activity", "c".repeat(64)) }
        val oldRevision = activity.contentRevision
        whenever(activities.findByScheduleMemberDeviceForUpdate(41, 7, device)).thenReturn(activity)
        service().projectJourney(session(), legs)
        assertThat(activity.activityId).isEqualTo("existing-activity")
        assertThat(activity.sourceNotificationGeneration).isEqualTo(4)
        assertThat(activity.contentRevision).isGreaterThan(oldRevision)
        val captured = argumentCaptor<LiveActivityDelivery>()
        verify(deliveries).save(captured.capture())
        assertThat(captured.firstValue.operation).isEqualTo(LiveActivityDeliveryOperation.UPDATE)
        assertThat(mapper.readTree(captured.firstValue.contentStateJson).path("status").asText()).isEqualTo("inTransit")
    }

    @Test
    fun `inflight lease defers journey projection instead of replaying START`() {
        prepare()
        val activity = activity().apply { dispatchLeaseId = "in-flight" }
        whenever(activities.findByScheduleMemberDeviceForUpdate(41, 7, device)).thenReturn(activity)
        service().projectJourney(session(), legs)
        verify(deliveries, never()).save(any())
        assertThat(mapper.readTree(activity.contentStateJson).path("status").asText()).isEqualTo("preparing")
    }

    @Test
    fun `late legacy departed intent cannot terminate active journey`() {
        prepare()
        whenever(journeys.findAllByMemberIdAndScheduleIdAndEndedAtIsNull(7, 41)).thenReturn(listOf(session()))
        service().endForMemberSchedule(7, 41, "DEPARTED")
        verify(activities, never()).findAllByMemberScheduleForUpdate(any(), any())
    }

    @Test
    fun `optional journey fields stay absent from legacy wire`() {
        val content = mapper.readTree(mapper.writeValueAsString(content()))
        assertThat(content.has("journey")).isFalse()
    }

    @Test
    fun `arrival emits END with arrived status and the final journey state`() {
        prepare()
        val activity = activity().apply { bindUpdateToken("existing-activity", "c".repeat(64)) }
        whenever(activities.findByScheduleMemberDeviceForUpdate(41, 7, device)).thenReturn(activity)
        val session = session()
        val service = service()
        service.projectJourney(session, legs)
        session.terminate(JourneyPhase.ARRIVED, now, "ARRIVED")
        service.projectJourney(session, legs)
        val captured = argumentCaptor<LiveActivityDelivery>()
        verify(deliveries, times(2)).save(captured.capture())
        val end = captured.lastValue
        assertThat(end.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        val state = mapper.readTree(end.contentStateJson)
        assertThat(state.path("status").asText()).isEqualTo("arrived")
        assertThat(state.path("journey").path("phase").asText()).isEqualTo("ARRIVED")
    }

    @Test
    fun `old ETA cannot replace the active journey even if notifications are reenabled`() {
        prepare()
        whenever(journeys.findAllByMemberIdAndScheduleIdAndEndedAtIsNull(7, 41)).thenReturn(listOf(session()))
        service().projectEta(ScheduleLiveActivityEtaProjection(
            7, 41, 10, 1, now, "회의", "목적지", now.plusSeconds(3600), 20, now,
            null, null, com.noLate.schedule.domain.TrafficSource.SELECTED_ROUTE, false, emptyList(), null,
        ))
        verify(activities, never()).findByScheduleMemberDeviceForUpdate(any(), any(), any())
        verify(deliveries, never()).save(any())
    }

    @Test
    fun `stop after deferred first projection closes the old predeparture Activity`() {
        prepare()
        val activity = activity().apply {
            bindUpdateToken("existing-activity", "c".repeat(64))
            dispatchLeaseId = "old-update-in-flight"
        }
        whenever(activities.findByScheduleMemberDeviceForUpdate(41, 7, device)).thenReturn(activity)
        val session = session()
        val service = service()
        service.projectJourney(session, legs)
        assertThat(mapper.readTree(activity.contentStateJson).path("journey").isMissingNode).isTrue()
        session.terminate(JourneyPhase.STOPPED, now, "USER_STOPPED")
        service.projectJourney(session, legs)
        assertThat(activity.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        val captured = argumentCaptor<LiveActivityDelivery>()
        verify(deliveries).save(captured.capture())
        assertThat(captured.firstValue.operation).isEqualTo(LiveActivityDeliveryOperation.END)
    }

    private fun prepare() {
        whenever(members.findByIdForUpdate(7)).thenReturn(Member(id = 7, sessionGeneration = 2))
        whenever(starts.findAllByMemberIdAndActivityTypeAndActiveTrueAndRetirementRequestedFalseOrderByIdAsc(7, NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE)).thenReturn(listOf(token))
        whenever(starts.findByIdForUpdate(21)).thenReturn(token)
        whenever(devices.findByIdForUpdate(11)).thenReturn(fcm)
        whenever(deliveries.findAllOpenByActivityForUpdate(any(), any())).thenReturn(emptyList())
        whenever(deliveries.save(any<LiveActivityDelivery>())).thenAnswer { it.arguments[0] }
    }

    private fun content() = NoLateDepartureContentStateWire(1, 20, null, null, now.plusSeconds(900).epochSecond, now.epochSecond, now.plusSeconds(3600).epochSecond, "preparing", emptyList())
    private fun activity() = ScheduleLiveActivity(
        id = 31, memberId = 7, scheduleId = 41, pushDeviceTokenId = 11, deviceFingerprint = device,
        activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE, startTokenId = 21, startTokenFingerprint = token.tokenFingerprint,
        startTokenGeneration = token.tokenGeneration, sourceNotificationGeneration = 4, sourceCheckCount = 2,
        contentFingerprint = "f".repeat(64), attributesJson = "{}", contentStateJson = mapper.writeValueAsString(content()), expiresAt = now.plusSeconds(3600),
    )
    private fun session() = JourneyGuidanceSession(
        sessionId = "journey", memberId = 7, scheduleId = 41, loginSessionGeneration = 2, deviceFingerprint = device,
        generation = 1, routeFingerprint = "f".repeat(64), routeSnapshotJson = "{}", routeLegsJson = "[]",
        phase = JourneyPhase.WALKING_TO_STOP, nextInstruction = "역까지 이동해 주세요", startedAt = now,
        observedAt = now, expiresAt = now.plusSeconds(3600),
    )
    private fun service() = ScheduleLiveActivityService(members, devices, starts, activities, deliveries, mapper, Clock.fixed(now, ZoneOffset.UTC), journeyRepository = journeys)
}
