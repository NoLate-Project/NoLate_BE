package com.noLate.schedule.application.cache

import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.application.service.MemberService
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarCacheRevisionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

@DataJpaTest
@Import(
    ScheduleCalendarCacheRevisionService::class,
    ScheduleCalendarCacheInvalidationCoordinator::class,
    ScheduleCalendarCacheInvalidationListener::class,
    ScheduleCalendarCacheRevisionBootstrapService::class,
    MemberService::class,
)
@TestPropertySource(properties = ["schedule.calendar-cache.enabled=false"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduleCalendarCacheRevisionTransactionIntegrationTest @Autowired constructor(
    private val memberRepository: MemberRepository,
    private val revisionRepository: ScheduleCalendarCacheRevisionRepository,
    private val memberService: MemberService,
    private val bootstrapService: ScheduleCalendarCacheRevisionBootstrapService,
    private val eventPublisher: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val requiresNewTransactions = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Test
    fun `cache disabled에서도 mutation과 durable revision은 같은 transaction으로 commit 된다`() {
        val memberId = createMember()

        transactions.executeWithoutResult {
            val member = memberRepository.findById(memberId).orElseThrow()
            member.name = "변경된 이름"
            eventPublisher.publishEvent(
                ScheduleCalendarCacheInvalidationEvent(
                    memberIds = setOf(memberId),
                    reason = "schedule-updated",
                )
            )
        }

        val committed = memberRepository.findById(memberId).orElseThrow()
        assertEquals("변경된 이름", committed.name)
        assertEquals(1L, revision(memberId))
    }

    @Test
    fun `mutation이 rollback되면 durable revision도 증가하지 않는다`() {
        val memberId = createMember()

        assertThrows(IllegalStateException::class.java) {
            transactions.executeWithoutResult {
                val member = memberRepository.findById(memberId).orElseThrow()
                member.name = "rollback 이름"
                eventPublisher.publishEvent(
                    ScheduleCalendarCacheInvalidationEvent(
                        memberIds = setOf(memberId),
                        reason = "schedule-updated",
                    )
                )
                throw IllegalStateException("force rollback")
            }
        }

        val rolledBack = memberRepository.findById(memberId).orElseThrow()
        assertEquals("revision member", rolledBack.name)
        assertEquals(0L, revision(memberId))
    }

    @Test
    fun `한 transaction의 여러 event는 audience union을 terminal 순서에서 한 번만 증가시킨다`() {
        val lowerMemberId = createMember()
        val higherMemberId = createMember()
        var revisionBeforeTerminalLock = -1L

        transactions.executeWithoutResult {
            eventPublisher.publishEvent(
                ScheduleCalendarCacheInvalidationEvent(
                    memberIds = setOf(higherMemberId),
                    reason = "schedule-updated",
                )
            )
            eventPublisher.publishEvent(
                ScheduleCalendarCacheInvalidationEvent(
                    memberIds = setOf(lowerMemberId, higherMemberId),
                    reason = "calendar-settings-updated",
                )
            )
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization, Ordered {
                    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE - 1

                    override fun beforeCommit(readOnly: Boolean) {
                        revisionBeforeTerminalLock = revision(higherMemberId)
                    }
                }
            )
        }

        assertEquals(0L, revisionBeforeTerminalLock)
        assertEquals(1L, revision(lowerMemberId))
        assertEquals(1L, revision(higherMemberId))
    }

    @Test
    fun `REQUIRES_NEW accumulator는 outer rollback과 분리되어 inner commit만 남긴다`() {
        val outerMemberId = createMember()
        val innerMemberId = createMember()

        assertThrows(IllegalStateException::class.java) {
            transactions.executeWithoutResult {
                eventPublisher.publishEvent(
                    ScheduleCalendarCacheInvalidationEvent(
                        memberIds = setOf(outerMemberId),
                        reason = "outer-update",
                    )
                )
                requiresNewTransactions.executeWithoutResult {
                    eventPublisher.publishEvent(
                        ScheduleCalendarCacheInvalidationEvent(
                            memberIds = setOf(innerMemberId),
                            reason = "inner-update",
                        )
                    )
                }
                throw IllegalStateException("rollback outer")
            }
        }

        assertEquals(0L, revision(outerMemberId))
        assertEquals(1L, revision(innerMemberId))
    }

    @Test
    fun `non-prod bootstrap은 기존 member의 누락 revision row를 idempotent하게 채운다`() {
        val legacyMemberId = requireNotNull(
            transactions.execute {
                memberRepository.saveAndFlush(member("legacy")).id
            }
        )

        assertEquals(null, revisionRepository.findRevisionByMemberId(legacyMemberId))
        assertEquals(1, bootstrapService.backfillMissingRows())
        assertEquals(0L, revision(legacyMemberId))
        assertEquals(0, bootstrapService.backfillMissingRows())
    }

    private fun createMember(): Long =
        requireNotNull(
            transactions.execute {
                memberService.addMember(member("revision")).id
            }
        )

    private fun member(prefix: String) = Member(
        name = "$prefix member",
        password = "Password1!",
        email = "$prefix-${System.nanoTime()}@example.com",
        loginType = LoginType.COMMON,
    )

    private fun revision(memberId: Long): Long =
        requireNotNull(revisionRepository.findRevisionByMemberId(memberId))
}
