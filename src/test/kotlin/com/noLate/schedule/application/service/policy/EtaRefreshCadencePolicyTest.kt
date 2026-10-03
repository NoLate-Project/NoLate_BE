package com.noLate.schedule.application.service.policy

import com.noLate.schedule.domain.SchedulePushJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class EtaRefreshCadencePolicyTest {
    private val now = Instant.parse("2026-09-25T01:00:00Z")
    private fun legacyJob() = SchedulePushJob.create(
        memberId = 1L, scheduleId = 2L,
        scheduleAt = now.plusSeconds(7200), departureAt = now.plusSeconds(3600),
        monitorStartAt = now.minusSeconds(600), intervalMinutes = 20,
    )

    @Test
    fun `existing twenty minute job is advanced to shared cadence without resetting notification history`() {
        val job = legacyJob()
        job.finishCheck(
            travelMinutes = 60, recommendedDepartureAt = now.plusSeconds(3600),
            pushSent = true, notifiedDepartureAt = now.plusSeconds(3600),
            nextCheckAt = now.plusSeconds(1200), completeAfterCheck = false, now = now,
        )
        val generation = job.notificationGeneration
        val pushedAt = job.lastPushedAt
        job.alignEtaCadence(EtaRefreshCadencePolicy.INTERVAL_MINUTES, now)
        assertEquals(10, job.intervalMinutes)
        assertEquals(now.plusSeconds(600), job.nextCheckAt)
        assertEquals(now.plusSeconds(600), job.lastEtaRefreshDueAt)
        assertEquals(generation, job.notificationGeneration)
        assertEquals(pushedAt, job.lastPushedAt)
        assertEquals(1, job.checkCount)
        job.alignEtaCadence(EtaRefreshCadencePolicy.INTERVAL_MINUTES, now.plusSeconds(60))
        assertEquals(now.plusSeconds(600), job.nextCheckAt)
    }

    @Test
    fun `retry timing and in-flight leases survive cadence rollout`() {
        val retry = legacyJob()
        retry.retryLater("provider failure", now.plusSeconds(90))
        retry.alignEtaCadence(10, now)
        assertEquals(10, retry.intervalMinutes)
        assertEquals(now.plusSeconds(90), retry.nextCheckAt)
        assertEquals(1, retry.retryCount)

        val processing = legacyJob()
        processing.startProcessing("worker", now)
        processing.alignEtaCadence(10, now)
        assertEquals(20, processing.intervalMinutes)
        assertEquals("worker", processing.lockedBy)
    }

    @Test
    fun `future monitoring window is not opened early`() {
        val future = SchedulePushJob.create(
            memberId = 1L, scheduleId = 2L,
            scheduleAt = now.plusSeconds(14400), departureAt = now.plusSeconds(10800),
            monitorStartAt = now.plusSeconds(3600), intervalMinutes = 20,
        )
        future.alignEtaCadence(10, now)
        assertEquals(now.plusSeconds(3600), future.nextCheckAt)
    }


    @ParameterizedTest
    @CsvSource(
        "3600, 300", // 출발 60분 전 → 5분 뒤 조회
        "1860, 60",  // 출발 31분 전 → 30분 전 경계에서 조회
        "1801, 1",   // 출발 30분 1초 전 → 1초 뒤 경계 도달
        "1800, 120", // 정확히 30분 전 → 2분 뒤 조회
        "1200, 120", // 출발 20분 전 → 2분 뒤 조회
        "960, 60",   // 출발 16분 전 → 15분 전 경계에서 조회
        "901, 1",    // 출발 15분 1초 전 → 1초 뒤 경계 도달
        "900, 60",   // 정확히 15분 전 → 1분 뒤 조회
        "30, 30",    // 출발 30초 전 → 출발시각을 넘기지 않음
    )
    fun `남은 시간과 주기 전환 경계에 맞춰 다음 조회시각을 정한다`(
        remainingSeconds: Long,
        expectedDelaySeconds: Long,
    ) {
        val departureAt = now.plusSeconds(remainingSeconds)

        val result = EtaRefreshCadencePolicy.nextRefreshAt(
            now = now,
            recommendedDepartureAt = departureAt,
        )

        // 분 단위 반올림이나 경계 누락 없이 정확한 예약 시각을 계산해야 한다.
        assertEquals(now.plusSeconds(expectedDelaySeconds), result)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -60L])
    fun `추천 출발시각에 도달했거나 지났으면 다음 ETA 조회시각은 없다`(
        remainingSeconds: Long,
    ) {
        val result = EtaRefreshCadencePolicy.nextRefreshAt(
            now = now,
            recommendedDepartureAt = now.plusSeconds(remainingSeconds),
        )

        // 이후 알림 일정은 Worker의 기존 알림 정책에서 처리한다.
        assertNull(result)
    }


}
