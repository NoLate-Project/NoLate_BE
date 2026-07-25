package com.noLate.schedule.application.service

import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleShareDto
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@DataJpaTest
@Import(
    ScheduleShareService::class,
    ScheduleCategoryService::class,
    ScheduleCategoryDeleteCoordinator::class,
    ScheduleCategoryDeleteWriter::class,
    ScheduleTravelAccessCleanupService::class,
    ScheduleAccessPolicy::class,
    ScheduleSharingAvailabilityPolicy::class,
    ScheduleCategoryDeleteRaceTestConfig::class,
)
@RecordApplicationEvents
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-share;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.sharing.enabled=true",
    ]
)
class ScheduleShareServiceConcurrencyIntegrationTest @Autowired constructor(
    private val service: ScheduleShareService,
    private val categoryService: ScheduleCategoryService,
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val categoryRepository: ScheduleCategoryRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val categoryDeleteObserver: BlockingScheduleCategoryDeleteObserver,
) {

    @Autowired
    private lateinit var applicationEvents: ApplicationEvents

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent schedule sharing leaves exactly one active share`() {
        val fixture = createFixture()

        val results = runConcurrentShareCalls(callCount = 8) {
            service.shareSchedule(
                ownerMemberId = fixture.ownerId,
                scheduleId = fixture.scheduleId,
                targetEmail = null,
                targetAppId = fixture.targetId,
                permission = ScheduleSharePermission.VIEWER,
                presentedSessionGeneration = 0L,
            )
        }

        assertTrue(results.failures.isEmpty(), "동시 공유 호출은 예외 없이 같은 공유 row로 수렴해야 한다. failures=${results.failures}")
        assertEquals(8, results.successes.size)
        assertEquals(1, results.successes.map { it.id }.distinct().size)

        val activeShares = scheduleShareRepository
            .findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(
                fixture.scheduleId,
                ScheduleShareStatus.ACTIVE,
            )
        assertEquals(1, activeShares.size)
        assertEquals(fixture.targetId, activeShares.single().targetMemberId)
        assertEquals(1L, applicationEvents.stream(ScheduleShareGrantedEvent::class.java).count())
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent category sharing leaves exactly one active share`() {
        val fixture = createFixture()

        val results = runConcurrentShareCalls(callCount = 8) {
            service.shareCategory(
                ownerMemberId = fixture.ownerId,
                categoryId = fixture.categoryId,
                targetEmail = fixture.targetEmail,
                permission = ScheduleSharePermission.EDITOR,
                presentedSessionGeneration = 0L,
            )
        }

        assertTrue(results.failures.isEmpty(), "동시 카테고리 공유 호출은 예외 없이 같은 공유 row로 수렴해야 한다. failures=${results.failures}")
        assertEquals(8, results.successes.size)
        assertEquals(1, results.successes.map { it.id }.distinct().size)

        val activeShares = categoryShareRepository
            .findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(
                fixture.categoryId,
                ScheduleShareStatus.ACTIVE,
            )
        assertEquals(1, activeShares.size)
        assertEquals(fixture.targetId, activeShares.single().targetMemberId)
        assertEquals(1L, applicationEvents.stream(ScheduleShareGrantedEvent::class.java).count())
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `share committed during delete preview is removed by bounded fresh transaction retry`() {
        val fixture = createFixture()
        val gate = categoryDeleteObserver.arm()
        val executor = Executors.newSingleThreadExecutor()
        val deletion = executor.submit {
            categoryService.deleteCategory(
                memberId = fixture.ownerId,
                categoryId = fixture.categoryId,
                presentedSessionGeneration = 0L,
            )
        }
        try {
            assertTrue(gate.previewRead.await(5, TimeUnit.SECONDS))
            service.shareCategory(
                ownerMemberId = fixture.ownerId,
                categoryId = fixture.categoryId,
                targetEmail = fixture.targetEmail,
                permission = ScheduleSharePermission.VIEWER,
                presentedSessionGeneration = 0L,
            )
            gate.continueDelete.countDown()
            deletion.get(10, TimeUnit.SECONDS)

            assertTrue(categoryRepository.findById(fixture.categoryId).orElseThrow().deleted)
            assertTrue(
                categoryShareRepository
                    .findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(
                        fixture.categoryId,
                        ScheduleShareStatus.ACTIVE,
                    )
                    .isEmpty()
            )
        } finally {
            gate.continueDelete.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delete committed first rejects a later category share`() {
        val fixture = createFixture()

        categoryService.deleteCategory(
            memberId = fixture.ownerId,
            categoryId = fixture.categoryId,
            presentedSessionGeneration = 0L,
        )

        org.junit.jupiter.api.assertThrows<com.noLate.global.error.BusinessException> {
            service.shareCategory(
                ownerMemberId = fixture.ownerId,
                categoryId = fixture.categoryId,
                targetEmail = fixture.targetEmail,
                permission = ScheduleSharePermission.VIEWER,
                presentedSessionGeneration = 0L,
            )
        }
        assertTrue(
            categoryShareRepository
                .findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(
                    fixture.categoryId,
                    ScheduleShareStatus.ACTIVE,
                )
                .isEmpty()
        )
    }

    private fun runConcurrentShareCalls(
        callCount: Int,
        call: () -> ScheduleShareDto,
    ): ConcurrentShareResult {
        val executor = Executors.newFixedThreadPool(callCount)
        val ready = CountDownLatch(callCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(callCount)
        val successes = ConcurrentLinkedQueue<ScheduleShareDto>()
        val failures = ConcurrentLinkedQueue<Throwable>()

        repeat(callCount) {
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    successes.add(call())
                } catch (error: Throwable) {
                    failures.add(error)
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "동시성 테스트 작업자 준비가 제한 시간 안에 끝나야 한다.")
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS), "동시성 테스트 호출이 제한 시간 안에 끝나야 한다.")
        executor.shutdownNow()

        return ConcurrentShareResult(
            successes = successes.toList(),
            failures = failures.toList(),
        )
    }

    private fun createFixture(): ShareFixture {
        val suffix = fixtureSequence.incrementAndGet()
        val owner = memberRepository.saveAndFlush(
            Member(
                name = "Owner",
                password = "Password1!",
                email = "owner-$suffix@example.com",
            )
        )
        val target = memberRepository.saveAndFlush(
            Member(
                name = "Target",
                password = "Password1!",
                email = "target-$suffix@example.com",
            )
        )
        val category = categoryRepository.saveAndFlush(
            ScheduleCategory(
                memberId = requireNotNull(owner.id),
                title = "팀",
                color = "#2196f3",
                sortOrder = 0,
            )
        )
        val schedule = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = requireNotNull(owner.id),
                title = "공유 일정",
                startAt = Instant.parse("2026-07-10T01:00:00Z"),
                endAt = Instant.parse("2026-07-10T02:00:00Z"),
                categoryId = category.id,
            ).apply {
                updateCategorySnapshot(requireNotNull(category.id).toString(), category.title, category.color)
            }
        )

        return ShareFixture(
            ownerId = requireNotNull(owner.id),
            targetId = requireNotNull(target.id),
            targetEmail = requireNotNull(target.email),
            categoryId = requireNotNull(category.id),
            scheduleId = requireNotNull(schedule.id),
        )
    }
}

private data class ShareFixture(
    val ownerId: Long,
    val targetId: Long,
    val targetEmail: String,
    val categoryId: Long,
    val scheduleId: Long,
)

private data class ConcurrentShareResult(
    val successes: List<ScheduleShareDto>,
    val failures: List<Throwable>,
)

private val fixtureSequence = AtomicLong()

@TestConfiguration
class ScheduleCategoryDeleteRaceTestConfig {
    @Bean
    fun blockingScheduleCategoryDeleteObserver(): BlockingScheduleCategoryDeleteObserver =
        BlockingScheduleCategoryDeleteObserver()
}

class BlockingScheduleCategoryDeleteObserver : ScheduleCategoryDeleteObserver {
    private val armed = AtomicReference<ScheduleCategoryDeleteGate?>()

    fun arm(): ScheduleCategoryDeleteGate =
        ScheduleCategoryDeleteGate().also { check(armed.compareAndSet(null, it)) }

    override fun afterSharePreview(categoryId: Long) {
        val gate = armed.getAndSet(null) ?: return
        gate.previewRead.countDown()
        check(gate.continueDelete.await(10, TimeUnit.SECONDS))
    }
}

class ScheduleCategoryDeleteGate(
    val previewRead: CountDownLatch = CountDownLatch(1),
    val continueDelete: CountDownLatch = CountDownLatch(1),
)
