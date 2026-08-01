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
    fun `ODsay 시간표 provenance는 저장하되 live 비교 baseline을 만들지 않는다`() {
        val scheduleAt = Instant.parse("2026-07-29T05:00:00Z")
        val checkedAt = scheduleAt.minus(90, ChronoUnit.MINUTES)
        val fetchedAt = checkedAt.minusSeconds(2)
        val recommendedDepartureAt = scheduleAt.minus(50, ChronoUnit.MINUTES)
        val job = SchedulePushJob.create(
            memberId = 3L,
            scheduleId = 30L,
            scheduleAt = scheduleAt,
            departureAt = scheduleAt.minus(40, ChronoUnit.MINUTES),
            monitorStartAt = checkedAt,
            intervalMinutes = 20,
        )
        job.startProcessing("odsay-worker")
        job.finishCheck(
            travelMinutes = 35,
            recommendedDepartureAt = recommendedDepartureAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = checkedAt.plus(20, ChronoUnit.MINUTES),
            completeAfterCheck = false,
            etaSource = TrafficSource.TIMETABLE_PROVIDER,
            liveFetchedAt = fetchedAt,
            etaStale = false,
            etaRouteFingerprint = "odsay-route-v1",
            now = checkedAt,
        )

        repository.saveAndFlush(job)
        entityManager.clear()

        val stored = requireNotNull(repository.findByScheduleIdAndMemberId(30L, 3L))
        assertEquals(TrafficSource.TIMETABLE_PROVIDER, stored.lastEtaSource)
        assertEquals(false, stored.lastEtaStale)
        assertEquals(recommendedDepartureAt, stored.lastRecommendedDepartureAt)
        assertEquals(checkedAt, stored.lastCheckedAt)
        assertEquals(null, stored.lastLiveFetchedAt)
        assertEquals(null, stored.lastLiveTravelMinutes)
    }

    @Test
    fun `legacy provenance null row도 새 nullable 컬럼 schema에서 그대로 읽을 수 있다`() {
        val scheduleAt = Instant.parse("2026-07-24T05:00:00Z")
        val checkedAt = scheduleAt.minus(90, ChronoUnit.MINUTES)
        val job = SchedulePushJob.create(
            memberId = 2L,
            scheduleId = 20L,
            scheduleAt = scheduleAt,
            departureAt = scheduleAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = checkedAt,
            intervalMinutes = 20,
        )
        job.startProcessing("legacy-seed")
        job.finishCheck(
            travelMinutes = 30,
            recommendedDepartureAt = scheduleAt.minus(30, ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = checkedAt.plus(20, ChronoUnit.MINUTES),
            completeAfterCheck = false,
            etaSource = TrafficSource.SAVED_FALLBACK,
            etaStale = true,
            etaFailureReason = "PROVIDER_DISABLED: safe",
            etaRouteFingerprint = "legacy-route",
            now = checkedAt,
        )
        repository.saveAndFlush(job)
        entityManager.entityManager.createNativeQuery(
            """
                UPDATE schedule_push_job
                SET last_eta_source = NULL,
                    last_eta_stale = NULL,
                    last_eta_route_fingerprint = NULL,
                    last_live_travel_minutes = NULL
                WHERE schedule_id = 20 AND member_id = 2
            """.trimIndent()
        ).executeUpdate()
        entityManager.clear()

        val stored = requireNotNull(repository.findByScheduleIdAndMemberId(20L, 2L))
        assertEquals(30, stored.lastTravelMinutes)
        assertEquals(null, stored.lastEtaSource)
        assertEquals(null, stored.lastEtaStale)
        assertEquals(null, stored.lastEtaRouteFingerprint)
        assertEquals(null, stored.lastLiveTravelMinutes)
    }

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
            etaRouteFingerprint = "route-v1",
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
            etaRouteFingerprint = "route-v1",
            now = fallbackCheckedAt,
        )

        repository.saveAndFlush(job)
        entityManager.clear()

        val stored = requireNotNull(repository.findByScheduleIdAndMemberId(10L, 1L))
        assertEquals(fallbackCheckedAt, stored.lastCheckedAt)
        assertEquals(liveFetchedAt, stored.lastLiveFetchedAt)
        assertEquals(30, stored.lastLiveTravelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, stored.lastEtaSource)
        assertTrue(stored.lastEtaStale == true)
        assertTrue(stored.lastEtaFailureReason.orEmpty().startsWith("PROVIDER_TIMEOUT:"))
        assertEquals(null, stored.lastTrafficChangeMinutes)
        assertEquals(null, stored.lastChangedAt)

        val recoveredAt = fallbackCheckedAt.plus(20, ChronoUnit.MINUTES)
        stored.startProcessing("restarted-live-worker")
        stored.finishCheck(
            travelMinutes = 45,
            recommendedDepartureAt = scheduleAt.minus(45, ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = recoveredAt.plus(20, ChronoUnit.MINUTES),
            completeAfterCheck = false,
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = recoveredAt,
            etaStale = false,
            etaRouteFingerprint = "route-v1",
            liveComparatorMaxAgeMinutes = 60,
            now = recoveredAt,
        )
        repository.saveAndFlush(stored)
        entityManager.clear()

        val recovered = requireNotNull(repository.findByScheduleIdAndMemberId(10L, 1L))
        assertEquals(45, recovered.lastLiveTravelMinutes)
        assertEquals(recoveredAt, recovered.lastLiveFetchedAt)
        assertEquals(15, recovered.lastTrafficChangeMinutes)
        assertEquals(recoveredAt, recovered.lastChangedAt)
    }
}
