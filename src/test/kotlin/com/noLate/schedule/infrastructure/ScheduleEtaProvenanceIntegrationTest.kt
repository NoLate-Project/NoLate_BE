package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.TrafficSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.time.temporal.ChronoUnit

@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-eta-provenance;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
class ScheduleEtaProvenanceIntegrationTest @Autowired constructor(
    private val repository: SchedulePushJobRepository,
    private val entityManager: TestEntityManager,
) {
    @Test
    fun `lastCheckedAt과 liveFetchedAt 및 fallback provenance를 독립 컬럼으로 저장한다`() {
        val scheduleAt = Instant.parse("2026-07-24T05:00:00Z")
        val liveCheckedAt = scheduleAt.minus(90, ChronoUnit.MINUTES)
        val fallbackCheckedAt = liveCheckedAt.plus(20, ChronoUnit.MINUTES)
        val liveFetchedAt = liveCheckedAt.minusSeconds(3)
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = scheduleAt,
            departureAt = scheduleAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = liveCheckedAt,
            intervalMinutes = 20,
        )
        job.startProcessing("live-worker")
        job.finishCheck(
            travelMinutes = 30,
            recommendedDepartureAt = scheduleAt.minus(30, ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = fallbackCheckedAt,
            completeAfterCheck = false,
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = liveFetchedAt,
            etaStale = false,
            now = liveCheckedAt,
        )
        job.startProcessing("fallback-worker")
        job.finishCheck(
            travelMinutes = 34,
            recommendedDepartureAt = scheduleAt.minus(34, ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = fallbackCheckedAt.plus(20, ChronoUnit.MINUTES),
            completeAfterCheck = false,
            etaSource = TrafficSource.SELECTED_ROUTE,
            etaStale = true,
            etaFailureReason = "PROVIDER_TIMEOUT: 실시간 ETA 공급자 응답 시간이 초과되었습니다.",
            now = fallbackCheckedAt,
        )

        repository.saveAndFlush(job)
        entityManager.clear()

        val stored = requireNotNull(repository.findByScheduleIdAndMemberId(10L, 1L))
        assertEquals(fallbackCheckedAt, stored.lastCheckedAt)
        assertEquals(liveFetchedAt, stored.lastLiveFetchedAt)
        assertEquals(TrafficSource.SELECTED_ROUTE, stored.lastEtaSource)
        assertTrue(stored.lastEtaStale == true)
        assertTrue(stored.lastEtaFailureReason.orEmpty().startsWith("PROVIDER_TIMEOUT:"))
        assertEquals(null, stored.lastTrafficChangeMinutes)
        assertEquals(null, stored.lastChangedAt)
    }
}
