package com.noLate.schedule.application.service

import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 일정 수정 시 기존 PushJob이 과거 실행 상태를 끌고 가지 않고 새 일정 기준으로
 * 완전히 재예약되는지 검증한다.
 */
@ExtendWith(MockitoExtension::class)
class SchedulePushJobUpdateTest {
    /*
     * 테스트 시간 설정
     *
     * 수정 전/후 일정의 기준 시각을 상단에서 관리한다.
     * 출발 시각과 모니터링 시작 시각은 일정 시작 시각에서 상대적으로 계산된다.
     */
    private val originalScheduleAt = Instant.parse("2026-06-12T03:00:00Z")
    private val updatedScheduleAt = Instant.parse("2026-06-13T04:00:00Z")
    private val originalDepartureAt = originalScheduleAt.minus(30, ChronoUnit.MINUTES)
    private val originalMonitorStartAt = originalDepartureAt.minus(60, ChronoUnit.MINUTES)
    private val updatedDepartureAt = updatedScheduleAt.minus(40, ChronoUnit.MINUTES)
    private val updatedMonitorStartAt = updatedDepartureAt.minus(120, ChronoUnit.MINUTES)

    @Mock
    lateinit var repository: SchedulePushJobRepository

    @Test
    fun `일정 시간과 알림 설정을 수정하면 기존 job의 시간과 실행 이력을 초기화한다`() {
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = originalScheduleAt,
            departureAt = originalDepartureAt,
            monitorStartAt = originalMonitorStartAt,
            intervalMinutes = 20,
        )
        job.startProcessing("test-worker")
        job.finishCheck(
            travelMinutes = 35,
            recommendedDepartureAt = originalScheduleAt.minus(35, ChronoUnit.MINUTES),
            pushSent = true,
            notifiedDepartureAt = originalScheduleAt.minus(35, ChronoUnit.MINUTES),
            nextCheckAt = originalMonitorStartAt.plus(20, ChronoUnit.MINUTES),
            completeAfterCheck = false,
            now = originalMonitorStartAt,
        )

        whenever(repository.findByScheduleId(10L)).thenReturn(job)
        whenever(repository.save(any<SchedulePushJob>())).thenAnswer { it.getArgument(0) }

        SchedulePushJobService(repository).registerFromScheduleDto(
            memberId = 1L,
            scheduleDto = enabledScheduleDto(),
        )

        assertEquals(updatedScheduleAt, job.scheduleAt)
        assertEquals(updatedDepartureAt, job.departureAt)
        assertEquals(updatedMonitorStartAt, job.monitorStartAt)
        assertEquals(job.monitorStartAt, job.nextCheckAt)
        assertEquals(30, job.intervalMinutes)
        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(0, job.checkCount)
        assertEquals(0, job.retryCount)
        assertNull(job.lastTravelMinutes)
        assertNull(job.lastRecommendedDepartureAt)
        assertNull(job.lastNotifiedDepartureAt)
        assertNull(job.lastCheckedAt)
        assertNull(job.lastPushedAt)
        assertNull(job.failureReason)
    }

    private fun enabledScheduleDto() = ScheduleDto(
        id = 10L,
        title = "수정된 회의",
        startAt = updatedScheduleAt.toString(),
        endAt = updatedScheduleAt.plus(60, ChronoUnit.MINUTES).toString(),
        travelMinutes = 40,
        departAt = updatedDepartureAt.toString(),
        travelMode = ScheduleTravelMode.CAR,
        origin = SchedulePlaceDto(lat = 37.1, lng = 127.1),
        destination = SchedulePlaceDto(lat = 37.2, lng = 127.2),
        category = ScheduleCategoryDto(id = "1", title = "업무", color = "#000000"),
        notificationEnabled = true,
        notificationLeadMinutes = 120,
        notificationIntervalMinutes = 30,
    )
}
