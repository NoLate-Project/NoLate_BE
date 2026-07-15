package com.noLate.schedule.application.service

import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@Import(ScheduleShareService::class)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-share-invitation;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
class ScheduleShareInvitationConcurrencyIntegrationTest @Autowired constructor(
    private val service: ScheduleShareService,
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val invitationRepository: ScheduleShareInvitationRepository,
    @Suppress("unused") private val categoryRepository: ScheduleCategoryRepository,
    @Suppress("unused") private val categoryShareRepository: ScheduleCategoryShareRepository,
) {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `single use invitation can be accepted by only one concurrent caller`() {
        val fixture = createFixture()
        val invitation = service.createScheduleInvitation(
            ownerMemberId = fixture.ownerId,
            scheduleId = fixture.scheduleId,
            permission = ScheduleSharePermission.VIEWER,
            ttlHours = 24,
            maxAcceptCount = 1,
        )

        val results = runConcurrentAcceptCalls(listOf(fixture.firstTargetId, fixture.secondTargetId)) { memberId ->
            service.acceptInvitation(memberId, invitation.token)
        }

        assertEquals(1, results.successCount)
        assertEquals(1, results.failureCount)
        assertEquals(1, scheduleShareRepository.findAll().size)
        assertEquals(1, invitationRepository.findAll().single().acceptedCount)
    }

    private fun runConcurrentAcceptCalls(
        memberIds: List<Long>,
        call: (Long) -> Any,
    ): ConcurrentAcceptResult {
        val executor = Executors.newFixedThreadPool(memberIds.size)
        val ready = CountDownLatch(memberIds.size)
        val start = CountDownLatch(1)
        val done = CountDownLatch(memberIds.size)
        val successes = ConcurrentLinkedQueue<Any>()
        val failures = ConcurrentLinkedQueue<Throwable>()

        memberIds.forEach { memberId ->
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    successes.add(call(memberId))
                } catch (error: Throwable) {
                    failures.add(error)
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        return ConcurrentAcceptResult(
            successCount = successes.size,
            failureCount = failures.size,
        )
    }

    private fun createFixture(): InvitationConcurrencyFixture {
        val owner = memberRepository.saveAndFlush(
            Member(name = "Owner", password = "Password1!", email = "inv-owner-${System.nanoTime()}@example.com")
        )
        val firstTarget = memberRepository.saveAndFlush(
            Member(name = "Target 1", password = "Password1!", email = "inv-target1-${System.nanoTime()}@example.com")
        )
        val secondTarget = memberRepository.saveAndFlush(
            Member(name = "Target 2", password = "Password1!", email = "inv-target2-${System.nanoTime()}@example.com")
        )
        val schedule = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = requireNotNull(owner.id),
                title = "링크 공유 일정",
                startAt = Instant.parse("2026-07-11T01:00:00Z"),
                endAt = Instant.parse("2026-07-11T02:00:00Z"),
            ).apply {
                updateCategorySnapshot("1", "팀", "#2196f3")
            }
        )

        return InvitationConcurrencyFixture(
            ownerId = requireNotNull(owner.id),
            firstTargetId = requireNotNull(firstTarget.id),
            secondTargetId = requireNotNull(secondTarget.id),
            scheduleId = requireNotNull(schedule.id),
        )
    }
}

private data class InvitationConcurrencyFixture(
    val ownerId: Long,
    val firstTargetId: Long,
    val secondTargetId: Long,
    val scheduleId: Long,
)

private data class ConcurrentAcceptResult(
    val successCount: Int,
    val failureCount: Int,
)
