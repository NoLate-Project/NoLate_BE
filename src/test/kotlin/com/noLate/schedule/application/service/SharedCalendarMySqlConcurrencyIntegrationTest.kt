package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleShareInvitationStatus
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationAcceptanceRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.sharing.application.SharingBlockPolicy
import com.noLate.sharing.application.SharingSafetyService
import com.noLate.sharing.domain.SharingReportReason
import com.noLate.sharing.domain.SharingReportStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NoLateMySqlContainer(imageName: String) :
    MySQLContainer<NoLateMySqlContainer>(imageName)

/**
 * H2는 빠른 회귀 테스트에는 적합하지만 InnoDB row lock과 unique-key 경합을 완전히 재현하지
 * 못한다. 이 테스트는 Docker가 있는 CI/개발 환경에서 MySQL 8을 실제로 띄워 아래 두 계약을
 * 검증한다. Docker가 없으면 실패하도록 고정해 CI에서 skip이 성공으로 오인되지 않게 한다.
 */
@DataJpaTest
@Import(
    ScheduleCalendarService::class,
    ScheduleShareService::class,
    ScheduleSharingAvailabilityPolicy::class,
    ScheduleAccessPolicy::class,
    ScheduleRouteSetupReminderWriter::class,
    ScheduleRouteSetupReminderRegistrar::class,
    SharingBlockPolicy::class,
    SharingSafetyService::class,
)
@Testcontainers(disabledWithoutDocker = false)
@TestPropertySource(properties = ["schedule.sharing.enabled=true"])
class SharedCalendarMySqlConcurrencyIntegrationTest @Autowired constructor(
    private val calendarService: ScheduleCalendarService,
    private val shareService: ScheduleShareService,
    private val registrar: ScheduleRouteSetupReminderRegistrar,
    private val memberRepository: MemberRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val reminderRepository: ScheduleRouteSetupReminderRepository,
    private val invitationRepository: ScheduleShareInvitationRepository,
    private val invitationAcceptanceRepository: ScheduleShareInvitationAcceptanceRepository,
    private val scheduleRepository: ScheduleRepository,
    private val accessPolicy: ScheduleAccessPolicy,
    private val sharingSafetyService: SharingSafetyService,
) {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `share acceptance report block and unblock enforce the full MySQL safety boundary`() {
        val owner = member("mysql-e2e-owner")
        val target = member("mysql-e2e-target")
        val ownerId = requireNotNull(owner.id)
        val targetId = requireNotNull(target.id)
        val schedule = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = ownerId,
                title = "MySQL 공유 안전 E2E",
                startAt = Instant.parse("2026-08-10T01:00:00Z"),
                endAt = Instant.parse("2026-08-10T02:00:00Z"),
            )
        )
        val scheduleId = requireNotNull(schedule.id)
        val invitation = shareService.createScheduleInvitation(
            ownerMemberId = ownerId,
            scheduleId = scheduleId,
            permission = ScheduleSharePermission.VIEWER,
            contentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            ttlHours = 24,
            maxAcceptCount = 1,
            presentedSessionGeneration = owner.sessionGeneration,
        )

        val accepted = shareService.acceptInvitation(
            currentMemberId = targetId,
            token = invitation.token,
            presentedSessionGeneration = target.sessionGeneration,
        )
        assertEquals(targetId, accepted.share.targetMemberId)
        val repeated = shareService.acceptInvitation(
            currentMemberId = targetId,
            token = invitation.token,
            presentedSessionGeneration = target.sessionGeneration,
        )
        assertEquals(accepted.share.id, repeated.share.id)
        assertEquals(
            1,
            invitationRepository.findById(invitation.id.toLong()).orElseThrow().acceptedCount,
        )
        assertTrue(
            invitationAcceptanceRepository.findByInvitationIdAndMemberId(
                invitation.id.toLong(),
                targetId,
            ) != null,
        )
        assertTrue(accessPolicy.resolve(targetId, scheduleRepository.findById(scheduleId).orElseThrow()).canView)

        val report = sharingSafetyService.reportShare(
            reporterMemberId = targetId,
            reportedMemberId = ownerId,
            resourceType = ScheduleShareResourceType.SCHEDULE,
            resourceId = scheduleId,
            reason = SharingReportReason.UNWANTED_SHARING,
            details = "원치 않는 일정 공유",
            presentedSessionGeneration = target.sessionGeneration,
        )
        assertEquals(SharingReportStatus.SUBMITTED, report.status)

        val pendingInvitation = shareService.createScheduleInvitation(
            ownerMemberId = ownerId,
            scheduleId = scheduleId,
            permission = ScheduleSharePermission.VIEWER,
            contentMode = ScheduleShareContentMode.SCHEDULE_ONLY,
            ttlHours = 24,
            maxAcceptCount = 1,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        sharingSafetyService.blockMember(
            blockerMemberId = targetId,
            blockedMemberId = ownerId,
            presentedSessionGeneration = target.sessionGeneration,
        )

        assertFalse(accessPolicy.resolve(targetId, scheduleRepository.findById(scheduleId).orElseThrow()).canView)
        assertTrue(shareService.getShareInbox(targetId).receivedShares.isEmpty())
        val blocked = assertThrows<BusinessException> {
            shareService.acceptInvitation(
                currentMemberId = targetId,
                token = pendingInvitation.token,
                presentedSessionGeneration = target.sessionGeneration,
            )
        }
        assertEquals(ErrorCode.SHARING_INTERACTION_BLOCKED, blocked.errorCode)

        sharingSafetyService.unblockMember(
            blockerMemberId = targetId,
            blockedMemberId = ownerId,
            presentedSessionGeneration = target.sessionGeneration,
        )
        assertTrue(accessPolicy.resolve(targetId, scheduleRepository.findById(scheduleId).orElseThrow()).canView)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `calendar row lock serializes concurrent email and app id member grants on MySQL`() {
        val owner = member("mysql-owner")
        val target = member("mysql-target")
        val calendar = calendarService.createCalendar(
            ownerMemberId = requireNotNull(owner.id),
            title = "MySQL 공유 캘린더",
            color = "#2F80FF",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            presentedSessionGeneration = owner.sessionGeneration,
        )

        runConcurrently(
            {
                calendarService.addMember(
                    ownerMemberId = requireNotNull(owner.id),
                    calendarId = calendar.id,
                    targetEmail = target.email,
                    targetAppId = null,
                    role = ScheduleCalendarRole.VIEWER,
                    authenticatedActorMemberId = requireNotNull(owner.id),
                    presentedSessionGeneration = owner.sessionGeneration,
                )
            },
            {
                calendarService.addMember(
                    ownerMemberId = requireNotNull(owner.id),
                    calendarId = calendar.id,
                    targetEmail = null,
                    targetAppId = target.id,
                    role = ScheduleCalendarRole.EDITOR,
                    authenticatedActorMemberId = requireNotNull(owner.id),
                    presentedSessionGeneration = owner.sessionGeneration,
                )
            },
        )

        val rows = calendarMemberRepository.findAll().filter {
            it.calendarId == calendar.id && it.memberId == target.id
        }
        assertEquals(1, rows.size)
        assertEquals(ScheduleCalendarMemberStatus.ACTIVE, rows.single().status)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `reminder unique key makes concurrent MySQL scanners elect one creator`() {
        val recipientMemberId = requireNotNull(member("mysql-reminder-recipient").id)
        val created = ConcurrentLinkedQueue<Boolean>()
        runConcurrently(
            {
                created.add(
                    registrar.register(
                        scheduleId = 100L,
                        memberId = recipientMemberId,
                        fingerprint = "b".repeat(64),
                        now = Instant.parse("2026-07-23T00:00:00Z"),
                    )
                )
            },
            {
                created.add(
                    registrar.register(
                        scheduleId = 100L,
                        memberId = recipientMemberId,
                        fingerprint = "b".repeat(64),
                        now = Instant.parse("2026-07-23T00:00:00Z"),
                    )
                )
            },
        )

        assertEquals(1, created.count { it })
        assertEquals(1, created.count { !it })
        assertEquals(1, reminderRepository.count())
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `calendar link acceptance and archive finish without a MySQL deadlock`() {
        val owner = member("mysql-invitation-owner")
        val target = member("mysql-invitation-target")
        val ownerId = requireNotNull(owner.id)
        val calendar = calendarService.createCalendar(
            ownerMemberId = ownerId,
            title = "수락 보관 경합",
            color = "#2F80FF",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        val invitation = shareService.createCalendarInvitation(
            ownerMemberId = ownerId,
            calendarId = calendar.id,
            permission = ScheduleSharePermission.VIEWER,
            ttlHours = 24,
            maxAcceptCount = 1,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        val accepted = AtomicBoolean(false)

        runConcurrently(
            {
                try {
                    shareService.acceptInvitation(
                        requireNotNull(target.id),
                        invitation.token,
                        target.sessionGeneration,
                    )
                    accepted.set(true)
                } catch (error: BusinessException) {
                    assertTrue(
                        error.errorCode in setOf(
                            ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND,
                            ErrorCode.SCHEDULE_SHARE_INVITATION_NOT_FOUND,
                        )
                    )
                }
            },
            {
                calendarService.archiveCalendar(
                    ownerId,
                    calendar.id,
                    owner.sessionGeneration,
                )
            },
        )

        val persistedInvitation = invitationRepository.findAll().single()
        assertTrue(
            persistedInvitation.status in setOf(
                ScheduleShareInvitationStatus.ACCEPTED,
                ScheduleShareInvitationStatus.REVOKED,
            )
        )
        if (accepted.get()) {
            assertEquals(ScheduleShareInvitationStatus.ACCEPTED, persistedInvitation.status)
        }
        assertTrue(calendarService.getCalendars(ownerId).isEmpty())
    }

    /** 두 호출이 실제로 겹치도록 출발 latch를 공유하고 worker 예외도 테스트 스레드로 전달한다. */
    private fun runConcurrently(vararg calls: () -> Unit) {
        val executor = Executors.newFixedThreadPool(calls.size)
        val ready = CountDownLatch(calls.size)
        val start = CountDownLatch(1)
        val done = CountDownLatch(calls.size)
        val failures = ConcurrentLinkedQueue<Throwable>()

        calls.forEach { call ->
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    call()
                } catch (error: Throwable) {
                    failures.add(error)
                } finally {
                    done.countDown()
                }
            }
        }

        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS), "동시성 worker가 준비되지 않았습니다.")
            start.countDown()
            assertTrue(done.await(20, TimeUnit.SECONDS), "동시성 worker가 제한 시간 안에 끝나지 않았습니다.")
            assertTrue(failures.isEmpty(), failures.joinToString { it.message.orEmpty() })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun member(label: String): Member = memberRepository.saveAndFlush(
        Member(
            name = label,
            password = "Password1!",
            email = "$label-${System.nanoTime()}@example.com",
        )
    )

    companion object {
        @Container
        @JvmStatic
        val mysql = NoLateMySqlContainer("mysql:8.4")
            .withDatabaseName("nolate_test")
            .withUsername("nolate")
            .withPassword("nolate")

        @DynamicPropertySource
        @JvmStatic
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName)
            // application-test.yml forces H2Dialect globally; override it for the real MySQL
            // container so Hibernate generates MySQL-compatible DDL (AUTO_INCREMENT, etc.)
            registry.add("spring.jpa.properties.hibernate.dialect") { "org.hibernate.dialect.MySQLDialect" }
            // The container is disposable. Avoid a delayed shutdown-hook drop after the
            // Testcontainers extension has already stopped MySQL.
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
            registry.add("spring.sql.init.mode") { "never" }
        }
    }
}
