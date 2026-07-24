package com.noLate.schedule.application.useCase

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.application.service.AccountCleanupService
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.schedule.application.cache.ScheduleCalendarCacheInvalidationEvent
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleImportProvider
import com.noLate.schedule.domain.ScheduleImportSource
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.event.EventListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-creation-session-fence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.push.enabled=false",
        "notification.push-outbox.enabled=false",
    ],
)
@Import(ScheduleCreationSessionFenceTestConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduleCreationSessionFenceIntegrationTest @Autowired constructor(
    private val scheduleUseCase: ScheduleUseCase,
    private val accountCleanupService: AccountCleanupService,
    private val memberRepository: MemberRepository,
    private val categoryRepository: ScheduleCategoryRepository,
    private val scheduleRepository: ScheduleRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val appNotificationRepository: AppNotificationRepository,
    private val observer: BlockingScheduleCreationObserver,
) {
    @AfterEach
    fun releaseBlockedCreation() {
        observer.release()
    }

    @Test
    fun `create first holds member fence until schedule and push job commit then withdrawal removes all`() {
        val fixture = fixture("create-first", sessionGeneration = 7L)
        val memberBeforeWithdrawal = memberRepository.findById(fixture.memberId).orElseThrow()
        val gate = observer.arm()
        val executor = Executors.newFixedThreadPool(2)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val withdrawalStarted = CountDownLatch(1)

        val creation = executor.submit {
            runCatching {
                scheduleUseCase.addSchedule(
                    memberId = fixture.memberId,
                    scheduleDto = scheduleDto(fixture.categoryId, "create-first"),
                    presentedSessionGeneration = 7L,
                )
            }.onFailure(failures::add)
        }

        try {
            assertTrue(gate.creationPersisted.await(10, TimeUnit.SECONDS))
            val withdrawal = executor.submit {
                withdrawalStarted.countDown()
                runCatching {
                    accountCleanupService.withdraw(memberBeforeWithdrawal)
                }.onFailure(failures::add)
            }
            assertTrue(withdrawalStarted.await(5, TimeUnit.SECONDS))

            // The withdrawal must be waiting at the same member FOR UPDATE lock while creation is
            // paused after the schedule INSERT but before push-job creation/outer commit.
            assertThrows<TimeoutException> {
                withdrawal.get(300, TimeUnit.MILLISECONDS)
            }

            gate.allowCommit.countDown()
            creation.get(10, TimeUnit.SECONDS)
            withdrawal.get(10, TimeUnit.SECONDS)

            assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
            assertRecipientNotificationStateIsEmpty(fixture.memberId)
            val withdrawn = memberRepository.findById(fixture.memberId).orElseThrow()
            assertTrue(withdrawn.deleted)
        } finally {
            gate.allowCommit.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `withdrawal first rejects stale add and import without recreating any rows`() {
        val fixture = fixture("withdraw-first", sessionGeneration = 3L)
        val member = memberRepository.findById(fixture.memberId).orElseThrow()
        accountCleanupService.withdraw(member)

        val staleAdd = assertThrows<BusinessException> {
            scheduleUseCase.addSchedule(
                memberId = fixture.memberId,
                scheduleDto = scheduleDto(fixture.categoryId, "stale-add"),
                presentedSessionGeneration = 3L,
            )
        }
        val staleImport = assertThrows<BusinessException> {
            scheduleUseCase.importSchedule(
                memberId = fixture.memberId,
                scheduleDto = scheduleDto(fixture.categoryId, "stale-import"),
                source = importSource("stale"),
                presentedSessionGeneration = 3L,
            )
        }

        assertEquals(ErrorCode.INVALID_TOKEN, staleAdd.errorCode)
        assertEquals(ErrorCode.INVALID_TOKEN, staleImport.errorCode)
        assertRecipientNotificationStateIsEmpty(fixture.memberId)
    }

    @Test
    fun `matching generation allows normal add and import while wrong generation mutates nothing`() {
        val fixture = fixture("normal", sessionGeneration = 11L)

        val added = scheduleUseCase.addSchedule(
            memberId = fixture.memberId,
            scheduleDto = scheduleDto(fixture.categoryId, "normal-add"),
            presentedSessionGeneration = 11L,
        )
        val imported = scheduleUseCase.importSchedule(
            memberId = fixture.memberId,
            scheduleDto = scheduleDto(fixture.categoryId, "normal-import"),
            source = importSource("normal"),
            presentedSessionGeneration = 11L,
        )
        val countBeforeStaleCall = scheduleRepository.findAllByMemberId(fixture.memberId).size

        val stale = assertThrows<BusinessException> {
            scheduleUseCase.addSchedule(
                memberId = fixture.memberId,
                scheduleDto = scheduleDto(fixture.categoryId, "wrong-generation"),
                presentedSessionGeneration = 10L,
            )
        }

        assertEquals(ErrorCode.INVALID_TOKEN, stale.errorCode)
        assertEquals(fixture.memberId, added.ownerMemberId)
        assertTrue(imported.created)
        assertEquals(fixture.memberId, imported.schedule.ownerMemberId)
        assertEquals(2, countBeforeStaleCall)
        assertEquals(2, scheduleRepository.findAllByMemberId(fixture.memberId).size)
        assertEquals(2, pushJobRepository.findAll().count { it.memberId == fixture.memberId })
        assertFalse(memberRepository.findById(fixture.memberId).orElseThrow().deleted)
    }

    private fun assertRecipientNotificationStateIsEmpty(memberId: Long) {
        assertTrue(scheduleRepository.findAllByMemberId(memberId).isEmpty())
        assertTrue(pushJobRepository.findAll().none { it.memberId == memberId })
        assertTrue(appNotificationRepository.findAllByMemberIdOrderByIdDesc(memberId).isEmpty())
    }

    private fun fixture(
        suffix: String,
        sessionGeneration: Long,
    ): Fixture {
        val member = memberRepository.saveAndFlush(
            Member(
                name = "member-$suffix",
                password = "Password1!",
                email = "$suffix-session-fence@example.com",
                loginType = LoginType.COMMON,
                sessionGeneration = sessionGeneration,
            ),
        )
        val memberId = requireNotNull(member.id)
        val category = categoryRepository.saveAndFlush(
            ScheduleCategory(
                memberId = memberId,
                title = "업무",
                color = "#123456",
            ),
        )
        return Fixture(
            memberId = memberId,
            categoryId = requireNotNull(category.id),
        )
    }

    private fun scheduleDto(
        categoryId: Long,
        title: String,
    ): ScheduleDto =
        ScheduleDto(
            title = title,
            startAt = "2099-07-24T05:00:00Z",
            endAt = "2099-07-24T06:00:00Z",
            travelMinutes = 30,
            departAt = "2099-07-24T04:30:00Z",
            travelMode = ScheduleTravelMode.CAR,
            origin = SchedulePlaceDto(
                name = "출발지",
                lat = 37.1,
                lng = 127.1,
            ),
            destination = SchedulePlaceDto(
                name = "도착지",
                lat = 37.2,
                lng = 127.2,
            ),
            category = ScheduleCategoryDto(
                id = categoryId.toString(),
                title = "client-value-is-not-trusted",
                color = "#000000",
            ),
            notificationEnabled = true,
            notificationLeadMinutes = 60,
            notificationIntervalMinutes = 20,
        )

    private fun importSource(suffix: String): ScheduleImportSource =
        ScheduleImportSource(
            provider = ScheduleImportProvider.GOOGLE,
            calendarId = "calendar-$suffix",
            eventId = "event-$suffix",
            occurrenceStartAt = "2099-07-24T05:00:00Z",
        )

    private data class Fixture(
        val memberId: Long,
        val categoryId: Long,
    )
}

@TestConfiguration
class ScheduleCreationSessionFenceTestConfig {
    @Bean
    fun blockingScheduleCreationObserver(): BlockingScheduleCreationObserver =
        BlockingScheduleCreationObserver()
}

class BlockingScheduleCreationObserver {
    private val armedGate = AtomicReference<ScheduleCreationGate?>()

    fun arm(): ScheduleCreationGate =
        ScheduleCreationGate().also { gate ->
            check(armedGate.compareAndSet(null, gate))
        }

    fun release() {
        armedGate.getAndSet(null)?.allowCommit?.countDown()
    }

    @EventListener
    fun afterScheduleInsert(event: ScheduleCalendarCacheInvalidationEvent) {
        if (event.reason != "schedule-created") return
        val gate = armedGate.getAndSet(null) ?: return
        gate.creationPersisted.countDown()
        check(gate.allowCommit.await(10, TimeUnit.SECONDS))
    }
}

class ScheduleCreationGate(
    val creationPersisted: CountDownLatch = CountDownLatch(1),
    val allowCommit: CountDownLatch = CountDownLatch(1),
)
