package com.noLate.schedule.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.notification.application.service.AppNotificationSnapshot
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.liveactivity.application.ScheduleLiveActivityEtaProjection
import com.noLate.notification.liveactivity.application.LiveActivityLifecyclePublisher
import com.noLate.notification.liveactivity.application.ScheduleLiveActivityService
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.EffectiveTransitRouteSegment
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind
import com.noLate.schedule.application.EffectiveTransitRouteSnapshot
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.application.TransitRouteProvenance
import com.noLate.schedule.application.TransitTimingBasis
import com.noLate.schedule.application.service.policy.DepartureReminderPolicy
import com.noLate.schedule.application.service.policy.PeriodicPushPolicy
import com.noLate.schedule.application.service.policy.TrafficChangePolicy
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleAlertMode
import com.noLate.schedule.domain.ScheduleDepartureReminderStage
import com.noLate.schedule.domain.ScheduleEtaRouteFingerprint
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.infrastructure.ScheduleEtaEvaluationHistoryRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.lang.reflect.Modifier
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @ParameterizedTest
    @ValueSource(strings = ["pending", "timeout", "late", "missingTiming", "expired"])
    fun `출발 후 미관측 시간표 재확인은 주기만 앞당기고 실패를 정상 ETA로 만들지 않는다`(case: String) {
        val schedule = schedule(shortScheduleStartAt, travelMode = ScheduleTravelMode.TRANSIT)
        val baseline = testNow.minusSeconds(10)
        val job = persistedJob(1L, 10L, schedule.startAt, baseline,
            testNow.minusSeconds(600), notificationIntervalMinutes)
        val boarding = testNow.plusSeconds(if (case == "expired") -1 else 240)
        val table = com.noLate.eta.domain.TransitLegTimetable(
            provider = "seoul-timetable", line = com.noLate.eta.domain.SeoulSubwayLine.LINE_4,
            serviceDate = testNow.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
            trainNumber = "4012", directionCode = "DOWN",
            serviceClass = com.noLate.eta.domain.TransitServiceClass.LOCAL,
            fromStationCode = "0411", toStationCode = "0433", terminalName = "사당",
            boardingAt = boarding, arrivalAt = boarding.plusSeconds(1800), fetchedAt = testNow,
        )
        val route = effectiveTransitRoute("a".repeat(64), testNow, table.arrivalAt, 34, "4호선")
        val timedRoute = route.copy(segments = route.segments.map { it.copy(rideTiming =
            if (case == "missingTiming") null else com.noLate.schedule.application.EffectiveTransitRideTiming(
                table, table.boardingAt, table.arrivalAt,
                com.noLate.schedule.application.EffectiveTransitRideTimingBasis.TIMETABLE)) })
        val failure = when (case) {
            "timeout" -> TrafficFailureReasons.TRANSIT_ARRIVAL_API_TIMEOUT
            "late" -> TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE
            else -> TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED
        }
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(TrafficResult(
            travelMinutes = 60, source = TrafficSource.TIMETABLE_PROVIDER, fetchedAt = testNow,
            stale = true, failureReason = failure, recommendedDepartureAt = baseline,
            predictedArrivalAt = null, transitTimingBasis = TransitTimingBasis.TIMETABLE_ONLY,
            effectiveTransitRoute = timedRoute,
        ))
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        if (case == "pending") assertEquals(testNow.plusSeconds(60), job.nextCheckAt)
        else assertTrue(requireNotNull(job.nextCheckAt).isAfter(testNow.plusSeconds(60)))
        assertEquals(baseline, job.lastRecommendedDepartureAt)
        assertEquals(true, job.lastEtaStale)
        assertEquals(failure, job.lastEtaFailureReason)
    }

    @ParameterizedTest
    @CsvSource("0, 240, 60", "121, 240, 180", "0, 3, 180")
    fun `조회 중 출발 경계를 지나도 아직 탈 수 있는 신선한 첫 차량이면 1분 뒤 다시 확인한다`(
        sourceAgeSeconds: Long,
        boardingDelaySeconds: Long,
        expectedDelaySeconds: Long,
    ) {
        val schedule = schedule(shortScheduleStartAt, travelMode = ScheduleTravelMode.TRANSIT)
        val job = dueDepartureJob(schedule)
        val departure = testNow.plusSeconds(1)
        val transition = testNow.plusSeconds(4)
        var wallTime = testNow
        val advancingClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant() = wallTime
        }
        whenever(pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
            eq(SchedulePushJobStatus.ACTIVE), any(), eq(org.springframework.data.domain.PageRequest.of(0, 1)),
        )).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(job.scheduleId, job.memberId)).thenReturn(schedule)
        val result = TrafficResult(
            travelMinutes = 60, source = TrafficSource.LIVE_PROVIDER, fetchedAt = testNow,
            stale = false, recommendedDepartureAt = departure, predictedArrivalAt = schedule.startAt,
            firstBoardingObservation = com.noLate.eta.domain.TransitFirstBoardingObservation(
                provider = "seoul-openapi", stationName = "노원", lineName = "4호선",
                departureAt = departure, readyAt = departure.plusSeconds(1),
                expectedBoardingAt = testNow.plusSeconds(boardingDelaySeconds),
                sourceUpdatedAt = transition.minusSeconds(sourceAgeSeconds), evaluatedAt = testNow,
                trainNumber = "4599",
            ),
        )
        whenever(trafficClient.getTravelMinutes(any())).thenAnswer {
            wallTime = transition // 실제 공급자 응답 대기 중 출발 경계를 지나는 상황.
            result
        }
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker(clock = advancingClock).runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFenced(
            memberId = any(), title = any(), body = any(), data = check {
                assertEquals("DEPART_NOW", it["departureReminderDecision"])
                assertEquals("true", it["departNow"])
            }, inboxDeduplicationKey = any(), persistInInbox = any(), dispatchFence = any(),
        )

        // 신선한 차량은 1분, 그 외에는 기존 출발 후 3분 리마인드가 먼저다.
        // 일정 시작까지 건너뛰지 않고 API freshness와 같은 실제 갱신 경계를 저장한다.
        assertEquals(transition.plusSeconds(expectedDelaySeconds), job.nextCheckAt)
        assertEquals(job.nextCheckAt, job.lastEtaRefreshDueAt)
        assertEquals(departure, job.lastRecommendedDepartureAt)
    }

    private var nextJobId = 1_000L
    private val fixtureJobs = mutableListOf<SchedulePushJob>()
    private val storedJobs = mutableMapOf<Long, SchedulePushJob>()

    @Mock
    lateinit var pushJobRepository: SchedulePushJobRepository

    // 실제 DB 없이 Coordinator의 이력 저장 경로를 실행한다.
    @Mock
    lateinit var scheduleEtaEvaluationHistoryRepository: ScheduleEtaEvaluationHistoryRepository

    @Mock
    lateinit var scheduleRepository: ScheduleRepository

    @Mock
    lateinit var trafficClient: TrafficClient

    @Mock
    lateinit var notificationUseCase: NotificationUseCase

    @Mock
    lateinit var travelPlanRepository: ScheduleTravelPlanRepository

    @Mock
    lateinit var scheduleAccessPolicy: ScheduleAccessPolicy

    @Mock
    lateinit var departureAlarmSyncService: DepartureAlarmSyncService

    @Mock
    lateinit var mockedPushJobCoordinator: SchedulePushJobCoordinator

    @Mock
    lateinit var scheduleLiveActivityService: ScheduleLiveActivityService

    @Mock
    lateinit var liveActivityLifecyclePublisher: LiveActivityLifecyclePublisher

    @BeforeEach
    fun prepareStoredJobBoundary() {
        // 테스트용 DB 기본 동작이다. 비활성화/실패 테스트에서는 호출하지 않을 수 있다.
        // claim의 flush 시점에 복사해 Worker가 수정하는 객체와 저장된 lease를 분리한다.
        lenient().doAnswer {
            fixtureJobs.forEach { job ->
                storedJobs[requireNotNull(job.id)] = snapshot(job)
            }
            null
        }.`when`(pushJobRepository).flush()

        lenient().`when`(pushJobRepository.findByIdForUpdate(any())).thenAnswer {
            storedJobs[it.getArgument<Long>(0)]
        }
        lenient().`when`(pushJobRepository.saveAndFlush(any<SchedulePushJob>())).thenAnswer {
            val job = it.getArgument<SchedulePushJob>(0)
            storedJobs[requireNotNull(job.id)] = snapshot(job)
            job
        }
    }

    @Test
    fun `disabled ETA worker does not claim or recover jobs`() {
        assertEquals(0, worker(enabled = false).runDueJobs(testNow))

        verifyNoInteractions(
            pushJobRepository,
            scheduleRepository,
            trafficClient,
            notificationUseCase,
        )
    }

    @Test
    fun `disabled scoped worker does not touch coordinator or providers`() {
        assertEquals(
            0,
            worker(enabled = false, coordinator = mockedPushJobCoordinator)
                .runDueJobForSchedule(296, 363, testNow),
        )
        verifyNoInteractions(mockedPushJobCoordinator, scheduleRepository, trafficClient, notificationUseCase)
    }

    @ParameterizedTest
    @CsvSource("0,363", "-1,363", "296,0", "296,-1")
    fun `scoped worker rejects invalid IDs before claiming`(memberId: Long, scheduleId: Long) {
        assertThrows(IllegalArgumentException::class.java) {
            worker(coordinator = mockedPushJobCoordinator)
                .runDueJobForSchedule(memberId, scheduleId, testNow)
        }
        verifyNoInteractions(mockedPushJobCoordinator, scheduleRepository, trafficClient, notificationUseCase)
    }

    @Test
    fun `scoped worker without due job does not scan or repair other jobs`() {
        assertEquals(
            0,
            worker(coordinator = mockedPushJobCoordinator)
                .runDueJobForSchedule(296, 363, testNow),
        )
        verify(mockedPushJobCoordinator).claimDueJobForSchedule(eq(296), eq(363), eq(testNow), any())
        verifyNoMoreInteractions(mockedPushJobCoordinator)
        verifyNoInteractions(scheduleRepository, trafficClient, notificationUseCase)
    }

    @Test
    fun `scoped worker runs existing ETA persistence and metrics for only the requested job`() {
        val schedule = schedule()
        val job = persistedJob(
            memberId = 1,
            scheduleId = 10,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = testNow,
            intervalMinutes = notificationIntervalMinutes,
        )
        whenever(mockedPushJobCoordinator.claimDueJobForSchedule(eq(1), eq(10), eq(testNow), any()))
            .thenAnswer { invocation ->
                job.startProcessing(invocation.getArgument(3), testNow)
                job
            }
        whenever(mockedPushJobCoordinator.execute<Unit>(any())).thenAnswer { invocation ->
            invocation.getArgument<() -> Unit>(0).invoke()
        }
        whenever(mockedPushJobCoordinator.persist(any(), any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(true)
        whenever(scheduleRepository.findScheduleDetail(10, 1)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(47))
        val registry = SimpleMeterRegistry()

        assertEquals(
            1,
            worker(coordinator = mockedPushJobCoordinator, operationalMetrics = NoLateOperationalMetrics(registry))
                .runDueJobForSchedule(1, 10, testNow),
        )

        verify(mockedPushJobCoordinator).claimDueJobForSchedule(eq(1), eq(10), eq(testNow), any())
        verify(mockedPushJobCoordinator).execute<Unit>(any())
        verify(mockedPushJobCoordinator).persist(eq(job), any(), anyOrNull(), anyOrNull(), check { history ->
            assertNotNull(history)
            assertEquals(10L, history?.scheduleId)
            assertEquals(1L, history?.memberId)
        })
        // 전역 cadence 정리·stale 복구·due 검색은 이 진입점에서 한 번도 실행되지 않는다.
        verifyNoMoreInteractions(mockedPushJobCoordinator)
        verify(trafficClient).getTravelMinutes(any())
        assertEquals(47, job.lastTravelMinutes)
        assertEquals(1, job.checkCount)
        assertEtaTransitionCount(registry, "claimed", 1.0)
        assertEtaTransitionCount(registry, "processed", 1.0)
        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
    }

    @Test
    fun `worker cancels a shared member job when travel permission was reduced`() {
        val schedule = schedule(memberId = 1L)
        val job = persistedJob(
            memberId = 2L,
            scheduleId = requireNotNull(schedule.id),
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(job.scheduleId, job.memberId)).thenReturn(schedule)
        whenever(scheduleAccessPolicy.resolve(2L, schedule)).thenReturn(
            ScheduleAccessDecision(
                canView = true,
                canEdit = false,
                travelEnabled = false,
                canViewAllTravelPlans = false,
            )
        )

        worker(accessPolicy = scheduleAccessPolicy).runDueJobs(testNow)

        assertEquals(SchedulePushJobStatus.CANCELED, job.status)
        verify(trafficClient, never()).getTravelMinutes(any())
        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `알림 시각 전에는 ETA만 조회하고 사용자 푸시 없이 다음 체크를 예약한다`() {
        val schedule = schedule()
        val travelMinutes = 47
        val job = persistedJob(
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
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(travelMinutes))
        val registry = SimpleMeterRegistry()
        assertEquals(
            1,
            worker(
                operationalMetrics = NoLateOperationalMetrics(registry),
            ).runDueJobs(testNow),
        )

        verify(trafficClient, times(1)).getTravelMinutes(check<TrafficRequest> {
            assertEquals(37.1, it.originLat)
            assertEquals(127.2, it.destinationLng)
            assertEquals(ScheduleTravelMode.CAR, it.travelMode)
            assertEquals(30, it.fallbackTravelMinutes)
        })
        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any(), any(), any())
        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(travelMinutes, job.lastTravelMinutes)
        assertEquals(testNow, job.lastCheckedAt)
        assertEquals(null, job.lastPushedAt)
        assertEquals(1, job.checkCount)
        // 출발까지 30분 넘게 남았으므로 5분 뒤 다시 조회한다.
        assertEquals(testNow.plus(5, ChronoUnit.MINUTES), job.nextCheckAt)
        assertEquals(
            1.0,
            registry.get("nolate.eta.jobs").tag("outcome", "claimed").counter().count(),
        )
        assertEquals(
            1.0,
            registry.get("nolate.eta.jobs").tag("outcome", "processed").counter().count(),
        )
        assertEquals(
            1.0,
            registry.get("nolate.eta.resolutions")
                .tag("source", "live_provider")
                .tag("quality", "fresh")
                .counter()
                .count(),
        )
    }

    @Test
    fun `worker forwards a past recommended departure as alarm sync policy input instead of creating now alarm`() {
        val schedule = schedule(
            startAt = testNow.plus(60, ChronoUnit.MINUTES),
            alertMode = ScheduleAlertMode.ALARM,
        )
        val job = persistedJob(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(90))
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker(alarmSyncService = departureAlarmSyncService).runDueJobs(testNow)

        verify(departureAlarmSyncService).synchronizeAutomaticEta(
            memberId = 1L,
            scheduleId = 10L,
            notificationEnabled = true,
            alertMode = ScheduleAlertMode.ALARM,
            recommendedDepartureAt = testNow.minus(30, ChronoUnit.MINUTES),
            scheduleTitle = "회의",
        )
    }

    @Test
    fun `공통 일정 조건과 맞지 않는 개인 계획의 push job은 교통 조회 전에 취소한다`() {
        val schedule = schedule(memberId = 1L)
        val job = persistedJob(
            memberId = 2L,
            scheduleId = requireNotNull(schedule.id),
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        val stalePlan = ScheduleTravelPlan(scheduleId = 10L, memberId = 2L).apply {
            replace(
                command = ScheduleTravelPlanUpsertCommand(
                    travelMinutes = 30,
                    travelMode = ScheduleTravelMode.CAR,
                    originLat = 37.3,
                    originLng = 127.3,
                    routeJson = "{}",
                    notificationEnabled = true,
                ),
                scheduleFingerprint = "stale-fingerprint",
                departAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
                routeJson = "{}",
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = 20,
            )
        }
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(schedule)
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 2L))
            .thenReturn(stalePlan)

        worker().runDueJobs(testNow)

        assertEquals(SchedulePushJobStatus.CANCELED, job.status)
        verify(trafficClient, never()).getTravelMinutes(any())
        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any(), any(), any())
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
        val job = persistedJob(
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
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = previousRecommendedDepartureAt.minus(60, ChronoUnit.MINUTES),
            etaStale = false,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = previousRecommendedDepartureAt.minus(60, ChronoUnit.MINUTES),
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(currentTravelMinutes))
        worker().runDueJobs(testNow)

        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any(), any(), any())
        // 출발까지 30분 넘게 남았으므로 5분 뒤 다시 조회한다.
        assertEquals(testNow.plus(2, ChronoUnit.MINUTES), job.nextCheckAt)
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
        val job = persistedJob(
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
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = testNow.minus(1, ChronoUnit.MINUTES),
            etaStale = false,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = testNow.minus(1, ChronoUnit.MINUTES),
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(currentTravelMinutes))
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(1L),
            title = eq("이동 시간이 늘었어요"),
            body = check { assertTrue(it.contains("15분 더 걸려요")) },
            data = check {
                assertEquals("SCHEDULE_TRAFFIC", it["type"])
                assertEquals("false", it["departNow"])
                assertEquals("15", it["trafficChangeMinutes"])
                assertEquals(currentRecommendedDepartureAt.toString(), it["recommendedDepartureAt"])
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals(testNow, job.lastPushedAt)
        assertNull(job.lastNotifiedDepartureAt)
        assertEquals(TrafficSource.LIVE_PROVIDER, job.lastEtaSource)
        assertEquals(false, job.lastEtaStale)
        assertEquals(testNow, job.lastLiveFetchedAt)
        assertEquals(currentTravelMinutes, job.lastLiveTravelMinutes)
        assertEquals(15, job.lastTrafficChangeMinutes)
        assertEquals(testNow, job.lastChangedAt)
        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
    }

    @Test
    fun `all-covered alarm boundary advances handling without fabricating visible push success`() {
        val schedule = schedule(
            startAt = testNow.plus(60, ChronoUnit.MINUTES),
            alertMode = ScheduleAlertMode.ALARM,
        )
        val recommendedDepartureAt = testNow.plus(15, ChronoUnit.MINUTES)
        val job = persistedJob(
            memberId = schedule.memberId,
            scheduleId = requireNotNull(schedule.id),
            scheduleAt = schedule.startAt,
            departureAt = recommendedDepartureAt,
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        stubDueJob(job, schedule, travelMinutes = 45)
        whenever(
            notificationUseCase.sendToMemberFencedWithNativeAlarmCoverage(
                any(), any(), any(), any(), any(), any(), any(),
            )
        ).thenReturn(
            NotificationSendResult(
                requestedCount = 0,
                nativeAlarmCoveredCount = 2,
            )
        )

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFencedWithNativeAlarmCoverage(
            memberId = eq(schedule.memberId),
            title = eq("출발 준비하세요"),
            body = any(),
            data = check {
                assertEquals("ADVANCE_NOTICE", it["departureReminderDecision"])
                assertEquals(testNow.toString(), it["reminderBoundaryAt"])
                assertEquals("0", it["trafficChangeMinutes"])
            },
            inboxDeduplicationKey = any(),
            dispatchFence = any(),
            nativeAlarmCoverageSelector = check {
                assertEquals("M15", it.occurrenceId)
                assertEquals(testNow, it.occurrenceTriggerAt)
                assertEquals(recommendedDepartureAt, it.recommendedDepartureAt)
            },
        )
        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any(), any(), any())
        assertEquals(testNow, job.lastHandledReminderBoundaryAt)
        assertNull(job.lastReminderBoundaryAt)
        assertNull(job.lastPushedAt)
        assertNull(job.lastNotifiedDepartureAt)
        assertEquals(1, job.checkCount)
    }

    @Test
    fun `alarm boundary keeps ETA shift warning visible while measuring native reminder coverage`() {
        val previousTravelMinutes = 30
        val currentTravelMinutes = 45
        val schedule = schedule(
            startAt = testNow.plus(60, ChronoUnit.MINUTES),
            alertMode = ScheduleAlertMode.ALARM,
        )
        val previousRecommendedDepartureAt =
            schedule.startAt.minus(previousTravelMinutes.toLong(), ChronoUnit.MINUTES)
        val currentRecommendedDepartureAt =
            schedule.startAt.minus(currentTravelMinutes.toLong(), ChronoUnit.MINUTES)
        val job = persistedJob(
            memberId = schedule.memberId,
            scheduleId = requireNotNull(schedule.id),
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
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = testNow.minus(1, ChronoUnit.MINUTES),
            etaStale = false,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = testNow.minus(1, ChronoUnit.MINUTES),
        )
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any()))
            .thenReturn(liveTrafficResult(currentTravelMinutes))
        whenever(
            notificationUseCase.sendToMemberFencedWithNativeAlarmCoverage(
                any(), any(), any(), any(), any(), any(), any(),
            )
        ).thenReturn(
            NotificationSendResult(
                requestedCount = 1,
                nativeAlarmCoveredCount = 1,
                sentCount = 1,
            )
        )

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFencedWithNativeAlarmCoverage(
            memberId = eq(schedule.memberId),
            title = eq("이동 시간이 늘었어요"),
            body = check {
                assertTrue(it.contains("15분 더 걸려요"))
            },
            data = check {
                assertEquals("ADVANCE_NOTICE", it["departureReminderDecision"])
                assertEquals("15", it["trafficChangeMinutes"])
                assertEquals("0", it["departureAdvanceMinutes"])
                assertEquals(currentRecommendedDepartureAt.toString(), it["recommendedDepartureAt"])
            },
            inboxDeduplicationKey = any(),
            dispatchFence = any(),
            nativeAlarmCoverageSelector = check {
                assertEquals("M15", it.occurrenceId)
                assertEquals(testNow, it.occurrenceTriggerAt)
                assertEquals(currentRecommendedDepartureAt, it.recommendedDepartureAt)
                assertTrue(it.semanticWarningVisible)
            },
        )
        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `ODsay 시간표가 계산한 실제 출발시각을 단순 ETA 역산 대신 저장하고 알린다`() {
        val routeJson = """
            {
              "provider": "odsay",
              "transitLegs": [
                {
                  "type": "BUS",
                  "durationMinutes": 40,
                  "waitingMinutes": 7,
                  "providerRouteId": "100100118",
                  "lineName": "서울 100",
                  "startName": "시청",
                  "endName": "서울역",
                  "startStopCode": "ARS:02028",
                  "endStopCode": "ARS:02110"
                }
              ]
            }
        """.trimIndent()
        val schedule = schedule(
            routeJson = routeJson,
            travelMode = ScheduleTravelMode.TRANSIT,
        )
        val previousRecommendedDepartureAt =
            schedule.startAt.minus(40, ChronoUnit.MINUTES)
        val providerRecommendedDepartureAt =
            schedule.startAt.minus(47, ChronoUnit.MINUTES)
        val job = persistedJob(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = previousRecommendedDepartureAt,
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        job.startProcessing("previous-worker")
        job.finishCheck(
            travelMinutes = 40,
            recommendedDepartureAt = previousRecommendedDepartureAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = testNow,
            completeAfterCheck = false,
            etaSource = TrafficSource.TIMETABLE_PROVIDER,
            liveFetchedAt = testNow.minus(1, ChronoUnit.MINUTES),
            etaStale = false,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = testNow.minus(1, ChronoUnit.MINUTES),
        )
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 40,
                source = TrafficSource.TIMETABLE_PROVIDER,
                fetchedAt = testNow,
                stale = false,
                recommendedDepartureAt = providerRecommendedDepartureAt,
                predictedArrivalAt = schedule.startAt.minus(7, ChronoUnit.MINUTES),
            )
        )
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(trafficClient).getTravelMinutes(check<TrafficRequest> {
            assertEquals(ScheduleTravelMode.TRANSIT, it.travelMode)
            assertEquals(schedule.startAt, it.targetArrivalAt)
            assertEquals(previousRecommendedDepartureAt, it.plannedDepartureAt)
            assertTrue(it.selectedRouteJson.orEmpty().contains("\"provider\": \"odsay\""))
        })
        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(1L),
            title = eq("출발 시간이 앞당겨졌어요"),
            body = check {
                assertTrue(it.contains("7분 일찍 출발"))
            },
            data = check {
                assertEquals("7", it["departureAdvanceMinutes"])
                assertEquals("0", it["trafficChangeMinutes"])
                assertEquals(providerRecommendedDepartureAt.toString(), it["recommendedDepartureAt"])
                assertEquals(schedule.startAt.minus(7, ChronoUnit.MINUTES).toString(), it["predictedArrivalAt"])
                assertEquals("true", it["onTimeArrivalPossible"])
                assertEquals("TIMETABLE_PROVIDER", it["etaSource"])
                assertEquals("false", it["etaStale"])
                assertEquals("", it["etaFailureReason"])
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals(providerRecommendedDepartureAt, job.lastRecommendedDepartureAt)
        assertEquals(TrafficSource.TIMETABLE_PROVIDER, job.lastEtaSource)
        assertEquals(false, job.lastEtaStale)
        assertNull(job.lastLiveFetchedAt)
    }

    @Test
    fun `verified alternative route transition triggers push and persists its identity`() {
        val schedule = schedule(
            routeJson = """{"steps":[{"mode":"BUS","route":"341"}]}""",
            travelMode = ScheduleTravelMode.TRANSIT,
        )
        val job = persistedJob(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        val recommendedDepartureAt = schedule.startAt.minus(65, ChronoUnit.MINUTES)
        val predictedArrivalAt = schedule.startAt.minus(10, ChronoUnit.MINUTES)
        val effectiveRoute = effectiveTransitRoute(
            identity = "e".repeat(64),
            departureAt = recommendedDepartureAt,
            arrivalAt = predictedArrivalAt,
            totalMinutes = 55,
            lineName = "2호선",
        )
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 55,
                source = TrafficSource.TIMETABLE_PROVIDER,
                fetchedAt = testNow,
                stale = false,
                recommendedDepartureAt = recommendedDepartureAt,
                predictedArrivalAt = predictedArrivalAt,
                transitRouteProvenance = TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
                transitTimingBasis = TransitTimingBasis.TIMETABLE_ONLY,
                firstBoardingWaitMinutes = 6,
                effectiveTransitRoute = effectiveRoute,
            )
        )
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(1L),
            title = eq("대중교통 경로가 바뀌었어요"),
            body = check {
                assertTrue(it.contains("현재 탈 수 있고"))
                assertTrue(it.contains("2호선"))
            },
            data = check {
                assertEquals("true", it["routeChanged"])
                assertEquals("e".repeat(64), it["effectiveRouteIdentity"])
                assertEquals("6", it["firstBoardingWaitMinutes"])
                assertEquals("ODSAY_ALTERNATIVE_ROUTE", it["etaRouteProvenance"])
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        val stored = EffectiveTransitRouteSnapshot.parse(
            objectMapper,
            job.lastEffectiveTransitRouteJson,
        )
        assertTrue(stored?.accepted == true)
        assertTrue(stored?.routeChanged == true)
        assertEquals("e".repeat(64), stored?.effectiveTransitRoute?.identity)
    }

    @Test
    fun `정시 도착 불가 ETA는 거짓 정시 문구 대신 예상 도착시각과 degraded provenance를 알린다`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = persistedJob(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        val predictedArrivalAt = schedule.startAt.plus(10, ChronoUnit.MINUTES)
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = ChronoUnit.MINUTES.between(testNow, predictedArrivalAt).toInt(),
                source = TrafficSource.LIVE_PROVIDER,
                fetchedAt = testNow,
                stale = true,
                failureReason = TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE,
                recommendedDepartureAt = testNow,
                predictedArrivalAt = predictedArrivalAt,
            )
        )
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(1L),
            title = eq("정시 도착이 어려워요"),
            body = check {
                assertTrue(it.contains("제시간 도착하기 어려워요"))
                assertTrue(it.contains("가장 빠른 예상 도착"))
            },
            data = check {
                assertEquals(predictedArrivalAt.toString(), it["predictedArrivalAt"])
                assertEquals("false", it["onTimeArrivalPossible"])
                assertEquals("LIVE_PROVIDER", it["etaSource"])
                assertEquals("true", it["etaStale"])
                assertEquals(
                    TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE,
                    it["etaFailureReason"],
                )
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals(predictedArrivalAt, job.lastPredictedArrivalAt)
        assertEquals(ScheduleTravelMode.TRANSIT, job.lastEtaTravelMode)
        assertEquals(true, job.lastEtaStale)
        assertEquals(
            TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE,
            job.lastEtaFailureReason,
        )
    }

    @Test
    fun `환승 실패 전이는 리마인드 시각 전에도 경로 재확인 push를 한 번 보낸다`() {
        val schedule = schedule(
            alertMode = ScheduleAlertMode.ALARM,
            travelMode = ScheduleTravelMode.TRANSIT,
        )
        val job = dueDepartureJob(schedule)
        val impossibleTimetableArrival = schedule.startAt.minus(5, ChronoUnit.MINUTES)
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 45,
                source = TrafficSource.LIVE_PROVIDER,
                fetchedAt = testNow,
                stale = true,
                failureReason = TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
                recommendedDepartureAt = testNow,
                predictedArrivalAt = impossibleTimetableArrival,
            )
        )
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker(alarmSyncService = departureAlarmSyncService).runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(job.memberId),
            title = eq("선택한 환승을 놓칠 수 있어요"),
            body = check {
                assertTrue(it.contains("환승 차량을 현재 ETA로는 탈 수 없어요"))
                assertTrue(it.contains("기존 출발 알람을 신뢰하지 말고"))
                assertTrue(it.contains("기기 알람 상태"))
                assertTrue(it.contains("경로를 다시 확인"))
                assertTrue(!it.contains("지금 출발"))
            },
            data = check {
                assertEquals("", it["predictedArrivalAt"])
                assertEquals("", it["onTimeArrivalPossible"])
                assertEquals(
                    job.departureAt.toString(),
                    it["recommendedDepartureAt"],
                )
                assertEquals(
                    TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
                    it["etaFailureReason"],
                )
                assertEquals("MISSED", it["transitTransferFeasibility"])
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertNull(job.lastPredictedArrivalAt)
        assertEquals(
            job.departureAt,
            job.lastRecommendedDepartureAt,
        )
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_MISSED, job.lastEtaFailureReason)
        verify(departureAlarmSyncService).synchronizeAutomaticEta(
            memberId = eq(job.memberId),
            scheduleId = eq(job.scheduleId),
            notificationEnabled = eq(false),
            alertMode = eq(ScheduleAlertMode.ALARM),
            recommendedDepartureAt = eq(job.departureAt),
            scheduleTitle = eq(schedule.title),
            resumeCanceledAfterTransitTransferFailure = eq(false),
            cancellationReason = eq(com.noLate.schedule.domain.DepartureAlarmCancellationReason.TRANSIT_TRANSFER_FAILURE),
        )
    }

    @Test
    fun `환승 실패 전환 push가 재시도되어도 취소 원인과 알람 상태를 같은 전이로 보존한다`() {
        val schedule = schedule(
            alertMode = ScheduleAlertMode.ALARM,
            travelMode = ScheduleTravelMode.TRANSIT,
        )
        val job = dueDepartureJob(schedule)
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 45,
                source = TrafficSource.SELECTED_ROUTE,
                stale = true,
                failureReason = TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
            )
        )
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(
                NotificationSendResult(
                    requestedCount = 1,
                    attemptedCount = 1,
                    failedCount = 1,
                    retryableFailedCount = 1,
                )
            )

        worker(alarmSyncService = departureAlarmSyncService).runDueJobs(testNow)

        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(1, job.retryCount)
        assertEquals(0, job.checkCount)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_MISSED, job.lastEtaFailureReason)
        assertEquals(true, job.lastEtaStale)
        verify(departureAlarmSyncService).synchronizeAutomaticEta(
            memberId = eq(job.memberId),
            scheduleId = eq(job.scheduleId),
            notificationEnabled = eq(false),
            alertMode = eq(ScheduleAlertMode.ALARM),
            recommendedDepartureAt = eq(job.departureAt),
            scheduleTitle = eq(schedule.title),
            resumeCanceledAfterTransitTransferFailure = eq(false),
            cancellationReason = eq(com.noLate.schedule.domain.DepartureAlarmCancellationReason.TRANSIT_TRANSFER_FAILURE),
        )
    }

    @Test
    fun `같은 환승 실패를 반복 조회하면 경로 재확인 push를 중복 발송하지 않는다`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = dueDepartureJob(schedule)
        job.startProcessing("previous-worker")
        job.finishCheck(
            travelMinutes = 45,
            recommendedDepartureAt = schedule.startAt.minus(45, ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = testNow,
            completeAfterCheck = false,
            etaSource = TrafficSource.SELECTED_ROUTE,
            etaStale = true,
            etaFailureReason = TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = testNow.minus(1, ChronoUnit.MINUTES),
        )
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 45,
                source = TrafficSource.SELECTED_ROUTE,
                stale = true,
                failureReason = TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
                recommendedDepartureAt = testNow,
            )
        )

        worker(alarmSyncService = departureAlarmSyncService).runDueJobs(testNow)

        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any(), any(), any())
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_MISSED, job.lastEtaFailureReason)
    }

    @Test
    fun `환승 실패 뒤 정상 ETA가 회복되면 기존 안전 출발시각으로 취소 알람을 복구한다`() {
        val schedule = schedule(
            alertMode = ScheduleAlertMode.ALARM,
            travelMode = ScheduleTravelMode.TRANSIT,
        )
        val job = dueDepartureJob(schedule)

        val previousSafeDepartureAt =
            schedule.startAt.minus(45, ChronoUnit.MINUTES)

        job.startProcessing("previous-worker")
        job.finishCheck(
            travelMinutes = 45,
            recommendedDepartureAt = previousSafeDepartureAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = testNow,
            completeAfterCheck = false,
            etaSource = TrafficSource.SELECTED_ROUTE,
            etaStale = true,
            etaFailureReason = TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = testNow.minus(1, ChronoUnit.MINUTES),
        )

        stubDueJobLookup(job, schedule)

        // Provider가 기존보다 늦은 출발시각을 반환한 상황이다.
        val recoveredCandidateDepartureAt =
            schedule.startAt.minus(30, ChronoUnit.MINUTES)

        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 30,
                source = TrafficSource.LIVE_PROVIDER,
                fetchedAt = testNow,
                stale = false,
                recommendedDepartureAt = recoveredCandidateDepartureAt,
                predictedArrivalAt = schedule.startAt,
            )
        )

        worker(alarmSyncService = departureAlarmSyncService).runDueJobs(testNow)

        verify(departureAlarmSyncService).synchronizeAutomaticEta(
            memberId = eq(job.memberId),
            scheduleId = eq(job.scheduleId),
            notificationEnabled = eq(true),
            alertMode = eq(ScheduleAlertMode.ALARM),

            // 정상 회복은 했지만 기존 알림보다 늦추지는 않는다.
            recommendedDepartureAt = eq(previousSafeDepartureAt),

            scheduleTitle = eq(schedule.title),

            // 이전 MISSED 때문에 취소된 자동 알람은 다시 활성화한다.
            resumeCanceledAfterTransitTransferFailure = eq(true),
            cancellationReason = eq(com.noLate.schedule.domain.DepartureAlarmCancellationReason.EXPLICIT_OR_TERMINAL),
        )

        assertEquals(previousSafeDepartureAt, job.lastRecommendedDepartureAt)
        assertNull(job.lastEtaFailureReason)

        verify(notificationUseCase, never()).sendToMemberFenced(
            any(), any(), any(), any(), any(), any(),
            dispatchFence = any(),
        )
        verify(notificationUseCase, never()).sendToMember(
            any(), any(), any(), any(), any(), any(),
        )
    }

    @Test
    fun `정상 상태에서 환승 시간 UNKNOWN이면 기존 자동 알람을 유지한다`() {
        val schedule = schedule(
            alertMode = ScheduleAlertMode.ALARM,
            travelMode = ScheduleTravelMode.TRANSIT,
        )
        val job = dueDepartureJob(schedule)

        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 45,
                source = TrafficSource.SELECTED_ROUTE,
                stale = true,
                failureReason = TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN,
            )
        )
        whenever(
            notificationUseCase.sendToMemberFenced(
                any(), any(), any(), any(), any(), any(),
                dispatchFence = any(),
            )
        ).thenReturn(
            NotificationSendResult(requestedCount = 1, sentCount = 1)
        )

        worker(alarmSyncService = departureAlarmSyncService).runDueJobs(testNow)

        // 불확실 결과로 서비스까지 호출하면 과거 fallback 시각이 미래 알람을
        // 취소할 수 있으므로 알람 변경 자체를 요청하지 않는다.
        verifyNoInteractions(departureAlarmSyncService)

        assertEquals(
            TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN,
            job.lastEtaFailureReason,
        )
        assertEquals(job.departureAt, job.lastRecommendedDepartureAt)
    }

    @ParameterizedTest
    @ValueSource(strings = ["STALE_ONLY", "PROVIDER_TIMEOUT", "TRANSIT_TRANSFER_TIMING_UNKNOWN", "TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY"])
    fun `첫 저신뢰도 평가의 과거 출발시각으로 자동 알람을 변경하지 않는다`(diagnostic: String) {
        val schedule = schedule(alertMode = ScheduleAlertMode.ALARM, travelMode = ScheduleTravelMode.TRANSIT)
        val job = persistedJob(
            memberId = schedule.memberId, scheduleId = requireNotNull(schedule.id), scheduleAt = schedule.startAt,
            departureAt = testNow.minus(1, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(2, ChronoUnit.MINUTES), intervalMinutes = notificationIntervalMinutes,
        )
        val reason = when (diagnostic) {
            "STALE_ONLY" -> null
            "TRANSIT_TRANSFER_TIMING_UNKNOWN" -> TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN
            "TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY" -> TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY
            else -> "PROVIDER_TIMEOUT: 실시간 ETA 공급자 응답 시간이 초과되었습니다."
        }
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 45, source = TrafficSource.LIVE_PROVIDER, fetchedAt = testNow,
                stale = true, failureReason = reason,
                recommendedDepartureAt = testNow.minus(1, ChronoUnit.MINUTES),
            )
        )
        lenient().`when`(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        lenient().`when`(notificationUseCase.sendToMemberFencedWithNativeAlarmCoverage(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker(alarmSyncService = departureAlarmSyncService).runDueJobs(testNow)

        verifyNoInteractions(departureAlarmSyncService)
        assertEquals(reason, job.lastEtaFailureReason)
        assertEquals(true, job.lastEtaStale)
        assertEquals(0, job.retryCount)
    }

    @Test
    fun `환승 실패 종류가 바뀌면 새 상태를 한 번 알린다`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = dueDepartureJob(schedule)
        job.startProcessing("previous-worker")
        job.finishCheck(
            travelMinutes = 45,
            recommendedDepartureAt = schedule.startAt.minus(45, ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = testNow,
            completeAfterCheck = false,
            etaSource = TrafficSource.SELECTED_ROUTE,
            etaStale = true,
            etaFailureReason = TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = testNow.minus(1, ChronoUnit.MINUTES),
        )
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 45,
                source = TrafficSource.SELECTED_ROUTE,
                stale = true,
                failureReason = TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN,
            )
        )
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(job.memberId),
            title = eq("환승 가능 여부를 확인할 수 없어요"),
            body = check {
                assertTrue(it.contains("환승 시간표나 안전 여유를 확정하지 못했어요"))
                assertTrue(it.contains("경로를 다시 확인"))
            },
            data = check {
                assertEquals(
                    TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN,
                    it["etaFailureReason"],
                )
                assertEquals("UNKNOWN", it["transitTransferFeasibility"])
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals(
            TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN,
            job.lastEtaFailureReason,
        )
    }

    @Test
    fun `saved worker event는 TTL과 check count를 direct provider fence에 전달한다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        SchedulePushJob::class.java.getDeclaredField("id").apply {
            isAccessible = true
            set(job, 123L)
        }
        SchedulePushJob::class.java.getDeclaredField("version").apply {
            isAccessible = true
            set(job, 0L)
        }
        job.startProcessing("mock-owner", testNow)
        whenever(mockedPushJobCoordinator.claimNextDueJob(any(), any())).thenReturn(job, null)
        whenever(mockedPushJobCoordinator.execute<Unit>(any())).thenAnswer { invocation ->
            invocation.getArgument<() -> Unit>(0).invoke()
        }
        whenever(mockedPushJobCoordinator.persist(any(), any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(true)
        whenever(scheduleRepository.findScheduleDetail(job.scheduleId, job.memberId)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(60))
        whenever(
            notificationUseCase.sendToMemberFenced(
                any(), any(), any(), any(), any(), any(), any(),
            )
        ).thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker(coordinator = mockedPushJobCoordinator).runDueJobs(testNow)

        val fence = argumentCaptor<com.noLate.notification.application.service.PushDispatchFence>()
        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(job.memberId),
            title = any(),
            body = any(),
            data = any(),
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = fence.capture(),
        )
        assertEquals(0, fence.firstValue.expectedCheckCount)
        assertEquals(testNow.plusSeconds(120), fence.firstValue.sourceExpiresAt)
    }

    @Test
    fun `성공한 ETA 저장 뒤 Live Activity에 대기시간 포함 projection을 독립 전달한다`() {
        val schedule = schedule(
            routeJson = """{"steps":[{"mode":"BUS","route":"341"}]}""",
            travelMode = ScheduleTravelMode.TRANSIT,
        )
        val job = persistedJob(
            memberId = 1,
            scheduleId = 10,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(60, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minusSeconds(1),
            intervalMinutes = notificationIntervalMinutes,
        )
        SchedulePushJob::class.java.getDeclaredField("id").apply {
            isAccessible = true
            set(job, 321L)
        }
        job.startProcessing("mock-owner", testNow)
        whenever(mockedPushJobCoordinator.claimNextDueJob(any(), any())).thenReturn(job, null)
        whenever(mockedPushJobCoordinator.execute<Unit>(any())).thenAnswer { invocation ->
            invocation.getArgument<() -> Unit>(0).invoke()
        }
        whenever(mockedPushJobCoordinator.persist(any(), any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(true)
        whenever(scheduleRepository.findScheduleDetail(10, 1)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            liveTrafficResult(60).copy(
                firstBoardingWaitMinutes = 20,
                transitRouteProvenance = TransitRouteProvenance.SELECTED_ROUTE_PRESERVED,
                transitTimingBasis = TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE,
                effectiveTransitRoute = effectiveTransitRoute(
                    identity = "f".repeat(64),
                    departureAt = schedule.startAt.minus(60, ChronoUnit.MINUTES),
                    arrivalAt = schedule.startAt,
                    totalMinutes = 60,
                    lineName = "2호선",
                ),
            )
        )

        worker(
            coordinator = mockedPushJobCoordinator,
            liveActivityService = scheduleLiveActivityService,
        ).runDueJobs(testNow)

        val projection = argumentCaptor<ScheduleLiveActivityEtaProjection>()
        verify(scheduleLiveActivityService).projectEta(projection.capture())
        assertEquals(60, projection.firstValue.travelMinutes)
        assertEquals(20, projection.firstValue.firstBoardingWaitMinutes)
        assertEquals("회사", projection.firstValue.destinationName)
        assertEquals(listOf("subway"), projection.firstValue.routeSegments.map { it.kind })
        assertEquals(listOf("2호선"), projection.firstValue.routeSegments.map { it.label })
        assertEquals(0, projection.firstValue.checkCount)
        assertEquals("f".repeat(64), EffectiveTransitRouteSnapshot.parse(
            objectMapper,
            job.lastEffectiveTransitRouteJson,
        )?.effectiveTransitRoute?.identity)
    }

    @ParameterizedTest
    @CsvSource(
        "60, 5, 300, 600",
        "30, 5, 120, 420",
        "15, 5, 60, 360",
        "31, 5, 60, 360",
        "60, 0, 300, 300",
        "60, 2, 300, 420",
        "60, 60, 300, 3900",
        "-28, 5, 60, 120",
    )
    fun `Live Activity 유효시간은 저장한 갱신 경계와 설정된 유예시간을 따른다`(
        minutesUntilDeparture: Long,
        graceMinutes: Long,
        expectedRefreshSeconds: Long,
        expectedStaleSeconds: Long,
    ) {
        // 이동시간은 30분으로 고정하고 출발까지 남은 시간만 바꾼다.
        // 마지막 행은 일정 2분 전이다. 1분 전 리마인드에 다시 조회하더라도
        // 유효시간은 조회시각+5분이 아니라 일정 시작 시각에서 끝나야 한다.
        val schedule = schedule(testNow.plus(minutesUntilDeparture + 30, ChronoUnit.MINUTES))
        val job = dueDepartureJob(schedule)
        stubDueJob(job, schedule, travelMinutes = 30)
        if (minutesUntilDeparture <= departureAlertLeadMinutes) {
            whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
                .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))
        }

        worker(
            liveActivityService = scheduleLiveActivityService,
            departureStatusSnapshotGraceMinutes = graceMinutes,
        ).runDueJobs(testNow)

        val projection = argumentCaptor<ScheduleLiveActivityEtaProjection>()
        verify(scheduleLiveActivityService).projectEta(projection.capture())
        val expectedRefreshAt = testNow.plusSeconds(expectedRefreshSeconds)
        assertEquals(expectedRefreshAt, job.lastEtaRefreshDueAt)
        assertEquals(expectedRefreshAt, storedJobs[requireNotNull(job.id)]?.lastEtaRefreshDueAt)
        assertEquals(testNow.plusSeconds(expectedStaleSeconds), projection.firstValue.staleAt)
        assertFalse(requireNotNull(projection.firstValue.staleAt).isAfter(schedule.startAt))
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, 61])
    fun `Live Activity 유예시간 설정이 허용 범위를 벗어나면 시작하지 않는다`(graceMinutes: Long) {
        assertThrows(IllegalArgumentException::class.java) {
            worker(departureStatusSnapshotGraceMinutes = graceMinutes)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `푸시 실패 뒤 Live Activity 유효시간은 실제 ETA 재시도 경계를 사용한다`(retryable: Boolean) {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(
                NotificationSendResult(
                    requestedCount = 1,
                    attemptedCount = 1,
                    failedCount = 1,
                    retryableFailedCount = if (retryable) 1 else 0,
                ),
            )

        worker(liveActivityService = scheduleLiveActivityService).runDueJobs(testNow)

        val projection = argumentCaptor<ScheduleLiveActivityEtaProjection>()
        verify(scheduleLiveActivityService).projectEta(projection.capture())
        assertEquals(1, job.retryCount)
        assertEquals(0, job.checkCount)
        assertEquals(testNow.plus(1, ChronoUnit.MINUTES), job.lastEtaRefreshDueAt)
        assertEquals(testNow.plus(6, ChronoUnit.MINUTES), projection.firstValue.staleAt)
    }

    @ParameterizedTest
    @ValueSource(strings = ["REJECTED", "FAILED", "MISSING_DUE"])
    fun `저장 실패 또는 ETA 갱신 경계 누락 시 Live Activity를 갱신하지 않는다`(scenario: String) {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        stubClaimedEtaJob(job, schedule, travelMinutes = 30)
        whenever(mockedPushJobCoordinator.persist(any(), any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenAnswer {
                when (scenario) {
                    "REJECTED" -> false
                    "FAILED" -> throw IllegalStateException("test persistence failure")
                    else -> {
                        // 잘못된 저장 결과에서도 유효시간 없는 projection을 보내면 안 된다.
                        ReflectionTestUtils.setField(job, "lastEtaRefreshDueAt", null)
                        true
                    }
                }
            }

        worker(
            coordinator = mockedPushJobCoordinator,
            liveActivityService = scheduleLiveActivityService,
        ).runDueJobs(testNow)

        verify(mockedPushJobCoordinator).persist(any(), any(), anyOrNull(), anyOrNull(), anyOrNull())
        verifyNoInteractions(scheduleLiveActivityService)
        // projection 실패가 외부 처리 catch로 전파되어 추가 재시도를 만들지 않는다.
        assertEquals(0, job.retryCount)
    }

    @Test
    fun `실행 재예약 시각이 바뀌어도 Live Activity는 저장한 ETA 갱신 경계를 유지한다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        stubClaimedEtaJob(job, schedule, travelMinutes = 30)
        whenever(mockedPushJobCoordinator.persist(any(), any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenAnswer {
                // 실행 예약의 변경만으로 이전 ETA의 유효기간을 연장해서는 안 된다.
                ReflectionTestUtils.setField(job, "nextCheckAt", testNow.plus(20, ChronoUnit.MINUTES))
                true
            }

        worker(
            coordinator = mockedPushJobCoordinator,
            liveActivityService = scheduleLiveActivityService,
        ).runDueJobs(testNow)

        val projection = argumentCaptor<ScheduleLiveActivityEtaProjection>()
        verify(scheduleLiveActivityService).projectEta(projection.capture())
        assertEquals(testNow.plus(20, ChronoUnit.MINUTES), job.nextCheckAt)
        assertEquals(testNow.plus(2, ChronoUnit.MINUTES), job.lastEtaRefreshDueAt)
        assertEquals(testNow.plus(7, ChronoUnit.MINUTES), projection.firstValue.staleAt)
    }

    @Test
    fun `intercity fallback keeps ticket departure in reminders and Live Activity despite later arrival deadline`() {
        val fixed = testNow.plusSeconds(3600)
        val journey = objectMapper.createObjectNode().put("version", 1).put("departureAt", fixed.toString()).put("arrivalAt", testNow.plusSeconds(5400).toString())
        journey.set<com.fasterxml.jackson.databind.node.ObjectNode>("service", objectMapper.createObjectNode().put("kind", "INTERCITY_BUS").put("timingBasis", "TICKET"))
        val root = objectMapper.createObjectNode().put("schemaVersion", 3).put("minutes", 30)
        root.set<com.fasterxml.jackson.databind.node.ObjectNode>("intercityJourney", journey)
        root.set<com.fasterxml.jackson.databind.node.ObjectNode>("routeInfo", objectMapper.createObjectNode().put("timeBasis", "anchored_journey").put("departureTime", fixed.toString()).set("intercityJourney", journey.deepCopy()))
        val schedule = schedule(routeJson = root.toString(), travelMode = ScheduleTravelMode.TRANSIT)
        val job = persistedJob(1, 10, schedule.startAt, fixed, testNow.minusSeconds(1), notificationIntervalMinutes)
        ReflectionTestUtils.setField(job, "id", 322L)
        job.startProcessing("mock-owner", testNow)
        whenever(mockedPushJobCoordinator.claimNextDueJob(any(), any())).thenReturn(job, null)
        whenever(mockedPushJobCoordinator.execute<Unit>(any())).thenAnswer { it.getArgument<() -> Unit>(0).invoke() }
        whenever(mockedPushJobCoordinator.persist(any(), any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(true)
        whenever(scheduleRepository.findScheduleDetail(10, 1)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(TrafficResult(travelMinutes = 30, source = TrafficSource.SELECTED_ROUTE, stale = true, failureReason = TrafficFailureReasons.TRANSIT_INTERCITY_TIMETABLE_UNAVAILABLE))
        worker(coordinator = mockedPushJobCoordinator, liveActivityService = scheduleLiveActivityService).runDueJobs(testNow)
        val projection = argumentCaptor<ScheduleLiveActivityEtaProjection>()
        verify(scheduleLiveActivityService).projectEta(projection.capture())
        assertEquals(fixed, job.lastRecommendedDepartureAt)
        assertEquals(fixed, projection.firstValue.recommendedDepartureAt)
        assertTrue(projection.firstValue.etaStale)
        assertNull(projection.firstValue.predictedArrivalAt)
    }

    @Test
    fun `degraded effective candidate never replaces the saved Live Activity route bar`() {
        val schedule = schedule(
            routeJson = """{"steps":[{"mode":"BUS","route":"341"}]}""",
            travelMode = ScheduleTravelMode.TRANSIT,
        )
        val job = persistedJob(
            memberId = 1,
            scheduleId = 10,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(60, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minusSeconds(1),
            intervalMinutes = notificationIntervalMinutes,
        )
        SchedulePushJob::class.java.getDeclaredField("id").apply {
            isAccessible = true
            set(job, 322L)
        }
        job.startProcessing("mock-owner", testNow)
        whenever(mockedPushJobCoordinator.claimNextDueJob(any(), any())).thenReturn(job, null)
        whenever(mockedPushJobCoordinator.execute<Unit>(any())).thenAnswer { invocation ->
            invocation.getArgument<() -> Unit>(0).invoke()
        }
        whenever(mockedPushJobCoordinator.persist(any(), any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(true)
        whenever(scheduleRepository.findScheduleDetail(10, 1)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 60,
                source = TrafficSource.TIMETABLE_PROVIDER,
                fetchedAt = testNow,
                stale = true,
                failureReason = TrafficFailureReasons.PROVIDER_TIMEOUT,
                transitRouteProvenance = TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
                transitTimingBasis = TransitTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN,
                firstBoardingWaitMinutes = 20,
                effectiveTransitRoute = effectiveTransitRoute(
                    identity = "1".repeat(64),
                    departureAt = schedule.startAt.minus(60, ChronoUnit.MINUTES),
                    arrivalAt = schedule.startAt,
                    totalMinutes = 60,
                    lineName = "2호선",
                ),
            )
        )

        worker(
            coordinator = mockedPushJobCoordinator,
            liveActivityService = scheduleLiveActivityService,
        ).runDueJobs(testNow)

        val projection = argumentCaptor<ScheduleLiveActivityEtaProjection>()
        verify(scheduleLiveActivityService).projectEta(projection.capture())
        assertEquals(30, projection.firstValue.travelMinutes)
        assertEquals(schedule.startAt.minus(30, ChronoUnit.MINUTES), projection.firstValue.recommendedDepartureAt)
        assertNull(projection.firstValue.predictedArrivalAt)
        assertNull(projection.firstValue.firstBoardingWaitMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, projection.firstValue.etaSource)
        assertTrue(projection.firstValue.etaStale)
        assertEquals(listOf("bus"), projection.firstValue.routeSegments.map { it.kind })
        assertEquals(listOf("341"), projection.firstValue.routeSegments.map { it.label })
        val snapshot = EffectiveTransitRouteSnapshot.parse(
            objectMapper,
            job.lastEffectiveTransitRouteJson,
        )
        assertTrue(snapshot?.accepted == false)
        assertFalse(snapshot?.routeChanged == true)
    }

    @Test
    fun `degraded refresh keeps one coherent previously accepted Live Activity projection`() {
        val schedule = schedule(
            routeJson = """{"steps":[{"mode":"BUS","route":"341"}]}""",
            travelMode = ScheduleTravelMode.TRANSIT,
        )
        val acceptedRoute = effectiveTransitRoute(
            identity = "2".repeat(64),
            departureAt = schedule.startAt.minus(50, ChronoUnit.MINUTES),
            arrivalAt = schedule.startAt.minus(5, ChronoUnit.MINUTES),
            totalMinutes = 45,
            lineName = "2호선",
        )
        val acceptedResult = TrafficResult(
            travelMinutes = 45,
            source = TrafficSource.LIVE_PROVIDER,
            fetchedAt = testNow.minus(10, ChronoUnit.MINUTES),
            stale = false,
            recommendedDepartureAt = acceptedRoute.departureAt,
            predictedArrivalAt = acceptedRoute.arrivalAt,
            transitRouteProvenance = TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
            transitTimingBasis = TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE,
            firstBoardingWaitMinutes = 7,
            effectiveTransitRoute = acceptedRoute,
        )
        val acceptedSnapshot = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult = acceptedResult,
                previous = null,
                evaluationCheckCount = 0,
            )
        )
        val job = persistedJob(
            memberId = 1,
            scheduleId = 10,
            scheduleAt = schedule.startAt,
            departureAt = acceptedRoute.departureAt,
            monitorStartAt = testNow.minus(20, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        job.startProcessing("previous-worker", testNow.minus(10, ChronoUnit.MINUTES))
        job.finishCheck(
            travelMinutes = acceptedResult.travelMinutes,
            recommendedDepartureAt = acceptedRoute.departureAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = testNow,
            completeAfterCheck = false,
            etaSource = acceptedResult.source,
            liveFetchedAt = acceptedResult.fetchedAt,
            etaStale = false,
            predictedArrivalAt = acceptedRoute.arrivalAt,
            etaTravelMode = ScheduleTravelMode.TRANSIT,
            etaRouteFingerprint = routeFingerprint(schedule),
            effectiveTransitRouteJson = objectMapper.writeValueAsString(acceptedSnapshot),
            now = testNow.minus(10, ChronoUnit.MINUTES),
        )
        SchedulePushJob::class.java.getDeclaredField("id").apply {
            isAccessible = true
            set(job, 323L)
        }
        job.startProcessing("mock-owner", testNow)
        whenever(mockedPushJobCoordinator.claimNextDueJob(any(), any())).thenReturn(job, null)
        whenever(mockedPushJobCoordinator.execute<Unit>(any())).thenAnswer { invocation ->
            invocation.getArgument<() -> Unit>(0).invoke()
        }
        whenever(mockedPushJobCoordinator.persist(any(), any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(true)
        whenever(scheduleRepository.findScheduleDetail(10, 1)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 60,
                source = TrafficSource.TIMETABLE_PROVIDER,
                fetchedAt = testNow,
                stale = true,
                failureReason = TrafficFailureReasons.PROVIDER_TIMEOUT,
                transitRouteProvenance = TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
                transitTimingBasis = TransitTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN,
                firstBoardingWaitMinutes = 20,
                effectiveTransitRoute = effectiveTransitRoute(
                    identity = "3".repeat(64),
                    departureAt = schedule.startAt.minus(60, ChronoUnit.MINUTES),
                    arrivalAt = schedule.startAt,
                    totalMinutes = 60,
                    lineName = "9호선",
                ),
            )
        )

        worker(
            coordinator = mockedPushJobCoordinator,
            liveActivityService = scheduleLiveActivityService,
        ).runDueJobs(testNow)

        val projection = argumentCaptor<ScheduleLiveActivityEtaProjection>()
        verify(scheduleLiveActivityService).projectEta(projection.capture())
        assertEquals(45, projection.firstValue.travelMinutes)
        assertEquals(acceptedRoute.departureAt, projection.firstValue.recommendedDepartureAt)
        assertEquals(acceptedRoute.arrivalAt, projection.firstValue.predictedArrivalAt)
        assertEquals(7, projection.firstValue.firstBoardingWaitMinutes)
        assertEquals(TrafficSource.LIVE_PROVIDER, projection.firstValue.etaSource)
        assertTrue(projection.firstValue.etaStale)
        assertEquals(listOf("2호선"), projection.firstValue.routeSegments.map { it.label })
        assertTrue(projection.firstValue.routeSegments.none { it.label == "9호선" })
        val degradedSnapshot = EffectiveTransitRouteSnapshot.parse(
            objectMapper,
            job.lastEffectiveTransitRouteJson,
        )
        assertFalse(degradedSnapshot?.accepted == true)
        assertEquals("2".repeat(64), degradedSnapshot?.lastAcceptedRouteIdentity)
        assertEquals("2".repeat(64), degradedSnapshot?.acceptedRoute()?.identity)
    }

    @Test
    fun `다중 기기 발송 중 fence가 만료되면 앞선 성공을 보존하고 즉시 fresh check를 연다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(
                NotificationSendResult(
                    requestedCount = 2,
                    attemptedCount = 1,
                    sentCount = 1,
                    fenceRejected = true,
                )
            )

        worker(liveActivityService = scheduleLiveActivityService).runDueJobs(testNow)

        assertEquals(1, job.checkCount)
        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(testNow.plusSeconds(1), job.nextCheckAt)
        assertEquals(testNow, job.lastPushedAt)
        assertEquals(testNow, job.departureNoticeSentAt)
        val projection = argumentCaptor<ScheduleLiveActivityEtaProjection>()
        verify(scheduleLiveActivityService).projectEta(projection.capture())
        assertEquals(testNow.plusSeconds(1), job.lastEtaRefreshDueAt)
        assertEquals(testNow.plusSeconds(301), projection.firstValue.staleAt)
    }

    @Test
    fun `재시도 event A를 보낸 회차는 provider B 대신 A 경로를 API 상태로 유지한다`() {
        val schedule = schedule(
            startAt = shortScheduleStartAt,
            travelMode = ScheduleTravelMode.TRANSIT,
        )
        val job = dueDepartureJob(schedule)
        val eventA = seedFrozenTransitEtaEvent(
            job = job,
            schedule = schedule,
            trafficResult = acceptedTransitTrafficResult(
                schedule = schedule,
                identity = "a".repeat(64),
                travelMinutes = 45,
                provenance = TransitRouteProvenance.SELECTED_ROUTE_PRESERVED,
                firstBoardingWaitMinutes = 4,
                lineName = "2호선",
                fetchedAt = testNow.minusSeconds(30),
            ),
        )
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            acceptedTransitTrafficResult(
                schedule = schedule,
                identity = "b".repeat(64),
                travelMinutes = 55,
                provenance = TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
                firstBoardingWaitMinutes = 7,
                lineName = "9호선",
                fetchedAt = testNow,
            )
        )
        whenever(notificationUseCase.findPersistedEvent(eq(job.memberId), any()))
            .thenReturn(eventA)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, attemptedCount = 1, sentCount = 1))

        worker(liveActivityService = scheduleLiveActivityService).runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(job.memberId),
            title = eq(eventA.title),
            body = eq(eventA.body),
            data = check {
                assertEquals("45", it["travelMinutes"])
                assertEquals("a".repeat(64), it["effectiveRouteIdentity"])
                assertEquals(
                    TransitRouteProvenance.SELECTED_ROUTE_PRESERVED.name,
                    it["etaRouteProvenance"],
                )
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals(1, job.checkCount)
        assertEquals(testNow.plusSeconds(1), job.nextCheckAt)
        assertEquals(45, job.lastTravelMinutes)
        assertEquals(shortScheduleStartAt.minus(45, ChronoUnit.MINUTES), job.lastRecommendedDepartureAt)
        assertEquals(testNow.minusSeconds(30), job.lastCheckedAt)
        val stored = EffectiveTransitRouteSnapshot.parse(objectMapper, job.lastEffectiveTransitRouteJson)
        assertEquals("a".repeat(64), stored?.effectiveTransitRoute?.identity)
        verifyNoInteractions(scheduleLiveActivityService)
    }

    @Test
    fun `재시도 event A 발송이 실패하면 provider B를 저장하거나 Live Activity에 투영하지 않는다`() {
        val schedule = schedule(
            startAt = shortScheduleStartAt,
            travelMode = ScheduleTravelMode.TRANSIT,
        )
        val job = dueDepartureJob(schedule)
        val eventA = seedFrozenTransitEtaEvent(
            job = job,
            schedule = schedule,
            trafficResult = acceptedTransitTrafficResult(
                schedule = schedule,
                identity = "c".repeat(64),
                travelMinutes = 45,
                provenance = TransitRouteProvenance.SELECTED_ROUTE_PRESERVED,
                firstBoardingWaitMinutes = 4,
                lineName = "2호선",
                fetchedAt = testNow.minusSeconds(30),
            ),
        )
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            acceptedTransitTrafficResult(
                schedule = schedule,
                identity = "d".repeat(64),
                travelMinutes = 55,
                provenance = TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
                firstBoardingWaitMinutes = 7,
                lineName = "9호선",
                fetchedAt = testNow,
            )
        )
        whenever(notificationUseCase.findPersistedEvent(eq(job.memberId), any()))
            .thenReturn(eventA)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(
                NotificationSendResult(
                    requestedCount = 1,
                    attemptedCount = 1,
                    failedCount = 1,
                    retryableFailedCount = 1,
                )
            )

        worker(liveActivityService = scheduleLiveActivityService).runDueJobs(testNow)

        assertEquals(0, job.checkCount)
        assertEquals(1, job.retryCount)
        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(testNow.plus(1, ChronoUnit.MINUTES), job.nextCheckAt)
        assertEquals(testNow.plusSeconds(30), job.lastEtaRefreshDueAt)
        assertEquals(45, job.lastTravelMinutes)
        assertEquals(testNow.minusSeconds(30), job.lastCheckedAt)
        val stored = EffectiveTransitRouteSnapshot.parse(objectMapper, job.lastEffectiveTransitRouteJson)
        assertEquals("c".repeat(64), stored?.effectiveTransitRoute?.identity)
        verifyNoInteractions(scheduleLiveActivityService)
    }

    @Test
    fun `outbox event와 job ETA가 어긋나면 fail closed로 발송 없이 A 상태를 보존한다`() {
        val schedule = schedule(
            startAt = shortScheduleStartAt,
            travelMode = ScheduleTravelMode.TRANSIT,
        )
        val job = dueDepartureJob(schedule)
        val coherentEvent = seedFrozenTransitEtaEvent(
            job = job,
            schedule = schedule,
            trafficResult = acceptedTransitTrafficResult(
                schedule = schedule,
                identity = "e".repeat(64),
                travelMinutes = 45,
                provenance = TransitRouteProvenance.SELECTED_ROUTE_PRESERVED,
                firstBoardingWaitMinutes = 4,
                lineName = "2호선",
                fetchedAt = testNow.minusSeconds(30),
            ),
        )
        val malformedEvent = coherentEvent.copy(
            data = coherentEvent.data + ("effectiveRouteIdentity" to "f".repeat(64)),
        )
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            acceptedTransitTrafficResult(
                schedule = schedule,
                identity = "1".repeat(64),
                travelMinutes = 55,
                provenance = TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
                firstBoardingWaitMinutes = 7,
                lineName = "9호선",
                fetchedAt = testNow,
            )
        )
        whenever(notificationUseCase.findPersistedEvent(eq(job.memberId), any()))
            .thenReturn(malformedEvent)

        worker(liveActivityService = scheduleLiveActivityService).runDueJobs(testNow)

        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any(), any(), any())
        assertEquals(1, job.checkCount)
        assertEquals(testNow.plusSeconds(1), job.nextCheckAt)
        assertEquals(45, job.lastTravelMinutes)
        assertEquals(testNow.minusSeconds(30), job.lastCheckedAt)
        val stored = EffectiveTransitRouteSnapshot.parse(objectMapper, job.lastEffectiveTransitRouteJson)
        assertEquals("e".repeat(64), stored?.effectiveTransitRoute?.identity)
        verifyNoInteractions(scheduleLiveActivityService)
    }

    @Test
    fun `immutable ETA event가 TTL을 넘기면 발송하지 않고 다음 회차에서 새 payload를 만든다`() {
        val schedule = schedule()
        val job = dueDepartureJob(schedule)
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(30))
        whenever(notificationUseCase.findPersistedEvent(eq(job.memberId), any())).thenReturn(
            AppNotificationSnapshot(
                id = 77L,
                logicalEventKey = "expired-eta-event",
                title = "오래된 출발 안내",
                body = "이 payload는 발송되면 안 됩니다.",
                data = mapOf(
                    SCHEDULE_ETA_EVENT_EXPIRES_AT to testNow.minusSeconds(1).toString(),
                    "departureReminderDecision" to "ADVANCE_NOTICE",
                    "recommendedDepartureAt" to job.departureAt.toString(),
                ),
                createdAt = testNow.minus(5, ChronoUnit.MINUTES),
            )
        )

        worker().runDueJobs(testNow)

        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any(), any(), any())
        assertEquals(1, job.checkCount)
        assertEquals(testNow.plusSeconds(1), job.nextCheckAt)
        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
    }

    @Test
    fun `provider timeout fallback 증가는 교통 악화 push나 change 이력을 만들지 않는다`() {
        val schedule = schedule(defaultScheduleStartAt)
        val job = persistedJob(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        job.startProcessing("live-worker")
        job.finishCheck(
            travelMinutes = 30,
            recommendedDepartureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = testNow,
            completeAfterCheck = false,
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = testNow.minus(1, ChronoUnit.MINUTES),
            etaStale = false,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = testNow.minus(1, ChronoUnit.MINUTES),
        )
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 45,
                source = TrafficSource.SAVED_FALLBACK,
                stale = true,
                failureReason = "PROVIDER_TIMEOUT: 실시간 ETA 공급자 응답 시간이 초과되었습니다.",
            )
        )

        worker().runDueJobs(testNow)

        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any(), any(), any())
        assertEquals(45, job.lastTravelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, job.lastEtaSource)
        assertTrue(job.lastEtaStale == true)
        assertEquals(30, job.lastLiveTravelMinutes)
        assertEquals(testNow.minus(1, ChronoUnit.MINUTES), job.lastLiveFetchedAt)
        assertNull(job.lastTrafficChangeMinutes)
        assertNull(job.lastChangedAt)
        assertEquals(
            schedule.startAt.minus(30, ChronoUnit.MINUTES),
            job.lastRecommendedDepartureAt,
        )
    }

    @Test
    fun `live 이후 fallback을 거쳐 복구한 live는 마지막 fresh live 대비 correction을 한 번 보낸다`() {
        val schedule = schedule(defaultScheduleStartAt)
        val recoveryAt = testNow.plus(20, ChronoUnit.MINUTES)
        val job = persistedJob(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        job.startProcessing("live-worker")
        job.finishCheck(
            travelMinutes = 30,
            recommendedDepartureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = testNow,
            completeAfterCheck = false,
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = testNow.minus(1, ChronoUnit.MINUTES),
            etaStale = false,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = testNow.minus(1, ChronoUnit.MINUTES),
        )
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                recoveryAt,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 30,
                source = TrafficSource.SAVED_FALLBACK,
                stale = true,
                failureReason = "PROVIDER_TIMEOUT: 실시간 ETA 공급자 응답 시간이 초과되었습니다.",
            ),
            TrafficResult(
                travelMinutes = 45,
                source = TrafficSource.LIVE_PROVIDER,
                fetchedAt = recoveryAt,
                stale = false,
            ),
        )
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))
        val worker = worker()

        worker.runDueJobs(testNow)
        assertEquals(30, job.lastLiveTravelMinutes)
        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any(), any(), any())

        worker.runDueJobs(recoveryAt)

        verify(notificationUseCase, times(1)).sendToMemberFenced(
            memberId = eq(1L),
            title = eq("이동 시간이 늘었어요"),
            body = check { assertTrue(it.contains("15분 더 걸려요")) },
            data = check { assertEquals("15", it["trafficChangeMinutes"]) },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals(45, job.lastLiveTravelMinutes)
        assertEquals(recoveryAt, job.lastLiveFetchedAt)
        assertEquals(15, job.lastTrafficChangeMinutes)
        assertEquals(recoveryAt, job.lastChangedAt)
    }

    @Test
    fun `freshness가 만료된 live baseline은 correction 비교에 사용하지 않는다`() {
        val schedule = schedule(defaultScheduleStartAt)
        val job = persistedJob(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(61, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        job.startProcessing("old-live-worker")
        job.finishCheck(
            travelMinutes = 30,
            recommendedDepartureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = testNow,
            completeAfterCheck = false,
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = testNow.minus(61, ChronoUnit.MINUTES),
            etaStale = false,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = testNow.minus(61, ChronoUnit.MINUTES),
        )
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(45))

        worker(liveComparatorMaxAgeMinutes = 60).runDueJobs(testNow)

        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any(), any(), any())
        assertEquals(45, job.lastLiveTravelMinutes)
        assertEquals(testNow, job.lastLiveFetchedAt)
        assertNull(job.lastTrafficChangeMinutes)
        assertNull(job.lastChangedAt)
    }

    @Test
    fun `추천 출발 15분 전에 출발 준비 푸시를 보내고 ETA 조회를 계속한다`() {
        val travelMinutes = 45
        val schedule = schedule(shortScheduleStartAt)
        val recommendedDepartureAt =
            schedule.startAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES)
        val job = persistedJob(
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
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(travelMinutes))
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFenced(
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
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(recommendedDepartureAt, job.lastNotifiedDepartureAt)
        assertEquals(testNow, job.lastReminderBoundaryAt)
        // 출발까지 30분 넘게 남았으므로 5분 뒤 다시 조회한다.
        assertEquals(testNow.plus(1, ChronoUnit.MINUTES), job.nextCheckAt)    }

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
        val job = persistedJob(
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
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = firstReminderBoundaryAt,
            etaStale = false,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = firstReminderBoundaryAt,
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                secondReminderAt,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(travelMinutes))
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(secondReminderAt)

        verify(notificationUseCase).sendToMemberFenced(
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
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals(secondReminderAt, job.lastReminderBoundaryAt)

        // 두 번째 알림 시점에는 출발까지 10분 남았으므로 1분 뒤 다시 조회한다.
        assertEquals(secondReminderAt.plus(1, ChronoUnit.MINUTES), job.nextCheckAt)
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
        val job = persistedJob(
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
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = testNow.minus(1, ChronoUnit.MINUTES),
            etaStale = false,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = testNow.minus(1, ChronoUnit.MINUTES),
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(currentTravelMinutes))
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFenced(
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
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals(currentRecommendedDepartureAt, job.lastNotifiedDepartureAt)
        assertEquals(testNow, job.lastReminderBoundaryAt)
    }

    @Test
    fun `실시간 추천 출발 시각에 도달하면 지금 출발 푸시를 보내고 후속 알림을 예약한다`() {
        val travelMinutes = 60
        val schedule = schedule(shortScheduleStartAt)
        val job = persistedJob(
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
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(travelMinutes))
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(1L),
            title = eq("지금 출발하세요"),
            body = check { assertTrue(it.contains("지금 출발")) },
            data = check {
                assertEquals("SCHEDULE_DEPARTURE_REMINDER", it["type"])
                assertEquals("true", it["departNow"])
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(testNow, job.lastPushedAt)
        assertEquals(testNow, job.departureNoticeSentAt)
        assertEquals(testNow, job.handledDepartureNoticeAt)
        assertEquals(testNow, job.lastNotifiedDepartureAt)
        assertEquals(testNow, job.lastHandledDepartureAt)
        assertEquals(TrafficSource.LIVE_PROVIDER, job.lastEtaSource)
        assertEquals(false, job.lastEtaStale)
        assertEquals(testNow, job.lastLiveFetchedAt)
        assertEquals(travelMinutes, job.lastLiveTravelMinutes)
        assertEquals(testNow.plus(3, ChronoUnit.MINUTES), job.nextCheckAt)
    }

    @Test
    fun `지금 출발 알림 후 3분이 지나면 출발 확인 후속 푸시를 보낸다`() {
        val schedule = schedule(startAt = testNow.plus(30, ChronoUnit.MINUTES))
        val previousRecommendedDepartureAt = testNow.minus(3, ChronoUnit.MINUTES)
        val job = dueDepartureJob(schedule)
        markDepartNowSent(
            job = job,
            travelMinutes = 33,
            recommendedDepartureAt = previousRecommendedDepartureAt,
            sentAt = previousRecommendedDepartureAt,
        )

        stubDueJob(job, schedule, travelMinutes = 33)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(1L),
            title = eq("출발 확인이 필요해요"),
            body = check { assertTrue(it.contains("지금 출발해야 해요")) },
            data = check {
                assertEquals("SCHEDULE_DEPARTURE_REMINDER", it["type"])
                assertEquals("true", it["departNow"])
                assertEquals("AFTER_DEPARTURE_3", it["departureReminderDecision"])
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals("AFTER_DEPARTURE_3", job.lastDepartureReminderStage)
        assertEquals(testNow, job.lastDepartureReminderBoundaryAt)
        assertEquals(testNow.plus(4, ChronoUnit.MINUTES), job.nextCheckAt)
    }

    @Test
    fun `여러 후속 경계가 밀렸다면 가장 긴급한 일정 1분 전 알림만 보낸다`() {
        val schedule = schedule(startAt = testNow.plus(1, ChronoUnit.MINUTES))
        val previousRecommendedDepartureAt = testNow.minus(10, ChronoUnit.MINUTES)
        val job = dueDepartureJob(schedule)
        markDepartNowSent(
            job = job,
            travelMinutes = 11,
            recommendedDepartureAt = previousRecommendedDepartureAt,
            sentAt = previousRecommendedDepartureAt,
        )

        stubDueJob(job, schedule, travelMinutes = 11)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(1L),
            title = eq("곧 일정 시작이에요"),
            body = check { assertTrue(it.contains("1분 남았어요")) },
            data = check {
                assertEquals("BEFORE_SCHEDULE_1", it["departureReminderDecision"])
                assertEquals("true", it["departNow"])
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals("BEFORE_SCHEDULE_1", job.lastDepartureReminderStage)
        assertEquals(testNow, job.lastDepartureReminderBoundaryAt)
        assertEquals(schedule.startAt, job.nextCheckAt)
    }

    @Test
    fun `사용자가 다시 알림을 요청한 시각에는 스누즈 푸시를 보내고 예약을 비운다`() {
        val schedule = schedule(startAt = testNow.plus(30, ChronoUnit.MINUTES))
        val previousRecommendedDepartureAt = testNow.minus(5, ChronoUnit.MINUTES)
        val job = dueDepartureJob(schedule)
        markDepartNowSent(
            job = job,
            travelMinutes = 35,
            recommendedDepartureAt = previousRecommendedDepartureAt,
            sentAt = previousRecommendedDepartureAt,
        )
        job.snoozeUntil(testNow)

        stubDueJob(job, schedule, travelMinutes = 35)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(1L),
            title = eq("다시 알려드려요"),
            body = check { assertTrue(it.contains("아직 출발 전이면")) },
            data = check {
                assertEquals("SNOOZE", it["departureReminderDecision"])
                assertEquals("5", it["snoozeMinutes"])
                assertEquals("true", it["departNow"])
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertNull(job.snoozedUntil)
        assertEquals("DEPART_NOW", job.lastDepartureReminderStage)
    }

    @Test
    fun `지금 출발 푸시가 한 기기에도 전송되지 않으면 완료하지 않고 재시도한다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)

        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 0, failedCount = 1))

        worker().runDueJobs(testNow)

        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(testNow.plus(1, ChronoUnit.MINUTES), job.nextCheckAt)
        assertEquals(testNow.plus(1, ChronoUnit.MINUTES), job.lastEtaRefreshDueAt)
        assertEquals(1, job.retryCount)
        assertNull(job.lastPushedAt)
        assertTrue(job.failureReason.orEmpty().contains("푸시 공급자"))
    }

    @Test
    fun `과거 SUCCESS 재조회는 lastPushedAt을 현재 시각으로 이동시키지 않는다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        val deliveredAt = testNow.minus(2, ChronoUnit.MINUTES)

        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(
                NotificationSendResult(
                    requestedCount = 1,
                    alreadyDeliveredCount = 1,
                    alreadyDeliveredAt = deliveredAt,
                )
            )

        worker().runDueJobs(testNow)

        assertEquals(deliveredAt, job.lastPushedAt)
        assertEquals(deliveredAt, job.departureNoticeSentAt)
    }

    @Test
    fun `모호한 전달은 이벤트 단계만 전진시키고 confirmed success 시각은 기록하지 않는다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        val registry = SimpleMeterRegistry()

        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(
                NotificationSendResult(
                    requestedCount = 3,
                    ambiguousCount = 3,
                )
            )

        worker(
            operationalMetrics = NoLateOperationalMetrics(registry),
        ).runDueJobs(testNow)

        assertEquals(1, job.checkCount)
        assertNull(job.departureNoticeSentAt)
        assertNull(job.lastDepartureReminderStage)
        assertEquals(testNow, job.handledDepartureNoticeAt)
        assertEquals("DEPART_NOW", job.lastHandledDepartureReminderStage)
        assertEquals(testNow, job.lastUncertainAt)
        assertNull(job.lastPushedAt)
        assertEquals(0, job.retryCount)
        assertEquals(testNow.plus(3, ChronoUnit.MINUTES), job.nextCheckAt)
        assertEquals(
            1.0,
            registry.get("nolate.eta.jobs")
                .tag("outcome", "uncertain_delivery")
                .counter()
                .count(),
        )
    }

    @Test
    fun `모호한 전달 전이 저장이 실패하면 ETA 결과 카운터를 기록하지 않는다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        val registry = SimpleMeterRegistry()

        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(
                NotificationSendResult(
                    requestedCount = 3,
                    ambiguousCount = 3,
                )
            )
        whenever(pushJobRepository.saveAndFlush(job))
            .thenThrow(IllegalStateException("database unavailable"))

        worker(
            operationalMetrics = NoLateOperationalMetrics(registry),
        ).runDueJobs(testNow)

        assertEquals(
            0.0,
            registry.get("nolate.eta.jobs")
                .tag("outcome", "processed")
                .counter()
                .count(),
        )
        assertEquals(
            0.0,
            registry.get("nolate.eta.jobs")
                .tag("outcome", "uncertain_delivery")
                .counter()
                .count(),
        )
    }

    @Test
    fun `fallback ETA는 직전 fresh 출발시각과 live baseline을 보존하고 새 알림을 만들지 않는다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        val baselineFetchedAt = testNow.minus(1, ChronoUnit.MINUTES)
        job.startProcessing("live-baseline-worker", baselineFetchedAt)
        job.finishCheck(
            travelMinutes = 30,
            recommendedDepartureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = testNow,
            completeAfterCheck = false,
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = baselineFetchedAt,
            etaStale = false,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = baselineFetchedAt,
        )

        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(job.scheduleId, job.memberId)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 60,
                source = TrafficSource.SAVED_FALLBACK,
                stale = true,
                failureReason = "PROVIDER_TIMEOUT: fallback used",
            )
        )
        worker().runDueJobs(testNow)

        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any(), any(), any())
        assertEquals(TrafficSource.SAVED_FALLBACK, job.lastEtaSource)
        assertEquals(true, job.lastEtaStale)
        assertEquals(schedule.startAt.minus(30, ChronoUnit.MINUTES), job.lastRecommendedDepartureAt)
        assertEquals(30, job.lastLiveTravelMinutes)
        assertEquals(baselineFetchedAt, job.lastLiveFetchedAt)
        assertNull(job.lastTrafficChangeMinutes)
        assertNull(job.lastChangedAt)
        assertNull(job.lastPushedAt)
        assertNull(job.lastNotifiedDepartureAt)
        assertNull(job.departureNoticeSentAt)
        assertNull(job.lastHandledDepartureAt)
        assertNull(job.handledDepartureNoticeAt)
        assertNull(job.lastUncertainAt)
    }

    @Test
    fun `일정 reset 뒤 generation이 바뀌어 check 0 알림도 새 이벤트로 전송한다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList(), listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(job.scheduleId, job.memberId)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(60))
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker().runDueJobs(testNow)
        assertEquals(1, job.checkCount)
        assertEquals(0, job.notificationGeneration)

        job.changeSchedule(
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = schedule.startAt.minus(90, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
            notificationInputFingerprint = "meaningful-edit-fingerprint",
        )
        assertEquals(0, job.checkCount)
        assertEquals(1, job.notificationGeneration)

        worker().runDueJobs(testNow)

        val eventKeys = argumentCaptor<String>()
        verify(notificationUseCase, times(2)).sendToMemberFenced(
            memberId = eq(job.memberId),
            title = any(),
            body = any(),
            data = any(),
            inboxDeduplicationKey = eventKeys.capture(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals(2, eventKeys.allValues.distinct().size)
        assertTrue(eventKeys.firstValue.contains(":g0:c0"))
        assertTrue(eventKeys.secondValue.contains(":g1:c0"))
        assertEquals(1, job.checkCount)
    }

    @Test
    fun `푸시 토큰이 없으면 원인을 기록하고 재시도한다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)

        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult())

        worker().runDueJobs(testNow)

        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals("등록된 푸시 토큰이 없습니다.", job.failureReason)
        assertEquals(1, job.retryCount)
    }

    @Test
    fun `retry counter is not emitted when the durable transition fails`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        val registry = SimpleMeterRegistry()

        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult())
        whenever(pushJobRepository.saveAndFlush(job))
            .thenThrow(IllegalStateException("database unavailable"))

        worker(
            operationalMetrics = NoLateOperationalMetrics(registry),
        ).runDueJobs(testNow)

        assertEquals(1, job.retryCount)
        assertEquals(
            0.0,
            registry.get("nolate.eta.jobs")
                .tag("outcome", "retry_scheduled")
                .counter()
                .count(),
        )
        assertEquals(
            0.0,
            registry.get("nolate.eta.jobs")
                .tag("outcome", "terminal_failure")
                .counter()
                .count(),
        )
    }

    @Test
    fun `processing exception event and committed retry use separate meters`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        val registry = SimpleMeterRegistry()

        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any()))
            .thenThrow(IllegalStateException("provider unavailable"))

        worker(
            operationalMetrics = NoLateOperationalMetrics(registry),
        ).runDueJobs(testNow)

        assertEtaProcessingExceptionCount(registry, 1.0)
        assertEtaTransitionCount(registry, "retry_scheduled", 1.0)
        assertEtaTransitionCount(registry, "terminal_failure", 0.0)
        assertNull(
            registry.find("nolate.eta.jobs")
                .tag("outcome", "processing_exception")
                .counter()
        )
    }

    @Test
    fun `processing exception commits only the terminal transition after retry limit`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule).apply {
            retryLater("first failure", testNow)
            retryLater("second failure", testNow)
        }
        val registry = SimpleMeterRegistry()

        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any()))
            .thenThrow(IllegalStateException("provider unavailable"))

        worker(
            operationalMetrics = NoLateOperationalMetrics(registry),
        ).runDueJobs(testNow)

        assertEtaProcessingExceptionCount(registry, 1.0)
        assertEtaTransitionCount(registry, "retry_scheduled", 0.0)
        assertEtaTransitionCount(registry, "terminal_failure", 1.0)
    }

    @Test
    fun `processing exception persist throw records no durable transition`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        val registry = SimpleMeterRegistry()

        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any()))
            .thenThrow(IllegalStateException("provider unavailable"))
        whenever(pushJobRepository.saveAndFlush(job))
            .thenThrow(IllegalStateException("database unavailable"))

        worker(
            operationalMetrics = NoLateOperationalMetrics(registry),
        ).runDueJobs(testNow)

        assertEtaProcessingExceptionCount(registry, 1.0)
        assertEtaTransitionCount(registry, "retry_scheduled", 0.0)
        assertEtaTransitionCount(registry, "terminal_failure", 0.0)
    }

    @Test
    fun `processing exception persist false records no durable transition`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        val registry = SimpleMeterRegistry()
        SchedulePushJob::class.java.getDeclaredField("id").apply {
            isAccessible = true
            set(job, 91L)
        }

        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any()))
            .thenThrow(IllegalStateException("provider unavailable"))
        whenever(pushJobRepository.findByIdForUpdate(91L)).thenReturn(null)

        worker(
            operationalMetrics = NoLateOperationalMetrics(registry),
        ).runDueJobs(testNow)

        assertEtaProcessingExceptionCount(registry, 1.0)
        assertEtaTransitionCount(registry, "retry_scheduled", 0.0)
        assertEtaTransitionCount(registry, "terminal_failure", 0.0)
        verify(pushJobRepository, never()).saveAndFlush(job)
    }

    @Test
    fun `최대 재시도 횟수에 도달하면 job을 실패 상태로 종료한다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        job.retryLater("첫 번째 실패", testNow)
        job.retryLater("두 번째 실패", testNow)

        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, failedCount = 1))

        worker(
            coordinator = SchedulePushJobCoordinator(
                pushJobRepository,
                liveActivityLifecyclePublisher = liveActivityLifecyclePublisher,
                scheduleEtaEvaluationHistoryRepository = scheduleEtaEvaluationHistoryRepository,
            ),
        ).runDueJobs(testNow)

        assertEquals(SchedulePushJobStatus.FAILED, job.status)
        assertEquals(3, job.retryCount)
        assertNull(job.lockedBy)
        verify(liveActivityLifecyclePublisher).endMemberSchedule(
            job.memberId,
            job.scheduleId,
            "JOB_TERMINAL_FAILURE",
        )
    }

    @Test
    fun `재시도 가능한 기기 실패도 한계 도달 시 Live Activity END를 함께 저장한다`() {
        val schedule = schedule(shortScheduleStartAt)
        val job = dueDepartureJob(schedule)
        job.retryLater("첫 번째 실패", testNow)
        job.retryLater("두 번째 실패", testNow)

        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(
                NotificationSendResult(
                    requestedCount = 1,
                    failedCount = 1,
                    retryableFailedCount = 1,
                ),
            )

        worker(
            liveActivityService = scheduleLiveActivityService,
            coordinator = SchedulePushJobCoordinator(
                pushJobRepository,
                liveActivityLifecyclePublisher = liveActivityLifecyclePublisher,
                scheduleEtaEvaluationHistoryRepository = scheduleEtaEvaluationHistoryRepository,
            ),
        ).runDueJobs(testNow)

        assertEquals(SchedulePushJobStatus.FAILED, job.status)
        verify(liveActivityLifecyclePublisher).endMemberSchedule(
            job.memberId,
            job.scheduleId,
            "JOB_TERMINAL_FAILURE",
        )
        // 종료 전이는 ETA 갱신 경계가 없어도 END만 보내고 projection을 만들지 않는다.
        assertNull(job.lastEtaRefreshDueAt)
        verifyNoInteractions(scheduleLiveActivityService)
    }

    @Test
    fun `일정 시작 이후에는 지연된 출발 푸시 없이 job을 완료한다`() {
        val scheduleStartAt = testNow.minus(2, ChronoUnit.MINUTES)
        val schedule = schedule(scheduleStartAt)
        val job = persistedJob(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = schedule.startAt.minus(90, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )

        stubDueJob(job, schedule, travelMinutes = 60)
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        worker(liveActivityService = scheduleLiveActivityService).runDueJobs(testNow)

        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(1L),
            title = eq("지금 출발하세요"),
            body = any(),
            data = check {
                assertEquals("SCHEDULE_DEPARTURE_REMINDER", it["type"])
                assertEquals("true", it["departNow"])
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals(SchedulePushJobStatus.COMPLETED, job.status)
        assertNull(job.lastEtaRefreshDueAt)
        verifyNoInteractions(scheduleLiveActivityService)
    }

    @Test
    fun `일정 시작 후 유예 시간이 지나면 푸시 없이 job을 완료한다`() {
        val scheduleStartAt = testNow.minus(11, ChronoUnit.MINUTES)
        val schedule = schedule(scheduleStartAt)
        val job = persistedJob(
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
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())

        worker().runDueJobs(testNow)

        verify(notificationUseCase, never()).sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any())
        verify(notificationUseCase, never()).sendToMember(any(), any(), any(), any(), any(), any())
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
        val firstJob = persistedJob(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = firstSchedule.startAt,
            departureAt = firstSchedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )
        val secondJob = persistedJob(
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
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(firstJob), listOf(secondJob), emptyList())
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(firstSchedule)
        whenever(scheduleRepository.findScheduleDetail(20L, 2L)).thenReturn(secondSchedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            liveTrafficResult(45),
            liveTrafficResult(50),
        )
        whenever(notificationUseCase.sendToMemberFenced(any(), any(), any(), any(), any(), any(), dispatchFence = any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        assertEquals(2, worker().runDueJobs(testNow))

        verify(scheduleRepository).findScheduleDetail(10L, 1L)
        verify(scheduleRepository).findScheduleDetail(20L, 2L)
        verify(scheduleRepository, never()).findScheduleDetail(10L, 2L)
        verify(scheduleRepository, never()).findScheduleDetail(20L, 1L)
        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(1L),
            title = eq("출발 준비하세요"),
            body = check { assertTrue(it.contains("회원 1 일정")) },
            data = check {
                assertEquals("SCHEDULE_DEPARTURE_REMINDER", it["type"])
                assertEquals("10", it["scheduleId"])
                assertEquals("45", it["travelMinutes"])
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        verify(notificationUseCase).sendToMemberFenced(
            memberId = eq(2L),
            title = eq("출발 준비하세요"),
            body = check { assertTrue(it.contains("회원 2 일정")) },
            data = check {
                assertEquals("SCHEDULE_DEPARTURE_REMINDER", it["type"])
                assertEquals("20", it["scheduleId"])
                assertEquals("50", it["travelMinutes"])
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
            dispatchFence = any(),
        )
        assertEquals(45, firstJob.lastTravelMinutes)
        assertEquals(50, secondJob.lastTravelMinutes)
    }

    @Test
    fun `느린 첫 처리 뒤 tail job은 실제 claim 시각으로 lease된다`() {
        val advancingClock = AdvancingTestClock(testNow)
        val later = testNow.plus(11, ChronoUnit.MINUTES)
        val firstSchedule = schedule(
            startAt = testNow.plus(180, ChronoUnit.MINUTES),
            scheduleId = 31L,
            memberId = 1L,
        )
        val secondSchedule = schedule(
            startAt = testNow.plus(180, ChronoUnit.MINUTES),
            scheduleId = 32L,
            memberId = 2L,
        )
        val firstJob = persistedJob(
            memberId = 1L,
            scheduleId = 31L,
            scheduleAt = firstSchedule.startAt,
            departureAt = firstSchedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minusSeconds(1),
            intervalMinutes = notificationIntervalMinutes,
        )
        val secondJob = persistedJob(
            memberId = 2L,
            scheduleId = 32L,
            scheduleAt = secondSchedule.startAt,
            departureAt = secondSchedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = testNow.minusSeconds(1),
            intervalMinutes = notificationIntervalMinutes,
        )
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(firstJob))
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                later,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(secondJob), emptyList())
        whenever(scheduleRepository.findScheduleDetail(31L, 1L)).thenReturn(firstSchedule)
        whenever(scheduleRepository.findScheduleDetail(32L, 2L)).thenReturn(secondSchedule)
        whenever(trafficClient.getTravelMinutes(any())).thenAnswer {
            if (advancingClock.instant() == testNow) {
                advancingClock.advanceTo(later)
            }
            TrafficResult(
                travelMinutes = 30,
                source = TrafficSource.LIVE_PROVIDER,
                fetchedAt = advancingClock.instant(),
                stale = false,
            )
        }

        assertEquals(2, worker(clock = advancingClock).runDueJobs(testNow))

        verify(
            pushJobRepository,
            times(2),
        ).findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
            SchedulePushJobStatus.ACTIVE,
            later,
            org.springframework.data.domain.PageRequest.of(0, 1),
        )
        assertEquals(later, firstJob.lastCheckedAt)
        assertEquals(later, secondJob.lastCheckedAt)
    }

    @Test
    // Simulates a server crash or shutdown after a worker marked a job PROCESSING.
    // The next worker run should unlock stale jobs and put them back into ACTIVE so they can be retried.
    fun `PROCESSING job이 timeout을 넘으면 ACTIVE로 복구하고 즉시 재검사 대상으로 만든다`() {
        val schedule = schedule()
        val job = persistedJob(
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
                org.springframework.data.domain.PageRequest.of(0, 50),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
                org.springframework.data.domain.PageRequest.of(0, 1),
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
    fun `selected JSON ETA가 canonical과 다르면 fallback에서 canonical을 우선한다`() {
        val schedule = schedule(
            routeJson = """{"id":"selected-route","minutes":42,"source":"api","providerRouteOption":"2"}"""
        )
        val job = persistedJob(
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
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(42))

        worker().runDueJobs(testNow)

        verify(trafficClient).getTravelMinutes(check<TrafficRequest> {
            assertEquals(30, it.fallbackTravelMinutes)
            assertNull(it.selectedRouteTravelMinutes)
            assertEquals("2", it.selectedRouteOption)
            assertTrue(it.selectedRouteJson.orEmpty().contains("selected-route"))
            assertEquals(testNow, it.evaluatedAt)
            assertEquals(job.departureAt, it.plannedDepartureAt)
        })
    }

    private fun worker(
        enabled: Boolean = true,
        accessPolicy: ScheduleAccessPolicy? = null,
        liveComparatorMaxAgeMinutes: Long = 60,
        clock: Clock = Clock.fixed(testNow, ZoneOffset.UTC),
        operationalMetrics: NoLateOperationalMetrics? = null,
        alarmSyncService: DepartureAlarmSyncService? = null,
        coordinator: SchedulePushJobCoordinator? = null,
        liveActivityService: ScheduleLiveActivityService? = null,
        departureStatusSnapshotGraceMinutes: Long = 5L,
    ) = SchedulePushJobWorker(
        scheduleRepository = scheduleRepository,
        objectMapper = objectMapper,
        trafficClient = trafficClient,
        notificationUseCase = notificationUseCase,
        periodicPushPolicy = PeriodicPushPolicy(),
        departureReminderPolicy = DepartureReminderPolicy(),
        trafficChangePolicy = TrafficChangePolicy(),
        pushJobCoordinator = coordinator ?: SchedulePushJobCoordinator(
            repository = pushJobRepository,
            departureAlarmSyncService = alarmSyncService,
            scheduleEtaEvaluationHistoryRepository = scheduleEtaEvaluationHistoryRepository,
        ),
        enabled = enabled,
        batchSize = 50,
        retryDelayMinutes = 1,
        maxRetryCount = 3,
        departureAlertLeadMinutes = departureAlertLeadMinutes,
        departureReminderIntervalMinutes = departureReminderIntervalMinutes,
        processingTimeoutMinutes = 10,
        liveComparatorMaxAgeMinutes = liveComparatorMaxAgeMinutes,
        travelPlanRepository = travelPlanRepository,
        scheduleAccessPolicy = accessPolicy,
        clock = clock,
        operationalMetrics = operationalMetrics,
        departureAlarmSyncService = alarmSyncService,
        scheduleLiveActivityService = liveActivityService,
        departureStatusSnapshotGraceMinutes = departureStatusSnapshotGraceMinutes,
    )

    private fun assertEtaProcessingExceptionCount(
        registry: SimpleMeterRegistry,
        expected: Double,
    ) {
        assertEquals(
            expected,
            registry.get("nolate.eta.worker.events")
                .tag("event", "processing_exception")
                .counter()
                .count(),
        )
    }

    private fun assertEtaTransitionCount(
        registry: SimpleMeterRegistry,
        outcome: String,
        expected: Double,
    ) {
        assertEquals(
            expected,
            registry.get("nolate.eta.jobs")
                .tag("outcome", outcome)
                .counter()
                .count(),
        )
    }

    /**
     * 저장된 작업처럼 ID와 버전을 가진 fixture를 만든다. 실제 DB에는 접근하지 않는다.
     */
    private fun persistedJob(
        memberId: Long,
        scheduleId: Long,
        scheduleAt: Instant,
        departureAt: Instant,
        monitorStartAt: Instant,
        intervalMinutes: Int,
    ): SchedulePushJob = SchedulePushJob.create(
        memberId = memberId,
        scheduleId = scheduleId,
        scheduleAt = scheduleAt,
        departureAt = departureAt,
        monitorStartAt = monitorStartAt,
        intervalMinutes = intervalMinutes,
    ).also { job ->
        // 실제 DB 저장에 해당하는 ID와 낙관적 잠금 버전을 테스트 안에서만 부여한다.
        ReflectionTestUtils.setField(job, "id", nextJobId++)
        ReflectionTestUtils.setField(job, "version", 0L)
        fixtureJobs += job
    }

    private fun snapshot(job: SchedulePushJob): SchedulePushJob = SchedulePushJob.create(
        memberId = job.memberId,
        scheduleId = job.scheduleId,
        scheduleAt = job.scheduleAt,
        departureAt = job.departureAt,
        monitorStartAt = job.monitorStartAt,
        intervalMinutes = job.intervalMinutes,
    ).also { copy ->
        // 같은 객체를 반환하면 finishCheck가 DB 조회용 PROCESSING 상태까지 바꿔버린다.
        SchedulePushJob::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                field.isAccessible = true
                field.set(copy, field.get(job))
            }
    }

    private fun dueDepartureJob(schedule: Schedule) = persistedJob(
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
        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(travelMinutes))
    }

    // 저장 반환값을 조작하는 경계 테스트에서만 Coordinator를 대체한다.
    private fun stubClaimedEtaJob(job: SchedulePushJob, schedule: Schedule, travelMinutes: Int) {
        job.startProcessing("mock-owner", testNow)
        whenever(mockedPushJobCoordinator.claimNextDueJob(any(), any())).thenReturn(job, null)
        whenever(mockedPushJobCoordinator.execute<Unit>(any())).thenAnswer { invocation ->
            invocation.getArgument<() -> Unit>(0).invoke()
        }
        whenever(scheduleRepository.findScheduleDetail(job.scheduleId, job.memberId)).thenReturn(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(liveTrafficResult(travelMinutes))
    }

    private fun stubDueJobLookup(job: SchedulePushJob, schedule: Schedule) {
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                testNow,
                org.springframework.data.domain.PageRequest.of(0, 1),
            )
        ).thenReturn(listOf(job), emptyList())
        whenever(scheduleRepository.findScheduleDetail(job.scheduleId, job.memberId)).thenReturn(schedule)
    }

    private fun markDepartNowSent(
        job: SchedulePushJob,
        travelMinutes: Int,
        recommendedDepartureAt: Instant,
        sentAt: Instant,
    ) {
        job.startProcessing("previous-worker")
        job.finishCheck(
            travelMinutes = travelMinutes,
            recommendedDepartureAt = recommendedDepartureAt,
            pushSent = true,
            notifiedDepartureAt = recommendedDepartureAt,
            departureReminderStage = ScheduleDepartureReminderStage.DEPART_NOW,
            departureReminderBoundaryAt = recommendedDepartureAt,
            nextCheckAt = testNow,
            completeAfterCheck = false,
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = sentAt,
            etaStale = false,
            now = sentAt,
        )
    }

    private fun liveTrafficResult(travelMinutes: Int) = TrafficResult(
        travelMinutes = travelMinutes,
        source = TrafficSource.LIVE_PROVIDER,
        fetchedAt = testNow,
        stale = false,
    )

    private fun effectiveTransitRoute(
        identity: String,
        departureAt: Instant,
        arrivalAt: Instant,
        totalMinutes: Int,
        lineName: String,
    ) = EffectiveTransitRoute(
        provider = "ODSAY",
        identity = identity,
        departureAt = departureAt,
        arrivalAt = arrivalAt,
        totalMinutes = totalMinutes,
        segments = listOf(
            EffectiveTransitRouteSegment(
                sequence = 0,
                kind = EffectiveTransitRouteSegmentKind.SUBWAY,
                durationMinutes = totalMinutes,
                lineName = lineName,
                fromName = "강남역",
                toName = "시청역",
            )
        ),
    )

    private fun acceptedTransitTrafficResult(
        schedule: Schedule,
        identity: String,
        travelMinutes: Int,
        provenance: TransitRouteProvenance,
        firstBoardingWaitMinutes: Int,
        lineName: String,
        fetchedAt: Instant,
    ): TrafficResult {
        val departureAt = schedule.startAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES)
        return TrafficResult(
            travelMinutes = travelMinutes,
            source = TrafficSource.LIVE_PROVIDER,
            fetchedAt = fetchedAt,
            stale = false,
            recommendedDepartureAt = departureAt,
            predictedArrivalAt = schedule.startAt,
            transitRouteProvenance = provenance,
            transitTimingBasis = TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE,
            firstBoardingWaitMinutes = firstBoardingWaitMinutes,
            effectiveTransitRoute = effectiveTransitRoute(
                identity = identity,
                departureAt = departureAt,
                arrivalAt = schedule.startAt,
                totalMinutes = travelMinutes,
                lineName = lineName,
            ),
        )
    }

    private fun seedFrozenTransitEtaEvent(
        job: SchedulePushJob,
        schedule: Schedule,
        trafficResult: TrafficResult,
    ): AppNotificationSnapshot {
        val routeSnapshot = requireNotNull(
            EffectiveTransitRouteSnapshot.from(
                trafficResult = trafficResult,
                previous = null,
                evaluationCheckCount = job.checkCount,
                notificationGeneration = job.notificationGeneration,
            )
        )
        val recommendedDepartureAt = requireNotNull(trafficResult.recommendedDepartureAt)
        val predictedArrivalAt = requireNotNull(trafficResult.predictedArrivalAt)
        job.recordEtaEvaluationBeforeRetry(
            travelMinutes = trafficResult.travelMinutes,
            recommendedDepartureAt = recommendedDepartureAt,
            etaSource = trafficResult.source,
            liveFetchedAt = trafficResult.fetchedAt,
            etaStale = trafficResult.stale,
            etaFailureReason = trafficResult.failureReason,
            predictedArrivalAt = predictedArrivalAt,
            etaTravelMode = ScheduleTravelMode.TRANSIT,
            etaRouteFingerprint = routeFingerprint(schedule),
            etaRefreshDueAt = requireNotNull(trafficResult.fetchedAt)
                .plus(1, ChronoUnit.MINUTES),
            effectiveTransitRouteJson = objectMapper.writeValueAsString(routeSnapshot),
            now = requireNotNull(trafficResult.fetchedAt),
        )
        val deduplicationKey =
            "schedule-push-job:${requireNotNull(job.id)}:" +
                "g${job.notificationGeneration}:c${job.checkCount}"
        return AppNotificationSnapshot(
            id = 77L,
            logicalEventKey = deduplicationKey,
            title = "경로 A 출발 안내",
            body = "동결된 경로 A를 사용합니다.",
            data = mapOf(
                "type" to "SCHEDULE_DEPARTURE_REMINDER",
                "scheduleId" to job.scheduleId.toString(),
                "travelMinutes" to trafficResult.travelMinutes.toString(),
                "recommendedDepartureAt" to recommendedDepartureAt.toString(),
                "predictedArrivalAt" to predictedArrivalAt.toString(),
                "onTimeArrivalPossible" to "true",
                "etaSource" to trafficResult.source.name,
                "etaStale" to trafficResult.stale.toString(),
                "etaFailureReason" to "",
                "transitTransferFeasibility" to "",
                "etaRouteProvenance" to requireNotNull(trafficResult.transitRouteProvenance).name,
                "etaTimingBasis" to requireNotNull(trafficResult.transitTimingBasis).name,
                "firstBoardingWaitMinutes" to
                    requireNotNull(trafficResult.firstBoardingWaitMinutes).toString(),
                "routeChanged" to routeSnapshot.routeChanged.toString(),
                "effectiveRouteIdentity" to
                    requireNotNull(routeSnapshot.effectiveTransitRoute).identity,
                "departNow" to "false",
                "departureReminderDecision" to "ADVANCE_NOTICE",
                "reminderBoundaryAt" to testNow.toString(),
                "snoozeMinutes" to "5",
                "trafficChangeMinutes" to "0",
                "departureAdvanceMinutes" to "0",
                "schedulePushJobId" to (job.id?.toString() ?: ""),
                "schedulePushCheckCount" to job.checkCount.toString(),
                "notificationGeneration" to job.notificationGeneration.toString(),
                "notificationInputFingerprint" to job.notificationInputFingerprint,
                SCHEDULE_ETA_EVENT_EXPIRES_AT to testNow.plusSeconds(90).toString(),
            ),
            createdAt = testNow.minusSeconds(30),
            deduplicationKey = deduplicationKey,
            scheduleId = job.scheduleId,
        )
    }

    private fun routeFingerprint(schedule: Schedule): String {
        val route = requireNotNull(schedule.route)
        return ScheduleEtaRouteFingerprint.calculate(
            schedule = schedule,
            travelMinutes = route.travelMinutes,
            travelMode = route.travelMode,
            originLat = route.originLat,
            originLng = route.originLng,
            routeJson = route.routeJson,
        )
    }

    private fun schedule(
        startAt: Instant = defaultScheduleStartAt,
        scheduleId: Long = 10L,
        memberId: Long = 1L,
        routeJson: String? = null,
        title: String = "회의",
        alertMode: ScheduleAlertMode = ScheduleAlertMode.STANDARD,
        travelMode: ScheduleTravelMode = ScheduleTravelMode.CAR,
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
                travelMode = travelMode,
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
                alertMode = alertMode,
            )
        }

    @Test
    fun `대중교통 ETA가 줄어도 기존 출발 알림을 늦추지 않는다`() {
        val previousTravelMinutes = 40
        val currentTravelMinutes = 30
        val schedule = schedule(
            startAt = shortScheduleStartAt,
            travelMode = ScheduleTravelMode.TRANSIT,
        )
        val previousDepartureAt =
            schedule.startAt.minus(previousTravelMinutes.toLong(), ChronoUnit.MINUTES)
        val laterCandidateDepartureAt =
            schedule.startAt.minus(currentTravelMinutes.toLong(), ChronoUnit.MINUTES)

        val job = persistedJob(
            memberId = schedule.memberId,
            scheduleId = requireNotNull(schedule.id),
            scheduleAt = schedule.startAt,
            departureAt = previousDepartureAt,
            monitorStartAt = previousDepartureAt.minus(60, ChronoUnit.MINUTES),
            intervalMinutes = notificationIntervalMinutes,
        )

        job.startProcessing("previous-worker")
        job.finishCheck(
            travelMinutes = previousTravelMinutes,
            recommendedDepartureAt = previousDepartureAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = testNow,
            completeAfterCheck = false,
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = testNow.minus(1, ChronoUnit.MINUTES),
            etaStale = false,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = testNow.minus(1, ChronoUnit.MINUTES),
        )

        stubDueJobLookup(job, schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = currentTravelMinutes,
                source = TrafficSource.LIVE_PROVIDER,
                fetchedAt = testNow,
                stale = false,
                recommendedDepartureAt = laterCandidateDepartureAt,
                predictedArrivalAt = schedule.startAt,
            )
        )

        worker(alarmSyncService = departureAlarmSyncService).runDueJobs(testNow)

        // 대중교통 재조회 결과가 더 늦더라도 기존 안전 출발시각을 유지한다.
        assertEquals(previousDepartureAt, job.lastRecommendedDepartureAt)
        // 출발까지 30분 넘게 남았으므로 5분 뒤 다시 조회한다.
        assertEquals(testNow.plus(2, ChronoUnit.MINUTES), job.nextCheckAt)
        verify(departureAlarmSyncService).synchronizeAutomaticEta(
            memberId = eq(job.memberId),
            scheduleId = eq(job.scheduleId),
            notificationEnabled = eq(true),
            alertMode = eq(ScheduleAlertMode.STANDARD),
            recommendedDepartureAt = eq(previousDepartureAt),
            scheduleTitle = eq(schedule.title),
            resumeCanceledAfterTransitTransferFailure = eq(false),
            cancellationReason = eq(com.noLate.schedule.domain.DepartureAlarmCancellationReason.EXPLICIT_OR_TERMINAL),
        )
    }
}


private class AdvancingTestClock(
    private var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current

    fun advanceTo(next: Instant) {
        current = next
    }
}
