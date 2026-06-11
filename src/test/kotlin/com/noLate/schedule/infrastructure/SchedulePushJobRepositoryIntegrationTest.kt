package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-push;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
class SchedulePushJobRepositoryIntegrationTest @Autowired constructor(
    private val repository: SchedulePushJobRepository,
) {

    @Test
    fun `현재 시각 이전 ACTIVE job만 스케줄러 조회에 검출된다`() {
        val now = Instant.parse("2026-06-12T01:00:00Z")
        repository.save(
            SchedulePushJob.create(
                memberId = 1L,
                scheduleId = 10L,
                scheduleAt = Instant.parse("2026-06-12T03:00:00Z"),
                departureAt = Instant.parse("2026-06-12T02:00:00Z"),
                monitorStartAt = now.minusSeconds(60),
                intervalMinutes = 20,
            )
        )
        repository.save(
            SchedulePushJob.create(
                memberId = 1L,
                scheduleId = 11L,
                scheduleAt = Instant.parse("2026-06-12T04:00:00Z"),
                departureAt = Instant.parse("2026-06-12T03:00:00Z"),
                monitorStartAt = now.plusSeconds(60),
                intervalMinutes = 20,
            )
        )
        repository.flush()

        val dueJobs = repository
            .findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                now,
            )

        assertEquals(listOf(10L), dueJobs.map { it.scheduleId })
    }
}
