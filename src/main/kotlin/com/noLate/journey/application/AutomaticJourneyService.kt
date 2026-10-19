package com.noLate.journey.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.journey.domain.*
import com.noLate.journey.infrastructure.AutomaticJourneyPreparationRepository
import com.noLate.journey.infrastructure.JourneyGuidanceSessionRepository
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.ScheduleDepartureStatusService
import com.noLate.schedule.application.service.SchedulePushJobService
import com.noLate.schedule.application.service.ScheduleService
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class AutomaticJourneyPreparationDto(
    val sessionId: String, val scheduleId: String, val generation: Long, val routeRevision: Long,
    val sequence: Long, val state: AutomaticJourneyState, val clientOwnerId: String,
    val routeInputDigest: String, val preparedAt: Instant, val expiresAt: Instant,
    val execution: AutomaticJourneyExecution,
    val savedRouteValidated: Boolean = false,
)
data class AutomaticJourneyDepartureAck(
    val sessionId: String, val generation: Long, val routeRevision: Long, val eventKey: String,
    val sequence: Long = 1, val state: String = "ACTIVE", val accepted: Boolean = true,
    val duplicate: Boolean, val departedAt: Instant,
)
data class AutomaticJourneyValidationDto(
    val schemaVersion: Int = 1, val sessionId: String, val scheduleId: String,
    val generation: Long, val routeRevision: Long, val clientOwnerId: String,
    val routeInputDigest: String, val execution: AutomaticJourneyExecution,
    val state: AutomaticJourneyState, val validatedAt: Instant, val validUntil: Instant,
)
/** State-only receipt: never grants a location/observation lease or resumes a session. */
data class AutomaticJourneyReconciliationDto(
    val schemaVersion: Int = 1, val sessionId: String, val scheduleId: String,
    val generation: Long, val routeRevision: Long, val clientOwnerId: String,
    val routeInputDigest: String, val execution: AutomaticJourneyExecution,
    val state: AutomaticJourneyState, val checkedAt: Instant, val endedAt: Instant?,
)

@Service
class AutomaticJourneyService(
    private val repository: AutomaticJourneyPreparationRepository,
    private val memberRepository: MemberRepository,
    private val travelPlanService: ScheduleTravelPlanService,
    private val departureStatusService: ScheduleDepartureStatusService,
    private val pushJobService: SchedulePushJobService,
    private val scheduleService: ScheduleService,
    private val manualJourneys: JourneyGuidanceSessionRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${journey.guidance.enabled:false}") private val guidanceEnabled: Boolean = false,
    @Value("\${journey.automatic.enabled:false}") private val enabled: Boolean = false,
    @Value("\${journey.automatic.simulator-enabled:false}") private val simulatorEnabled: Boolean = false,
    @Value("\${journey.automatic.isolated-test-scope:false}") private val isolatedTestScope: Boolean = false,
    @Value("\${journey.automatic.device-enabled:false}") private val deviceEnabled: Boolean = false,
    @Value("\${spring.profiles.active:}") private val activeProfiles: String = "",
    @Value("\${journey.automatic.isolated-ttl-seconds:600}") private val isolatedTtlSeconds: Long = 600,
    private val walkingSnapshots: SavedJourneyWalkingSnapshotService? = null,
    @Value("\${journey.automatic.extended-navigation-enabled:false}") private val extendedNavigationEnabled: Boolean = false,
) {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun prepareSaved(memberId: Long, scheduleId: Long, loginGeneration: Long, command: com.noLate.journey.domain.AutomaticJourneySavedPrepareCommand): AutomaticJourneyPreparationDto {
        requireExecution(command.execution)
        requireUuid(command.requestId)
        requireUuid(command.clientOwnerId)
        requireDigest(command.routeInputDigest)
        lockActor(memberId, loginGeneration)
        if (command.routeInputJSON.toByteArray().size !in 1..1_048_576) invalid("안내 입력 크기가 올바르지 않습니다.")
        val mixed = runCatching { objectMapper.readTree(command.routeInputJSON).has("walkingSnapshot") }.getOrElse { false }
        val selected = if (mixed) travelPlanService.getMyJourneyWalkingTravelPlanForStart(memberId, scheduleId)
            else travelPlanService.getMyJourneyTravelPlanForStart(memberId, scheduleId) to 1L
        val (plan, revision) = selected
        if (plan.memberId != memberId || plan.scheduleId != scheduleId) invalid("저장 경로 소유자가 일치하지 않습니다.")
        val walking = if (mixed) walkingSnapshots?.currentForAdmission(memberId, scheduleId, loginGeneration,
            OwnedWalkingRoute(plan, revision)) ?: throw BusinessException(ErrorCode.FEATURE_DISABLED) else null
        SavedJourneyInputValidator(objectMapper).validate(plan, command.routeInputJSON, command.routeInputDigest, revision, walking)
        val fingerprint = JourneyRouteSnapshot.from(plan, objectMapper).fingerprint
        val result = prepareOwned(memberId, scheduleId, loginGeneration, AutomaticJourneyPrepareCommand(command.requestId, command.clientOwnerId, command.routeInputDigest, command.execution), walking)
        if (owned(result.sessionId, memberId, scheduleId, loginGeneration).routeFingerprint != fingerprint) invalid("저장 경로가 변경되었습니다.")
        return result.copy(savedRouteValidated = true)
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun prepare(memberId: Long, scheduleId: Long, loginGeneration: Long, command: AutomaticJourneyPrepareCommand): AutomaticJourneyPreparationDto {
        return prepareOwned(memberId, scheduleId, loginGeneration, command, null)
    }
    private fun prepareOwned(memberId: Long, scheduleId: Long, loginGeneration: Long, command: AutomaticJourneyPrepareCommand,
        walking: WalkingSnapshotContract?): AutomaticJourneyPreparationDto {
        requireExecution(command.execution)
        requireUuid(command.requestId)
        requireUuid(command.clientOwnerId)
        requireDigest(command.routeInputDigest)
        lockActor(memberId, loginGeneration)
        val route = JourneyRouteSnapshot.from(travelPlanService.getMyJourneyTravelPlanForStart(memberId, scheduleId), objectMapper)
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS)
        // v4 changes only the persisted session ceiling, never an observation/authentication lease.
        // Unvalidated/non-walking preparations keep their existing ten-minute ceiling.
        val extended = walking?.schemaVersion == 4
        if (extended && !extendedNavigationEnabled) throw BusinessException(ErrorCode.FEATURE_DISABLED)
        repository.findBySessionId(command.requestId)?.let { previous ->
            requireOwner(previous, memberId, scheduleId, loginGeneration)
            if (previous.clientOwnerId != command.clientOwnerId || previous.routeInputDigest != command.routeInputDigest ||
                previous.execution != command.execution || previous.routeFingerprint != route.fingerprint ||
                previous.walkingSnapshotId != walking?.snapshotId || previous.routeRevision != (walking?.routeRevision ?: 1L) ||
                previous.state != AutomaticJourneyState.ARMED || !now.isBefore(previous.expiresAt)) invalid("준비 요청을 재사용할 수 없습니다.")
            return dto(previous)
        }
        requireNoManualJourney(memberId, scheduleId)
        val previous = repository.findFirstByMemberIdAndScheduleIdOrderByGenerationDesc(memberId, scheduleId)
        if (previous != null) {
            if (previous.loginSessionGeneration != loginGeneration) previous.stop(now)
            previous.expire(now)
            if (previous.state == AutomaticJourneyState.ARMED || previous.state == AutomaticJourneyState.ACTIVE) {
                invalid("이미 준비 중이거나 활성화된 자동 출발 세션이 있습니다.")
            }
        }
        val nextGeneration = (previous?.generation ?: 0) + 1
        if (nextGeneration !in 1..9_007_199_254_740_991L) invalid("세션 세대 범위를 초과했습니다.")
        return dto(repository.saveAndFlush(AutomaticJourneyPreparation(
            sessionId = command.requestId, memberId = memberId, scheduleId = scheduleId,
            loginSessionGeneration = loginGeneration, clientOwnerId = command.clientOwnerId,
            routeInputDigest = command.routeInputDigest, routeFingerprint = route.fingerprint,
            generation = nextGeneration, execution = command.execution, routeRevision = walking?.routeRevision ?: 1,
            walkingSnapshotId = walking?.snapshotId,
            preparedAt = now, expiresAt = minOf((walking?.navigationExpiresAtMs ?: walking?.expiresAtMs)?.let(Instant::ofEpochMilli) ?: Instant.MAX,
                now.plusSeconds(if (command.execution == AutomaticJourneyExecution.SIMULATOR) {
                // requireExecution already enforces isolated non-production profile and both opt-ins.
                if (isolatedTtlSeconds !in 60..600) invalid("격리 시험 유효시간 범위를 벗어났습니다.")
                if (extended) 7200 else isolatedTtlSeconds
            } else if (extended) 7200 else 600)),
        )))
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun departure(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String, command: AutomaticJourneyDepartureCommand): AutomaticJourneyDepartureAck {
        requireExecution(command.execution)
        requireUuid(sessionId)
        requireUuid(command.clientOwnerId)
        requireDigest(command.routeInputDigest)
        // Same global lock order as depart-now: sorted recipient members, then schedule, then job.
        val fence = departureStatusService.lockNotificationActionMembers(memberId, scheduleId, loginGeneration)
        val plan = travelPlanService.getMyJourneyTravelPlanForStart(memberId, scheduleId)
        val preparation = owned(sessionId, memberId, scheduleId, loginGeneration)
        if (preparation.isDuplicate(command)) return ack(preparation, true)
        requireWalkingAdmission(preparation, memberId, scheduleId, loginGeneration)
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS)
        preparation.validateDeparture(command, now)
        val latest = repository.findFirstByMemberIdAndScheduleIdOrderByGenerationDesc(memberId, scheduleId)
        if (latest?.sessionId != preparation.sessionId) invalid("이전 자동 출발 세션입니다.")
        val route = JourneyRouteSnapshot.from(plan, objectMapper)
        if (route.fingerprint != preparation.routeFingerprint) invalid("저장된 개인 경로가 변경되었습니다.")
        requireNoManualJourney(memberId, scheduleId)
        // Every effect and the durable receipt commit in this one transaction. No manual journey
        // row or inTransit Activity is created; P2 owns ongoing navigation/Activity projection.
        val departure = departureStatusService.markDeparted(memberId, scheduleId, fence)
        travelPlanService.disableNotification(memberId, scheduleId)
        pushJobService.cancelByScheduleIdAndMemberId(scheduleId, memberId, "DEPARTED", preserveLiveActivity = false)
        scheduleService.markDeparted(memberId, scheduleId)
        preparation.activate(command, now, departure.departedAt?.truncatedTo(ChronoUnit.MILLIS) ?: now)
        repository.saveAndFlush(preparation)
        return ack(preparation, false)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun stop(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String, command: AutomaticJourneyStopCommand): AutomaticJourneyPreparationDto {
        requireEnabled()
        requireUuid(sessionId)
        requireUuid(command.clientOwnerId)
        requireDigest(command.routeInputDigest)
        lockActor(memberId, loginGeneration)
        travelPlanService.getTravelPlan(memberId, scheduleId, memberId)
        val preparation = owned(sessionId, memberId, scheduleId, loginGeneration)
        preparation.fence(command.generation, command.routeRevision, command.clientOwnerId, command.routeInputDigest)
        preparation.stop(Instant.now(clock))
        return dto(repository.saveAndFlush(preparation))
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun current(memberId: Long, scheduleId: Long, loginGeneration: Long, clientOwnerId: String): AutomaticJourneyPreparationDto? {
        requireEnabled()
        requireUuid(clientOwnerId)
        lockActor(memberId, loginGeneration)
        travelPlanService.getTravelPlan(memberId, scheduleId, memberId)
        val preparation = repository.findFirstByMemberIdAndScheduleIdOrderByGenerationDesc(memberId, scheduleId) ?: return null
        if (preparation.loginSessionGeneration != loginGeneration || preparation.clientOwnerId != clientOwnerId) return null
        preparation.expire(Instant.now(clock))
        return dto(preparation)
    }

    @Transactional(readOnly = true)
    fun reconcile(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String, command: AutomaticJourneyStopCommand): AutomaticJourneyReconciliationDto {
        requireEnabled()
        requireUuid(sessionId); requireUuid(command.clientOwnerId); requireDigest(command.routeInputDigest)
        val actor = memberRepository.findById(memberId).orElse(null)
        if (actor == null || actor.deleted || actor.sessionGeneration != loginGeneration) throw BusinessException(ErrorCode.INVALID_TOKEN)
        travelPlanService.getTravelPlan(memberId, scheduleId, memberId)
        val value = owned(sessionId, memberId, scheduleId, loginGeneration)
        requireExecution(value.execution)
        value.fence(command.generation, command.routeRevision, command.clientOwnerId, command.routeInputDigest)
        // Do not expire/stop an ACTIVE or ARMED session as a side effect of a restart read.
        // A terminal historical binding may be cleaned without touching a newer session.
        return AutomaticJourneyReconciliationDto(sessionId = sessionId, scheduleId = scheduleId.toString(),
            generation = value.generation, routeRevision = value.routeRevision, clientOwnerId = value.clientOwnerId,
            routeInputDigest = value.routeInputDigest, execution = value.execution, state = value.state,
            checkedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS), endedAt = value.endedAt)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun validate(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String, command: AutomaticJourneyStopCommand): AutomaticJourneyValidationDto {
        val value = observationFence(memberId, scheduleId, loginGeneration, sessionId, command)
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        return AutomaticJourneyValidationDto(sessionId = value.first.sessionId, scheduleId = scheduleId.toString(),
            generation = value.first.generation, routeRevision = value.first.routeRevision,
            clientOwnerId = value.first.clientOwnerId, routeInputDigest = value.first.routeInputDigest,
            execution = value.first.execution, state = value.first.state, validatedAt = now,
            validUntil = minOf(now.plusSeconds(30), value.first.expiresAt))
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun requireObservationFence(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String, command: com.noLate.journey.domain.JourneyBusBindingCommand): JourneyRouteSnapshot {
        val (_, route) = observationFence(memberId, scheduleId, loginGeneration, sessionId,
            AutomaticJourneyStopCommand(command.generation, command.routeRevision, command.clientOwnerId, command.routeInputDigest))
        val index = command.selectedBusLegIndex()
        if (index !in 1..29 || index % 2 != 1 || route.legs.getOrNull(0)?.kind != "WALK" ||
            route.legs.getOrNull(index - 1)?.kind != "WALK" || route.legs.getOrNull(index)?.kind != "BUS") {
            invalid("저장된 도보 다음 버스 구간만 관측할 수 있습니다.")
        }
        if (index > 1 && (route.legs.size !in 5..31 || route.legs.size % 2 != 1 ||
            index >= route.legs.lastIndex || route.legs.withIndex().any { (i, leg) ->
                if (i % 2 == 0) leg.kind != "WALK" else leg.kind !in setOf("BUS","SUBWAY")
            })) invalid("환승 구간 순서를 확인할 수 없습니다.")
        return route
    }

    private fun observationFence(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String,
        command: AutomaticJourneyStopCommand): Pair<AutomaticJourneyPreparation, JourneyRouteSnapshot> {
        requireEnabled()
        requireUuid(sessionId)
        requireUuid(command.clientOwnerId)
        requireDigest(command.routeInputDigest)
        val actor = memberRepository.findById(memberId).orElse(null)
        if (actor == null || actor.deleted || actor.sessionGeneration != loginGeneration) throw BusinessException(ErrorCode.INVALID_TOKEN)
        val plan = travelPlanService.getTravelPlan(memberId, scheduleId, memberId)
        val value = owned(sessionId, memberId, scheduleId, loginGeneration)
        requireWalkingAdmission(value, memberId, scheduleId, loginGeneration)
        val route = JourneyRouteSnapshot.from(plan, objectMapper)
        if (route.fingerprint != value.routeFingerprint) invalid("저장된 개인 경로가 변경되었습니다.")
        requireNoManualJourney(memberId, scheduleId)
        requireExecution(value.execution)
        value.fence(command.generation, command.routeRevision, command.clientOwnerId, command.routeInputDigest)
        val latest = repository.findFirstByMemberIdAndScheduleIdOrderByGenerationDesc(memberId, scheduleId)
        val now = clock.instant()
        if (latest?.sessionId != sessionId || value.state !in setOf(AutomaticJourneyState.ARMED, AutomaticJourneyState.ACTIVE) ||
            now < value.preparedAt || now >= value.expiresAt) invalid("유효한 자동 안내 관측 세션이 없습니다.")
        return value to route
    }

    /** Independent subway sidecar fence. Calling it never changes departure/ride state. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun requireActivityObservationFence(member:Long,schedule:Long,login:Long,session:String,
        command:AutomaticJourneyStopCommand,activeLegIndex:Int,phase:String,version:Int):JourneyRouteSnapshot {
        val (_,route)=observationFence(member,schedule,login,session,command)
        val transitIndex=if(activeLegIndex%2==1)activeLegIndex else if(phase=="FINAL_WALK")activeLegIndex-1 else activeLegIndex+1
        when(route.legs.getOrNull(transitIndex)?.kind) {
            "SUBWAY" -> {
                require(phase!="RIDING" || version==8)
                requireSubwayObservationFence(member,schedule,login,session,JourneySubwayObservationCommand(
                    command.generation,command.routeRevision,command.clientOwnerId,command.routeInputDigest,transitIndex))
            }
            "BUS" -> {
                require(version!=8)
                requireObservationFence(member,schedule,login,session,com.noLate.journey.domain.JourneyBusBindingCommand(
                    command.generation,command.routeRevision,command.clientOwnerId,command.routeInputDigest,transitIndex))
            }
            else -> invalid("실시간 현황의 대중교통 구간을 확인할 수 없습니다.")
        }
        return route
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun requireSubwayObservationFence(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String,
        command: JourneySubwayObservationCommand): JourneyRouteSnapshot {
        val (_,route)=observationFence(memberId,scheduleId,loginGeneration,sessionId,command.fence())
        val index=command.subwayLegIndex
        if (index !in 1..29 || index % 2 != 1 || route.legs.size !in 3..31 || route.legs.size % 2 != 1 ||
            index >= route.legs.lastIndex || route.legs.getOrNull(index)?.kind != "SUBWAY" ||
            route.legs.withIndex().any { (i,leg) -> if(i % 2 == 0) leg.kind != "WALK" else leg.kind !in setOf("BUS","SUBWAY") })
            invalid("저장된 도보 다음 지하철 구간을 확인할 수 없습니다.")
        return route
    }

    private fun requireWalkingAdmission(value: AutomaticJourneyPreparation, member: Long, schedule: Long, login: Long) {
        val id = value.walkingSnapshotId ?: return
        lockActor(member, login)
        val (plan, revision) = travelPlanService.getMyJourneyWalkingTravelPlanForStart(member, schedule)
        if (revision != value.routeRevision) invalid("도보 경로 버전이 변경되었습니다.")
        val contract = walkingSnapshots?.currentForAdmission(member, schedule, login, OwnedWalkingRoute(plan, revision), id, value.preparedAt)
            ?: throw BusinessException(ErrorCode.FEATURE_DISABLED)
        if (contract.schemaVersion == 4 && !extendedNavigationEnabled) throw BusinessException(ErrorCode.FEATURE_DISABLED)
    }

    private fun requireNoManualJourney(memberId: Long, scheduleId: Long) {
        if (manualJourneys.findAllByMemberIdAndScheduleIdAndEndedAtIsNull(memberId, scheduleId).isNotEmpty()) invalid("이미 활성화된 길안내가 있습니다.")
    }
    private fun owned(sessionId: String, memberId: Long, scheduleId: Long, loginGeneration: Long): AutomaticJourneyPreparation {
        val preparation = repository.findBySessionId(sessionId) ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        requireOwner(preparation, memberId, scheduleId, loginGeneration)
        return preparation
    }
    private fun requireOwner(preparation: AutomaticJourneyPreparation, memberId: Long, scheduleId: Long, loginGeneration: Long) {
        if (preparation.memberId != memberId || preparation.scheduleId != scheduleId || preparation.loginSessionGeneration != loginGeneration) {
            throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        }
    }
    private fun lockActor(memberId: Long, generation: Long) {
        val actor = memberRepository.findByIdForUpdate(memberId)
        if (actor == null || actor.deleted || actor.sessionGeneration != generation) throw BusinessException(ErrorCode.INVALID_TOKEN)
    }
    private fun requireEnabled() {
        if (!enabled || !guidanceEnabled) throw BusinessException(ErrorCode.FEATURE_DISABLED)
    }
    private fun requireExecution(execution: AutomaticJourneyExecution) {
        requireEnabled()
        if (execution == AutomaticJourneyExecution.DEVICE) {
            if (!deviceEnabled) throw BusinessException(ErrorCode.FEATURE_DISABLED)
        } else {
            val profiles = activeProfiles.split(',').map(String::trim)
            if (!simulatorEnabled || !isolatedTestScope || profiles.none { it in setOf("test", "journey-isolated-fixture") } ||
                profiles.any { it.equals("prod", true) || it.equals("production", true) }) throw BusinessException(ErrorCode.FEATURE_DISABLED)
        }
    }
    private fun requireUuid(value: String) {
        if (runCatching { UUID.fromString(value).toString() }.getOrNull() != value) throw BusinessException(ErrorCode.INVALID_INPUT, "식별자는 소문자 UUID여야 합니다.")
    }
    private fun requireDigest(value: String) {
        if (!value.matches(Regex("^[0-9a-f]{64}$"))) throw BusinessException(ErrorCode.INVALID_INPUT, "경로 digest 형식이 올바르지 않습니다.")
    }
    private fun dto(value: AutomaticJourneyPreparation) = AutomaticJourneyPreparationDto(
        value.sessionId, value.scheduleId.toString(), value.generation, value.routeRevision,
        value.sequence, value.state, value.clientOwnerId, value.routeInputDigest, value.preparedAt, value.expiresAt, value.execution,
    )
    private fun ack(value: AutomaticJourneyPreparation, duplicate: Boolean) = AutomaticJourneyDepartureAck(
        value.sessionId, value.generation, value.routeRevision, requireNotNull(value.acceptedEventKey),
        duplicate = duplicate, departedAt = requireNotNull(value.departedAt),
    )
    private fun invalid(message: String): Nothing = throw BusinessException(ErrorCode.INVALID_STATE, message)
}

@Service
class AutomaticJourneyRetention(private val repository: AutomaticJourneyPreparationRepository, private val clock: Clock = Clock.systemUTC()) {
    @Scheduled(fixedDelayString = "\${journey.automatic.retention-delay-ms:3600000}")
    @Transactional
    fun cleanExpiredHistory() = repository.deleteAllByRetentionExpiresAtBefore(Instant.now(clock))
}
