package com.noLate.schedule.application.service

import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
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
@Import(ScheduleRouteSetupReminderWriter::class, ScheduleRouteSetupReminderRegistrar::class)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:route-reminder-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
class ScheduleRouteSetupReminderConcurrencyIntegrationTest @Autowired constructor(
    private val registrar: ScheduleRouteSetupReminderRegistrar,
    private val repository: ScheduleRouteSetupReminderRepository,
    private val memberRepository: MemberRepository,
) {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent scanners create one marker for the same logical reminder`() {
        val memberId = requireNotNull(activeMember("concurrent").id)
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = ConcurrentLinkedQueue<Boolean>()
        val failures = ConcurrentLinkedQueue<Throwable>()

        repeat(2) {
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    results.add(
                        registrar.register(
                            scheduleId = 10L,
                            memberId = memberId,
                            fingerprint = "a".repeat(64),
                            now = Instant.parse("2026-07-23T00:00:00Z"),
                        )
                    )
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

        assertTrue(failures.isEmpty(), failures.joinToString { it.message.orEmpty() })
        assertEquals(1, results.count { it })
        assertEquals(1, results.count { !it })
        assertEquals(1, repository.count())
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `withdrawn recipient cannot recreate a route setup marker`() {
        val withdrawn = activeMember("withdrawn").apply { softDelete() }
        memberRepository.saveAndFlush(withdrawn)

        val created = registrar.register(
            scheduleId = 11L,
            memberId = requireNotNull(withdrawn.id),
            fingerprint = "c".repeat(64),
            now = Instant.parse("2026-07-23T00:00:00Z"),
        )

        assertEquals(false, created)
        assertEquals(0, repository.findAll().count { it.memberId == withdrawn.id })
    }

    private fun activeMember(label: String): Member =
        memberRepository.saveAndFlush(
            Member(
                name = label,
                password = "Password1!",
                email = "$label-${System.nanoTime()}@example.com",
            )
        )
}
