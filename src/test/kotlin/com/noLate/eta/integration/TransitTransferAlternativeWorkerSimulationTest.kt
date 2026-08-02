package com.noLate.eta.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.TransitEtaCalculationService
import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.application.transit.FirstBoardingRealtimeOverlay
import com.noLate.eta.application.transit.SafeDepartureResolver
import com.noLate.eta.application.transit.TransitJourneyMatcher
import com.noLate.eta.application.transit.TransitTransferFeasibilityEvaluator
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLegTimingBasis
import com.noLate.eta.domain.TransitLine
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.eta.domain.TransitStop
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.schedule.application.TrafficProviderClient
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.service.SchedulePushJobCoordinator
import com.noLate.schedule.application.service.SchedulePushJobWorker
import com.noLate.schedule.application.service.policy.DepartureReminderPolicy
import com.noLate.schedule.application.service.policy.PeriodicPushPolicy
import com.noLate.schedule.application.service.policy.TrafficChangePolicy
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleEtaRouteFingerprint
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.infrastructure.ModeAwareTrafficClient
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.TransitRealtimeTrafficClient
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** 실제 ETA 패키지 -> mode-aware client -> worker -> job/push 경계를 통과하는 결정적 시뮬레이션. */
class TransitTransferAlternativeWorkerSimulationTest {
    private val now = Instant.parse("2026-08-01T02:55:00Z")
    private val scheduleAt = Instant.parse("2026-08-01T04:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `선택 환승을 놓치면 대체 여정 provider를 조회하지 않고 선택 경로 fallback을 보존한다`() {
        val pushJobRepository = mock<SchedulePushJobRepository>()
        val scheduleRepository = mock<ScheduleRepository>()
        val notificationUseCase = mock<NotificationUseCase>()
        val roadProvider = mock<TrafficProviderClient>()
        val arrivalService = mock<TransitArrivalService>()
        val odsayProvider = TransferFixtureProvider(now)
        val schedule = schedule()
        val previousDepartureAt = Instant.parse("2026-08-01T03:25:00Z")
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = scheduleAt,
            departureAt = scheduleAt.minus(40, ChronoUnit.MINUTES),
            monitorStartAt = now.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = 20,
        )
        job.startProcessing("fixture-baseline")
        job.finishCheck(
            travelMinutes = 30,
            recommendedDepartureAt = previousDepartureAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = now,
            completeAfterCheck = false,
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = now.minusSeconds(60),
            etaStale = false,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = now.minusSeconds(60),
        )
        whenever(
            pushJobRepository.findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                eq(SchedulePushJobStatus.ACTIVE),
                eq(now),
                any<PageRequest>(),
            )
        ).thenReturn(listOf(job))
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(
            arrivalService.getBusArrivals(
                arsId = anyOrNull(),
                routeName = eq("402"),
                cityCode = anyOrNull(),
                nodeId = anyOrNull(),
                stationName = anyOrNull(),
                limit = any(),
                cityCodeNamespace = any(),
                providerCode = anyOrNull(),
            )
        ).thenReturn(listOf(arrival("402", "2026-08-01T03:39:00Z")))
        whenever(
            arrivalService.getBusArrivals(
                arsId = anyOrNull(),
                routeName = eq("500"),
                cityCode = anyOrNull(),
                nodeId = anyOrNull(),
                stationName = anyOrNull(),
                limit = any(),
                cityCodeNamespace = any(),
                providerCode = anyOrNull(),
            )
        ).thenReturn(listOf(arrival("500", "2026-08-01T03:33:00Z")))
        whenever(notificationUseCase.sendToMember(any(), any(), any(), any(), any(), any()))
            .thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        val matcher = TransitJourneyMatcher()
        val etaService = TransitEtaCalculationService(
            selectedRouteDecoder = SelectedTransitRouteDecoder(jacksonObjectMapper()),
            journeyProviders = listOf(odsayProvider),
            safeDepartureResolver = SafeDepartureResolver(
                matcher = matcher,
                maxSearches = 3,
                toleranceSeconds = 180,
            ),
            firstBoardingRealtimeOverlay = FirstBoardingRealtimeOverlay(
                transitArrivalService = arrivalService,
                clock = clock,
                boardingBufferSeconds = 60,
                arrivalLimit = 10,
            ),
            journeyMatcher = matcher,
            transferFeasibilityEvaluator = TransitTransferFeasibilityEvaluator(),
        )
        val worker = SchedulePushJobWorker(
            scheduleRepository = scheduleRepository,
            objectMapper = jacksonObjectMapper(),
            trafficClient = ModeAwareTrafficClient(
                trafficProviderClient = roadProvider,
                transitRealtimeTrafficClient = TransitRealtimeTrafficClient(etaService),
            ),
            notificationUseCase = notificationUseCase,
            periodicPushPolicy = PeriodicPushPolicy(),
            departureReminderPolicy = DepartureReminderPolicy(),
            trafficChangePolicy = TrafficChangePolicy(),
            pushJobCoordinator = SchedulePushJobCoordinator(pushJobRepository),
            batchSize = 1,
            retryDelayMinutes = 1,
            maxRetryCount = 3,
            departureAlertLeadMinutes = 15,
            departureReminderIntervalMinutes = 5,
            processingTimeoutMinutes = 10,
            clock = clock,
        )

        assertEquals(1, worker.runDueJobs(now))

        assertEquals(
            listOf(
                Instant.parse("2026-08-01T03:25:00Z"),
                Instant.parse("2026-08-01T03:20:00Z"),
                Instant.parse("2026-08-01T03:10:00Z"),
            ),
            odsayProvider.searchedAt,
        )
        // 같은 degraded 상태를 다시 조회해도 상태 전이 알림은 중복 발송하지 않는다.
        assertEquals(1, worker.runDueJobs(now))
        verify(roadProvider, never()).getTravelMinutes(any())
        verify(arrivalService, times(1)).getBusArrivals(
            arsId = anyOrNull(),
            routeName = eq("402"),
            cityCode = anyOrNull(),
            nodeId = anyOrNull(),
            stationName = anyOrNull(),
            limit = any(),
            cityCodeNamespace = any(),
            providerCode = anyOrNull(),
        )
        verify(arrivalService, never()).getBusArrivals(
            arsId = anyOrNull(),
            routeName = eq("500"),
            cityCode = anyOrNull(),
            nodeId = anyOrNull(),
            stationName = anyOrNull(),
            limit = any(),
            cityCodeNamespace = any(),
            providerCode = anyOrNull(),
        )
        verify(notificationUseCase, times(1)).sendToMember(
            memberId = eq(1L),
            title = eq("선택한 환승을 놓칠 수 있어요"),
            body = check {
                assertTrue(it.contains("환승 차량을 현재 ETA로는 탈 수 없어요"))
                assertTrue(it.contains("경로를 다시 확인"))
                assertTrue(!it.contains("지금 출발"))
            },
            data = check {
                assertEquals(
                    TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
                    it["etaFailureReason"],
                )
                assertEquals("MISSED", it["transitTransferFeasibility"])
                assertEquals("", it["predictedArrivalAt"])
                assertEquals("", it["onTimeArrivalPossible"])
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
        )
        assertEquals(40, job.lastTravelMinutes)
        // 환승 실패는 degraded 결과이므로 저장 경로 분수로 알람을 재계산하지 않고
        // 직전 fresh 추천 출발시각을 보존한다.
        assertEquals(previousDepartureAt, job.lastRecommendedDepartureAt)
        assertNull(job.lastPredictedArrivalAt)
        assertEquals(TrafficSource.SELECTED_ROUTE, job.lastEtaSource)
        assertEquals(true, job.lastEtaStale)
        assertEquals(
            "TRANSIT_TRANSFER_MISSED: 첫 승차 지연으로 선택 여정의 환승 차량을 탈 수 없습니다.",
            job.lastEtaFailureReason,
        )
    }

    private fun arrival(route: String, expectedAt: String) = TransitArrivalDto(
        provider = "fixture",
        kind = "BUS",
        routeName = route,
        stationName = "출발 정류장",
        expectedAt = expectedAt,
        observedAt = now.toString(),
        sourceUpdatedAt = now.toString(),
        freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
    )

    private fun schedule(): Schedule = Schedule(
        id = 10L,
        memberId = 1L,
        title = "환승 회의",
        startAt = scheduleAt,
        endAt = scheduleAt.plus(1, ChronoUnit.HOURS),
    ).apply {
        updateRoute(
            travelMinutes = 40,
            departAt = scheduleAt.minus(40, ChronoUnit.MINUTES),
            departedAt = null,
            travelMode = ScheduleTravelMode.TRANSIT,
            locationName = "목적지역",
            originName = "출발 정류장",
            originAddress = null,
            originLat = 37.55,
            originLng = 126.97,
            destinationName = "목적지역",
            destinationAddress = null,
            destinationLat = 37.50,
            destinationLng = 127.03,
            routeJson = selectedRouteJson(),
            notificationEnabled = true,
            notificationLeadMinutes = 60,
            notificationIntervalMinutes = 20,
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

    private fun selectedRouteJson(): String = """
        {
          "provider": "odsay", "minutes": 40,
          "transitLegs": [
            { "kind": "WALK", "durationMinutes": 5 },
            {
              "kind": "BUS", "durationMinutes": 15, "waitingMinutes": 5,
              "providerRouteId": "bus-402", "lineName": "402",
              "startArsID": "02005", "startName": "출발 정류장",
              "endID": "transfer-a", "endName": "환승역",
              "directionName": "환승역 방면", "directionCode": "DOWN"
            },
            { "kind": "WALK", "durationMinutes": 5 },
            {
              "kind": "SUBWAY", "durationMinutes": 16, "waitingMinutes": 0,
              "serviceClass": "LOCAL",
              "providerRouteId": "subway-2", "lineName": "2호선",
              "startID": "transfer-b", "startName": "환승역",
              "endID": "destination", "endName": "목적지역",
              "directionName": "목적지역 방면", "directionCode": "DOWN"
            }
          ]
        }
    """.trimIndent()

    private class TransferFixtureProvider(
        private val fetchedAt: Instant,
    ) : TransitJourneyProvider {
        override val providerId = "odsay"
        val searchedAt = mutableListOf<Instant>()

        override fun search(request: TransitJourneySearchRequest): List<TransitJourney> {
            searchedAt += request.departureAt
            return listOf(
                journey(
                    departureAt = request.departureAt,
                    firstRouteId = "bus-402",
                    firstLineName = "402",
                    subwayRouteId = "subway-2",
                    subwayLineName = "2호선",
                    firstRideMinutes = 15,
                    walkMinutes = 5,
                    subwayDepartureMinute = 19,
                    finalArrivalMinute = 35,
                ),
                journey(
                    departureAt = request.departureAt,
                    firstRouteId = "bus-500",
                    firstLineName = "500",
                    subwayRouteId = "subway-9",
                    subwayLineName = "9호선",
                    firstRideMinutes = 12,
                    walkMinutes = 3,
                    subwayDepartureMinute = 17,
                    subwayWaitingMinutes = 8,
                    finalArrivalMinute = 33,
                ),
            )
        }

        private fun journey(
            departureAt: Instant,
            firstRouteId: String,
            firstLineName: String,
            subwayRouteId: String,
            subwayLineName: String,
            firstRideMinutes: Long,
            walkMinutes: Int,
            subwayDepartureMinute: Long,
            subwayWaitingMinutes: Int = 0,
            finalArrivalMinute: Long,
        ): TransitJourney = TransitJourney(
            provider = providerId,
            requestedDepartureAt = departureAt,
            departureAt = departureAt,
            arrivalAt = departureAt.plus(finalArrivalMinute, ChronoUnit.MINUTES),
            totalMinutes = finalArrivalMinute.toInt(),
            fetchedAt = fetchedAt,
            legs = listOf(
                TransitJourneyLeg(
                    sequence = 0,
                    mode = TransitLegMode.WALK,
                    durationMinutes = 5,
                    scheduledDepartureAt = departureAt,
                    scheduledArrivalAt = departureAt.plus(5, ChronoUnit.MINUTES),
                    timingBasis = TransitLegTimingBasis.TIMETABLE,
                ),
                TransitJourneyLeg(
                    sequence = 1,
                    mode = TransitLegMode.BUS,
                    durationMinutes = (firstRideMinutes - 5).toInt(),
                    waitingMinutes = 5,
                    scheduledDepartureAt = departureAt.plus(5, ChronoUnit.MINUTES),
                    scheduledArrivalAt = departureAt.plus(firstRideMinutes, ChronoUnit.MINUTES),
                    from = TransitStop(arsId = "02005", name = "출발 정류장"),
                    to = TransitStop(providerStopId = "transfer-a", name = "환승역"),
                    line = TransitLine(providerRouteId = firstRouteId, name = firstLineName),
                    directionName = "환승역 방면",
                    directionCode = "DOWN",
                    timingBasis = TransitLegTimingBasis.TIMETABLE,
                ),
                TransitJourneyLeg(
                    sequence = 2,
                    mode = TransitLegMode.WALK,
                    durationMinutes = walkMinutes,
                    scheduledDepartureAt = departureAt.plus(firstRideMinutes, ChronoUnit.MINUTES),
                    scheduledArrivalAt = departureAt.plus(
                        firstRideMinutes + walkMinutes,
                        ChronoUnit.MINUTES,
                    ),
                    timingBasis = TransitLegTimingBasis.TIMETABLE,
                ),
                TransitJourneyLeg(
                    sequence = 3,
                    mode = TransitLegMode.SUBWAY,
                    durationMinutes = (finalArrivalMinute - subwayDepartureMinute).toInt(),
                    waitingMinutes = subwayWaitingMinutes,
                    scheduledDepartureAt = departureAt.plus(
                        subwayDepartureMinute,
                        ChronoUnit.MINUTES,
                    ),
                    scheduledArrivalAt = departureAt.plus(finalArrivalMinute, ChronoUnit.MINUTES),
                    from = TransitStop(providerStopId = "transfer-b", name = "환승역"),
                    to = TransitStop(providerStopId = "destination", name = "목적지역"),
                    line = TransitLine(
                        providerRouteId = subwayRouteId,
                        name = subwayLineName,
                        serviceClass = TransitServiceClass.LOCAL,
                    ),
                    directionName = "목적지역 방면",
                    directionCode = "DOWN",
                    timingBasis = TransitLegTimingBasis.TIMETABLE,
                ),
            ),
        )
    }
}
