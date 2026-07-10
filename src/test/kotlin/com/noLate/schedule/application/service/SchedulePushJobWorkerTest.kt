package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.service.policy.DepartureReminderPolicy
import com.noLate.schedule.application.service.policy.PeriodicPushPolicy
import com.noLate.schedule.application.service.policy.TrafficChangePolicy
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@ExtendWith(MockitoExtension::class)
class SchedulePushJobWorkerTest {
    /*
     * 테스트 시간 설정
     *
     * 푸시 시나리오를 다른 시각으로 시험하려면 이 블록만 수정한다.
     * 일정 시작, 추천 출발, 다음 검사 시각은 testNow를 기준으로 상대 계산한다.
     */
    private val testNow = Instant.parse("2026-06-12T01:00:00Z")
    private val notificationIntervalMinutes = 20
    private val departureAlertLeadMinutes = 15
    private val departureReminderIntervalMinutes = 5
    private val defaultScheduleStartAt = testNow.plus(120, ChronoUnit.MINUTES)
    private val shortScheduleStartAt = testNow.plus(60, ChronoUnit.MINUTES)
    private val seoulTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm")
        .withZone(ZoneId.of("Asia/Seoul"))
    private val objectMapper = ObjectMapper()

    @Mock
    lateinit var pushJobRepository: SchedulePushJobRepository

    @Mock
    lateinit var scheduleRepository: ScheduleRepository

    @Mock
    lateinit var trafficClient: TrafficClient

    @Mock
    lateinit var notificationUseCase: NotificationUseCase

    @Test
    fun `알림 시각 전에는 ETA만 조회하고 사용자 푸시 없이 다음 체크를 예약한다`() {
        val schedule = schedule()
        val travelMinutes = 47
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(60, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
            )
        ).thenReturn(listOf(job))
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(travelMinutes)
        assertEquals(1, worker().runDueJobs(testNow))

        verify(trafficClient, times(1)).getTravelMinutes(check<TrafficRequest> {
            assertEquals(37.1, it.originLat)
            assertEquals(127.2, it.destinationLng)
            assertEquals(ScheduleTravelMode.CAR, it.travelMode)
            assertEquals(30, it.fallbackTravelMinutes)
        })
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any())
        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(travelMinutes, job.lastTravelMinutes)
        assertEquals(testNow, job.lastCheckedAt)
        assertEquals(null, job.lastPushedAt)
        assertEquals(1, job.checkCount)
        assertEquals(
            testNow.plus(notificationIntervalMinutes.toLong(), ChronoUnit.MINUTES),
            job.nextCheckAt,
        )
    }

    @Test
    fun `ETA가 줄어들면 푸시하지 않고 변경된 출발 전 알림 시각으로 다음 체크를 재설정한다`() {
        val previousTravelMinutes = 40
        val currentTravelMinutes = 30
        val schedule = schedule(shortScheduleStartAt)
        val previousRecommendedDepartureAt =
            schedule.startAt.minus(previousTravelMinutes.toLong(), ChronoUnit.MINUTES)
        val currentRecommendedDepartureAt =
            schedule.startAt.minus(currentTravelMinutes.toLong(), ChronoUnit.MINUTES)
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = previousRecommendedDepartureAt,
            monitorStartAt = previousRecommendedDepartureAt.minus(60, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        job.startProcessing("previous-worker")
        job.finishCheck(
            travelMinutes = previousTravelMinutes,
            recommendedDepartureAt = previousRecommendedDepartureAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = testNow,
            completeAfterCheck = false,
            now = previousRecommendedDepartureAt.minus(60, ChronoUnit.MINUTES),
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
            )
        ).thenReturn(listOf(job))
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(currentTravelMinutes)
        worker().runDueJobs(testNow)

        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any())
        assertEquals(
            currentRecommendedDepartureAt.minus(
                departureAlertLeadMinutes.toLong(),
                ChronoUnit.MINUTES,
            ),
            job.nextCheckAt,
        )
        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
    }

    @Test
    fun `ETA가 늘어나면 교통 변화 푸시를 보내고 다음 체크를 예약한다`() {
        val previousTravelMinutes = 30
        val currentTravelMinutes = 45
        val schedule = schedule(defaultScheduleStartAt)
        val previousRecommendedDepartureAt =
            schedule.startAt.minus(previousTravelMinutes.toLong(), ChronoUnit.MINUTES)
        val currentRecommendedDepartureAt =
            schedule.startAt.minus(currentTravelMinutes.toLong(), ChronoUnit.MINUTES)
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = previousRecommendedDepartureAt,
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        job.startProcessing("previous-worker")
        job.finishCheck(
            travelMinutes = previousTravelMinutes,
            recommendedDepartureAt = previousRecommendedDepartureAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = testNow,
            completeAfterCheck = false,
            now = testNow.minus(1, ChronoUnit.MINUTES),
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
            )
        ).thenReturn(listOf(job))
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(currentTravelMinutes)
        whenever(notificationUseCase.sendToMember(any(), any(), any(), any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMember(
            memberId = eq(1L),
            title = eq("이동 시간이 늘었어요"),
            body = check { assertTrue(it.contains("15분 더 걸려요")) },
            data = check {
                assertEquals("SCHEDULE_TRAFFIC", it["type"])
                assertEquals("false", it["departNow"])
                assertEquals("15", it["trafficChangeMinutes"])
                assertEquals(currentRecommendedDepartureAt.toString(), it["recommendedDepartureAt"])
            },
        )
        assertEquals(testNow, job.lastPushedAt)
        assertNull(job.lastNotifiedDepartureAt)
        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
    }

    @Test
    fun `추천 출발 15분 전에 출발 준비 푸시를 보내고 ETA 조회를 계속한다`() {
        val travelMinutes = 45
        val schedule = schedule(shortScheduleStartAt)
        val recommendedDepartureAt =
            schedule.startAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES)
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = schedule.startAt.minus(90, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
            )
        ).thenReturn(listOf(job))
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(travelMinutes)
        whenever(notificationUseCase.sendToMember(any(), any(), any(), any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMember(
            memberId = eq(1L),
            title = eq("출발 준비하세요"),
            body = check {
                assertTrue(it.contains(seoulTimeFormatter.format(recommendedDepartureAt)))
                assertTrue(it.contains("15분 남았어요"))
            },
            data = check {
                assertEquals("SCHEDULE_DEPARTURE_REMINDER", it["type"])
                assertEquals("false", it["departNow"])
                assertEquals(recommendedDepartureAt.toString(), it["recommendedDepartureAt"])
            },
        )
        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(recommendedDepartureAt, job.lastNotifiedDepartureAt)
        assertEquals(testNow, job.lastReminderBoundaryAt)
        assertEquals(testNow.plus(departureReminderIntervalMinutes.toLong(), ChronoUnit.MINUTES), job.nextCheckAt)
    }

    @Test
    fun `출발 준비 알림 후 5분 경계에 다시 리마인드 푸시를 보낸다`() {
        val travelMinutes = 45
        val schedule = schedule(shortScheduleStartAt)
        val recommendedDepartureAt =
            schedule.startAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES)
        val firstReminderBoundaryAt =
            recommendedDepartureAt.minus(departureAlertLeadMinutes.toLong(), ChronoUnit.MINUTES)
        val secondReminderAt = firstReminderBoundaryAt.plus(
            departureReminderIntervalMinutes.toLong(),
            ChronoUnit.MINUTES,
        )
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = schedule.startAt.minus(90, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        job.startProcessing("previous-worker")
        job.finishCheck(
            travelMinutes = travelMinutes,
            recommendedDepartureAt = recommendedDepartureAt,
            pushSent = true,
            notifiedDepartureAt = recommendedDepartureAt,
            reminderBoundaryAt = firstReminderBoundaryAt,
            nextCheckAt = secondReminderAt,
            completeAfterCheck = false,
            now = firstReminderBoundaryAt,
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                secondReminderAt,
            )
        ).thenReturn(listOf(job))
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(travelMinutes)
        whenever(notificationUseCase.sendToMember(any(), any(), any(), any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(secondReminderAt)

        verify(notificationUseCase).sendToMember(
            memberId = eq(1L),
            title = eq("출발 시간 안내"),
            body = check {
                assertTrue(it.contains(seoulTimeFormatter.format(recommendedDepartureAt)))
                assertTrue(it.contains("10분 남았어요"))
            },
            data = check {
                assertEquals("SCHEDULE_DEPARTURE_REMINDER", it["type"])
                assertEquals("false", it["departNow"])
            },
        )
        assertEquals(secondReminderAt, job.lastReminderBoundaryAt)
        assertEquals(
            secondReminderAt.plus(departureReminderIntervalMinutes.toLong(), ChronoUnit.MINUTES),
            job.nextCheckAt,
        )
    }

    @Test
    fun `출발 준비 알림 후 ETA가 바뀌면 변경된 추천 출발 시각을 다시 안내한다`() {
        val previousTravelMinutes = 40
        val currentTravelMinutes = 45
        val schedule = schedule(shortScheduleStartAt)
        val previousRecommendedDepartureAt =
            schedule.startAt.minus(previousTravelMinutes.toLong(), ChronoUnit.MINUTES)
        val currentRecommendedDepartureAt =
            schedule.startAt.minus(currentTravelMinutes.toLong(), ChronoUnit.MINUTES)
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = previousRecommendedDepartureAt,
            monitorStartAt = previousRecommendedDepartureAt.minus(60, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        job.startProcessing("previous-worker")
        job.finishCheck(
            travelMinutes = previousTravelMinutes,
            recommendedDepartureAt = previousRecommendedDepartureAt,
            pushSent = true,
            notifiedDepartureAt = previousRecommendedDepartureAt,
            nextCheckAt = testNow,
            completeAfterCheck = false,
            now = testNow.minus(1, ChronoUnit.MINUTES),
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
            )
        ).thenReturn(listOf(job))
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(currentTravelMinutes)
        whenever(notificationUseCase.sendToMember(any(), any(), any(), any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMember(
            memberId = eq(1L),
            title = eq("이동 시간이 늘었어요"),
            body = check {
                assertTrue(it.contains("5분 더 걸려요"))
                assertTrue(it.contains(seoulTimeFormatter.format(currentRecommendedDepartureAt)))
            },
            data = check {
                assertEquals("SCHEDULE_DEPARTURE_REMINDER", it["type"])
                assertEquals("5", it["trafficChangeMinutes"])
                assertEquals(currentRecommendedDepartureAt.toString(), it["recommendedDepartureAt"])
            },
        )
        assertEquals(currentRecommendedDepartureAt, job.lastNotifiedDepartureAt)
        assertEquals(testNow, job.lastReminderBoundaryAt)
    }

    @Test
    fun `실시간 추천 출발 시각에 도달하면 지금 출발 푸시를 보내고 job을 완료한다`() {
        val travelMinutes = 60
        val schedule = schedule(shortScheduleStartAt)
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = schedule.startAt.minus(90, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
            )
        ).thenReturn(listOf(job))
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(travelMinutes)
        whenever(notificationUseCase.sendToMember(any(), any(), any(), any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMember(
            memberId = eq(1L),
            title = eq("지금 출발하세요"),
            body = check { assertTrue(it.contains("지금 출발")) },
            data = check {
                assertEquals("SCHEDULE_DEPARTURE_REMINDER", it["type"])
                assertEquals("true", it["departNow"])
            },
        )
        assertEquals(SchedulePushJobStatus.COMPLETED, job.status)
        assertEquals(testNow, job.lastPushedAt)
    }

    @Test
    fun `지금 출발 푸시가 한 기기에도 전송되지 않으면 완료하지 않고 재시도한다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)

        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMember(any(), any(), any(), any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 0, failedCount = 1))

        worker().runDueJobs(testNow)

        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(testNow.plus(5, ChronoUnit.MINUTES), job.nextCheckAt)
        assertEquals(1, job.retryCount)
        assertNull(job.lastPushedAt)
        assertTrue(job.failureReason.orEmpty().contains("푸시 공급자"))
    }

    @Test
    fun `푸시 토큰이 없으면 원인을 기록하고 재시도한다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)

        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMember(any(), any(), any(), any()))
            .thenReturn(NotificationSendResult())

        worker().runDueJobs(testNow)

        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals("등록된 푸시 토큰이 없습니다.", job.failureReason)
        assertEquals(1, job.retryCount)
    }

    @Test
    fun `최대 재시도 횟수에 도달하면 job을 실패 상태로 종료한다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        job.retryLater("첫 번째 실패", testNow)
        job.retryLater("두 번째 실패", testNow)

        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMember(any(), any(), any(), any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, failedCount = 1))

        worker().runDueJobs(testNow)

        assertEquals(SchedulePushJobStatus.FAILED, job.status)
        assertEquals(3, job.retryCount)
        assertNull(job.lockedBy)
    }

    @Test
    fun `일정 시작 직후에는 지연된 지금 출발 푸시를 발송한다`() {
        val scheduleStartAt = testNow.minus(2, ChronoUnit.MINUTES)
        val schedule = schedule(scheduleStartAt)
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = schedule.startAt.minus(90, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )

        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMember(any(), any(), any(), any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMember(
            memberId = eq(1L),
            title = eq("지금 출발하세요"),
            body = any(),
            data = check {
                assertEquals("SCHEDULE_DEPARTURE_REMINDER", it["type"])
                assertEquals("true", it["departNow"])
            },
        )
        assertEquals(SchedulePushJobStatus.COMPLETED, job.status)
    }

    @Test
    fun `일정 시작 후 유예 시간이 지나면 푸시 없이 job을 완료한다`() {
        val scheduleStartAt = testNow.minus(11, ChronoUnit.MINUTES)
        val schedule = schedule(scheduleStartAt)
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = schedule.startAt.minus(90, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
            )
        ).thenReturn(listOf(job))

        worker().runDueJobs(testNow)

        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any())
        assertEquals(SchedulePushJobStatus.COMPLETED, job.status)
    }

    @Test
    fun `여러 회원의 job이 동시에 검출되어도 각 회원 일정으로 푸시한다`() {
        val firstSchedule = schedule(
            startAt = shortScheduleStartAt,
            scheduleId = 10L,
            memberId = 1L,
            title = "회원 1 일정",
        )
        val secondSchedule = schedule(
            startAt = shortScheduleStartAt,
            scheduleId = 20L,
            memberId = 2L,
            title = "회원 2 일정",
        )
        val firstJob = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = firstSchedule.startAt,
            departureAt = firstSchedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        val secondJob = SchedulePushJob.create(
            memberId = 2L,
            scheduleId = 20L,
            scheduleAt = secondSchedule.startAt,
            departureAt = secondSchedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
            )
        ).thenReturn(listOf(firstJob, secondJob))
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(firstSchedule)
        whenever(scheduleRepository.findScheduleDetail(20L, 2L)).thenReturn(secondSchedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(45, 50)
        whenever(notificationUseCase.sendToMember(any(), any(), any(), any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        assertEquals(2, worker().runDueJobs(testNow))

        verify(scheduleRepository).findScheduleDetail(10L, 1L)
        verify(scheduleRepository).findScheduleDetail(20L, 2L)
        verify(scheduleRepository, never()).findScheduleDetail(10L, 2L)
        verify(scheduleRepository, never()).findScheduleDetail(20L, 1L)
        verify(notificationUseCase).sendToMember(
            memberId = eq(1L),
            title = eq("출발 준비하세요"),
            body = check { assertTrue(it.contains("회원 1 일정")) },
            data = check {
                assertEquals("SCHEDULE_DEPARTURE_REMINDER", it["type"])
                assertEquals("10", it["scheduleId"])
                assertEquals("45", it["travelMinutes"])
            },
        )
        verify(notificationUseCase).sendToMember(
            memberId = eq(2L),
            title = eq("출발 준비하세요"),
            body = check { assertTrue(it.contains("회원 2 일정")) },
            data = check {
                assertEquals("SCHEDULE_DEPARTURE_REMINDER", it["type"])
                assertEquals("20", it["scheduleId"])
                assertEquals("50", it["travelMinutes"])
            },
        )
        assertEquals(45, firstJob.lastTravelMinutes)
        assertEquals(50, secondJob.lastTravelMinutes)
    }

    @Test
    // Simulates a server crash or shutdown after a worker marked a job PROCESSING.
    // The next worker run should unlock stale jobs and put them back into ACTIVE so they can be retried.
    fun `PROCESSING job이 timeout을 넘으면 ACTIVE로 복구하고 즉시 재검사 대상으로 만든다`() {
        val schedule = schedule()
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(60, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(30, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        job.startProcessing("previous-worker")

        whenever(
            pushJobRepository.findAllByStatusAndLockedAtLessThanEqualOrderByLockedAtAsc(
                SchedulePushJobStatus.PROCESSING,
                testNow.minus(10, ChronoUnit.MINUTES),
            )
        ).thenReturn(listOf(job))
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
            )
        ).thenReturn(emptyList())

        assertEquals(0, worker().runDueJobs(testNow))

        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(testNow, job.nextCheckAt)
        assertEquals(1, job.retryCount)
        assertNull(job.lockedBy)
        assertNull(job.lockedAt)
        assertTrue(job.failureReason.orEmpty().contains("Processing timeout"))
    }

    @Test
    fun `사용자가 선택한 경로의 ETA 스냅샷을 실시간 교통 조회 fallback으로 넘긴다`() {
        val schedule = schedule(routeJson = """{"id":"selected-route","minutes":42,"source":"api"}""")
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(60, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
            )
        ).thenReturn(listOf(job))
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(42)

        worker().runDueJobs(testNow)

        verify(trafficClient).getTravelMinutes(check<TrafficRequest> {
            assertEquals(30, it.fallbackTravelMinutes)
            assertEquals(42, it.selectedRouteTravelMinutes)
            assertTrue(it.selectedRouteJson.orEmpty().contains("selected-route"))
        })
    }

    private fun worker() = SchedulePushJobWorker(
        pushJobRepository = pushJobRepository,
        scheduleRepository = scheduleRepository,
        objectMapper = objectMapper,
        trafficClient = trafficClient,
        notificationUseCase = notificationUseCase,
        periodicPushPolicy = PeriodicPushPolicy(),
        departureReminderPolicy = DepartureReminderPolicy(),
        trafficChangePolicy = TrafficChangePolicy(),
        retryDelayMinutes = 5,
        maxRetryCount = 3,
        deliveryGraceMinutes = 10,
        departureAlertLeadMinutes = departureAlertLeadMinutes,
        departureReminderIntervalMinutes = departureReminderIntervalMinutes,
        processingTimeoutMinutes = 10,
    )

    /**
     * 최종 출발 알림 시나리오에서 공통으로 사용하는 due job을 만든다.
     */
    private fun dueDepartureJob(schedule: Schedule) = SchedulePushJob.create(
        memberId = schedule.memberId,
        scheduleId = requireNotNull(schedule.id),
        scheduleAt = schedule.startAt,
        departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
        monitorStartAt = schedule.startAt.minus(90, ChronoUnit.MINUTES),
        intervalMinutes = notificationIntervalMinutes,
    )

    /**
     * 테스트가 발송 결과에만 집중할 수 있도록 due job 조회와 ETA 응답을 한곳에서 준비한다.
     */
    private fun stubDueJob(job: SchedulePushJob, schedule: Schedule, travelMinutes: Int) {
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
            )
        ).thenReturn(listOf(job))
        whenever(scheduleRepository.findScheduleDetail(job.scheduleId, job.memberId)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(travelMinutes)
    }

    private fun schedule(
        startAt: Instant = defaultScheduleStartAt,
        scheduleId: Long = 10L,
        memberId: Long = 1L,
        routeJson: String? = null,
        title: String = "회의",
    ): Schedule =
        Schedule(
            id = scheduleId,
            memberId = memberId,
            title = title,
            startAt = startAt,
            endAt = startAt.plus(60, ChronoUnit.MINUTES),
        ).apply {
            updateRoute(
                travelMinutes = 30,
                departAt = startAt.minus(30, ChronoUnit.MINUTES),
                departedAt = null,
                travelMode = ScheduleTravelMode.CAR,
                locationName = "회사",
                originName = "집",
                originAddress = null,
                originLat = 37.1,
                originLng = 127.1,
                destinationName = "회사",
                destinationAddress = null,
                destinationLat = 37.2,
                destinationLng = 127.2,
                routeJson = routeJson,
                notificationEnabled = true,
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = notificationIntervalMinutes,
            )
        }
}
