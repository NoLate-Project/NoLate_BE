package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.application.TrafficClient
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
        verify(travelPlanRepository, never()).findByScheduleIdAndMemberIdAndDeletedFalse(any(), any())
    }

    @Test
    fun `departure status 조회 서비스는 provider client를 의존하지 않는다`() {
        val constructorParameterTypes = ScheduleDepartureEtaService::class.java.declaredConstructors
            .flatMap { it.parameterTypes.toList() }

        assertFalse(constructorParameterTypes.contains(TrafficClient::class.java))
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
    ) {
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
            etaStale = source != TrafficSource.LIVE_PROVIDER,
            etaFailureReason = failureReason,
            now = checkedAt,
        )
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
    ): Schedule =
        Schedule(
            id = 10L,
            memberId = memberId,
            title = "회의",
            startAt = queryAt.plus(2, ChronoUnit.HOURS),
            endAt = queryAt.plus(3, ChronoUnit.HOURS),
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
