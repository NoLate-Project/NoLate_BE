package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleEtaConfidence
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@ExtendWith(MockitoExtension::class)
class ScheduleDepartureEtaServiceTest {
    private val evaluatedAt = Instant.parse("2026-07-24T03:00:00Z")
    private val providerFetchedAt = evaluatedAt.minusSeconds(2)
    private val clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC)
    private val objectMapper = ObjectMapper()

    @Mock
    lateinit var scheduleRepository: ScheduleRepository

    @Mock
    lateinit var travelPlanRepository: ScheduleTravelPlanRepository

    @Mock
    lateinit var pushJobRepository: SchedulePushJobRepository

    @Mock
    lateinit var scheduleAccessPolicy: ScheduleAccessPolicy

    @Mock
    lateinit var trafficClient: TrafficClient

    @Test
    fun `LIVE provider 응답은 실제 fetchedAt과 높은 신뢰도로 반환하고 searchOption을 유지한다`() {
        val schedule = schedule(routeJson = """{"minutes":35,"providerRouteOption":"2"}""")
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(35, ChronoUnit.MINUTES),
            monitorStartAt = evaluatedAt.minus(10, ChronoUnit.MINUTES),
            intervalMinutes = 20,
        )
        job.startProcessing("old-worker")
        job.finishCheck(
            travelMinutes = 35,
            recommendedDepartureAt = schedule.startAt.minus(35, ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = evaluatedAt.plus(20, ChronoUnit.MINUTES),
            completeAfterCheck = false,
            now = evaluatedAt.minus(20, ChronoUnit.MINUTES),
        )
        job.startProcessing("newer-worker")
        job.finishCheck(
            travelMinutes = 40,
            recommendedDepartureAt = schedule.startAt.minus(40, ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = evaluatedAt.plus(20, ChronoUnit.MINUTES),
            completeAfterCheck = false,
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = evaluatedAt.minus(10, ChronoUnit.MINUTES),
            etaStale = false,
            now = evaluatedAt.minus(10, ChronoUnit.MINUTES),
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 40,
                source = TrafficSource.LIVE_PROVIDER,
                fetchedAt = providerFetchedAt,
                stale = false,
            )
        )

        val result = service().getDepartureStatus(1L, 10L)

        verify(trafficClient).getTravelMinutes(check<TrafficRequest> {
            assertEquals("2", it.selectedRouteOption)
            assertTrue(it.selectedRouteJson.orEmpty().contains("providerRouteOption"))
            assertEquals(35, it.selectedRouteTravelMinutes)
        })
        assertEquals(40, result.travelMinutes)
        assertEquals(schedule.startAt.minus(40, ChronoUnit.MINUTES), result.recommendedDepartureAt)
        assertEquals(evaluatedAt, result.evaluatedAt)
        assertEquals(providerFetchedAt, result.liveFetchedAt)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(ScheduleEtaConfidence.HIGH, result.confidence)
        assertEquals(false, result.stale)
        assertEquals(5, result.lastTrafficChangeMinutes)
        assertEquals(evaluatedAt.minus(10, ChronoUnit.MINUTES), result.lastChangedAt)
        assertNull(result.preparationMinutes)
        assertNull(result.preparationStartAt)
        assertNull(result.safetyBufferMinutes)
        assertEquals("Asia/Seoul", result.timeZone)
    }

    @Test
    fun `선택 경로와 저장 fallback의 출처 및 신뢰도를 그대로 노출한다`() {
        val schedule = schedule(routeJson = """{"minutes":42}""")
        stubVisible(schedule)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 42,
                source = TrafficSource.SELECTED_ROUTE,
                stale = true,
                failureReason = TrafficFailureReasons.SELECTED_ROUTE_OPTION_MISSING,
            ),
            TrafficResult(
                travelMinutes = 30,
                source = TrafficSource.SAVED_FALLBACK,
                stale = true,
                failureReason = TrafficFailureReasons.PROVIDER_TIMEOUT,
            ),
        )

        val selected = service().getDepartureStatus(1L, 10L)
        val saved = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SELECTED_ROUTE, selected.source)
        assertEquals(ScheduleEtaConfidence.MEDIUM, selected.confidence)
        assertEquals(TrafficFailureReasons.SELECTED_ROUTE_OPTION_MISSING, selected.failureReason)
        assertNull(selected.liveFetchedAt)
        assertEquals(TrafficSource.SAVED_FALLBACK, saved.source)
        assertEquals(ScheduleEtaConfidence.LOW, saved.confidence)
        assertEquals(TrafficFailureReasons.PROVIDER_TIMEOUT, saved.failureReason)
        assertNull(saved.liveFetchedAt)
    }

    @Test
    fun `개인 계획의 선택 대중교통 itinerary를 ETA 요청까지 보존한다`() {
        val schedule = schedule()
        val routeJson = """
            {
              "minutes": 44,
              "selectedItinerary": {
                "transferCount": 1,
                "legs": [{"mode": "BUS", "route": "간선 100"}]
              }
            }
        """.trimIndent()
        val plan = ScheduleTravelPlan(scheduleId = 10L, memberId = 1L).apply {
            replace(
                command = ScheduleTravelPlanUpsertCommand(
                    travelMinutes = 44,
                    travelMode = ScheduleTravelMode.TRANSIT,
                    originLat = 37.3,
                    originLng = 127.3,
                    routeJson = routeJson,
                ),
                scheduleFingerprint = ScheduleTravelPlanFingerprint.calculate(schedule),
                departAt = null,
                routeJson = routeJson,
                notificationLeadMinutes = null,
                notificationIntervalMinutes = null,
            )
        }
        stubVisible(schedule)
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 1L))
            .thenReturn(plan)
        whenever(trafficClient.getTravelMinutes(any())).thenReturn(
            TrafficResult(
                travelMinutes = 44,
                source = TrafficSource.SELECTED_ROUTE,
                stale = true,
                failureReason = TrafficFailureReasons.SELECTED_TRANSIT_ROUTE_NOT_REFRESHABLE,
            )
        )

        service().getDepartureStatus(1L, 10L)

        verify(trafficClient).getTravelMinutes(check<TrafficRequest> {
            assertEquals(ScheduleTravelMode.TRANSIT, it.travelMode)
            assertEquals(44, it.selectedRouteTravelMinutes)
            assertTrue(it.selectedTransitItineraryJson.orEmpty().contains("간선 100"))
            assertTrue(it.selectedRouteJson.orEmpty().contains("selectedItinerary"))
        })
    }

    @Test
    fun `다른 회원에게 보이지 않는 일정은 provider 조회 전에 차단한다`() {
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(null)

        val exception = assertThrows(BusinessException::class.java) {
            service().getDepartureStatus(2L, 10L)
        }

        assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, exception.errorCode)
        verify(scheduleAccessPolicy, never()).resolve(any(), any())
        verify(trafficClient, never()).getTravelMinutes(any())
    }

    @Test
    fun `일정만 공유되고 이동 권한이 없는 회원은 departure status를 조회할 수 없다`() {
        val schedule = schedule(memberId = 1L)
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(schedule)
        whenever(scheduleAccessPolicy.resolve(2L, schedule)).thenReturn(
            ScheduleAccessDecision(
                canView = true,
                canEdit = false,
                travelEnabled = false,
                canViewAllTravelPlans = false,
            )
        )

        val exception = assertThrows(BusinessException::class.java) {
            service().getDepartureStatus(2L, 10L)
        }

        assertEquals(ErrorCode.FORBIDDEN, exception.errorCode)
        verify(trafficClient, never()).getTravelMinutes(any())
    }

    @Test
    fun `저장된 이동 계획이 없으면 존재하지 않는 준비시간을 만들지 않고 nullable 계약을 반환한다`() {
        val schedule = Schedule(
            id = 10L,
            memberId = 1L,
            title = "경로 미설정",
            startAt = evaluatedAt.plus(2, ChronoUnit.HOURS),
            endAt = evaluatedAt.plus(3, ChronoUnit.HOURS),
        )
        stubVisible(schedule)

        val result = service().getDepartureStatus(1L, 10L)

        assertNull(result.travelMinutes)
        assertNull(result.recommendedDepartureAt)
        assertNull(result.source)
        assertNull(result.confidence)
        assertTrue(result.failureReason.orEmpty().startsWith("ROUTE_NOT_CONFIGURED:"))
        assertNull(result.preparationMinutes)
        assertNull(result.preparationStartAt)
        assertNull(result.safetyBufferMinutes)
        verify(trafficClient, never()).getTravelMinutes(any())
    }

    private fun stubVisible(schedule: Schedule) {
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleAccessPolicy.resolve(1L, schedule)).thenReturn(
            ScheduleAccessDecision(
                canView = true,
                canEdit = true,
                travelEnabled = true,
                canViewAllTravelPlans = true,
            )
        )
    }

    private fun service() = ScheduleDepartureEtaService(
        scheduleRepository = scheduleRepository,
        travelPlanRepository = travelPlanRepository,
        pushJobRepository = pushJobRepository,
        scheduleAccessPolicy = scheduleAccessPolicy,
        trafficClient = trafficClient,
        objectMapper = objectMapper,
        clock = clock,
    )

    private fun schedule(
        memberId: Long = 1L,
        routeJson: String? = null,
    ): Schedule =
        Schedule(
            id = 10L,
            memberId = memberId,
            title = "회의",
            startAt = evaluatedAt.plus(2, ChronoUnit.HOURS),
            endAt = evaluatedAt.plus(3, ChronoUnit.HOURS),
        ).apply {
            updateRoute(
                travelMinutes = 30,
                departAt = null,
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
                notificationIntervalMinutes = 20,
            )
        }
}
