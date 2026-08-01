package com.noLate.notification.infrastructure

import com.noLate.notification.domain.AppNotification
import com.noLate.notification.domain.PushDelivery
import com.noLate.notification.domain.PushPlatform
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:departure-alarm-cleanup;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ],
)
class DepartureAlarmControlCleanupIntegrationTest @Autowired constructor(
    private val sourceRepository: AppNotificationRepository,
    private val deliveryRepository: PushDeliveryRepository,
) {
    @Test
    fun `schedule cleanup deletes visible push data but preserves cancel control source and manifest`() {
        sourceRepository.saveAllAndFlush(
            listOf(
                source("event:visible", "SCHEDULE_DETAIL", inboxVisible = true),
                source("event:cancel", "DEPARTURE_ALARM_SYNC", inboxVisible = false),
            )
        )
        deliveryRepository.saveAllAndFlush(
            listOf(
                delivery("event:visible", "visible-device", "SCHEDULE_DETAIL"),
                delivery("event:cancel", "cancel-device", "DEPARTURE_ALARM_SYNC"),
            )
        )

        deliveryRepository.deleteAllByScheduleIdIn(listOf(41L))
        sourceRepository.deleteAllByScheduleIdIn(listOf(41L))

        assertThat(sourceRepository.findAll().map { it.logicalEventKey })
            .containsExactly("event:cancel")
        assertThat(deliveryRepository.findAll().map { it.eventKey })
            .containsExactly("event:cancel")
    }

    private fun source(
        eventKey: String,
        type: String,
        inboxVisible: Boolean,
    ): AppNotification =
        AppNotification(
            memberId = 7L,
            logicalEventKey = eventKey,
            type = type,
            scheduleId = 41L,
            title = "title",
            body = "body",
            dataJson = """{"type":"$type","scheduleId":"41"}""",
            createdAt = Instant.parse("2026-07-29T03:00:00Z"),
            inboxVisible = inboxVisible,
        )

    private fun delivery(
        eventKey: String,
        deviceKey: String,
        payloadType: String,
    ): PushDelivery =
        PushDelivery(
            memberId = 7L,
            eventKey = eventKey,
            deviceKey = deviceKey,
            tokenFingerprint = deviceKey.padEnd(64, '0'),
            tokenOwnershipVersion = 0L,
            platform = PushPlatform.IOS,
            scheduleId = 41L,
            payloadType = payloadType,
        )
}
