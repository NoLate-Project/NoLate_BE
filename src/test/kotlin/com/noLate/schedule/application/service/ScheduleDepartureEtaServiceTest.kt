package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleEtaConfidence
import com.noLate.schedule.domain.ScheduleEtaRouteFingerprint
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@ExtendWith(MockitoExtension::class)
class ScheduleDepartureEtaServiceTest {
    private val queryAt = Instant.parse("2026-07-24T03:00:00Z")
    private val clock = Clock.fixed(queryAt, ZoneOffset.UTC)
    private val objectMapper = ObjectMapper()

    @Mock
    lateinit var scheduleRepository: ScheduleRepository

    @Mock
    lateinit var travelPlanRepository: ScheduleTravelPlanRepository

    @Mock
    lateinit var pushJobRepository: SchedulePushJobRepository

    @Mock
    lateinit var scheduleAccessPolicy: ScheduleAccessPolicy

    @Test
    fun `저장된 LIVE job snapshot을 provider 재호출 없이 그대로 반환한다`() {
        val schedule = schedule()
        val firstCheckedAt = queryAt.minus(20, ChronoUnit.MINUTES)
        val secondCheckedAt = queryAt.minus(10, ChronoUnit.MINUTES)
        val liveFetchedAt = secondCheckedAt.minusSeconds(2)
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 35,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = firstCheckedAt,
            liveFetchedAt = firstCheckedAt.minusSeconds(2),
        )
        finish(
            job = job,
            travelMinutes = 40,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = secondCheckedAt,
            liveFetchedAt = liveFetchedAt,
        )
        job.startProcessing("in-flight-worker")
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(40, result.travelMinutes)
        assertEquals(schedule.startAt.minus(40, ChronoUnit.MINUTES), result.recommendedDepartureAt)
        assertEquals(secondCheckedAt, result.evaluatedAt)
        assertEquals(liveFetchedAt, result.liveFetchedAt)
        assertFalse(result.evaluatedAt.isBefore(result.liveFetchedAt))
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(ScheduleEtaConfidence.HIGH, result.confidence)
        assertFalse(result.stale)
        assertEquals(5, result.lastTrafficChangeMinutes)
        assertEquals(secondCheckedAt, result.lastChangedAt)
        assertEquals(job.nextCheckAt, result.nextCheckAt)
        assertNull(result.preparationMinutes)
        assertNull(result.preparationStartAt)
        assertNull(result.safetyBufferMinutes)
        assertEquals("Asia/Seoul", result.timeZone)
    }

    @Test
    fun `ODsay 실제 출발시각이 ETA 역산값보다 이르더라도 시간표 snapshot을 그대로 노출한다`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val checkedAt = queryAt.minus(5, ChronoUnit.MINUTES)
        val providerFetchedAt = checkedAt.minusSeconds(2)
        val providerRecommendedDepartureAt =
            schedule.startAt.minus(50, ChronoUnit.MINUTES)
        val route = requireNotNull(schedule.route)
        job.startProcessing("odsay-worker")
        job.finishCheck(
            travelMinutes = 35,
            recommendedDepartureAt = providerRecommendedDepartureAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = checkedAt.plus(20, ChronoUnit.MINUTES),
            completeAfterCheck = false,
            etaSource = TrafficSource.TIMETABLE_PROVIDER,
            liveFetchedAt = providerFetchedAt,
            etaStale = false,
            etaRouteFingerprint = ScheduleEtaRouteFingerprint.calculate(
                schedule = schedule,
                travelMinutes = route.travelMinutes,
                travelMode = route.travelMode,
                originLat = route.originLat,
                originLng = route.originLng,
                routeJson = route.routeJson,
            ),
            now = checkedAt,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(35, result.travelMinutes)
        assertEquals(providerRecommendedDepartureAt, result.recommendedDepartureAt)
        assertEquals(TrafficSource.TIMETABLE_PROVIDER, result.source)
        assertEquals(ScheduleEtaConfidence.MEDIUM, result.confidence)
        assertFalse(result.stale)
        assertEquals(checkedAt, result.evaluatedAt)
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `정시 도착 불가 진단은 늦은 예측 도착을 LOW confidence 상태로 노출한다`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val checkedAt = queryAt.minus(5, ChronoUnit.MINUTES)
        val liveFetchedAt = checkedAt.minusSeconds(2)
        val predictedArrivalAt = schedule.startAt.plus(5, ChronoUnit.MINUTES)
        val travelMinutes = ChronoUnit.MINUTES.between(checkedAt, predictedArrivalAt).toInt()
        val route = requireNotNull(schedule.route)
        job.startProcessing("odsay-live-worker")
        job.finishCheck(
            travelMinutes = travelMinutes,
            recommendedDepartureAt = checkedAt,
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = checkedAt.plus(20, ChronoUnit.MINUTES),
            completeAfterCheck = false,
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = liveFetchedAt,
            etaStale = true,
            etaFailureReason = TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE,
            predictedArrivalAt = predictedArrivalAt,
            etaRouteFingerprint = ScheduleEtaRouteFingerprint.calculate(
                schedule = schedule,
                travelMinutes = route.travelMinutes,
                travelMode = route.travelMode,
                originLat = route.originLat,
                originLng = route.originLng,
                routeJson = route.routeJson,
            ),
            now = checkedAt,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(travelMinutes, result.travelMinutes)
        assertEquals(checkedAt, result.recommendedDepartureAt)
        assertEquals(predictedArrivalAt, result.predictedArrivalAt)
        assertEquals(false, result.onTimeArrivalPossible)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertTrue(result.stale)
        assertEquals(
            TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE,
            result.failureReason,
        )
        assertEquals(liveFetchedAt, result.liveFetchedAt)
    }

    @Test
    fun `환승 실패 fallback snapshot은 선택 경로여도 LOW confidence로 노출한다`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        val checkedAt = queryAt.minus(5, ChronoUnit.MINUTES)
        val route = requireNotNull(schedule.route)
        job.startProcessing("transfer-fallback-worker")
        job.finishCheck(
            travelMinutes = 45,
            recommendedDepartureAt = schedule.startAt.minus(45, ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = checkedAt.plus(20, ChronoUnit.MINUTES),
            completeAfterCheck = false,
            etaSource = TrafficSource.SELECTED_ROUTE,
            etaStale = true,
            etaFailureReason = TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
            etaTravelMode = ScheduleTravelMode.TRANSIT,
            etaRouteFingerprint = ScheduleEtaRouteFingerprint.calculate(
                schedule = schedule,
                travelMinutes = route.travelMinutes,
                travelMode = route.travelMode,
                originLat = route.originLat,
                originLng = route.originLng,
                routeJson = route.routeJson,
            ),
            now = checkedAt,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_MISSED, result.failureReason)
    }

    @Test
    fun `정상 provider snapshot의 늦은 예측 도착은 계속 거부한다`() {
        val schedule = schedule(travelMode = ScheduleTravelMode.TRANSIT)
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(5, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(5, ChronoUnit.MINUTES).minusSeconds(2),
            snapshotSchedule = schedule,
        )
        // 영속 계층에서 유입될 수 있는 legacy/손상 snapshot을 재현한다. 새 도메인 쓰기 경로는
        // 정시 도착 불가 진단이 아닌 late predictedArrivalAt 자체를 이미 차단한다.
        setField(
            job,
            "lastPredictedArrivalAt",
            schedule.startAt.plus(5, ChronoUnit.MINUTES),
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertNull(result.predictedArrivalAt)
        assertNull(result.onTimeArrivalPossible)
    }

    @Test
    fun `departure status 조회 서비스는 provider client를 의존하지 않는다`() {
        val constructorParameterTypes = ScheduleDepartureEtaService::class.java.declaredConstructors
            .flatMap { it.parameterTypes.toList() }

        assertFalse(constructorParameterTypes.contains(TrafficClient::class.java))
    }

    @Test
    fun `global sharing off selects owner detail before ETA status lookup`() {
        whenever(scheduleAccessPolicy.isSharingDisabled()).thenReturn(true)
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 2L)).thenReturn(null)

        val error = assertThrows(BusinessException::class.java) {
            service().getDepartureStatus(memberId = 2L, scheduleId = 10L)
        }

        assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, error.errorCode)
        verify(scheduleRepository).findOwnedScheduleDetail(10L, 2L)
        verify(scheduleRepository, never()).findScheduleDetail(10L, 2L)
        verify(pushJobRepository, never()).findByScheduleIdAndMemberId(any(), any())
        verify(travelPlanRepository, never()).findByScheduleIdAndMemberIdAndDeletedFalse(any(), any())
    }

    @Test
    fun `global sharing off preserves owner saved ETA status`() {
        val schedule = schedule(memberId = 1L)
        whenever(scheduleAccessPolicy.isSharingDisabled()).thenReturn(true)
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleAccessPolicy.resolve(1L, schedule)).thenReturn(
            ScheduleAccessDecision(
                canView = true,
                canEdit = true,
                travelEnabled = true,
                canViewAllTravelPlans = true,
            )
        )

        val result = service().getDepartureStatus(memberId = 1L, scheduleId = 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(schedule.startAt.minus(30, ChronoUnit.MINUTES), result.recommendedDepartureAt)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        verify(scheduleRepository).findOwnedScheduleDetail(10L, 1L)
        verify(scheduleRepository, never()).findScheduleDetail(10L, 1L)
    }

    @Test
    fun `저장된 timeout fallback snapshot은 이전 live 시각과 낮은 신뢰도를 노출한다`() {
        val schedule = schedule()
        val liveCheckedAt = queryAt.minus(20, ChronoUnit.MINUTES)
        val fallbackCheckedAt = queryAt.minus(5, ChronoUnit.MINUTES)
        val liveFetchedAt = liveCheckedAt.minusSeconds(2)
        val job = job(schedule)
        finish(job, 30, TrafficSource.LIVE_PROVIDER, liveCheckedAt, liveFetchedAt)
        finish(
            job = job,
            travelMinutes = 45,
            source = TrafficSource.SAVED_FALLBACK,
            checkedAt = fallbackCheckedAt,
            failureReason = "PROVIDER_TIMEOUT: 실시간 ETA 공급자 응답 시간이 초과되었습니다.",
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(45, result.travelMinutes)
        assertEquals(fallbackCheckedAt, result.evaluatedAt)
        assertEquals(liveFetchedAt, result.liveFetchedAt)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertTrue(result.stale)
        assertTrue(result.failureReason.orEmpty().startsWith("PROVIDER_TIMEOUT:"))
        assertNull(result.lastTrafficChangeMinutes)
        assertNull(result.lastChangedAt)
    }

    @Test
    fun `저장된 provider 원문은 departure status에서 안정 코드로 sanitize한다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 45,
            source = TrafficSource.SAVED_FALLBACK,
            checkedAt = queryAt.minus(5, ChronoUnit.MINUTES),
            failureReason =
                "GET http://provider.internal/routes?startX=127.1&startY=37.1 failed",
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertTrue(result.failureReason.orEmpty().startsWith("ETA_FALLBACK:"))
        assertFalse(result.failureReason.orEmpty().contains("provider.internal"))
        assertFalse(result.failureReason.orEmpty().contains("127.1"))
    }

    @Test
    fun `invalid provider 응답 snapshot은 saved fallback과 낮은 신뢰도로 노출한다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = TrafficSource.SAVED_FALLBACK,
            checkedAt = queryAt.minus(5, ChronoUnit.MINUTES),
            failureReason =
                "PROVIDER_INVALID_RESPONSE: internal parser at http://provider.internal",
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertTrue(result.stale)
        assertEquals(
            "PROVIDER_INVALID_RESPONSE: 실시간 ETA 공급자 응답을 해석할 수 없습니다.",
            result.failureReason,
        )
    }

    @Test
    fun `legacy job의 source와 stale이 null이면 ETA source null 대신 현재 saved fallback을 반환한다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 35,
            source = TrafficSource.SAVED_FALLBACK,
            checkedAt = queryAt.minus(5, ChronoUnit.MINUTES),
            failureReason = "PROVIDER_DISABLED: safe",
            snapshotSchedule = schedule,
        )
        setField(job, "lastEtaSource", null)
        setField(job, "lastEtaStale", null)
        setField(job, "lastEtaRouteFingerprint", null)
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertTrue(result.stale)
    }

    @Test
    fun `제품 상한 초과 또는 불완전 LIVE provenance job은 현재 route fallback으로 내린다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(5, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(5, ChronoUnit.MINUTES),
            snapshotSchedule = schedule,
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        setField(job, "lastTravelMinutes", 2_000)
        val oversized = service().getDepartureStatus(1L, 10L)

        assertEquals(30, oversized.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, oversized.source)
        assertNull(oversized.liveFetchedAt)

        setField(job, "lastTravelMinutes", 30)
        setField(
            job,
            "lastRecommendedDepartureAt",
            schedule.startAt.minus(30, ChronoUnit.MINUTES),
        )
        setField(job, "lastLiveFetchedAt", null)
        val incompleteLive = service().getDepartureStatus(1L, 10L)

        assertEquals(30, incompleteLive.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, incompleteLive.source)
        assertNull(incompleteLive.liveFetchedAt)
    }

    @Test
    fun `job이 없으면 routeInfo 전체 시간으로 선택 경로 snapshot을 구성한다`() {
        val schedule = schedule(
            routeJson = """
                {
                  "routeInfo": {
                    "totalDurationMinutes": 40,
                    "steps": [{"durationMinutes": 5}]
                  }
                }
            """.trimIndent(),
            travelMinutes = 40,
        )
        stubVisible(schedule)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(40, result.travelMinutes)
        assertEquals(queryAt, result.evaluatedAt)
        assertNull(result.liveFetchedAt)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertEquals(ScheduleEtaConfidence.MEDIUM, result.confidence)
        assertTrue(result.stale)
        assertTrue(result.failureReason.orEmpty().startsWith("SELECTED_ROUTE_SNAPSHOT:"))
    }

    @Test
    fun `route-level 시간이 없으면 step 시간이 아니라 저장 travelMinutes를 사용한다`() {
        val schedule = schedule(
            routeJson = """{"routeInfo":{"steps":[{"durationMinutes":5}]}}""",
        )
        stubVisible(schedule)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
        assertTrue(result.failureReason.orEmpty().startsWith("SAVED_ROUTE_SNAPSHOT:"))
    }

    @Test
    fun `과대 selected JSON ETA는 canonical 저장 시간을 덮어쓰지 않는다`() {
        val schedule = schedule(routeJson = """{"minutes":2000}""", travelMinutes = 30)
        stubVisible(schedule)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(ScheduleEtaConfidence.LOW, result.confidence)
    }

    @Test
    fun `fraction routeInfo ETA가 canonical과 일치하면 선택 경로 snapshot으로 사용한다`() {
        val schedule = schedule(
            routeJson = """{"routeInfo":{"totalDurationMinutes":29.2}}""",
            travelMinutes = 30,
        )
        stubVisible(schedule)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertEquals(ScheduleEtaConfidence.MEDIUM, result.confidence)
    }

    @Test
    fun `job이 없는 개인 대중교통 계획은 선택 itinerary snapshot만 반환한다`() {
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

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(44, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertEquals(ScheduleEtaConfidence.MEDIUM, result.confidence)
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `owner 경로가 바뀌고 job이 취소되면 이전 live snapshot을 사용하지 않는다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            snapshotSchedule = schedule,
        )
        schedule.route?.apply {
            travelMinutes = 25
            destinationName = "새 회사"
            destinationLat = 37.8
        }
        job.cancel()
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(25, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(queryAt, result.evaluatedAt)
        assertNull(result.liveFetchedAt)
        assertNull(result.nextCheckAt)
    }

    @Test
    fun `owner 목적지가 바뀌면 active job의 이전 route fingerprint snapshot도 거부한다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            snapshotSchedule = schedule,
        )
        schedule.route?.apply {
            destinationName = "새 목적지"
            destinationLat = 38.1
        }
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(queryAt, result.evaluatedAt)
        assertNull(result.liveFetchedAt)
        assertNull(result.lastTrafficChangeMinutes)
    }

    @Test
    fun `일정 시작 시각이 바뀌면 이전 scheduleAt job snapshot을 거부한다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            snapshotSchedule = schedule,
        )
        schedule.startAt = schedule.startAt.plus(1, ChronoUnit.HOURS)
        schedule.endAt = schedule.endAt.plus(1, ChronoUnit.HOURS)
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(schedule.startAt.minus(30, ChronoUnit.MINUTES), result.recommendedDepartureAt)
        assertEquals(queryAt, result.evaluatedAt)
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `FAILED job은 현재 저장 경로보다 우선하지 않는다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 35,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            snapshotSchedule = schedule,
        )
        job.fail("terminal")
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertEquals(queryAt, result.evaluatedAt)
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `알림이 비활성화되면 active job snapshot도 거부한다`() {
        val schedule = schedule()
        val job = job(schedule)
        finish(
            job = job,
            travelMinutes = 30,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            snapshotSchedule = schedule,
        )
        schedule.route?.notificationEnabled = false
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)

        val result = service().getDepartureStatus(1L, 10L)

        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertTrue(result.failureReason.orEmpty().startsWith("NOTIFICATION_DISABLED:"))
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `shared participant 계획 fingerprint가 stale이면 이전 job snapshot을 거부한다`() {
        val schedule = schedule(memberId = 1L)
        val plan = ScheduleTravelPlan(scheduleId = 10L, memberId = 2L).apply {
            replace(
                command = ScheduleTravelPlanUpsertCommand(
                    travelMinutes = 30,
                    travelMode = ScheduleTravelMode.CAR,
                    originLat = 37.1,
                    originLng = 127.1,
                    notificationEnabled = true,
                ),
                scheduleFingerprint = ScheduleTravelPlanFingerprint.calculate(schedule),
                departAt = null,
                routeJson = null,
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = 20,
            )
        }
        val participantJob = SchedulePushJob.create(
            memberId = 2L,
            scheduleId = 10L,
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = queryAt.minus(30, ChronoUnit.MINUTES),
            intervalMinutes = 20,
        )
        finish(
            job = participantJob,
            travelMinutes = 30,
            source = TrafficSource.LIVE_PROVIDER,
            checkedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            liveFetchedAt = queryAt.minus(10, ChronoUnit.MINUTES),
            snapshotSchedule = schedule,
        )
        schedule.route?.apply {
            destinationName = "변경된 목적지"
            destinationLat = 38.0
        }
        stubVisible(schedule, 2L)
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 2L))
            .thenReturn(plan)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 2L))
            .thenReturn(participantJob)

        val result = service().getDepartureStatus(2L, 10L)

        assertEquals(30, result.travelMinutes)
        assertEquals(TrafficSource.SAVED_FALLBACK, result.source)
        assertTrue(result.failureReason.orEmpty().startsWith("ROUTE_STALE:"))
        assertNull(result.liveFetchedAt)
    }

    @Test
    fun `다른 회원에게 보이지 않는 일정은 snapshot 조회 전에 차단한다`() {
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(null)

        val exception = assertThrows(BusinessException::class.java) {
            service().getDepartureStatus(2L, 10L)
        }

        assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, exception.errorCode)
        verify(scheduleAccessPolicy, never()).resolve(any(), any())
        verify(pushJobRepository, never()).findByScheduleIdAndMemberId(any(), any())
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
        verify(pushJobRepository, never()).findByScheduleIdAndMemberId(any(), any())
    }

    @Test
    fun `이동 계획이 없으면 존재하지 않는 ETA와 준비시간을 만들지 않는다`() {
        val schedule = Schedule(
            id = 10L,
            memberId = 1L,
            title = "경로 미설정",
            startAt = queryAt.plus(2, ChronoUnit.HOURS),
            endAt = queryAt.plus(3, ChronoUnit.HOURS),
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
    }

    private fun finish(
        job: SchedulePushJob,
        travelMinutes: Int,
        source: TrafficSource,
        checkedAt: Instant,
        liveFetchedAt: Instant? = null,
        failureReason: String? = null,
        snapshotSchedule: Schedule = schedule(),
    ) {
        val route = requireNotNull(snapshotSchedule.route)
        job.startProcessing("eta-test")
        job.finishCheck(
            travelMinutes = travelMinutes,
            recommendedDepartureAt = job.scheduleAt.minus(travelMinutes.toLong(), ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = checkedAt.plus(20, ChronoUnit.MINUTES),
            completeAfterCheck = false,
            etaSource = source,
            liveFetchedAt = liveFetchedAt,
            etaStale = source != TrafficSource.LIVE_PROVIDER &&
                source != TrafficSource.TIMETABLE_PROVIDER,
            etaFailureReason = failureReason,
            etaRouteFingerprint = ScheduleEtaRouteFingerprint.calculate(
                schedule = snapshotSchedule,
                travelMinutes = route.travelMinutes,
                travelMode = route.travelMode,
                originLat = route.originLat,
                originLng = route.originLng,
                routeJson = route.routeJson,
            ),
            now = checkedAt,
        )
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun stubVisible(schedule: Schedule) {
        stubVisible(schedule, 1L)
    }

    private fun stubVisible(schedule: Schedule, viewerId: Long) {
        whenever(scheduleRepository.findScheduleDetail(10L, viewerId)).thenReturn(schedule)
        whenever(scheduleAccessPolicy.resolve(viewerId, schedule)).thenReturn(
            ScheduleAccessDecision(
                canView = true,
                canEdit = viewerId == schedule.memberId,
                travelEnabled = true,
                canViewAllTravelPlans = viewerId == schedule.memberId,
            )
        )
    }

    private fun service() = ScheduleDepartureEtaService(
        scheduleRepository = scheduleRepository,
        travelPlanRepository = travelPlanRepository,
        pushJobRepository = pushJobRepository,
        scheduleAccessPolicy = scheduleAccessPolicy,
        objectMapper = objectMapper,
        clock = clock,
    )

    private fun job(schedule: Schedule) = SchedulePushJob.create(
        memberId = 1L,
        scheduleId = 10L,
        scheduleAt = schedule.startAt,
        departureAt = schedule.startAt.minus(30, ChronoUnit.MINUTES),
        monitorStartAt = queryAt.minus(30, ChronoUnit.MINUTES),
        intervalMinutes = 20,
    )

    private fun schedule(
        memberId: Long = 1L,
        routeJson: String? = null,
        travelMinutes: Int? = 30,
        destinationName: String = "회사",
        destinationLat: Double = 37.2,
        notificationEnabled: Boolean = true,
        travelMode: ScheduleTravelMode = ScheduleTravelMode.CAR,
    ): Schedule =
        Schedule(
            id = 10L,
            memberId = memberId,
            title = "회의",
            startAt = queryAt.plus(2, ChronoUnit.HOURS),
            endAt = queryAt.plus(3, ChronoUnit.HOURS),
        ).apply {
            updateRoute(
                travelMinutes = travelMinutes,
                departAt = null,
                departedAt = null,
                travelMode = travelMode,
                locationName = "회사",
                originName = "집",
                originAddress = null,
                originLat = 37.1,
                originLng = 127.1,
                destinationName = destinationName,
                destinationAddress = null,
                destinationLat = destinationLat,
                destinationLng = 127.2,
                routeJson = routeJson,
                notificationEnabled = notificationEnabled,
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = 20,
            )
        }
}
