package com.noLate.journey.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.journey.domain.*
import com.noLate.journey.infrastructure.JourneyGuidanceSessionRepository
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.liveactivity.application.ScheduleLiveActivityService
import com.noLate.schedule.application.service.*
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import com.noLate.schedule.domain.ScheduleTravelPlanStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class JourneyGuidanceServiceTest {
    private val mapper = jacksonObjectMapper()
    private val now = Instant.parse("2026-10-03T01:00:00Z")
    private val sessions = mock<JourneyGuidanceSessionRepository>()
    private val members = mock<MemberRepository>()
    private val plans = mock<ScheduleTravelPlanService>()
    private val departure = mock<ScheduleDepartureStatusService>()
    private val jobs = mock<SchedulePushJobService>()
    private val schedules = mock<ScheduleService>()
    private val activities = mock<ScheduleLiveActivityService>()
    private val member = Member(id = 7, sessionGeneration = 2)
    private val requestId = "0167598a-6c84-4d0d-8d25-134e0c01c12e"
    private val route = mapper.readTree("""{"transitLegs":[{"kind":"WALK","durationMinutes":3},{"kind":"BUS","lineName":"123","stationCount":5,"passStops":[{"name":"A","coord":{"lat":37.5,"lng":127.0}}]}]}""")
    private val plan = ScheduleTravelPlanDto(scheduleId = 41, memberId = 7, status = ScheduleTravelPlanStatus.READY, travelMode = ScheduleTravelMode.TRANSIT, route = route)

    @Test
    fun `feature defaults off before any side effects`() {
        assertThatThrownBy { service(false).start(7, 41, 2, JourneyStartCommand(requestId)) }
            .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.errorCode).isEqualTo(ErrorCode.FEATURE_DISABLED) }
        verifyNoInteractions(sessions, departure, jobs, activities)
    }

    @Test
    fun `automatic guidance flag alone never opens the legacy manual departure path`() {
        assertThatThrownBy { service(manualEnabled = false).start(7, 41, 2, JourneyStartCommand(requestId)) }
            .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.errorCode).isEqualTo(ErrorCode.FEATURE_DISABLED) }
        verifyNoInteractions(sessions, departure, jobs, activities)
    }

    @Test
    fun `authorized schedule without a journey returns null before requiring a personal plan`() {
        whenever(members.findByIdForUpdate(7)).thenReturn(member)
        assertThat(service().current(7, 41, 2)).isNull()
        verify(schedules).getScheduleDetail(7, 41)
        verifyNoInteractions(plans)
    }

    @Test
    fun `start freezes actor plan and cancels departure with explicit Activity handoff`() {
        prepareStart()
        val response = service().start(7, 41, 2, JourneyStartCommand(requestId, "device"))
        val saved = argumentCaptor<JourneyGuidanceSession>()
        verify(sessions).saveAndFlush(saved.capture())
        assertThat(response.sessionId).isEqualTo(requestId)
        assertThat(response.sequence).isZero()
        assertThat(response.phase).isEqualTo(JourneyPhase.WALKING_TO_STOP)
        assertThat(response.routeLegs[1].path("passStops")[0].path("coord").path("lat").asDouble()).isEqualTo(37.5)
        assertThat(saved.firstValue.deviceFingerprint).isEqualTo(OpaquePushIdentifier.fingerprint("device"))
        verify(plans).getMyJourneyTravelPlanForStart(7, 41)
        verify(departure).markDeparted(eq(7), eq(41), any())
        verify(jobs).cancelByScheduleIdAndMemberId(41, 7, "DEPARTED", preserveLiveActivity = true)
        verify(activities).projectJourney(eq(saved.firstValue), any())
    }

    @Test
    fun `same start UUID returns current session without repeated departure effects`() {
        prepareStart()
        val existing = session()
        whenever(sessions.findBySessionId(requestId)).thenReturn(existing)
        assertThat(service().start(7, 41, 2, JourneyStartCommand(requestId)).sessionId).isEqualTo(requestId)
        verify(sessions, never()).saveAndFlush(any())
        verifyNoInteractions(jobs, activities)
    }

    @Test
    fun `forged login session is rejected before private route reads`() {
        whenever(members.findByIdForUpdate(7)).thenReturn(member)
        assertThatThrownBy { service().progress(7, 41, 1, requestId, command()) }
            .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.errorCode).isEqualTo(ErrorCode.INVALID_TOKEN) }
        verifyNoInteractions(plans, sessions, activities)
    }

    @Test
    fun `foreign journey ID cannot be read or mutated`() {
        whenever(members.findByIdForUpdate(7)).thenReturn(member)
        whenever(plans.getTravelPlan(7, 41, 7)).thenReturn(plan)
        whenever(sessions.findBySessionId(requestId)).thenReturn(session(memberId = 99))
        assertThatThrownBy { service().progress(7, 41, 2, requestId, command()) }
            .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.errorCode).isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND) }
        verifyNoInteractions(activities)
    }

    @Test
    fun `changed personal route cannot receive an old session progress update`() {
        whenever(members.findByIdForUpdate(7)).thenReturn(member)
        whenever(plans.getTravelPlan(7, 41, 7)).thenReturn(plan.copy(route = mapper.readTree("""{"transitLegs":[{"kind":"BUS","lineName":"999"}]}""")))
        whenever(sessions.findBySessionId(requestId)).thenReturn(session())
        assertThatThrownBy { service().progress(7, 41, 2, requestId, command()) }.isInstanceOf(BusinessException::class.java)
        verifyNoInteractions(activities)
    }

    @Test
    fun `reconciler stops logged out session instead of starting another remote Activity`() {
        val session = session()
        whenever(sessions.findOwnerMemberId(1)).thenReturn(7)
        whenever(sessions.findById(1)).thenReturn(Optional.of(session))
        whenever(members.findByIdForUpdate(7)).thenReturn(Member(id = 7, sessionGeneration = 3))
        service().reconcile(1)
        assertThat(session.phase).isEqualTo(JourneyPhase.STOPPED)
        verifyNoInteractions(plans)
        verify(activities).projectJourney(eq(session), any())
    }

    @Test
    fun `reconciler commits terminal state when schedule travel permission disappears`() {
        val session = session()
        whenever(sessions.findOwnerMemberId(1)).thenReturn(7)
        whenever(sessions.findById(1)).thenReturn(Optional.of(session))
        whenever(members.findByIdForUpdate(7)).thenReturn(member)
        whenever(plans.findMyJourneyTravelPlan(7, 41)).thenReturn(null)
        service().reconcile(1)
        assertThat(session.phase).isEqualTo(JourneyPhase.STOPPED)
        verify(activities).projectJourney(eq(session), any())
    }

    @Test
    fun `unconfigured stale walking-only and unsupported routes fail closed`() {
        listOf(
            plan.copy(status = ScheduleTravelPlanStatus.STALE),
            plan.copy(travelMode = ScheduleTravelMode.WALK),
            plan.copy(route = mapper.readTree("""{"transitLegs":[{"kind":"WALK"}]}""")),
            plan.copy(route = mapper.readTree("""{"transitLegs":[{"kind":"ETC"}]}""")),
        ).forEach { invalid ->
            assertThatThrownBy { JourneyRouteSnapshot.from(invalid, mapper) }.isInstanceOf(BusinessException::class.java)
        }
    }

    @Test
    fun `terminal retry never projects another END into a newer Activity`() {
        val session = session()
        val stop = JourneyTerminalCommand(1, 1, 1, now)
        session.finish(stop, JourneyPhase.STOPPED, JourneyRouteSnapshot.describe(mapper.readTree(session.routeLegsJson)), now)
        whenever(members.findByIdForUpdate(7)).thenReturn(member)
        whenever(plans.getTravelPlan(7, 41, 7)).thenReturn(plan)
        whenever(sessions.findBySessionId(requestId)).thenReturn(session)
        assertThat(service().finish(7, 41, 2, requestId, stop, JourneyPhase.STOPPED).phase).isEqualTo(JourneyPhase.STOPPED)
        verifyNoInteractions(activities)
    }

    private fun prepareStart() {
        whenever(departure.lockNotificationActionMembers(7, 41, 2)).thenReturn(ScheduleDepartureMemberFence(7, 41, emptySet(), setOf(7)))
        whenever(plans.getMyJourneyTravelPlanForStart(7, 41)).thenReturn(plan)
        whenever(sessions.saveAndFlush(any<JourneyGuidanceSession>())).thenAnswer { it.arguments[0] }
    }

    private fun session(memberId: Long = 7): JourneyGuidanceSession {
        val snapshot = JourneyRouteSnapshot.from(plan, mapper)
        return JourneyGuidanceSession(
            id = 1, sessionId = requestId, memberId = memberId, scheduleId = 41, loginSessionGeneration = 2,
            generation = 1, routeFingerprint = snapshot.fingerprint, routeSnapshotJson = snapshot.json, routeLegsJson = snapshot.legsJson,
            phase = JourneyPhase.WALKING_TO_STOP, nextInstruction = "이동해 주세요", startedAt = now, observedAt = now, expiresAt = now.plusSeconds(3600),
        )
    }
    private fun command() = JourneyProgressCommand(1, 1, 1, JourneyPhase.WAITING, 1, legProgress = 0.0, observedAt = now)
    private fun service(enabled: Boolean = true, manualEnabled: Boolean = true) = JourneyGuidanceService(
        sessions, members, plans, departure, jobs, schedules, activities, mapper,
        Clock.fixed(now, ZoneOffset.UTC), enabled, manualEnabled = manualEnabled)
}
