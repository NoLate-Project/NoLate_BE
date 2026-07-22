package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleShareInvitationStatus
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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
 * 검증한다. Docker가 없는 환경에서는 JUnit이 명시적으로 skip하므로 일반 단위 테스트를 막지
 * 않으며, skip 결과를 MySQL 검증 통과로 해석해서는 안 된다.
 */
@DataJpaTest
@Import(
    ScheduleCalendarService::class,
    ScheduleShareService::class,
    ScheduleRouteSetupReminderWriter::class,
    ScheduleRouteSetupReminderRegistrar::class,
)
@Testcontainers(disabledWithoutDocker = true)
class SharedCalendarMySqlConcurrencyIntegrationTest @Autowired constructor(
    private val calendarService: ScheduleCalendarService,
    private val shareService: ScheduleShareService,
    private val registrar: ScheduleRouteSetupReminderRegistrar,
    private val memberRepository: MemberRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val reminderRepository: ScheduleRouteSetupReminderRepository,
    private val invitationRepository: ScheduleShareInvitationRepository,
) {

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
        )

        runConcurrently(
            {
                calendarService.addMember(
                    ownerMemberId = requireNotNull(owner.id),
                    calendarId = calendar.id,
                    targetEmail = target.email,
                    targetAppId = null,
                    role = ScheduleCalendarRole.VIEWER,
                )
            },
            {
                calendarService.addMember(
                    ownerMemberId = requireNotNull(owner.id),
                    calendarId = calendar.id,
                    targetEmail = null,
                    targetAppId = target.id,
                    role = ScheduleCalendarRole.EDITOR,
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
        val created = ConcurrentLinkedQueue<Boolean>()
        runConcurrently(
            {
                created.add(
                    registrar.register(
                        scheduleId = 100L,
                        memberId = 200L,
                        fingerprint = "b".repeat(64),
                        now = Instant.parse("2026-07-23T00:00:00Z"),
                    )
                )
            },
            {
                created.add(
                    registrar.register(
                        scheduleId = 100L,
                        memberId = 200L,
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
        )
        val invitation = shareService.createCalendarInvitation(
            ownerMemberId = ownerId,
            calendarId = calendar.id,
            permission = ScheduleSharePermission.VIEWER,
            ttlHours = 24,
            maxAcceptCount = 1,
        )
        val accepted = AtomicBoolean(false)

        runConcurrently(
            {
                try {
                    shareService.acceptInvitation(requireNotNull(target.id), invitation.token)
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
            { calendarService.archiveCalendar(ownerId, calendar.id) },
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
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
            registry.add("spring.sql.init.mode") { "never" }
        }
    }
}
