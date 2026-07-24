package com.noLate.member.application.service

import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.AppNotification
import com.noLate.notification.domain.PushDelivery
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.domain.PushSendHistory
import com.noLate.notification.domain.PushSendStatus
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import com.noLate.notification.application.service.NotificationTokenRetirementService
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.ScheduleRouteSetupReminder
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset

@DataJpaTest
@Import(
    AccountCleanupService::class,
    NotificationTokenRetirementService::class,
    AccountOwnerWithdrawalCleanupTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:owner-withdrawal-cleanup;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountOwnerWithdrawalCleanupIntegrationTest @Autowired constructor(
    private val cleanupService: AccountCleanupService,
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val jobRepository: SchedulePushJobRepository,
    private val planRepository: ScheduleTravelPlanRepository,
    private val markerRepository: ScheduleRouteSetupReminderRepository,
    private val notificationRepository: AppNotificationRepository,
    private val deliveryRepository: PushDeliveryRepository,
    private val historyRepository: PushSendHistoryRepository,
) {

    @Test
    fun `owner withdrawal removes lower-id participant notification state before schedule`() {
        // Participant is deliberately inserted first so its row ID is lower than the owner ID.
        val participant = memberRepository.saveAndFlush(
            Member(
                name = "participant",
                password = "Password1!",
                email = "participant-withdrawal@example.com",
            )
        )
        val owner = memberRepository.saveAndFlush(
            Member(
                name = "owner",
                password = "Password1!",
                email = "owner-withdrawal@example.com",
                sessionGeneration = 4L,
            )
        )
        val participantId = requireNotNull(participant.id)
        val ownerId = requireNotNull(owner.id)
        val startAt = Instant.parse("2099-07-25T01:00:00Z")
        val schedule = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = ownerId,
                title = "owner schedule",
                startAt = startAt,
                endAt = startAt.plusSeconds(3_600),
            )
        )
        val scheduleId = requireNotNull(schedule.id)
        planRepository.saveAndFlush(
            ScheduleTravelPlan(
                scheduleId = scheduleId,
                memberId = participantId,
                travelMinutes = 30,
                notificationEnabled = true,
                scheduleFingerprint = "f".repeat(64),
            )
        )
        jobRepository.saveAndFlush(
            SchedulePushJob.create(
                memberId = participantId,
                scheduleId = scheduleId,
                scheduleAt = startAt,
                departureAt = startAt.minusSeconds(1_800),
                monitorStartAt = startAt.minusSeconds(3_600),
                intervalMinutes = 20,
            )
        )
        markerRepository.saveAndFlush(
            ScheduleRouteSetupReminder(
                scheduleId = scheduleId,
                memberId = participantId,
                scheduleFingerprint = "f".repeat(64),
                nextAttemptAt = Instant.parse("2026-07-24T00:00:00Z"),
            )
        )
        val source = notificationRepository.saveAndFlush(
            AppNotification(
                memberId = participantId,
                logicalEventKey = "logical:owner-withdrawal",
                type = "SCHEDULE_DEPARTURE_REMINDER",
                scheduleId = scheduleId,
                title = "private title",
                body = "private body",
                dataJson = "{}",
                createdAt = Instant.parse("2026-07-24T00:00:00Z"),
            )
        )
        deliveryRepository.saveAndFlush(
            PushDelivery(
                memberId = participantId,
                eventKey = source.logicalEventKey,
                deviceKey = "device-sha256:test",
                tokenFingerprint = "a".repeat(64),
                tokenOwnershipVersion = 1L,
                platform = PushPlatform.ANDROID,
                scheduleId = scheduleId,
            )
        )
        historyRepository.saveAndFlush(
            PushSendHistory(
                memberId = participantId,
                scheduleId = scheduleId,
                title = "private title",
                body = "private body",
                dataJson = "{}",
                status = PushSendStatus.FAILED,
                sentAt = Instant.parse("2026-07-24T00:00:00Z"),
            )
        )

        cleanupService.withdraw(owner)

        assertFalse(memberRepository.findById(participantId).orElseThrow().deleted)
        assertTrue(memberRepository.findById(ownerId).orElseThrow().deleted)
        assertTrue(scheduleRepository.findById(scheduleId).isEmpty)
        assertTrue(jobRepository.findAllByScheduleId(scheduleId).isEmpty())
        assertTrue(planRepository.findAllByScheduleIdAndDeletedFalse(scheduleId).isEmpty())
        assertTrue(markerRepository.findAll().none { it.scheduleId == scheduleId })
        assertTrue(notificationRepository.findAll().none { it.scheduleId == scheduleId })
        assertTrue(deliveryRepository.findAll().none { it.scheduleId == scheduleId })
        assertTrue(
            historyRepository.findAllByScheduleIdOrderBySentAtDesc(
                scheduleId,
                org.springframework.data.domain.PageRequest.of(0, 10),
            ).isEmpty()
        )
    }
}

@TestConfiguration
class AccountOwnerWithdrawalCleanupTestConfig {
    @Bean
    fun accountOwnerWithdrawalCleanupClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC)
}
