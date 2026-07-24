package com.noLate.schedule.application.service

import com.noLate.notification.application.service.PushDispatchFence
import com.noLate.notification.support.ensureActivePushMember
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@DataJpaTest
@Import(
    SchedulePushJobService::class,
    SchedulePushDispatchFenceValidator::class,
    SchedulePushFenceTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-push-fence;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SchedulePushFenceConcurrencyIntegrationTest @Autowired constructor(
    private val repository: SchedulePushJobRepository,
    private val jobService: SchedulePushJobService,
    private val validator: SchedulePushDispatchFenceValidator,
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val executor = Executors.newFixedThreadPool(2)
    private val workerId = "fence-worker"

    @BeforeEach
    fun clean() {
        repository.deleteAll()
        ensureActivePushMember(jdbcTemplate, MEMBER_ID)
    }

    @AfterEach
    fun stopExecutor() {
        executor.shutdownNow()
    }

    @Test
    fun `job 없는 공유 편집도 actor와 owner를 잠가 새 job 등록보다 먼저 linearize된다`() {
        ensureActivePushMember(jdbcTemplate, EDITOR_MEMBER_ID)
        val scheduleId = 20L
        val editLocked = CountDownLatch(1)
        val releaseEdit = CountDownLatch(1)
        val registrationStarted = CountDownLatch(1)
        val registrationCompleted = CountDownLatch(1)

        val edit = executor.submit {
            transactions.executeWithoutResult {
                jobService.lockForScheduleEdit(
                    scheduleId = scheduleId,
                    requiredMemberIds = listOf(EDITOR_MEMBER_ID, MEMBER_ID),
                    actorMemberId = EDITOR_MEMBER_ID,
                    presentedSessionGeneration = 0L,
                )
                editLocked.countDown()
                check(releaseEdit.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(editLocked.await(5, TimeUnit.SECONDS))

        val registration = executor.submit {
            registrationStarted.countDown()
            try {
                jobService.registerFromScheduleDto(
                    MEMBER_ID,
                    originalDto().copy(id = scheduleId),
                )
            } finally {
                registrationCompleted.countDown()
            }
        }
        assertTrue(registrationStarted.await(5, TimeUnit.SECONDS))
        assertFalse(registrationCompleted.await(250, TimeUnit.MILLISECONDS))

        releaseEdit.countDown()
        edit.get(5, TimeUnit.SECONDS)
        registration.get(5, TimeUnit.SECONDS)

        assertEquals(1L, repository.count())
        assertEquals(MEMBER_ID, repository.findAll().single().memberId)
    }

    @Test
    fun `job 없는 알림 travel plan 회원도 edit gap 전에 잠가 backfill 등록과 직렬화한다`() {
        ensureActivePushMember(jdbcTemplate, PLAN_MEMBER_ID)
        val scheduleId = 21L
        val editLocked = CountDownLatch(1)
        val releaseEdit = CountDownLatch(1)
        val registrationCompleted = CountDownLatch(1)

        val edit = executor.submit {
            transactions.executeWithoutResult {
                jobService.lockForScheduleEdit(
                    scheduleId = scheduleId,
                    requiredMemberIds = listOf(MEMBER_ID, PLAN_MEMBER_ID),
                    actorMemberId = MEMBER_ID,
                    presentedSessionGeneration = 0L,
                )
                editLocked.countDown()
                check(releaseEdit.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(editLocked.await(5, TimeUnit.SECONDS))

        val registration = executor.submit {
            try {
                jobService.registerFromScheduleDto(
                    PLAN_MEMBER_ID,
                    originalDto().copy(id = scheduleId),
                )
            } finally {
                registrationCompleted.countDown()
            }
        }
        assertFalse(registrationCompleted.await(250, TimeUnit.MILLISECONDS))

        releaseEdit.countDown()
        edit.get(5, TimeUnit.SECONDS)
        registration.get(5, TimeUnit.SECONDS)

        assertEquals(PLAN_MEMBER_ID, repository.findAll().single().memberId)
    }

    @Test
    fun `edit lock이 먼저면 old generation provider fence는 거절된다`() {
        val original = originalDto()
        val jobId = createClaimedJob(original)
        val editLocked = CountDownLatch(1)
        val releaseEdit = CountDownLatch(1)
        val fenceAccepted = AtomicBoolean(true)

        val edit = executor.submit {
            transactions.executeWithoutResult {
                jobService.lockForScheduleEdit(
                    scheduleId = 10L,
                    requiredMemberIds = listOf(MEMBER_ID),
                    actorMemberId = MEMBER_ID,
                    presentedSessionGeneration = 0L,
                )
                jobService.registerFromScheduleDto(
                    MEMBER_ID,
                    original.copy(startAt = "2026-07-24T05:05:00Z"),
                )
                editLocked.countDown()
                check(releaseEdit.await(5, TimeUnit.SECONDS))
            }
        }
        check(editLocked.await(5, TimeUnit.SECONDS))
        val send = executor.submit {
            fenceAccepted.set(validateFenceWithFreshTransaction(jobId))
        }

        releaseEdit.countDown()
        edit.get(5, TimeUnit.SECONDS)
        send.get(5, TimeUnit.SECONDS)

        assertEquals(false, fenceAccepted.get())
        val persisted = repository.findById(jobId).orElseThrow()
        assertEquals(SchedulePushJobStatus.ACTIVE, persisted.status)
        assertEquals(1, persisted.notificationGeneration)
    }

    @Test
    fun `provider fence가 먼저면 old immutable event가 한 번 linearize된 뒤 edit이 새 generation을 연다`() {
        val original = originalDto()
        val jobId = createClaimedJob(original)
        val fenceValidated = CountDownLatch(1)
        val releaseFence = CountDownLatch(1)
        val providerCalls = AtomicInteger()

        val send = executor.submit {
            val accepted = transactions.execute {
                val valid = validator.validate(oldFence(jobId))
                fenceValidated.countDown()
                check(releaseFence.await(5, TimeUnit.SECONDS))
                valid
            } ?: false
            if (accepted) {
                providerCalls.incrementAndGet()
            }
        }
        check(fenceValidated.await(5, TimeUnit.SECONDS))
        val edit = executor.submit {
            jobService.registerFromScheduleDto(
                MEMBER_ID,
                original.copy(startAt = "2026-07-24T05:05:00Z"),
            )
        }

        releaseFence.countDown()
        send.get(5, TimeUnit.SECONDS)
        edit.get(5, TimeUnit.SECONDS)

        assertEquals(1, providerCalls.get())
        val persisted = repository.findById(jobId).orElseThrow()
        assertEquals(SchedulePushJobStatus.ACTIVE, persisted.status)
        assertEquals(1, persisted.notificationGeneration)
    }

    @Test
    fun `같은 worker id가 stale job을 다시 claim해도 이전 version fence는 거절된다`() {
        val jobId = createClaimedJob(originalDto())
        val staleFence = oldFence(jobId)

        transactions.executeWithoutResult {
            val job = requireNotNull(repository.findByIdForUpdate(jobId))
            job.recoverProcessingTimeout(
                reason = "stale test lease",
                nextCheckAt = NOW.plusSeconds(1),
            )
            repository.flush()
        }
        transactions.executeWithoutResult {
            val job = requireNotNull(repository.findByIdForUpdate(jobId))
            job.startProcessing(workerId, NOW.plusSeconds(1))
            repository.flush()
        }

        val accepted = transactions.execute {
            validator.validate(staleFence)
        } ?: true

        assertFalse(accepted)
    }

    private fun createClaimedJob(dto: ScheduleDto): Long {
        val created = requireNotNull(jobService.registerFromScheduleDto(MEMBER_ID, dto))
        val jobId = requireNotNull(created.id)
        transactions.executeWithoutResult {
            val job = requireNotNull(repository.findByIdForUpdate(jobId))
            job.startProcessing(workerId, NOW)
            repository.flush()
        }
        return jobId
    }

    private fun oldFence(jobId: Long): PushDispatchFence {
        val job = repository.findById(jobId).orElseThrow()
        return PushDispatchFence(
            jobId = jobId,
            workerId = workerId,
            jobVersion = requireNotNull(job.version),
            notificationGeneration = 0,
            notificationInputFingerprint = job.notificationInputFingerprint,
        )
    }

    private fun validateFenceWithFreshTransaction(jobId: Long): Boolean =
        try {
            transactions.execute {
                validator.validate(oldFence(jobId))
            } ?: false
        } catch (_: OptimisticLockingFailureException) {
            transactions.execute {
                validator.validate(oldFence(jobId))
            } ?: false
        }

    private fun originalDto(): ScheduleDto =
        ScheduleDto(
            id = 10L,
            title = "fence schedule",
            startAt = "2026-07-24T05:00:00Z",
            travelMinutes = 30,
            departAt = "2026-07-24T04:30:00Z",
            travelMode = ScheduleTravelMode.CAR,
            origin = SchedulePlaceDto(lat = 37.1, lng = 127.1),
            destination = SchedulePlaceDto(lat = 37.2, lng = 127.2),
            category = ScheduleCategoryDto(id = "1", title = "업무", color = "#000000"),
            notificationEnabled = true,
            notificationLeadMinutes = 60,
            notificationIntervalMinutes = 20,
        )

    companion object {
        private const val MEMBER_ID = 1L
        private const val EDITOR_MEMBER_ID = 2L
        private const val PLAN_MEMBER_ID = 3L
        private val NOW = Instant.parse("2026-07-24T03:00:00Z")
    }
}

@TestConfiguration
class SchedulePushFenceTestConfig {
    @Bean
    fun schedulePushFenceClock(): Clock = Clock.fixed(
        Instant.parse("2026-07-24T03:00:00Z"),
        ZoneOffset.UTC,
    )
}
