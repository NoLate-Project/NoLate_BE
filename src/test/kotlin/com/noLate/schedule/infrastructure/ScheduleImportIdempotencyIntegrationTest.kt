package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.Schedule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-import-idempotency;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
class ScheduleImportIdempotencyIntegrationTest @Autowired constructor(
    private val scheduleRepository: ScheduleRepository,
) {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `member and external source key allow only one concurrent insert`() {
        val memberId = 77L
        val sourceKey = "a".repeat(64)
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val successes = ConcurrentLinkedQueue<Long>()
        val failures = ConcurrentLinkedQueue<Throwable>()

        repeat(2) { index ->
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    val saved = scheduleRepository.saveAndFlush(
                        Schedule(
                            memberId = memberId,
                            externalSourceKey = sourceKey,
                            title = "동시 가져오기 $index",
                            startAt = Instant.parse("2026-07-17T10:00:00Z"),
                            endAt = Instant.parse("2026-07-17T11:00:00Z"),
                        )
                    )
                    successes.add(requireNotNull(saved.id))
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

        assertEquals(1, successes.size)
        assertEquals(1, failures.size)
        assertNotNull(
            scheduleRepository.findFirstByMemberIdAndExternalSourceKeyAndDeletedFalse(memberId, sourceKey)
        )
    }
}
