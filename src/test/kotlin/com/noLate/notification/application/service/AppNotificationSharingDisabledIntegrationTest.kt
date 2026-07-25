package com.noLate.notification.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.AppNotification
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.schedule.application.service.ScheduleAccessPolicy
import com.noLate.schedule.application.service.SchedulePushRecipientAccessValidator
import com.noLate.schedule.application.service.ScheduleSharingAvailabilityPolicy
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@DataJpaTest
@Import(
    AppNotificationService::class,
    AppNotificationWriter::class,
    SchedulePushRecipientAccessValidator::class,
    ScheduleAccessPolicy::class,
    ScheduleSharingAvailabilityPolicy::class,
    AppNotificationSharingDisabledTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:app-notification-sharing-disabled;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.sharing.enabled=false",
    ]
)
class AppNotificationSharingDisabledIntegrationTest @Autowired constructor(
    private val service: AppNotificationService,
    private val notificationRepository: AppNotificationRepository,
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
) {

    @Test
    fun `global off hides dormant share inbox and unread rows without deleting them`() {
        val owner = memberRepository.saveAndFlush(member("owner"))
        val target = memberRepository.saveAndFlush(member("target"))
        val ownerId = requireNotNull(owner.id)
        val targetId = requireNotNull(target.id)
        val schedule = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = ownerId,
                title = "owner private schedule",
                startAt = NOW.plusSeconds(3600),
                endAt = NOW.plusSeconds(7200),
            )
        )
        val scheduleId = requireNotNull(schedule.id)
        val hiddenShare = notificationRepository.saveAndFlush(
            notification(
                memberId = targetId,
                logicalEventKey = "evt:dormant-share",
                type = "SCHEDULE_SHARE_RECEIVED",
                scheduleId = scheduleId,
                title = "private dormant share",
            )
        )
        notificationRepository.saveAndFlush(
            notification(
                memberId = targetId,
                logicalEventKey = "evt:dormant-resource",
                type = "SCHEDULE_DETAIL",
                scheduleId = scheduleId,
                title = "private owner resource",
            )
        )
        notificationRepository.saveAndFlush(
            notification(
                memberId = targetId,
                logicalEventKey = "evt:ordinary",
                type = "GENERAL",
                title = "ordinary notification",
            )
        )
        notificationRepository.saveAndFlush(
            notification(
                memberId = ownerId,
                logicalEventKey = "evt:owner-schedule",
                type = "SCHEDULE_DETAIL",
                scheduleId = scheduleId,
                title = "owner schedule notification",
            )
        )

        val targetInbox = service.getInbox(targetId, cursorId = null, limit = 20, unreadOnly = false)
        val ownerInbox = service.getInbox(ownerId, cursorId = null, limit = 20, unreadOnly = false)

        assertEquals(listOf("ordinary notification"), targetInbox.items.map { it.title })
        assertEquals(1L, targetInbox.unreadCount)
        assertEquals(1L, service.getUnreadCount(targetId))
        assertEquals(listOf("owner schedule notification"), ownerInbox.items.map { it.title })
        assertEquals(1L, ownerInbox.unreadCount)
        assertEquals(4L, notificationRepository.count())

        val failure = assertThrows(BusinessException::class.java) {
            service.markRead(
                memberId = targetId,
                notificationId = requireNotNull(hiddenShare.id),
                presentedSessionGeneration = target.sessionGeneration,
            )
        }

        assertEquals(ErrorCode.NOTIFICATION_NOT_FOUND, failure.errorCode)
        assertNull(notificationRepository.findById(requireNotNull(hiddenShare.id)).orElseThrow().readAt)
        assertEquals(4L, notificationRepository.count())
        assertTrue(notificationRepository.existsById(requireNotNull(hiddenShare.id)))
    }

    private fun member(label: String) = Member(
        name = label,
        password = "Password1!",
        email = "sharing-disabled-$label-${System.nanoTime()}@example.com",
    )

    private fun notification(
        memberId: Long,
        logicalEventKey: String,
        type: String,
        title: String,
        scheduleId: Long? = null,
    ) = AppNotification(
        memberId = memberId,
        logicalEventKey = logicalEventKey,
        type = type,
        scheduleId = scheduleId,
        title = title,
        body = "private body",
        dataJson = "{}",
        createdAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-25T00:00:00Z")
    }
}

@TestConfiguration
class AppNotificationSharingDisabledTestConfig {
    @Bean
    fun appNotificationSharingDisabledClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC)

    @Bean
    fun appNotificationSharingDisabledObjectMapper(): ObjectMapper = ObjectMapper()
}
