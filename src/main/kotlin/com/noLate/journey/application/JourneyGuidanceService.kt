package com.noLate.journey.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.journey.domain.*
import com.noLate.journey.infrastructure.JourneyGuidanceSessionRepository
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.liveactivity.application.ScheduleLiveActivityService
import com.noLate.notification.liveactivity.infrastructure.LiveActivityLifecycleIntentRepository
import com.noLate.schedule.application.service.ScheduleDepartureStatusService
import com.noLate.schedule.application.service.SchedulePushJobService
import com.noLate.schedule.application.service.ScheduleService
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class JourneyStartCommand(val requestId: String, val deviceId: String? = null)

data class JourneySessionDto(
    val sessionId: String,
    val scheduleId: String,
    val generation: Long,
    val routeRevision: Long,
    val sequence: Long,
    val phase: JourneyPhase,
    val activeLegIndex: Int,
    val currentStopIndex: Int?,
    val remainingStops: Int?,
    val legProgress: Double,
    val nextInstruction: String,
    val observedAt: Instant,
    val source: JourneyProgressSource = JourneyProgressSource.MANUAL,
    val startedAt: Instant,
    val expiresAt: Instant,
    val routeSnapshot: JsonNode,
    val routeLegs: JsonNode,
)

@Service
class JourneyGuidanceService(
    private val repository: JourneyGuidanceSessionRepository,
    private val memberRepository: MemberRepository,
    private val travelPlanService: ScheduleTravelPlanService,
    private val departureStatusService: ScheduleDepartureStatusService,
    private val pushJobService: SchedulePushJobService,
    private val scheduleService: ScheduleService,
    private val liveActivityService: ScheduleLiveActivityService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${journey.guidance.enabled:false}") private val enabled: Boolean = false,
    private val lifecycleIntentRepository: LiveActivityLifecycleIntentRepository? = null,
    // The legacy progress endpoint accepts client-supplied MANUAL events. Enabling the
    // automatic engine must not silently expose that separate departure path in production.
    @Value("\${journey.guidance.manual-enabled:false}") private val manualEnabled: Boolean = true,
) {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun start(memberId: Long, scheduleId: Long, loginGeneration: Long, command: JourneyStartCommand): JourneySessionDto {
        requireEnabled()
        val requestId = runCatching { UUID.fromString(command.requestId).toString() }.getOrNull()
            ?.takeIf { it == command.requestId.lowercase() }
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "requestId는 UUID 형식이어야 합니다.")
        if (command.deviceId != null && (command.deviceId.isBlank() || command.deviceId.length > 200)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "기기 식별자가 올바르지 않습니다.")
        }
        // Reuse sorted recipient member locks before schedule/job locks; depart-now notifies others.
        val fence = departureStatusService.lockNotificationActionMembers(memberId, scheduleId, loginGeneration)
        val plan = travelPlanService.getMyJourneyTravelPlanForStart(memberId, scheduleId)
        val route = JourneyRouteSnapshot.from(plan, objectMapper)
        repository.findBySessionId(requestId)?.let {
            if (it.memberId != memberId || it.scheduleId != scheduleId || it.loginSessionGeneration != loginGeneration) {
                throw BusinessException(ErrorCode.INVALID_STATE, "다른 요청에서 사용한 길안내 식별자입니다.")
            }
            return dto(it)
        }
        val previous = repository.findFirstByMemberIdAndScheduleIdOrderByGenerationDesc(memberId, scheduleId)
        val now = Instant.now(clock)
        if (previous != null && !previous.phase.terminal) {
            if (previous.loginSessionGeneration == loginGeneration && previous.routeFingerprint == route.fingerprint && now.isBefore(previous.expiresAt)) {
                return dto(previous)
            }
            previous.terminate(JourneyPhase.STOPPED, now, "SESSION_REPLACED")
        }
        if (lifecycleIntentRepository?.hasUnfinishedEndForMemberSchedule(memberId, scheduleId) == true) {
            throw BusinessException(ErrorCode.INVALID_STATE, "이전 알림을 정리하고 있습니다. 잠시 후 길안내를 시작해 주세요.")
        }
        val firstPhase = JourneyGuidanceSession.allowedPhases(route.legs, 0).first()
        val session = repository.saveAndFlush(JourneyGuidanceSession(
            sessionId = requestId, memberId = memberId, scheduleId = scheduleId,
            loginSessionGeneration = loginGeneration,
            deviceFingerprint = command.deviceId?.let(OpaquePushIdentifier::fingerprint),
            generation = (previous?.generation ?: 0) + 1,
            routeFingerprint = route.fingerprint, routeSnapshotJson = route.json, routeLegsJson = route.legsJson,
            phase = firstPhase, nextInstruction = JourneyGuidanceSession.instruction(firstPhase, route.legs.first()),
            startedAt = now, observedAt = now, expiresAt = now.plusSeconds(8 * 60 * 60),
        ))
        departureStatusService.markDeparted(memberId, scheduleId, fence)
        travelPlanService.disableNotification(memberId, scheduleId)
        pushJobService.cancelByScheduleIdAndMemberId(scheduleId, memberId, "DEPARTED", preserveLiveActivity = true)
        scheduleService.markDeparted(memberId, scheduleId)
        project(session)
        return dto(session)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun current(memberId: Long, scheduleId: Long, loginGeneration: Long): JourneySessionDto? {
        requireEnabled()
        lockActor(memberId, loginGeneration)
        // Authorized schedules with no journey yet have a usable null response even if no plan
        // exists. Private frozen route data still requires current travel authorization below.
        scheduleService.getScheduleDetail(memberId, scheduleId)
        val session = repository.findFirstByMemberIdAndScheduleIdOrderByGenerationDesc(memberId, scheduleId) ?: return null
        if (session.loginSessionGeneration != loginGeneration) return null
        val plan = travelPlanService.getTravelPlan(memberId, scheduleId, memberId)
        if (!session.phase.terminal) {
            val route = runCatching { JourneyRouteSnapshot.from(plan, objectMapper) }.getOrNull()
            if (!Instant.now(clock).isBefore(session.expiresAt) || route?.fingerprint != session.routeFingerprint) {
                session.terminate(JourneyPhase.STOPPED, Instant.now(clock), "ROUTE_OR_SESSION_EXPIRED")
                project(session)
            }
        }
        return dto(session)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun progress(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String, command: JourneyProgressCommand): JourneySessionDto {
        val session = authorizedSession(memberId, scheduleId, loginGeneration, sessionId)
        if (session.apply(command, legs(session), Instant.now(clock))) project(session)
        return dto(session)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun finish(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String, command: JourneyTerminalCommand, terminal: JourneyPhase): JourneySessionDto {
        val session = authorizedSession(memberId, scheduleId, loginGeneration, sessionId, requireCurrentRoute = terminal != JourneyPhase.STOPPED)
        // First terminal commit already stores END/tombstone durably. Replaying that request must
        // not touch a newer predeparture Activity which could now occupy the same device row.
        if (session.finish(command, terminal, legs(session), Instant.now(clock))) project(session)
        return dto(session)
    }

    /** Durable session is the retry source for pending APNs credentials/provider leases. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun reconcile(id: Long) {
        val ownerMemberId = repository.findOwnerMemberId(id) ?: return
        val member = memberRepository.findByIdForUpdate(ownerMemberId)
        val session = repository.findById(id).orElse(null) ?: return
        if (session.phase.terminal) return
        val now = Instant.now(clock)
        val route = if (member != null && !member.deleted && member.sessionGeneration == session.loginSessionGeneration) {
            try {
                travelPlanService.findMyJourneyTravelPlan(session.memberId, session.scheduleId)
                    ?.let { JourneyRouteSnapshot.from(it, objectMapper) }
            } catch (_: BusinessException) { null }
        } else null
        if (!enabled || !manualEnabled || route?.fingerprint != session.routeFingerprint || !now.isBefore(session.expiresAt)) {
            session.terminate(JourneyPhase.STOPPED, now, "ACCESS_ROUTE_OR_SESSION_EXPIRED")
        }
        project(session)
    }

    private fun authorizedSession(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String, requireCurrentRoute: Boolean = true): JourneyGuidanceSession {
        requireEnabled()
        lockActor(memberId, loginGeneration)
        val plan = travelPlanService.getTravelPlan(memberId, scheduleId, memberId)
        val session = repository.findBySessionId(sessionId)
            ?.takeIf { it.memberId == memberId && it.scheduleId == scheduleId && it.loginSessionGeneration == loginGeneration }
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        if (requireCurrentRoute && !session.phase.terminal && JourneyRouteSnapshot.from(plan, objectMapper).fingerprint != session.routeFingerprint) {
            throw BusinessException(ErrorCode.INVALID_STATE, "저장된 경로가 변경되었습니다. 길안내를 다시 시작해 주세요.")
        }
        return session
    }

    private fun lockActor(memberId: Long, generation: Long) {
        val member = memberRepository.findByIdForUpdate(memberId)
        if (member == null || member.deleted || member.sessionGeneration != generation) throw BusinessException(ErrorCode.INVALID_TOKEN)
    }

    private fun requireEnabled() {
        if (!enabled || !manualEnabled) throw BusinessException(ErrorCode.FEATURE_DISABLED)
    }

    private fun legs(session: JourneyGuidanceSession) = JourneyRouteSnapshot.describe(objectMapper.readTree(session.routeLegsJson))

    private fun project(session: JourneyGuidanceSession) {
        liveActivityService.projectJourney(session, legs(session))
    }

    private fun dto(session: JourneyGuidanceSession) = JourneySessionDto(
        sessionId = session.sessionId, scheduleId = session.scheduleId.toString(), generation = session.generation,
        routeRevision = session.routeRevision, sequence = session.sequence, phase = session.phase,
        activeLegIndex = session.activeLegIndex, currentStopIndex = session.currentStopIndex, remainingStops = session.remainingStops,
        legProgress = session.legProgress, nextInstruction = session.nextInstruction, observedAt = session.observedAt,
        startedAt = session.startedAt, expiresAt = session.expiresAt,
        routeSnapshot = objectMapper.readTree(session.routeSnapshotJson), routeLegs = objectMapper.readTree(session.routeLegsJson),
    )
}
