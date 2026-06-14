package com.noLate.schedule.application.service

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
    private val defaultScheduleStartAt = testNow.plus(120, ChronoUnit.MINUTES)
    private val shortScheduleStartAt = testNow.plus(60, ChronoUnit.MINUTES)
    private val seoulTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm")
        .withZone(ZoneId.of("Asia/Seoul"))

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
    fun `ETA가 바뀌면 푸시하지 않고 변경된 출발 전 알림 시각으로 다음 체크를 재설정한다`() {
        val previousTravelMinutes = 20
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
            title = eq("출발 시간 안내"),
            body = check {
                assertTrue(it.contains(seoulTimeFormatter.format(recommendedDepartureAt)))
                assertTrue(it.contains("15분 후"))
            },
            data = check {
                assertEquals("false", it["departNow"])
                assertEquals(recommendedDepartureAt.toString(), it["recommendedDepartureAt"])
            },
        )
        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(recommendedDepartureAt, job.lastNotifiedDepartureAt)
        assertEquals(recommendedDepartureAt, job.nextCheckAt)
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
            title = eq("출발 시간 안내"),
            body = check {
                assertTrue(it.contains("5분 늘었습니다"))
                assertTrue(it.contains(seoulTimeFormatter.format(currentRecommendedDepartureAt)))
            },
            data = check {
                assertEquals("5", it["trafficChangeMinutes"])
                assertEquals(currentRecommendedDepartureAt.toString(), it["recommendedDepartureAt"])
            },
        )
        assertEquals(currentRecommendedDepartureAt, job.lastNotifiedDepartureAt)
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
            data = check { assertEquals("true", it["departNow"]) },
        )
        assertEquals(SchedulePushJobStatus.COMPLETED, job.status)
        assertEquals(testNow, job.lastPushedAt)
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
            title = eq("출발 시간 안내"),
            body = check { assertTrue(it.contains("회원 1 일정")) },
            data = check {
                assertEquals("10", it["scheduleId"])
                assertEquals("45", it["travelMinutes"])
            },
        )
        verify(notificationUseCase).sendToMember(
            memberId = eq(2L),
            title = eq("출발 시간 안내"),
            body = check { assertTrue(it.contains("회원 2 일정")) },
            data = check {
                assertEquals("20", it["scheduleId"])
                assertEquals("50", it["travelMinutes"])
            },
        )
        assertEquals(45, firstJob.lastTravelMinutes)
        assertEquals(50, secondJob.lastTravelMinutes)
    }

    private fun worker() = SchedulePushJobWorker(
        pushJobRepository = pushJobRepository,
        scheduleRepository = scheduleRepository,
        trafficClient = trafficClient,
        notificationUseCase = notificationUseCase,
        periodicPushPolicy = PeriodicPushPolicy(),
        departureReminderPolicy = DepartureReminderPolicy(),
        trafficChangePolicy = TrafficChangePolicy(),
        retryDelayMinutes = 5,
        departureAlertLeadMinutes = departureAlertLeadMinutes,
    )

    private fun schedule(
        startAt: Instant = defaultScheduleStartAt,
        scheduleId: Long = 10L,
        memberId: Long = 1L,
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
                routeJson = null,
                notificationEnabled = true,
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = notificationIntervalMinutes,
            )
        }
}
