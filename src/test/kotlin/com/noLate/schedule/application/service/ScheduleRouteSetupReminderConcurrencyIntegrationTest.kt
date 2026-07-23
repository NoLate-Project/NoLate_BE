package com.noLate.schedule.application.service

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
) {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent scanners create one marker for the same logical reminder`() {
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
                            memberId = 2L,
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
}
