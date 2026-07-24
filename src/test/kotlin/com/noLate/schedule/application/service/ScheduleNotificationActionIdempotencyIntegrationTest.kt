package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.notification.support.ensureActivePushMember
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDepartureStatus
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.infrastructure.ScheduleNotificationActionReceiptRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.reset
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@Import(
    ScheduleNotificationActionIdempotencyService::class,
    ScheduleNotificationActionIdempotencyWriter::class,
    ScheduleNotificationActionIdempotencyTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-action-idempotency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduleNotificationActionIdempotencyIntegrationTest @Autowired constructor(
    private val service: ScheduleNotificationActionIdempotencyService,
    private val receiptRepository: ScheduleNotificationActionReceiptRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val scheduleService: ScheduleService,
    private val departureStatusService: ScheduleDepartureStatusService,
    private val pushJobService: SchedulePushJobService,
    private val entityManager: EntityManager,
) {
    private val memberId = 41L
    private val scheduleId = 501L
    private val sessionGeneration = 0L
    private val logicalEventKey = "key:" + "a".repeat(64)
    private val departKey = "departNow:$logicalEventKey"
    private val snoozeKey = "snooze:$logicalEventKey"
    private val departureMemberFence = ScheduleDepartureMemberFence(
        memberId = memberId,
        scheduleId = scheduleId,
        frozenRecipientMemberIds = emptySet(),
        activeLockedMemberIds = setOf(memberId),
    )
    private val scheduleDto = ScheduleDto(
        id = scheduleId,
        ownerMemberId = memberId,
        title = "action idempotency",
        startAt = "2026-07-24T10:00:00Z",
        category = ScheduleCategoryDto(id = "1", title = "일정", color = "#246BFE"),
    )

    @BeforeEach
    fun setUp() {
        receiptRepository.deleteAll()
        ensureActivePushMember(jdbcTemplate, memberId)
        reset(scheduleService, departureStatusService, pushJobService)
        whenever(scheduleService.getScheduleDetail(any(), any())).thenReturn(scheduleDto)
        whenever(scheduleService.markDeparted(any(), any())).thenReturn(scheduleDto)
        whenever(
            departureStatusService.lockNotificationActionMembers(
                eq(memberId),
                eq(scheduleId),
                any(),
            )
        ).thenReturn(departureMemberFence)
        whenever(
            departureStatusService.markDeparted(
                memberId,
                scheduleId,
                departureMemberFence,
            )
        ).thenReturn(
            ScheduleDepartureStatus(
                scheduleId = scheduleId,
                memberId = memberId,
                departedAt = Instant.parse("2026-07-24T03:00:00Z"),
            )
        )
        whenever(departureStatusService.attachDepartureParticipants(any(), any()))
            .thenAnswer { invocation -> invocation.getArgument(1) }
        whenever(pushJobService.snoozeDepartureReminder(any(), any()))
            .thenReturn(Instant.parse("2026-07-24T03:05:00Z"))
    }

    @AfterEach
    fun clean() {
        receiptRepository.deleteAll()
    }

    @Test
    fun `concurrent same snooze key mutates exactly once and returns persisted result`() {
        val firstMutationEntered = CountDownLatch(1)
        val allowFirstMutation = CountDownLatch(1)
        whenever(pushJobService.snoozeDepartureReminder(memberId, scheduleId)).thenAnswer {
            firstMutationEntered.countDown()
            assertTrue(allowFirstMutation.await(5, TimeUnit.SECONDS))
            Instant.parse("2026-07-24T03:05:00Z")
        }

        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val results = ConcurrentLinkedQueue<Instant?>()
        repeat(2) {
            executor.submit {
                ready.countDown()
                start.await()
                runCatching {
                    service.snooze(memberId, scheduleId, snoozeKey, sessionGeneration)
                }.onSuccess(results::add).onFailure(failures::add)
                done.countDown()
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(firstMutationEntered.await(5, TimeUnit.SECONDS))
        allowFirstMutation.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertEquals(2, results.size)
        assertEquals(setOf(Instant.parse("2026-07-24T03:05:00Z")), results.toSet())
        verify(pushJobService, times(1)).snoozeDepartureReminder(memberId, scheduleId)
        assertEquals(1, receiptRepository.count())
    }

    @Test
    fun `response loss retry and persistence context restart do not move snooze again`() {
        val first = service.snooze(memberId, scheduleId, snoozeKey, sessionGeneration)
        entityManager.clear()

        // 첫 응답을 FE가 받지 못하고 프로세스가 다시 올라온 뒤 같은 key를 보낸 상황과 같다.
        val retried = service.snooze(memberId, scheduleId, snoozeKey, sessionGeneration)

        assertEquals(first, retried)
        verify(pushJobService, times(1)).snoozeDepartureReminder(memberId, scheduleId)
        assertEquals(1, receiptRepository.count())
    }

    @Test
    fun `depart now retry returns authoritative current result without reapplying mutation`() {
        assertEquals(
            scheduleDto,
            service.departNow(memberId, scheduleId, departKey, sessionGeneration),
        )
        assertEquals(
            scheduleDto,
            service.departNow(memberId, scheduleId, departKey, sessionGeneration),
        )

        verify(departureStatusService, times(1)).markDeparted(
            memberId,
            scheduleId,
            departureMemberFence,
        )
        verify(scheduleService, times(1)).markDeparted(memberId, scheduleId)
        verify(pushJobService, times(1)).cancelByScheduleIdAndMemberId(scheduleId, memberId)
        // 최초 detail + retry authoritative current detail.
        verify(scheduleService, times(2)).getScheduleDetail(memberId, scheduleId)
    }

    @Test
    fun `same key cannot cross member schedule or action scope`() {
        service.snooze(memberId, scheduleId, snoozeKey, sessionGeneration)

        listOf(
            { service.snooze(memberId + 1, scheduleId, snoozeKey, sessionGeneration) },
            { service.snooze(memberId, scheduleId + 1, snoozeKey, sessionGeneration) },
            // 동일 raw key를 다른 action endpoint에 재사용하면 기존 receipt scope가 우선해 409다.
            { service.departNow(memberId, scheduleId, snoozeKey, sessionGeneration) },
        ).forEach { request ->
            val failure = assertThrows<BusinessException> { request() }
            assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, failure.errorCode)
        }

        assertEquals(1, receiptRepository.count())
        verify(pushJobService, times(1)).snoozeDepartureReminder(memberId, scheduleId)
    }

    @Test
    fun `invalid or PII-shaped key is rejected before persistence`() {
        listOf(
            "snooze:$logicalEventKey:person@example.com",
            "free-form-key",
            "departNow:$logicalEventKey",
        ).forEach { invalidKey ->
            val failure = assertThrows<BusinessException> {
                service.snooze(memberId, scheduleId, invalidKey, sessionGeneration)
            }
            assertEquals(ErrorCode.INVALID_IDEMPOTENCY_KEY, failure.errorCode)
        }
        assertEquals(0, receiptRepository.count())
    }

    @Test
    fun `mutation failure rolls receipt back instead of leaving pending state`() {
        whenever(pushJobService.snoozeDepartureReminder(memberId, scheduleId))
            .thenThrow(IllegalStateException("simulated local mutation failure"))

        assertThrows<IllegalStateException> {
            service.snooze(memberId, scheduleId, snoozeKey, sessionGeneration)
        }
        assertNull(receiptRepository.findByKeyFingerprint(
            com.noLate.notification.domain.OpaquePushIdentifier.fingerprint(snoozeKey)
        ))
    }

    @Test
    fun `stale session generation cannot create a receipt or mutate either action`() {
        val staleGeneration = sessionGeneration + 1

        listOf(
            { service.snooze(memberId, scheduleId, snoozeKey, staleGeneration) },
            { service.departNow(memberId, scheduleId, departKey, staleGeneration) },
        ).forEach { request ->
            val failure = assertThrows<BusinessException> { request() }
            assertEquals(ErrorCode.INVALID_TOKEN, failure.errorCode)
        }

        assertEquals(0, receiptRepository.count())
        verify(pushJobService, never()).snoozeDepartureReminder(any(), any())
        verify(pushJobService, never()).cancelByScheduleIdAndMemberId(any(), any())
        verify(departureStatusService, never()).markDeparted(any(), any(), any())
        verify(scheduleService, never()).markDeparted(any(), any())
    }
}

@TestConfiguration
class ScheduleNotificationActionIdempotencyTestConfig {
    @Bean
    fun actionScheduleService(): ScheduleService = mock()

    @Bean
    fun actionDepartureStatusService(): ScheduleDepartureStatusService = mock()

    @Bean
    fun actionPushJobService(): SchedulePushJobService = mock()

    @Bean
    fun actionClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-24T03:00:00Z"), ZoneOffset.UTC)
}
