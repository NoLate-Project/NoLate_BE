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
import com.noLate.eta.domain.TransitLine
import com.noLate.eta.domain.TransitStop
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.schedule.application.TrafficProviderClient
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

/**
 * 외부 네트워크만 결정적 fixture로 대체하고 실제 ETA 패키지와 schedule worker를 모두 통과한다.
 *
 * 첫 검색의 차량은 마감보다 늦게 도착하지만, 더 이른 동일 경로 검색에서 아직 탈 수 있는 앞
 * 차량을 찾아 마감 전 도착하는 출발시각이 worker/job/push payload에 보존되는지 검증한다.
 */
class TransitEtaWorkerSimulationTest {
    private val now = Instant.parse("2026-07-29T02:55:00Z")
    private val scheduleAt = Instant.parse("2026-07-29T04:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `늦는 첫 버스 대신 더 이른 동일 경로 차량을 찾아 worker가 마감 전 ETA와 실제 출발시각을 푸시한다`() {
        val pushJobRepository = mock<SchedulePushJobRepository>()
        val scheduleRepository = mock<ScheduleRepository>()
        val notificationUseCase = mock<NotificationUseCase>()
        val roadProvider = mock<TrafficProviderClient>()
        val arrivalService = mock<TransitArrivalService>()
        val odsayProvider = TimetableFixtureProvider(fetchedAt = now)
        val schedule = schedule()
        val previousRecommendedDepartureAt =
            scheduleAt.minus(40, ChronoUnit.MINUTES)
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = scheduleAt,
            departureAt = previousRecommendedDepartureAt,
            monitorStartAt = now.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = 20,
        )
        job.startProcessing("fixture-baseline")
        job.finishCheck(
            travelMinutes = 40,
            recommendedDepartureAt = previousRecommendedDepartureAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = now,
            completeAfterCheck = false,
            etaSource = TrafficSource.TIMETABLE_PROVIDER,
            liveFetchedAt = now.minus(1, ChronoUnit.MINUTES),
            etaStale = false,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = now.minus(1, ChronoUnit.MINUTES),
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
                routeName = anyOrNull(),
                cityCode = anyOrNull(),
                nodeId = anyOrNull(),
                stationName = anyOrNull(),
                limit = any(),
                cityCodeNamespace = any(),
                providerCode = anyOrNull(),
            )
        ).thenReturn(
            listOf(
                TransitArrivalDto(
                    provider = "seoul-bus",
                    kind = "BUS",
                    routeName = "402",
                    stationName = "서울역버스환승센터",
                    expectedAt = Instant.parse("2026-07-29T03:24:00Z").toString(),
                    observedAt = now.toString(),
                    sourceUpdatedAt = now.toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                ),
                TransitArrivalDto(
                    provider = "seoul-bus",
                    kind = "BUS",
                    routeName = "402",
                    stationName = "서울역버스환승센터",
                    expectedAt = Instant.parse("2026-07-29T03:34:00Z").toString(),
                    observedAt = now.toString(),
                    sourceUpdatedAt = now.toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                )
            )
        )
        whenever(
            notificationUseCase.sendToMember(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        ).thenReturn(NotificationSendResult(requestedCount = 1, sentCount = 1))

        val matcher = TransitJourneyMatcher()
        val transitEtaService = TransitEtaCalculationService(
            selectedRouteDecoder = SelectedTransitRouteDecoder(jacksonObjectMapper()),
            journeyProviders = listOf(odsayProvider),
            safeDepartureResolver = SafeDepartureResolver(
                matcher = matcher,
                maxSearches = 3,
                toleranceSeconds = 60,
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
        val trafficClient = ModeAwareTrafficClient(
            trafficProviderClient = roadProvider,
            transitRealtimeTrafficClient = TransitRealtimeTrafficClient(transitEtaService),
        )
        val worker = SchedulePushJobWorker(
            scheduleRepository = scheduleRepository,
            objectMapper = jacksonObjectMapper(),
            trafficClient = trafficClient,
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
                Instant.parse("2026-07-29T03:20:00Z"),
                Instant.parse("2026-07-29T03:15:00Z"),
            ),
            odsayProvider.searchedAt,
        )
        verify(roadProvider, never()).getTravelMinutes(any())
        verify(arrivalService, times(1)).getBusArrivals(
            arsId = eq("02005"),
            routeName = eq("402"),
            cityCode = anyOrNull(),
            nodeId = anyOrNull(),
            stationName = eq("서울역버스환승센터"),
            limit = eq(10),
            cityCodeNamespace = any(),
            providerCode = anyOrNull(),
        )
        verify(notificationUseCase).sendToMember(
            memberId = eq(1L),
            title = eq("출발 시간이 앞당겨졌어요"),
            body = check {
                assertTrue(it.contains("5분 일찍 출발"))
            },
            data = check {
                assertEquals("40", it["travelMinutes"])
                assertEquals("2026-07-29T03:15:00Z", it["recommendedDepartureAt"])
                assertEquals("2026-07-29T03:55:00Z", it["predictedArrivalAt"])
                assertEquals("true", it["onTimeArrivalPossible"])
                assertEquals("LIVE_PROVIDER", it["etaSource"])
                assertEquals("false", it["etaStale"])
                assertEquals("5", it["departureAdvanceMinutes"])
            },
            inboxDeduplicationKey = any(),
            persistInInbox = eq(true),
        )
        assertEquals(40, job.lastTravelMinutes)
        assertEquals(Instant.parse("2026-07-29T03:15:00Z"), job.lastRecommendedDepartureAt)
        assertEquals(TrafficSource.LIVE_PROVIDER, job.lastEtaSource)
        assertEquals(now, job.lastLiveFetchedAt)
        assertEquals(false, job.lastEtaStale)
        assertEquals(Instant.parse("2026-07-29T03:55:00Z"), job.lastPredictedArrivalAt)
        assertEquals(ScheduleTravelMode.TRANSIT, job.lastEtaTravelMode)
        assertNull(job.lastEtaFailureReason)
    }

    private fun schedule(): Schedule =
        Schedule(
            id = 10L,
            memberId = 1L,
            title = "강남 회의",
            startAt = scheduleAt,
            endAt = scheduleAt.plus(1, ChronoUnit.HOURS),
        ).apply {
            updateRoute(
                travelMinutes = 40,
                departAt = scheduleAt.minus(40, ChronoUnit.MINUTES),
                departedAt = null,
                travelMode = ScheduleTravelMode.TRANSIT,
                locationName = "강남역",
                originName = "서울역",
                originAddress = null,
                originLat = 37.5547,
                originLng = 126.9706,
                destinationName = "강남역",
                destinationAddress = null,
                destinationLat = 37.4979,
                destinationLng = 127.0276,
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

    private fun selectedRouteJson(): String =
        """
            {
              "provider": "odsay",
              "minutes": 40,
              "transitLegs": [
                {
                  "kind": "WALK",
                  "durationMinutes": 5
                },
                {
                  "kind": "BUS",
                  "durationMinutes": 27,
                  "waitingMinutes": 5,
                  "providerRouteId": "bus-402",
                  "lineName": "402",
                  "startID": "1001",
                  "startArsID": "02005",
                  "startName": "서울역버스환승센터",
                  "endID": "2001",
                  "endArsID": "22009",
                  "endName": "강남역",
                  "directionName": "강남역 방면",
                  "directionCode": "DOWN"
                },
                {
                  "kind": "WALK",
                  "durationMinutes": 8
                }
              ]
            }
        """.trimIndent()

    private class TimetableFixtureProvider(
        private val fetchedAt: Instant,
    ) : TransitJourneyProvider {
        override val providerId: String = "odsay"
        val searchedAt = mutableListOf<Instant>()

        override fun search(request: TransitJourneySearchRequest): List<TransitJourney> {
            searchedAt += request.departureAt
            val departureAt = request.departureAt
            return listOf(
                TransitJourney(
                    provider = providerId,
                    requestedDepartureAt = request.departureAt,
                    departureAt = departureAt,
                    arrivalAt = departureAt.plus(43, ChronoUnit.MINUTES),
                    totalMinutes = 43,
                    legs = listOf(
                        TransitJourneyLeg(
                            sequence = 0,
                            mode = TransitLegMode.WALK,
                            durationMinutes = 5,
                        ),
                        TransitJourneyLeg(
                            sequence = 1,
                            mode = TransitLegMode.BUS,
                            durationMinutes = 30,
                            waitingMinutes = 7,
                            from = TransitStop(
                                providerStopId = "1001",
                                arsId = "02005",
                                name = "서울역버스환승센터",
                            ),
                            to = TransitStop(
                                providerStopId = "2001",
                                arsId = "22009",
                                name = "강남역",
                            ),
                            line = TransitLine(
                                providerRouteId = "bus-402",
                                name = "402",
                            ),
                            directionName = "강남역 방면",
                            directionCode = "DOWN",
                        ),
                        TransitJourneyLeg(
                            sequence = 2,
                            mode = TransitLegMode.WALK,
                            durationMinutes = 8,
                        ),
                    ),
                    fetchedAt = fetchedAt,
                )
            )
        }
    }
}
