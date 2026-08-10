package com.noLate.notification.liveactivity.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.liveactivity.infrastructure.LiveActivityDeliveryRepository
import com.noLate.notification.liveactivity.infrastructure.LiveActivityStartTokenRepository
import com.noLate.notification.liveactivity.infrastructure.ScheduleLiveActivityRepository
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.SelectedRouteMetadata
import com.noLate.schedule.domain.ScheduleTravelMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class LiveActivityRouteContractTest {
    private val objectMapper = ObjectMapper()
    private val service = ScheduleLiveActivityService(
        memberRepository = mock<MemberRepository>(),
        deviceTokenRepository = mock<NotificationDeviceTokenRepository>(),
        startTokenRepository = mock<LiveActivityStartTokenRepository>(),
        activityRepository = mock<ScheduleLiveActivityRepository>(),
        deliveryRepository = mock<LiveActivityDeliveryRepository>(),
        objectMapper = objectMapper,
    )

    @Test
    fun `long route keeps both ends one omission marker and destination as slot six`() {
        val selected = SelectedRouteMetadata.parse(
            objectMapper,
            """
                {
                  "selectedItinerary": {
                    "legs": [
                      {"mode":"WALK"},
                      {"mode":"BUS", "route":"341"},
                      {"mode":"WALK"},
                      {"mode":"SUBWAY", "route":"2호선"},
                      {"mode":"WALK"},
                      {"mode":"BUS", "route":"146"},
                      {"mode":"WALK"}
                    ]
                  }
                }
            """.trimIndent(),
            ScheduleTravelMode.TRANSIT,
        )

        val wire = service.normalizedRouteSegments(selected.routeSegments)

        assertThat(wire.map { it.kind }).containsExactly(
            "walk",
            "bus",
            "transfer",
            "bus",
            "walk",
            "destination",
        )
        assertThat(wire[2].label).isEqualTo("3구간 생략")
        assertThat(wire.last().label).isEqualTo("도착")
    }
}
