package com.noLate.notification.liveactivity.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.liveactivity.domain.LiveActivityAppearance
import com.noLate.notification.liveactivity.domain.LiveActivityDelivery
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryOperation
import com.noLate.notification.liveactivity.domain.LiveActivityStartToken
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivity
import com.noLate.notification.liveactivity.infrastructure.LiveActivityDeliveryRepository
import com.noLate.notification.liveactivity.infrastructure.LiveActivityStartTokenRepository
import com.noLate.notification.liveactivity.infrastructure.ScheduleLiveActivityRepository
import com.noLate.schedule.domain.TrafficSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ScheduleLiveActivityAppearanceProjectionTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `device appearance is carried by start content state and changes on the next eta update`() {
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val deviceFingerprint = "a".repeat(64)
        val memberRepository = mock<MemberRepository>()
        val deviceRepository = mock<NotificationDeviceTokenRepository>()
        val startRepository = mock<LiveActivityStartTokenRepository>()
        val activityRepository = mock<ScheduleLiveActivityRepository>()
        val deliveryRepository = mock<LiveActivityDeliveryRepository>()
        val eventPublisher = mock<ApplicationEventPublisher>()
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
            pushToStartToken = "b".repeat(64),
            lastSeenAt = now,
            appearance = LiveActivityAppearance.DARK,
        )
        val activity = ScheduleLiveActivity(
            id = 31,
            memberId = 7,
            scheduleId = 41,
            pushDeviceTokenId = 11,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            startTokenId = 21,
            startTokenFingerprint = startToken.tokenFingerprint,
            startTokenGeneration = startToken.tokenGeneration,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 0,
            contentFingerprint = "c".repeat(64),
            attributesJson = "{}",
            contentStateJson = objectMapper.writeValueAsString(contentState()),
            expiresAt = now.plusSeconds(4_200),
        )

        whenever(memberRepository.findByIdForUpdate(7)).thenReturn(member)
        whenever(
            startRepository.findAllByMemberIdAndActivityTypeAndActiveTrueAndRetirementRequestedFalseOrderByIdAsc(
                7,
                NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            )
        ).thenReturn(listOf(startToken))
        whenever(activityRepository.findByScheduleMemberDeviceForUpdate(41, 7, deviceFingerprint))
            .thenReturn(activity)
        whenever(deviceRepository.findByIdForUpdate(11)).thenReturn(fcm)
        whenever(startRepository.findByIdForUpdate(21)).thenReturn(startToken)
        whenever(deliveryRepository.findAllOpenByActivityForUpdate(any(), any()))
            .thenReturn(emptyList())
        whenever(deliveryRepository.findByEventKeyForUpdate(any())).thenReturn(null)
        whenever(deliveryRepository.save(any<LiveActivityDelivery>())).thenAnswer { it.arguments[0] }

        val service = ScheduleLiveActivityService(
            memberRepository = memberRepository,
            deviceTokenRepository = deviceRepository,
            startTokenRepository = startRepository,
            activityRepository = activityRepository,
            deliveryRepository = deliveryRepository,
            objectMapper = objectMapper,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            applicationEventPublisher = eventPublisher,
        )

        service.projectEta(projection(now, checkCount = 1))
        activity.markStartAccepted(activity.contentRevision)
        activity.bindUpdateToken("activity-41", "d".repeat(64))
        startToken.appearance = LiveActivityAppearance.LIGHT
        service.projectEta(projection(now.plusSeconds(60), checkCount = 2))

        val deliveries = argumentCaptor<LiveActivityDelivery>()
        verify(deliveryRepository, times(2)).save(deliveries.capture())
        verify(eventPublisher, times(2)).publishEvent(any<LiveActivityDispatchRequested>())
        assertThat(deliveries.allValues.map { it.operation }).containsExactly(
            LiveActivityDeliveryOperation.START,
            LiveActivityDeliveryOperation.UPDATE,
        )
        assertThat(contentAppearance(deliveries.firstValue)).isEqualTo("dark")
        assertThat(contentAppearance(deliveries.secondValue)).isEqualTo("light")
        assertThat(deliveries.secondValue.revision).isGreaterThan(deliveries.firstValue.revision)
    }

    @Test
    fun `legacy content without appearance decodes and null appearance stays omitted`() {
        val legacyJson = objectMapper.writeValueAsString(contentState())

        val decoded = objectMapper.readValue<NoLateDepartureContentStateWire>(legacyJson)

        assertThat(decoded.appearance).isNull()
        assertThat(legacyJson).doesNotContain("appearance")
    }

    private fun projection(evaluatedAt: Instant, checkCount: Int) = ScheduleLiveActivityEtaProjection(
        memberId = 7,
        scheduleId = 41,
        notificationGeneration = 3,
        checkCount = checkCount,
        evaluatedAt = evaluatedAt,
        scheduleTitle = "회의",
        destinationName = "강남역",
        scheduleStartAt = Instant.parse("2026-08-06T01:10:00Z"),
        travelMinutes = 60,
        recommendedDepartureAt = Instant.parse("2026-08-06T00:10:00Z"),
        predictedArrivalAt = Instant.parse("2026-08-06T01:10:00Z"),
        firstBoardingWaitMinutes = 20,
        etaSource = TrafficSource.LIVE_PROVIDER,
        etaStale = false,
        routeSegments = emptyList(),
        staleAt = evaluatedAt.plusSeconds(600),
    )

    private fun contentState() = NoLateDepartureContentStateWire(
        revision = 1,
        travelMinutes = 60,
        firstWaitMinutes = 20,
        predictedArrivalEpochSeconds = 1_785_960_000L,
        recommendedDepartureEpochSeconds = 1_785_956_400L,
        updatedAtEpochSeconds = 1_785_956_300L,
        actionExpiresAtEpochSeconds = 1_785_960_600L,
        status = "preparing",
        routeSegments = emptyList(),
    )

    private fun contentAppearance(delivery: LiveActivityDelivery): String? =
        objectMapper.readTree(delivery.contentStateJson).path("appearance").textValue()
}
