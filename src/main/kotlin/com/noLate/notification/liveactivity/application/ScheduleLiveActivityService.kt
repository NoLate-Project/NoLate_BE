package com.noLate.notification.liveactivity.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.annotation.JsonInclude
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushLogicalEventKey
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.application.service.PushTokenDispatchLeaseActiveException
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.liveactivity.domain.LiveActivityDelivery
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryOperation
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryStatus
import com.noLate.notification.liveactivity.domain.LiveActivityAppearance
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivity
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivityState
import com.noLate.notification.liveactivity.infrastructure.LiveActivityDeliveryRepository
import com.noLate.notification.liveactivity.infrastructure.LiveActivityStartTokenRepository
import com.noLate.notification.liveactivity.infrastructure.ScheduleLiveActivityRepository
import com.noLate.schedule.application.RouteDisplaySegment
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.journey.domain.JourneyGuidanceSession
import com.noLate.journey.domain.JourneyLeg
import com.noLate.journey.domain.JourneyPhase
import com.noLate.journey.infrastructure.JourneyGuidanceSessionRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

data class ScheduleLiveActivityEtaProjection(
    val memberId: Long,
    val scheduleId: Long,
    val notificationGeneration: Long,
    val checkCount: Int,
    val evaluatedAt: Instant,
    val scheduleTitle: String?,
    val destinationName: String?,
    val scheduleStartAt: Instant,
    val travelMinutes: Int,
    val recommendedDepartureAt: Instant,
    val predictedArrivalAt: Instant?,
    val firstBoardingWaitMinutes: Int?,
    val etaSource: TrafficSource,
    val etaStale: Boolean,
    val routeSegments: List<RouteDisplaySegment>,
    val staleAt: Instant?,
    val journey: JourneyLiveActivityWire? = null,
    val journeyExpiresAt: Instant? = null,
)

data class JourneyLiveActivityWire(
    val sessionId: String,
    val generation: Long,
    val routeRevision: Long,
    val sequence: Long,
    val phase: String,
    val activeLegIndex: Int,
    val currentStopIndex: Int?,
    val remainingStops: Int?,
    val legProgress: Double,
    val nextInstruction: String,
    val observedAtEpochSeconds: Long,
    val overallProgress: Double,
)

/** Exact Codable wire contract shared with NoLateDepartureActivityAttributes.swift. */
data class NoLateDepartureAttributesWire(
    val scheduleId: String,
    val recipientMemberId: Long,
    val generation: Long,
    val scheduleTitle: String,
    val destinationName: String,
    val scheduleStartAt: String,
    val actionEventKey: String,
    val logicalEventKey: String?,
)

data class NoLateDepartureContentStateWire(
    val revision: Long,
    val travelMinutes: Int,
    val firstWaitMinutes: Int?,
    val predictedArrivalEpochSeconds: Long?,
    val recommendedDepartureEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
    val actionExpiresAtEpochSeconds: Long,
    val status: String,
    val routeSegments: List<RouteDisplaySegment>,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val appearance: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val journey: JourneyLiveActivityWire? = null,
)

/**
 * Activity rows locked before the FCM row during a same-device invalid-token replacement.
 * Applying this plan in the same transaction preserves the global member -> activity -> FCM ->
 * delivery lock order and prevents an END tombstone from pinning a retired route forever.
 */
data class SameDeviceFcmEndHandoffPlan(
    val memberId: Long,
    val deviceFingerprint: String,
    val oldPushDeviceTokenIds: Set<Long>,
    val replaceableExistingPushDeviceTokenIds: Set<Long>,
    val activities: List<ScheduleLiveActivity>,
)

/** Same-owner remote Activities whose provider-invalid FCM row was physically deleted. */
data class SameDeviceFcmOrphanRecoveryPlan(
    val memberId: Long,
    val deviceFingerprint: String,
    val missingPushDeviceTokenIds: Set<Long>,
    val activities: List<ScheduleLiveActivity>,
)

@Service
class ScheduleLiveActivityService(
    private val memberRepository: MemberRepository,
    private val deviceTokenRepository: NotificationDeviceTokenRepository,
    private val startTokenRepository: LiveActivityStartTokenRepository,
    private val activityRepository: ScheduleLiveActivityRepository,
    private val deliveryRepository: LiveActivityDeliveryRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${notification.live-activity.start-lead-minutes:15}")
    private val startLeadMinutes: Long = 15,
    @Value("\${schedule.push.delivery-grace-minutes:10}")
    private val deliveryGraceMinutes: Long = 10,
    @Value("\${notification.live-activity.retirement-end-hold-seconds:600}")
    private val retirementEndHoldSeconds: Long = 600,
    @Value("\${notification.live-activity.terminal-retention-hours:24}")
    private val terminalRetentionHours: Long = 24,
    private val pushJobRepository: SchedulePushJobRepository? = null,
    private val applicationEventPublisher: ApplicationEventPublisher? = null,
    private val journeyRepository: JourneyGuidanceSessionRepository? = null,
    private val scheduleRepository: ScheduleRepository? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(startLeadMinutes in 1..60)
        require(deliveryGraceMinutes in 1..60)
        require(retirementEndHoldSeconds in 61..3_600)
        require(terminalRetentionHours in 1..720)
    }

    /** Non-locking owner snapshot used before NotificationTokenWriter's sorted member fence. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    fun findLiveActivityHandoffOwnerMemberIdsForRegistration(
        deviceFingerprint: String,
    ): List<Long> =
        activityRepository.findHandoffOwnerMemberIdsByDeviceFingerprint(
            deviceFingerprint,
            FCM_HANDOFF_OWNER_STATES,
        )

    /** Runs after the ETA-job commit and freezes one per-device desired state independently. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun projectEta(projection: ScheduleLiveActivityEtaProjection) {
        if (projection.memberId <= 0 || projection.scheduleId <= 0) return
        if (memberRepository.findByIdForUpdate(projection.memberId)?.deleted != false) return
        if (journeyRepository?.findAllByMemberIdAndScheduleIdAndEndedAtIsNull(projection.memberId, projection.scheduleId)?.isNotEmpty() == true) return
        // The worker projects only after its independent source commit. Re-read that monotonic
        // source under the same member->job lock order so a later cancel/delete/permission END
        // cannot be followed by this stale projection resurrecting a remote Activity.
        if (pushJobRepository != null) {
            val currentJob = pushJobRepository.findByScheduleIdAndMemberIdForUpdate(
                projection.scheduleId,
                projection.memberId,
            ) ?: return
            val checkStillCurrent = currentJob.checkCount == projection.checkCount ||
                currentJob.checkCount == projection.checkCount + 1
            if (
                currentJob.status != SchedulePushJobStatus.ACTIVE ||
                currentJob.notificationGeneration != projection.notificationGeneration ||
                !checkStillCurrent
            ) return
        }
        val startTokens = startTokenRepository
            .findAllByMemberIdAndActivityTypeAndActiveTrueAndRetirementRequestedFalseOrderByIdAsc(
                projection.memberId,
                NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
        )
        startTokens.forEach { snapshot ->
            // member row above serializes every resource owned by this recipient. Within that
            // fence keep the same order as dispatch: activity -> FCM -> start token -> delivery.
            val activity = activityRepository.findByScheduleMemberDeviceForUpdate(
                projection.scheduleId,
                projection.memberId,
                snapshot.deviceFingerprint,
            )
            val fcm = deviceTokenRepository.findByIdForUpdate(snapshot.pushDeviceTokenId)
                ?.takeIf {
                    it.memberId == projection.memberId &&
                        it.deviceFingerprint == snapshot.deviceFingerprint &&
                        it.platform == PushPlatform.IOS &&
                        !it.retirementRequested
                }
                ?: return@forEach
            val token = snapshot.id?.let(startTokenRepository::findByIdForUpdate)
                ?.takeIf {
                    it.pushDeviceTokenId == fcm.id && it.active && !it.retirementRequested
                }
                ?: return@forEach
            projectDevice(projection, token, fcm, activity)
        }
    }

    private fun projectDevice(
        projection: ScheduleLiveActivityEtaProjection,
        startToken: com.noLate.notification.liveactivity.domain.LiveActivityStartToken,
        fcm: com.noLate.notification.domain.NotificationDeviceToken,
        lockedActivity: ScheduleLiveActivity?,
    ) {
        val now = projection.evaluatedAt
        val expiresAt = projection.journeyExpiresAt ?: projection.scheduleStartAt.plus(deliveryGraceMinutes, ChronoUnit.MINUTES)
        if (!now.isBefore(expiresAt)) return
        var activity = lockedActivity
        // Provider I/O owns immutable state/token snapshots outside the DB transaction. A newer
        // ETA can wait for the next check; mutating a START in flight could create two Activities.
        // Lease TTL makes the row eligible for stale recovery; it does not prove the provider
        // attempt finished. Preserve desired state until recovery clears the lease identity.
        if (activity?.dispatchLeaseId != null) return
        if (
            activity == null &&
            projection.journey == null &&
            now.isBefore(projection.recommendedDepartureAt.minus(startLeadMinutes, ChronoUnit.MINUTES))
        ) {
            return
        }

        val actionEventKey = PushLogicalEventKey.deterministic(
            projection.memberId,
            "live-activity-depart-now:${projection.scheduleId}:g${projection.notificationGeneration}",
        )
        val attributes = NoLateDepartureAttributesWire(
            scheduleId = projection.scheduleId.toString(),
            recipientMemberId = projection.memberId,
            generation = projection.notificationGeneration,
            scheduleTitle = projection.scheduleTitle.canonicalText(60, "일정"),
            destinationName = projection.destinationName.canonicalText(40, "목적지"),
            scheduleStartAt = projection.scheduleStartAt.toString(),
            // This is already an opaque SHA-256 key. The canonical input is never persisted.
            actionEventKey = actionEventKey,
            logicalEventKey = actionEventKey,
        )
        val semanticContent = semanticContent(
            projection = projection,
            actionEventKey = actionEventKey,
            actionExpiresAt = expiresAt,
            appearance = startToken.appearance,
        )
        val semanticJson = canonicalJson(semanticContent.copy(revision = 0))
        val fingerprint = OpaquePushIdentifier.fingerprint(semanticJson)
        val attributesJson = canonicalJson(attributes)

        if (activity == null) {
            val contentJson = contentJson(semanticContent, revision = 1)
            activity = activityRepository.saveAndFlush(
                ScheduleLiveActivity(
                    memberId = projection.memberId,
                    scheduleId = projection.scheduleId,
                    pushDeviceTokenId = requireNotNull(fcm.id),
                    boundFcmOwnershipVersion = fcm.ownershipVersion,
                    deviceFingerprint = startToken.deviceFingerprint,
                    activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
                    startTokenId = requireNotNull(startToken.id),
                    startTokenFingerprint = startToken.tokenFingerprint,
                    startTokenGeneration = startToken.tokenGeneration,
                    sourceNotificationGeneration = projection.notificationGeneration,
                    sourceCheckCount = projection.checkCount,
                    contentRevision = 1,
                    contentFingerprint = fingerprint,
                    attributesJson = attributesJson,
                    contentStateJson = contentJson,
                    staleAt = projection.staleAt,
                    expiresAt = expiresAt,
                )
            )
            enqueue(
                activity,
                fcm,
                startToken.pushToStartToken,
                startToken.tokenFingerprint,
                LiveActivityDeliveryOperation.START,
                now,
            )
            return
        }

        if (projection.notificationGeneration < activity.sourceNotificationGeneration) return
        if (activity.requiresGenerationRestart(projection.notificationGeneration)) {
            when (activity.state) {
                // The old remote instance is the recovery authority until its END succeeds or a
                // client lifecycle ACK proves termination. Reusing this row earlier would erase
                // the old activity/update credential and make UNREGISTERED or token rotation
                // impossible to recover. A later ETA projection starts the new generation.
                ScheduleLiveActivityState.END_PENDING -> return
                ScheduleLiveActivityState.START_SENT,
                ScheduleLiveActivityState.TOKEN_PENDING,
                -> {
                    if (activity.updateToken == null) {
                        markWaitingForLateEndToken(activity, fcm, now, "GENERATION_REPLACED")
                    } else {
                        beginOldGenerationEnd(activity, fcm, now)
                    }
                    return
                }
                ScheduleLiveActivityState.ACTIVE -> {
                    beginOldGenerationEnd(activity, fcm, now)
                    return
                }
                // START_PENDING has no accepted provider instance (and the method already fenced
                // an in-flight lease). ENDED/INVALID are likewise safe to reuse immediately.
                ScheduleLiveActivityState.START_PENDING,
                ScheduleLiveActivityState.ENDED,
                ScheduleLiveActivityState.INVALID,
                -> Unit
            }
            val nextRevision = Math.addExact(activity.contentRevision, 1)
            activity.resetForGeneration(
                pushDeviceTokenId = requireNotNull(fcm.id),
                startTokenId = requireNotNull(startToken.id),
                startTokenFingerprint = startToken.tokenFingerprint,
                startTokenGeneration = startToken.tokenGeneration,
                notificationGeneration = projection.notificationGeneration,
                checkCount = projection.checkCount,
                fingerprint = fingerprint,
                attributesJson = attributesJson,
                contentJson = contentJson(semanticContent, nextRevision),
                staleAt = projection.staleAt,
                expiresAt = expiresAt,
                fcmOwnershipVersion = fcm.ownershipVersion,
            )
            supersedeOpen(activity, now) { it.operation != LiveActivityDeliveryOperation.END }
            enqueue(
                activity,
                fcm,
                startToken.pushToStartToken,
                startToken.tokenFingerprint,
                LiveActivityDeliveryOperation.START,
                now,
            )
            return
        }
        if (activity.state in TERMINAL_OR_ENDING_STATES) return
        if (!activity.sourceIsNewer(projection.notificationGeneration, projection.checkCount)) return
        val changed = activity.contentFingerprint != fingerprint
        val nextRevision = if (changed) Math.addExact(activity.contentRevision, 1) else activity.contentRevision
        val refreshed = activity.refreshProjection(
            pushDeviceTokenId = requireNotNull(fcm.id),
            startTokenId = requireNotNull(startToken.id),
            startTokenFingerprint = startToken.tokenFingerprint,
            startTokenGeneration = startToken.tokenGeneration,
            notificationGeneration = projection.notificationGeneration,
            checkCount = projection.checkCount,
            fingerprint = fingerprint,
            contentJson = contentJson(semanticContent, nextRevision),
            staleAt = projection.staleAt,
            expiresAt = expiresAt,
            fcmOwnershipVersion = fcm.ownershipVersion,
        )
        if (!refreshed) return
        when (activity.state) {
            ScheduleLiveActivityState.START_PENDING -> {
                supersedeOpen(activity, now) { it.operation == LiveActivityDeliveryOperation.START }
                enqueue(
                    activity,
                    fcm,
                    startToken.pushToStartToken,
                    startToken.tokenFingerprint,
                    LiveActivityDeliveryOperation.START,
                    now,
                )
            }
            ScheduleLiveActivityState.ACTIVE -> {
                val updateToken = activity.updateToken ?: return
                val updateFingerprint = activity.updateTokenFingerprint ?: return
                supersedeOpen(activity, now) { it.operation == LiveActivityDeliveryOperation.UPDATE }
                enqueue(
                    activity,
                    fcm,
                    updateToken,
                    updateFingerprint,
                    LiveActivityDeliveryOperation.UPDATE,
                    now,
                )
            }
            else -> Unit // START_SENT waits for the per-activity update token callback.
        }
    }

    /**
     * The caller owns the member/session fence and has validated the frozen personal route.
     * Journey starts reuse the normal credential/outbox/lease machinery, but do not depend on an
     * ACTIVE predeparture job or its lead-time window. Only the opted-in requesting device is used.
     */
    @Transactional
    fun projectJourney(session: JourneyGuidanceSession, legs: List<JourneyLeg>) {
        val device = session.deviceFingerprint ?: return
        if (memberRepository.findByIdForUpdate(session.memberId)?.deleted != false) return
        val now = Instant.now(clock)
        val wire = JourneyLiveActivityWire(
            session.sessionId, session.generation, session.routeRevision, session.sequence, session.phase.name,
            session.activeLegIndex, session.currentStopIndex, session.remainingStops, session.legProgress,
            session.nextInstruction, session.observedAt.epochSecond,
            if (session.phase == JourneyPhase.ARRIVED) 1.0 else
                ((session.activeLegIndex + session.legProgress) / legs.size.coerceAtLeast(1)).coerceIn(0.0, 1.0),
        )
        if (session.phase.terminal) {
            val activity = activityRepository.findByScheduleMemberDeviceForUpdate(session.scheduleId, session.memberId, device) ?: return
            val previous = decodeContent(activity.contentStateJson)
            // The first projection may still be deferred behind a predeparture provider lease.
            // That same device/schedule Activity must still be ended when the user stops; only a
            // different already-projected journey is protected from this terminal request.
            if (previous.journey != null && previous.journey.sessionId != session.sessionId) return
            // END already handles in-flight UPDATE snapshots and late update-token callbacks.
            val content = previous.copy(journey = wire)
            activity.contentStateJson = canonicalJson(content)
            endActivity(activity, now, if (session.phase == JourneyPhase.ARRIVED) "JOURNEY_ARRIVED" else "JOURNEY_STOPPED")
            return
        }
        val schedule = scheduleRepository?.findById(session.scheduleId)?.orElse(null)
        val tokens = startTokenRepository.findAllByMemberIdAndActivityTypeAndActiveTrueAndRetirementRequestedFalseOrderByIdAsc(
            session.memberId, NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
        ).filter { it.deviceFingerprint == device }
        tokens.forEach { snapshot ->
            val activity = activityRepository.findByScheduleMemberDeviceForUpdate(session.scheduleId, session.memberId, device)
            val priorJourney = activity?.let { decodeContent(it.contentStateJson).journey }
            // An END in progress must finish before reusing the unique schedule/member/device row.
            if (activity?.state == ScheduleLiveActivityState.END_PENDING) return@forEach
            val fcm = deviceTokenRepository.findByIdForUpdate(snapshot.pushDeviceTokenId)
                ?.takeIf { it.memberId == session.memberId && it.deviceFingerprint == device && it.platform == PushPlatform.IOS && !it.retirementRequested }
                ?: return@forEach
            val token = snapshot.id?.let(startTokenRepository::findByIdForUpdate)
                ?.takeIf { it.pushDeviceTokenId == fcm.id && it.active && !it.retirementRequested }
                ?: return@forEach
            val restarting = activity?.state in setOf(ScheduleLiveActivityState.ENDED, ScheduleLiveActivityState.INVALID)
            // Provider failure or an ended instance is not authorization to repeatedly create
            // Activities. A fresh explicit session, not the periodic retry, can start a new one.
            if (restarting && priorJourney?.sessionId == session.sessionId) return@forEach
            val notificationGeneration = when {
                activity == null -> 0L
                restarting -> activity.sourceNotificationGeneration + 1
                else -> activity.sourceNotificationGeneration
            }
            if (priorJourney != null && priorJourney.generation > session.generation) return@forEach
            val segments = legs.mapIndexed { index, leg ->
                RouteDisplaySegment(
                    leg.kind.lowercase(), (leg.lineName ?: if (leg.kind == "WALK") "도보" else "대중교통").take(18),
                    when (leg.kind) { "BUS" -> "#2979FF"; "SUBWAY" -> "#00B140"; else -> "#9CA3AF" }, index,
                )
            }.filterIndexed { index, _ -> index >= (session.activeLegIndex - 1).coerceAtLeast(0) }.take(5)
            projectDevice(ScheduleLiveActivityEtaProjection(
                memberId = session.memberId, scheduleId = session.scheduleId,
                notificationGeneration = notificationGeneration,
                checkCount = if (restarting) 0 else (activity?.sourceCheckCount ?: -1) + 1,
                evaluatedAt = session.observedAt, scheduleTitle = schedule?.title,
                destinationName = schedule?.route?.destinationName,
                scheduleStartAt = schedule?.startAt ?: session.expiresAt,
                travelMinutes = objectMapper.readTree(session.routeLegsJson)
                    .sumOf { it.path("durationMinutes").asInt(0).coerceAtLeast(0) }.coerceAtLeast(1),
                recommendedDepartureAt = session.startedAt, predictedArrivalAt = null,
                firstBoardingWaitMinutes = null, etaSource = TrafficSource.SELECTED_ROUTE,
                etaStale = false, routeSegments = segments,
                staleAt = session.observedAt.plusSeconds(90), journey = wire, journeyExpiresAt = session.expiresAt,
            ), token, fcm, activity)
        }
    }

    /** Joins token binding when called there, so the ActivityKit token and its delivery are atomic. */
    @Transactional
    fun enqueueCurrentUpdate(activityRowId: Long) {
        val snapshot = activityRepository.findById(activityRowId).orElse(null) ?: return
        val member = memberRepository.findByIdForUpdate(snapshot.memberId) ?: return
        val activity = activityRepository.findByIdForUpdate(activityRowId) ?: return
        if (member.deleted && activity.state != ScheduleLiveActivityState.END_PENDING) return
        if (activity.state !in setOf(
                ScheduleLiveActivityState.ACTIVE,
                ScheduleLiveActivityState.END_PENDING,
            )
        ) return
        val fcm = currentOwnedFcm(
            activity,
            allowReservedRetirementEnd = activity.state == ScheduleLiveActivityState.END_PENDING,
        ) ?: return
        val token = activity.updateToken ?: return
        val fingerprint = activity.updateTokenFingerprint ?: return
        val now = Instant.now(clock)
        if (activity.state == ScheduleLiveActivityState.END_PENDING) {
            if (hasExactDelivery(
                    activity,
                    fcm,
                    fingerprint,
                    LiveActivityDeliveryOperation.END,
                )
            ) return
            supersedeOpen(activity, now) { it.operation != LiveActivityDeliveryOperation.END }
            enqueue(
                activity,
                fcm,
                token,
                fingerprint,
                LiveActivityDeliveryOperation.END,
                now,
                requireNotNull(activity.activityId),
                reserveFcmForRetirement = fcm.retirementRequested,
            )
        } else {
            if (hasExactDelivery(
                    activity,
                    fcm,
                    fingerprint,
                    LiveActivityDeliveryOperation.UPDATE,
                )
            ) return
            supersedeOpen(activity, now) { it.operation == LiveActivityDeliveryOperation.UPDATE }
            enqueue(activity, fcm, token, fingerprint, LiveActivityDeliveryOperation.UPDATE, now)
        }
    }

    /** Joins start-token registration so credential rotation and recovery commit atomically. */
    @Transactional
    fun reconcileStartToken(memberId: Long, deviceFingerprint: String) {
        if (memberRepository.findByIdForUpdate(memberId)?.deleted != false) return
        val tokenSnapshot = startTokenRepository
            .findAllByMemberIdAndActivityTypeAndActiveTrueAndRetirementRequestedFalseOrderByIdAsc(
                memberId,
                NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            )
            .singleOrNull { it.deviceFingerprint == deviceFingerprint }
            ?: return
        val activities = activityRepository.findAllByMemberDeviceForUpdate(memberId, deviceFingerprint)
        val fcm = deviceTokenRepository.findByIdForUpdate(tokenSnapshot.pushDeviceTokenId)
            ?.takeIf {
                it.memberId == memberId && it.deviceFingerprint == deviceFingerprint &&
                    it.platform == PushPlatform.IOS && !it.retirementRequested
            } ?: return
        val startToken = tokenSnapshot.id?.let(startTokenRepository::findByIdForUpdate)
            ?.takeIf {
                it.pushDeviceTokenId == fcm.id && it.active && !it.retirementRequested
            } ?: return
        val now = Instant.now(clock)
        activities
            .filter {
                now.isBefore(it.expiresAt) &&
                    (
                        it.state == ScheduleLiveActivityState.START_PENDING ||
                            (
                                it.state == ScheduleLiveActivityState.INVALID &&
                                    it.activityId == null &&
                                    it.updateToken == null &&
                                    it.startTokenFingerprint != startToken.tokenFingerprint
                                )
                        )
            }
            .forEach { activity ->
                if (activity.state == ScheduleLiveActivityState.INVALID) {
                    val nextRevision = Math.addExact(activity.contentRevision, 1)
                    val content = canonicalJson(
                        decodeContent(activity.contentStateJson).copy(revision = nextRevision)
                    )
                    activity.retryInvalidStartCredential(
                        pushDeviceTokenId = requireNotNull(fcm.id),
                        startTokenId = requireNotNull(startToken.id),
                        startTokenFingerprint = startToken.tokenFingerprint,
                        startTokenGeneration = startToken.tokenGeneration,
                        contentJson = content,
                        fcmOwnershipVersion = fcm.ownershipVersion,
                    )
                } else {
                    activity.startTokenId = startToken.id
                    activity.startTokenFingerprint = startToken.tokenFingerprint
                    activity.startTokenGeneration = startToken.tokenGeneration
                    activity.bindFcmRoute(requireNotNull(fcm.id), fcm.ownershipVersion)
                }
                if (hasExactDelivery(
                        activity,
                        fcm,
                        startToken.tokenFingerprint,
                        LiveActivityDeliveryOperation.START,
                    )
                ) return@forEach
                supersedeOpen(activity, now) { it.operation == LiveActivityDeliveryOperation.START }
                enqueue(
                    activity,
                    fcm,
                    startToken.pushToStartToken,
                    startToken.tokenFingerprint,
                    LiveActivityDeliveryOperation.START,
                    now,
                )
            }
    }

    /**
     * Event identity includes the FCM ownership epoch. Presence of any exact immutable attempt is
     * a no-op (including FAILED/EXHAUSTED/INVALID), preserving backoff and fail-closed decisions;
     * a refreshed same-row FCM route has a different key and is materialized once.
     */
    private fun hasExactDelivery(
        activity: ScheduleLiveActivity,
        fcm: com.noLate.notification.domain.NotificationDeviceToken,
        activityTokenFingerprint: String,
        operation: LiveActivityDeliveryOperation,
    ): Boolean {
        val rowId = activity.id ?: return false
        val fcmId = fcm.id ?: return false
        val eventKey = liveActivityDeliveryEventKey(
            liveActivityId = rowId,
            operation = operation,
            revision = activity.contentRevision,
            activityTokenFingerprint = activityTokenFingerprint,
            activityTokenGeneration = if (operation == LiveActivityDeliveryOperation.START) {
                requireNotNull(activity.startTokenGeneration)
            } else {
                activity.updateTokenGeneration
            },
            pushDeviceTokenId = fcmId,
            fcmOwnershipVersion = fcm.ownershipVersion,
        )
        val existing = deliveryRepository.findByEventKeyForUpdate(eventKey) ?: return false
        if (existing.status in DISPATCHABLE_DELIVERY_STATUSES) {
            applicationEventPublisher?.publishEvent(
                LiveActivityDispatchRequested(existing.availableAt),
            )
        }
        return true
    }

    @Transactional
    fun endForMemberSchedule(memberId: Long, scheduleId: Long, reason: String) {
        memberRepository.findByIdForUpdate(memberId) ?: return
        if (reason == "DEPARTED" && journeyRepository?.findAllByMemberIdAndScheduleIdAndEndedAtIsNull(memberId, scheduleId)?.isNotEmpty() == true) return
        journeyRepository?.findAllByMemberIdAndScheduleIdAndEndedAtIsNull(memberId, scheduleId)
            ?.forEach { it.terminate(JourneyPhase.STOPPED, Instant.now(clock), reason) }
        activityRepository.findAllByMemberScheduleForUpdate(memberId, scheduleId)
            .forEach { endActivity(it, Instant.now(clock), reason) }
    }

    @Transactional
    fun endForMemberDevice(memberId: Long, deviceFingerprint: String, reason: String) {
        memberRepository.findByIdForUpdate(memberId) ?: return
        activityRepository.findAllByMemberDeviceForUpdate(memberId, deviceFingerprint)
            .forEach { endActivity(it, Instant.now(clock), reason) }
    }

    @Transactional
    fun endAllForSchedule(scheduleId: Long, reason: String) {
        val memberIds = activityRepository.findMemberIdsByScheduleId(scheduleId).sorted()
        if (memberIds.isNotEmpty()) memberRepository.findAllByIdsForUpdate(memberIds)
        memberIds.forEach { memberId ->
            journeyRepository?.findAllByMemberIdAndScheduleIdAndEndedAtIsNull(memberId, scheduleId)
                ?.forEach { it.terminate(JourneyPhase.STOPPED, Instant.now(clock), reason) }
        }
        activityRepository.findAllByScheduleIdForUpdate(scheduleId)
            .forEach { endActivity(it, Instant.now(clock), reason) }
    }

    @Transactional
    fun endAllForMember(memberId: Long, reason: String) {
        memberRepository.findByIdForUpdate(memberId) ?: return
        journeyRepository?.findAllByMemberIdAndEndedAtIsNull(memberId)
            ?.forEach { it.terminate(JourneyPhase.STOPPED, Instant.now(clock), reason) }
        activityRepository.findAllByMemberIdForUpdate(memberId)
            .forEach { endActivity(it, Instant.now(clock), reason) }
    }

    /**
     * Joins logout/session invalidation before ordinary FCM-token retirement. END rows reserve
     * their exact frozen FCM ownership route, so retirement keeps it until the END provider call.
     */
    @Transactional
    fun prepareEndAllForMemberBeforeTokenRetirement(memberId: Long, reason: String) {
        val member = memberRepository.findByIdForUpdate(memberId) ?: return
        val now = Instant.now(clock)
        val retiredSessionGeneration = member.sessionGeneration
            .takeIf { it > 0 }
            ?.minus(1)
        activityRepository.findAllByMemberIdForUpdate(memberId).forEach { activity ->
            endActivity(activity, now, reason, reserveFcmForRetirement = true)
            if (
                retiredSessionGeneration != null &&
                activity.state == ScheduleLiveActivityState.END_PENDING
            ) {
                // compareAndLogout advances the member generation before this call. Persist the
                // exact retired session on the tombstone so a client END ACK racing physical FCM
                // deletion remains authorizable without trusting a missing/mutable route row.
                activity.authorizeEndedReportForSession(retiredSessionGeneration)
            }
        }
        val startTokens = startTokenRepository.findAllByMemberIdForUpdate(memberId)
        val deleteNow = startTokens.filter { it.requestRetirement(now) }
        if (deleteNow.isNotEmpty()) startTokenRepository.deleteAll(deleteNow)
        startTokenRepository.flush()
    }

    /**
     * Joins the FCM registration transaction after its sorted member fence and before candidate
     * FCM rows are replaced. A remote-capable old-owner Activity gets an immutable END and its
     * exact route is retired/reserved; callers must commit and retry instead of swapping it now.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun prepareEndBeforeFcmOwnershipTransfer(
        oldOwnerMemberIds: Collection<Long>,
        candidatePushTokenIds: Collection<Long>,
    ): Set<Long> {
        if (oldOwnerMemberIds.isEmpty() || candidatePushTokenIds.isEmpty()) return emptySet()
        val now = Instant.now(clock)
        val activities = activityRepository.findAllByMembersAndPushTokensAndStatesForUpdate(
            oldOwnerMemberIds.distinct().sorted(),
            candidatePushTokenIds.distinct().sorted(),
            ACTIVE_STATES,
        )
        activities.forEach {
            endActivity(
                it,
                now,
                "FCM_OWNERSHIP_TRANSFER",
                reserveFcmForRetirement = true,
            )
        }
        val reservedRouteIds = activities
            .filter { it.state == ScheduleLiveActivityState.END_PENDING }
            .map { it.pushDeviceTokenId }
            .toSortedSet()
        reservedRouteIds.forEach { routeId ->
            deviceTokenRepository.findByIdForUpdate(routeId)
                ?.takeIf { it.memberId in oldOwnerMemberIds }
                ?.requestRetirement(now)
        }
        return reservedRouteIds
    }

    /**
     * Pre-locks same-owner END tombstones before NotificationTokenWriter locks/replaces the FCM
     * row. A non-null Activity lease means a provider completion may still be in flight, so the
     * registration must retry instead of stealing the route.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun prepareSameDeviceFcmEndHandoff(
        memberId: Long,
        deviceFingerprint: String,
        candidatePushTokenIds: Collection<Long>,
    ): SameDeviceFcmEndHandoffPlan? {
        val candidateIds = candidatePushTokenIds.distinct().toSet()
        val tombstones = activityRepository
            .findAllByMemberDeviceForUpdate(memberId, deviceFingerprint)
            .filter { it.state == ScheduleLiveActivityState.END_PENDING }
        if (tombstones.isEmpty()) return null
        // An invalid UPDATE route may have been deleted before cancellation created the END
        // tombstone. Missing route ids are safe to rebind; an existing non-candidate route is not
        // touched because its owner/device epoch did not participate in registration fencing.
        val oldRouteIds = tombstones.map { it.pushDeviceTokenId }.distinct().sorted()
        val existingRouteIds = deviceTokenRepository.findAllById(oldRouteIds)
            .mapNotNull { it.id }
            .toSet()
        val activities = tombstones.filter {
            it.pushDeviceTokenId in candidateIds || it.pushDeviceTokenId !in existingRouteIds
        }
        if (activities.isEmpty()) return null
        if (activities.any { it.dispatchLeaseId != null || it.dispatchLeaseUntil != null }) {
            throw PushTokenDispatchLeaseActiveException()
        }
        return SameDeviceFcmEndHandoffPlan(
            memberId = memberId,
            deviceFingerprint = deviceFingerprint,
            oldPushDeviceTokenIds = activities.map { it.pushDeviceTokenId }.toSortedSet(),
            replaceableExistingPushDeviceTokenIds = activities
                .map { it.pushDeviceTokenId }
                .filter { it in existingRouteIds }
                .toSortedSet(),
            activities = activities,
        )
    }

    /**
     * Locks non-terminal remote Activities before NotificationTokenWriter creates or rotates the
     * same owner's replacement FCM row. Only a physically missing exact route is recoverable; a
     * foreign/mismatched row or any provider lease remains a hard fence.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun prepareSameDeviceFcmOrphanRecovery(
        memberId: Long,
        deviceFingerprint: String,
    ): SameDeviceFcmOrphanRecoveryPlan? {
        val candidates = activityRepository
            .findAllByMemberDeviceForUpdate(memberId, deviceFingerprint)
            .filter { it.state in FCM_ORPHAN_RECOVERY_STATES }
        if (candidates.isEmpty()) return null
        val routeIds = candidates.map { it.pushDeviceTokenId }.distinct().sorted()
        val existingRouteIds = deviceTokenRepository.findAllById(routeIds)
            .mapNotNull { it.id }
            .toSet()
        val orphaned = candidates.filter { it.pushDeviceTokenId !in existingRouteIds }
        if (orphaned.isEmpty()) return null
        if (orphaned.any { it.dispatchLeaseId != null || it.dispatchLeaseUntil != null }) {
            throw PushTokenDispatchLeaseActiveException()
        }
        return SameDeviceFcmOrphanRecoveryPlan(
            memberId = memberId,
            deviceFingerprint = deviceFingerprint,
            missingPushDeviceTokenIds = orphaned.map { it.pushDeviceTokenId }.toSortedSet(),
            activities = orphaned,
        )
    }

    /** Rebinds a prepared same-owner orphan plan and emits at most one exact replacement UPDATE. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun applySameDeviceFcmOrphanRecovery(
        plan: SameDeviceFcmOrphanRecoveryPlan,
        replacementFcm: com.noLate.notification.domain.NotificationDeviceToken,
    ) {
        val replacementId = replacementFcm.id
        if (
            replacementId == null ||
            replacementFcm.memberId != plan.memberId ||
            replacementFcm.deviceFingerprint != plan.deviceFingerprint ||
            replacementFcm.platform != PushPlatform.IOS ||
            replacementFcm.retirementRequested ||
            replacementFcm.dispatchLeaseId != null ||
            replacementFcm.dispatchLeaseUntil != null ||
            deviceTokenRepository.findAllById(plan.missingPushDeviceTokenIds).isNotEmpty()
        ) {
            throw org.springframework.dao.CannotAcquireLockException(
                "Replacement FCM route changed during orphan recovery.",
            )
        }

        val now = Instant.now(clock)
        plan.activities.forEach { activity ->
            val rowId = activity.id
            if (
                rowId == null ||
                activity.memberId != plan.memberId ||
                activity.deviceFingerprint != plan.deviceFingerprint ||
                activity.state !in FCM_ORPHAN_RECOVERY_STATES ||
                activity.pushDeviceTokenId !in plan.missingPushDeviceTokenIds ||
                activity.dispatchLeaseId != null ||
                activity.dispatchLeaseUntil != null
            ) {
                throw org.springframework.dao.CannotAcquireLockException(
                    "Live Activity changed during FCM orphan recovery.",
                )
            }
            val oldRouteDeliveries = deliveryRepository.findAllOpenByActivityForUpdate(
                rowId,
                RETENTION_PROTECTED_DELIVERY_STATUSES,
            ).filter { it.pushDeviceTokenId != replacementId }
            if (oldRouteDeliveries.any { it.status == LiveActivityDeliveryStatus.DISPATCHING }) {
                throw PushTokenDispatchLeaseActiveException()
            }
            oldRouteDeliveries.forEach {
                it.markClientReconciled(now, "FCM_ROUTE_REPLACED")
            }

            activity.bindFcmRoute(replacementId, replacementFcm.ownershipVersion)
            val updateToken = activity.updateToken
            val updateFingerprint = activity.updateTokenFingerprint
            val targetActivityId = activity.activityId
            if (updateToken == null || updateFingerprint == null || targetActivityId == null) {
                return@forEach
            }
            if (hasExactDelivery(
                    activity,
                    replacementFcm,
                    updateFingerprint,
                    LiveActivityDeliveryOperation.UPDATE,
                )
            ) return@forEach
            enqueue(
                activity,
                replacementFcm,
                updateToken,
                updateFingerprint,
                LiveActivityDeliveryOperation.UPDATE,
                now,
                targetActivityId,
            )
        }
    }

    /**
     * A logout END can reserve its retired FCM route before ActivityKit has supplied the rotated
     * update token. That deterministic `la-end:*` marker is not a provider call. Once the caller
     * has locked the exact FCM epoch, prove that every matching END tombstone is still lease-free
     * and that no END delivery is DISPATCHING, then release only that reservation so the epoch can
     * be replaced atomically in the same registration transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun releaseFcmEndReservationForHandoff(
        plan: SameDeviceFcmEndHandoffPlan,
        fcm: com.noLate.notification.domain.NotificationDeviceToken,
    ): Boolean {
        val fcmId = fcm.id
        val reservation = liveActivityEndRetirementReservation(
            plan.memberId,
            plan.deviceFingerprint,
        )
        if (fcm.dispatchLeaseId != reservation) return false
        if (
            fcmId == null ||
            !fcm.retirementRequested ||
            fcm.memberId != plan.memberId ||
            fcm.deviceFingerprint != plan.deviceFingerprint ||
            fcm.platform != PushPlatform.IOS ||
            fcmId !in plan.replaceableExistingPushDeviceTokenIds
        ) {
            throw org.springframework.dao.CannotAcquireLockException(
                "Reserved FCM route changed during Live Activity END handoff.",
            )
        }
        val exactTombstones = plan.activities.filter { it.pushDeviceTokenId == fcmId }
        if (exactTombstones.isEmpty()) {
            throw org.springframework.dao.CannotAcquireLockException(
                "Reserved FCM route no longer owns an END tombstone.",
            )
        }
        val exactTombstonesById = exactTombstones.associateBy { requireNotNull(it.id) }
        val routeOpenEnds = deliveryRepository.findAllOpenEndByPushDeviceTokenForUpdate(
            fcmId,
            LiveActivityDeliveryOperation.END,
            OUTSTANDING_END_DELIVERY_STATUSES,
        )
        if (routeOpenEnds.any { it.status == LiveActivityDeliveryStatus.DISPATCHING }) {
            throw PushTokenDispatchLeaseActiveException()
        }
        // The marker reserves the whole FCM route, not just one aggregate. A legacy
        // generation-replacement END whose aggregate was already reset cannot be replanned by
        // this handoff; leave the reservation intact until its worker attempt becomes terminal.
        if (routeOpenEnds.any { delivery ->
                val tombstone = exactTombstonesById[delivery.liveActivityId]
                tombstone == null ||
                    delivery.sourceNotificationGeneration !=
                    tombstone.sourceNotificationGeneration ||
                    delivery.targetActivityId != tombstone.activityId
            }
        ) {
            throw PushTokenDispatchLeaseActiveException()
        }
        exactTombstones.forEach { activity ->
            val rowId = activity.id
            if (
                rowId == null ||
                activity.memberId != plan.memberId ||
                activity.deviceFingerprint != plan.deviceFingerprint ||
                activity.state != ScheduleLiveActivityState.END_PENDING ||
                activity.dispatchLeaseId != null ||
                activity.dispatchLeaseUntil != null
            ) {
                throw org.springframework.dao.CannotAcquireLockException(
                    "Live Activity END tombstone changed while releasing its reservation.",
                )
            }
        }
        if (!fcm.releaseDispatchLease(reservation)) {
            throw org.springframework.dao.CannotAcquireLockException(
                "Live Activity END reservation changed before epoch replacement.",
            )
        }
        return true
    }

    /**
     * Account-switch registration can arrive after the old FCM row was deleted but before the old
     * account's Activity was cancelled. Under the caller's sorted member fence, convert only
     * remote-capable activities whose exact route is still missing into END tombstones, then hand
     * those tombstones to a temporary old-owner END-only FCM route.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun prepareForeignOrphanFcmEndHandoff(
        memberId: Long,
        deviceFingerprint: String,
    ): SameDeviceFcmEndHandoffPlan? {
        val candidates = activityRepository
            .findAllByMemberDeviceForUpdate(memberId, deviceFingerprint)
            .filter { it.state in FCM_HANDOFF_OWNER_STATES }
        if (candidates.isEmpty()) return null
        if (candidates.any { it.dispatchLeaseId != null || it.dispatchLeaseUntil != null }) {
            throw PushTokenDispatchLeaseActiveException()
        }
        val routeIds = candidates.map { it.pushDeviceTokenId }.distinct().sorted()
        val existingRouteIds = deviceTokenRepository.findAllById(routeIds)
            .mapNotNull { it.id }
            .toSet()
        val orphaned = candidates.filter { it.pushDeviceTokenId !in existingRouteIds }
        if (orphaned.isEmpty()) return null
        val now = Instant.now(clock)
        orphaned
            .filter { it.state != ScheduleLiveActivityState.END_PENDING }
            .forEach { endActivity(it, now, "FOREIGN_FCM_ORPHAN_HANDOFF") }
        val tombstones = orphaned.filter { it.state == ScheduleLiveActivityState.END_PENDING }
        if (tombstones.isEmpty()) return null
        return SameDeviceFcmEndHandoffPlan(
            memberId = memberId,
            deviceFingerprint = deviceFingerprint,
            oldPushDeviceTokenIds = tombstones.map { it.pushDeviceTokenId }.toSortedSet(),
            replaceableExistingPushDeviceTokenIds = emptySet(),
            activities = tombstones,
        )
    }

    /**
     * Applies a prepared handoff after the canonical FCM row has advanced to a new ownership
     * epoch. Every mutation and the replacement END outbox insert share the registration
     * transaction, so a partial rebind is never committed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun applySameDeviceFcmEndHandoff(
        plan: SameDeviceFcmEndHandoffPlan,
        replacementFcm: com.noLate.notification.domain.NotificationDeviceToken,
        retireForEndOnly: Boolean = false,
    ) {
        val replacementId = replacementFcm.id
        if (
            replacementId == null ||
            replacementFcm.memberId != plan.memberId ||
            replacementFcm.deviceFingerprint != plan.deviceFingerprint ||
            replacementFcm.platform != PushPlatform.IOS ||
            replacementFcm.retirementRequested ||
            replacementFcm.dispatchLeaseId != null ||
            replacementFcm.dispatchLeaseUntil != null
        ) {
            throw org.springframework.dao.CannotAcquireLockException(
                "Replacement FCM route changed during Live Activity END handoff.",
            )
        }

        val now = Instant.now(clock)
        plan.activities.forEach { activity ->
            val rowId = activity.id
            if (
                rowId == null ||
                activity.memberId != plan.memberId ||
                activity.deviceFingerprint != plan.deviceFingerprint ||
                activity.state != ScheduleLiveActivityState.END_PENDING ||
                activity.pushDeviceTokenId !in plan.oldPushDeviceTokenIds ||
                activity.dispatchLeaseId != null ||
                activity.dispatchLeaseUntil != null
            ) {
                throw org.springframework.dao.CannotAcquireLockException(
                    "Live Activity END tombstone changed during FCM route handoff.",
                )
            }

            val oldRouteDeliveries = deliveryRepository.findAllOpenByActivityForUpdate(
                rowId,
                OUTSTANDING_END_DELIVERY_STATUSES,
            ).filter {
                it.operation == LiveActivityDeliveryOperation.END &&
                    (
                        it.pushDeviceTokenId != replacementId ||
                            it.fcmOwnershipVersion != replacementFcm.ownershipVersion ||
                            it.fcmTokenFingerprint != replacementFcm.tokenFingerprint
                        )
            }
            if (oldRouteDeliveries.any { it.status == LiveActivityDeliveryStatus.DISPATCHING }) {
                throw PushTokenDispatchLeaseActiveException()
            }
            // A new ownership epoch is a stronger recovery route for the same remote instance.
            // Terminalize every older non-dispatching unresolved END, including UNCERTAIN and
            // EXHAUSTED, so the obsolete pushDeviceTokenId cannot pin retirement forever.
            oldRouteDeliveries.forEach {
                it.markClientReconciled(now, "FCM_ROUTE_REPLACED")
            }

            activity.bindFcmRoute(replacementId, replacementFcm.ownershipVersion)
            val updateToken = activity.updateToken
            val updateFingerprint = activity.updateTokenFingerprint
            val targetActivityId = activity.activityId
            if (updateToken == null || updateFingerprint == null || targetActivityId == null) {
                return@forEach
            }
            if (hasExactDelivery(
                    activity,
                    replacementFcm,
                    updateFingerprint,
                    LiveActivityDeliveryOperation.END,
                )
            ) return@forEach
            enqueue(
                activity,
                replacementFcm,
                updateToken,
                updateFingerprint,
                LiveActivityDeliveryOperation.END,
                now,
                targetActivityId,
            )
        }
        if (retireForEndOnly) {
            if (!replacementFcm.requestRetirement(now)) {
                throw org.springframework.dao.ConcurrencyFailureException(
                    "Replacement FCM route became leased during END-only handoff.",
                )
            }
            reserveFcmEndBatch(replacementFcm, now)
        }
    }

    @Scheduled(fixedDelayString = "\${notification.live-activity.expiry-scan-millis:60000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun endExpiredActivities() {
        val now = Instant.now(clock)
        activityRepository.findExpiredCandidateIds(
            EXPIRY_SCANNABLE_STATES,
            now,
            PageRequest.of(0, 100),
        ).let { ids ->
            val snapshots = ids.mapNotNull { activityRepository.findById(it).orElse(null) }
            val memberIds = snapshots.map { it.memberId }.distinct().sorted()
            if (memberIds.isNotEmpty()) memberRepository.findAllByIdsForUpdate(memberIds)
            snapshots.forEach { snapshot ->
                activityRepository.findByIdForUpdate(requireNotNull(snapshot.id))?.let { activity ->
                    endActivity(activity, now, "EXPIRED")
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "\${notification.live-activity.withdrawn-cleanup-millis:60000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun cleanupWithdrawnTerminalActivities() {
        val candidateIds = activityRepository.findWithdrawnTerminalCandidateIds(
            setOf(ScheduleLiveActivityState.ENDED, ScheduleLiveActivityState.INVALID),
            PageRequest.of(0, 100),
        )
        val snapshots = candidateIds.mapNotNull { activityRepository.findById(it).orElse(null) }
        val members = if (snapshots.isEmpty()) emptyList() else memberRepository.findAllByIdsForUpdate(
            snapshots.map { it.memberId }.distinct().sorted(),
        )
        val deletedMemberIds = members.filter { it.deleted }.mapNotNull { it.id }.toSet()
        snapshots.forEach { snapshot ->
            if (snapshot.memberId !in deletedMemberIds) return@forEach
            val activityId = requireNotNull(snapshot.id)
            val activity = activityRepository.findByIdForUpdate(activityId) ?: return@forEach
            if (activity.state !in setOf(
                    ScheduleLiveActivityState.ENDED,
                    ScheduleLiveActivityState.INVALID,
                )
            ) return@forEach
            val outstanding = deliveryRepository.findAllOpenByActivityForUpdate(
                activityId,
                OUTSTANDING_END_DELIVERY_STATUSES,
            ).any { it.operation == LiveActivityDeliveryOperation.END }
            if (outstanding) return@forEach
            deliveryRepository.deleteAllByLiveActivityId(activityId)
            deliveryRepository.flush()
            activityRepository.delete(activity)
        }
        activityRepository.flush()
    }

    /**
     * Raw ActivityKit credentials and schedule/destination content are retained briefly for
     * provider diagnosis, then physically purged. Only unresolved END remains intact for
     * reconciliation/manual replay regardless of age; terminal START/UPDATE ambiguity is no
     * longer actionable and must not retain PII forever.
     */
    @Scheduled(fixedDelayString = "\${notification.live-activity.terminal-cleanup-millis:3600000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun cleanupRetainedTerminalActivities() {
        val now = Instant.now(clock)
        val ids = activityRepository.findTerminalRetentionCandidateIds(
            setOf(ScheduleLiveActivityState.ENDED, ScheduleLiveActivityState.INVALID),
            now.minus(terminalRetentionHours, ChronoUnit.HOURS),
            PageRequest.of(0, 100),
        )
        val snapshots = ids.mapNotNull { activityRepository.findById(it).orElse(null) }
        if (snapshots.isNotEmpty()) {
            memberRepository.findAllByIdsForUpdate(
                snapshots.map { it.memberId }.distinct().sorted(),
            )
        }
        snapshots.forEach { snapshot ->
            val rowId = requireNotNull(snapshot.id)
            val activity = activityRepository.findByIdForUpdate(rowId) ?: return@forEach
            if (
                activity.state !in setOf(
                    ScheduleLiveActivityState.ENDED,
                    ScheduleLiveActivityState.INVALID,
                ) || activity.endedAt?.isAfter(
                    now.minus(terminalRetentionHours, ChronoUnit.HOURS),
                ) != false
            ) return@forEach
            val unresolved = deliveryRepository.findAllOpenByActivityForUpdate(
                rowId,
                RETENTION_PROTECTED_DELIVERY_STATUSES,
            ).any { it.operation == LiveActivityDeliveryOperation.END }
            if (unresolved) return@forEach
            deliveryRepository.deleteAllByLiveActivityId(rowId)
            deliveryRepository.flush()
            activityRepository.delete(activity)
        }
        activityRepository.flush()
    }

    private fun endActivity(
        activity: ScheduleLiveActivity,
        now: Instant,
        reason: String,
        reserveFcmForRetirement: Boolean = false,
    ) {
        if (activity.state in TERMINAL_OR_ENDING_STATES) {
            if (reserveFcmForRetirement && activity.state == ScheduleLiveActivityState.END_PENDING) {
                reserveOpenEndForRetirement(activity, now)
            }
            return
        }
        val fcm = currentOwnedFcm(
            activity,
            allowMissingRouteReplacement = remoteActivityMayExist(activity),
        )
        val updateToken = activity.updateToken
        val updateFingerprint = activity.updateTokenFingerprint
        val targetActivityId = activity.activityId
        supersedeOpen(activity, now) { it.operation != LiveActivityDeliveryOperation.END }
        if (fcm == null) {
            if (remoteActivityMayExist(activity)) {
                markEndTombstone(activity, now, reason)
            } else {
                activity.markEnded(now, reason)
            }
            return
        }
        if (updateToken == null || updateFingerprint == null || targetActivityId == null) {
            if (
                activity.state == ScheduleLiveActivityState.START_SENT ||
                    activity.state == ScheduleLiveActivityState.TOKEN_PENDING ||
                    activity.dispatchLeaseId != null
            ) {
                markWaitingForLateEndToken(
                    activity,
                    fcm,
                    now,
                    reason,
                    reserveFcmForRetirement,
                )
            } else {
                activity.markEnded(now, reason)
                // A generation restart can leave an immutable old-generation END alongside the
                // credential-less START_PENDING row. The current generation is locally ended,
                // but logout must still reserve the exact FCM route for that older remote card.
                if (reserveFcmForRetirement) reserveOpenEndForRetirement(activity, now)
            }
            return
        }
        if (!activity.markEndPending(now, reason)) return
        val terminal = decodeContent(activity.contentStateJson).copy(
            revision = activity.contentRevision,
            updatedAtEpochSeconds = now.epochSecond,
            status = terminalStatus(reason),
            journey = decodeContent(activity.contentStateJson).journey?.let {
                if (it.phase in setOf("ARRIVED", "STOPPED")) it else it.copy(
                    phase = "STOPPED", sequence = it.sequence + 1,
                    nextInstruction = "길안내를 종료했어요", observedAtEpochSeconds = now.epochSecond,
                )
            },
        )
        val terminalJson = canonicalJson(terminal)
        activity.replaceTerminalContent(
            OpaquePushIdentifier.fingerprint(terminalJson),
            terminalJson,
        )
        enqueue(
            activity,
            fcm,
            updateToken,
            updateFingerprint,
            LiveActivityDeliveryOperation.END,
            now,
            targetActivityId,
            reserveFcmForRetirement,
        )
    }

    private fun beginOldGenerationEnd(
        activity: ScheduleLiveActivity,
        fcm: com.noLate.notification.domain.NotificationDeviceToken,
        now: Instant,
    ) {
        val updateToken = activity.updateToken
        val updateFingerprint = activity.updateTokenFingerprint
        val targetActivityId = activity.activityId
        supersedeOpen(activity, now) { it.operation != LiveActivityDeliveryOperation.END }
        if (updateToken == null || updateFingerprint == null || targetActivityId == null) return
        // projectEta already locked and validated this current same-member/device iOS route. The
        // aggregate may still reference a provider-invalid row from the old generation; bind the
        // replacement before freezing END so an immediate logout reserves the same exact epoch.
        activity.bindFcmRoute(requireNotNull(fcm.id), fcm.ownershipVersion)
        if (!activity.markEndPending(now, "GENERATION_REPLACED")) return
        val terminal = decodeContent(activity.contentStateJson).copy(
            revision = activity.contentRevision,
            updatedAtEpochSeconds = now.epochSecond,
            status = "cancelled",
        )
        val terminalJson = canonicalJson(terminal)
        activity.replaceTerminalContent(OpaquePushIdentifier.fingerprint(terminalJson), terminalJson)
        enqueue(
            activity,
            fcm,
            updateToken,
            updateFingerprint,
            LiveActivityDeliveryOperation.END,
            now,
            targetActivityId,
        )
    }

    private fun enqueue(
        activity: ScheduleLiveActivity,
        fcm: com.noLate.notification.domain.NotificationDeviceToken,
        activityToken: String,
        activityTokenFingerprint: String,
        operation: LiveActivityDeliveryOperation,
        now: Instant,
        targetActivityId: String? = activity.activityId,
        reserveFcmForRetirement: Boolean = false,
    ): LiveActivityDelivery? {
        val rowId = requireNotNull(activity.id)
        val timestamp = activity.nextApnsTimestamp(now)
        val eventKey = liveActivityDeliveryEventKey(
            liveActivityId = rowId,
            operation = operation,
            revision = activity.contentRevision,
            activityTokenFingerprint = activityTokenFingerprint,
            activityTokenGeneration = if (operation == LiveActivityDeliveryOperation.START) {
                requireNotNull(activity.startTokenGeneration)
            } else {
                activity.updateTokenGeneration
            },
            pushDeviceTokenId = requireNotNull(fcm.id),
            fcmOwnershipVersion = fcm.ownershipVersion,
        )
        deliveryRepository.findByEventKeyForUpdate(eventKey)?.let { existing ->
            if (existing.operation == LiveActivityDeliveryOperation.END) {
                existing.requeueKnownUnaccepted(now)
            }
            if (existing.status in DISPATCHABLE_DELIVERY_STATUSES) {
                applicationEventPublisher?.publishEvent(
                    LiveActivityDispatchRequested(existing.availableAt),
                )
            }
            return existing
        }
        // ActivityKit remote START must include an alert. UPDATE/END intentionally remain silent.
        val title = if (operation == LiveActivityDeliveryOperation.START) {
            "출발 준비"
        } else null
        val body = if (title != null) "일정까지 남은 이동 시간을 확인하세요." else null
        if (reserveFcmForRetirement) {
            reserveFcmEndBatch(fcm, now)
        }
        val delivery = deliveryRepository.save(
            LiveActivityDelivery(
                eventKey = eventKey,
                liveActivityId = rowId,
                memberId = activity.memberId,
                scheduleId = activity.scheduleId,
                pushDeviceTokenId = requireNotNull(fcm.id),
                fcmTokenFingerprint = fcm.tokenFingerprint,
                fcmOwnershipVersion = fcm.ownershipVersion,
                deviceFingerprint = activity.deviceFingerprint,
                activityToken = activityToken,
                activityTokenFingerprint = activityTokenFingerprint,
                startTokenId = activity.startTokenId.takeIf {
                    operation == LiveActivityDeliveryOperation.START
                },
                startTokenGeneration = activity.startTokenGeneration.takeIf {
                    operation == LiveActivityDeliveryOperation.START
                },
                updateTokenGeneration = activity.updateTokenGeneration.takeIf {
                    operation != LiveActivityDeliveryOperation.START
                },
                targetActivityId = targetActivityId,
                operation = operation,
                revision = activity.contentRevision,
                sourceNotificationGeneration = activity.sourceNotificationGeneration,
                apnsTimestamp = timestamp,
                activityType = activity.activityType,
                attributesJson = activity.attributesJson.takeIf {
                    operation == LiveActivityDeliveryOperation.START
                },
                contentStateJson = activity.contentStateJson,
                alertTitle = title,
                alertBody = body,
                staleAt = activity.staleAt,
                dismissalAt = now.takeIf { operation == LiveActivityDeliveryOperation.END },
                expirationAt = if (operation == LiveActivityDeliveryOperation.END) {
                    now.plusSeconds(END_PROVIDER_TTL_SECONDS)
                } else {
                    activity.expiresAt
                },
                availableAt = now,
            )
        )
        applicationEventPublisher?.publishEvent(
            LiveActivityDispatchRequested(delivery.availableAt),
        )
        return delivery
    }

    private fun reserveOpenEndForRetirement(activity: ScheduleLiveActivity, now: Instant) {
        val fcm = currentOwnedFcm(activity) ?: return
        val hasOpenEnd = activity.id?.let { rowId ->
            deliveryRepository.findAllOpenByActivityForUpdate(
                rowId,
                OUTSTANDING_END_DELIVERY_STATUSES,
            ).any { it.operation == LiveActivityDeliveryOperation.END }
        } == true
        val waitingForLateToken =
            activity.state == ScheduleLiveActivityState.END_PENDING &&
                activity.updateToken == null
        if (!waitingForLateToken && !hasOpenEnd) return
        reserveFcmEndBatch(fcm, now)
    }

    private fun reserveFcmEndBatch(
        fcm: com.noLate.notification.domain.NotificationDeviceToken,
        now: Instant,
    ) {
        val reservation = liveActivityEndRetirementReservation(
            fcm.memberId,
            requireNotNull(fcm.deviceFingerprint),
        )
        // The reservation may have passed its ordinary lease TTL while the durable END/tombstone
        // intentionally kept the route row alive. Its exact id still authorizes END-only claim.
        if (fcm.dispatchLeaseId == reservation) return
        if (fcm.retirementRequested) {
            if (fcm.restoreRetirementDispatchReservation(
                    reservation,
                    now.plusSeconds(retirementEndHoldSeconds),
                )
            ) return
            throw PushTokenDispatchLeaseActiveException()
        }
        if (fcm.hasActiveDispatchLease(now)) {
            if (fcm.dispatchLeaseId?.startsWith("la-") == true) {
                return
            }
            throw PushTokenDispatchLeaseActiveException()
        }
        if (!fcm.acquireDispatchLease(
                reservation,
                now,
                now.plusSeconds(retirementEndHoldSeconds),
            )
        ) {
            throw PushTokenDispatchLeaseActiveException()
        }
    }

    private fun supersedeOpen(
        activity: ScheduleLiveActivity,
        now: Instant,
        predicate: (LiveActivityDelivery) -> Boolean,
    ) {
        activity.id?.let { rowId ->
            deliveryRepository.findAllOpenByActivityForUpdate(
                rowId,
                setOf(LiveActivityDeliveryStatus.PENDING, LiveActivityDeliveryStatus.FAILED),
            ).filter(predicate).forEach { it.markSuperseded(now, "NEWER_DESIRED_STATE") }
        }
    }

    private fun currentOwnedFcm(
        activity: ScheduleLiveActivity,
        allowReservedRetirementEnd: Boolean = false,
        allowMissingRouteReplacement: Boolean = false,
    ): com.noLate.notification.domain.NotificationDeviceToken? {
        val exactRoute = deviceTokenRepository.findByIdForUpdate(activity.pushDeviceTokenId)
        if (exactRoute != null) {
            return exactRoute.takeIf {
                it.memberId == activity.memberId &&
                    it.deviceFingerprint == activity.deviceFingerprint &&
                    it.platform == PushPlatform.IOS &&
                    (
                        !it.retirementRequested ||
                            (
                                allowReservedRetirementEnd &&
                                    it.dispatchLeaseId == liveActivityEndRetirementReservation(
                                        activity.memberId,
                                        activity.deviceFingerprint,
                                    )
                                )
                        )
            }?.also {
                activity.bindFcmRoute(requireNotNull(it.id), it.ownershipVersion)
            }
        }
        // A provider-confirmed invalid FCM route can be physically deleted before a later
        // cancellation. Only a genuinely missing row may fall back: an existing mismatched or
        // retired row retains its ownership fence. A non-null Activity lease likewise means an
        // old provider completion can still arrive and forbids rebinding.
        if (
            !allowMissingRouteReplacement ||
            activity.dispatchLeaseId != null ||
            activity.dispatchLeaseUntil != null
        ) return null
        val replacement = deviceTokenRepository
            .findAllByMemberIdAndDeviceFingerprintForUpdate(
                activity.memberId,
                activity.deviceFingerprint,
            )
            .singleOrNull {
                it.platform == PushPlatform.IOS && !it.retirementRequested
            }
            ?: return null
        activity.bindFcmRoute(requireNotNull(replacement.id), replacement.ownershipVersion)
        return replacement
    }

    private fun remoteActivityMayExist(activity: ScheduleLiveActivity): Boolean =
        activity.activityId != null ||
            activity.state in setOf(
                ScheduleLiveActivityState.START_SENT,
                ScheduleLiveActivityState.ACTIVE,
                ScheduleLiveActivityState.TOKEN_PENDING,
            ) ||
            activity.dispatchLeaseId != null

    private fun markWaitingForLateEndToken(
        activity: ScheduleLiveActivity,
        fcm: com.noLate.notification.domain.NotificationDeviceToken,
        now: Instant,
        reason: String,
        reserveFcmForRetirement: Boolean = false,
    ) {
        markEndTombstone(activity, now, reason)
        if (reserveFcmForRetirement) reserveFcmEndBatch(fcm, now)
    }

    private fun markEndTombstone(
        activity: ScheduleLiveActivity,
        now: Instant,
        reason: String,
    ) {
        if (activity.state != ScheduleLiveActivityState.END_PENDING) {
            if (!activity.markEndPending(now, reason)) return
            val terminal = decodeContent(activity.contentStateJson).copy(
                revision = activity.contentRevision,
                updatedAtEpochSeconds = now.epochSecond,
                status = terminalStatus(reason),
            )
            val terminalJson = canonicalJson(terminal)
            activity.replaceTerminalContent(
                OpaquePushIdentifier.fingerprint(terminalJson),
                terminalJson,
            )
        }
    }

    private fun semanticContent(
        projection: ScheduleLiveActivityEtaProjection,
        @Suppress("UNUSED_PARAMETER") actionEventKey: String,
        actionExpiresAt: Instant,
        appearance: LiveActivityAppearance?,
    ): NoLateDepartureContentStateWire = NoLateDepartureContentStateWire(
        revision = 0,
        travelMinutes = projection.travelMinutes,
        firstWaitMinutes = projection.firstBoardingWaitMinutes
            ?.coerceIn(0, projection.travelMinutes),
        predictedArrivalEpochSeconds = projection.predictedArrivalAt?.epochSecond,
        recommendedDepartureEpochSeconds = projection.recommendedDepartureAt.epochSecond,
        updatedAtEpochSeconds = projection.evaluatedAt.epochSecond,
        actionExpiresAtEpochSeconds = actionExpiresAt.epochSecond,
        status = when {
            projection.journey != null -> "inTransit"
            projection.etaStale -> "delayed"
            !projection.evaluatedAt.isBefore(projection.recommendedDepartureAt) -> "leaveNow"
            else -> "preparing"
        },
        routeSegments = normalizedRouteSegments(projection.routeSegments),
        appearance = appearance?.wireValue,
        journey = projection.journey,
    )

    private fun contentJson(content: NoLateDepartureContentStateWire, revision: Long): String =
        canonicalJson(content.copy(revision = revision))

    private fun canonicalJson(value: Any): String = objectMapper.writeValueAsString(value).also {
        require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_JSON_BYTES) {
            "Live Activity payload state exceeds the bounded JSON contract."
        }
    }

    private fun decodeContent(json: String): NoLateDepartureContentStateWire =
        objectMapper.readValue(json, NoLateDepartureContentStateWire::class.java)

    private fun terminalStatus(reason: String): String =
        if (reason == "JOURNEY_ARRIVED" || reason.contains("DEPART", ignoreCase = true)) "arrived" else "cancelled"

    private fun String?.canonicalText(maxLength: Int, fallback: String): String =
        this?.trim()
            ?.replace(CONTROL_CHARACTER, "")
            ?.take(maxLength)
            ?.takeIf(String::isNotBlank)
            ?: fallback

    internal fun normalizedRouteSegments(incoming: List<RouteDisplaySegment>): List<RouteDisplaySegment> =
        incoming.asSequence()
            .filter { it.kind != "destination" }
            .map { it.normalized() }
            .fold(mutableListOf<RouteDisplaySegment>()) { result, segment ->
                if (result.lastOrNull() != segment) result += segment
                result
            }
            .take(5) + RouteDisplaySegment("destination", "도착", "#FF4444")

    private fun RouteDisplaySegment.normalized(): RouteDisplaySegment {
        val safeKind = kind.takeIf { it in SUPPORTED_ROUTE_KINDS } ?: "transfer"
        val fallbackColor = ROUTE_COLORS.getValue(safeKind)
        return RouteDisplaySegment(
            kind = safeKind,
            label = label.canonicalText(18, fallbackRouteLabel(safeKind)),
            colorHex = colorHex.takeIf(HEX_COLOR::matches)?.uppercase() ?: fallbackColor,
            legIndex = legIndex,
        )
    }

    private fun fallbackRouteLabel(kind: String): String = when (kind) {
        "walk" -> "도보"
        "bus" -> "버스"
        "subway" -> "지하철"
        "drive" -> "자동차"
        "bike" -> "자전거"
        "origin" -> "출발"
        "destination" -> "도착"
        else -> "환승"
    }

    private companion object {
        val DISPATCHABLE_DELIVERY_STATUSES = setOf(
            LiveActivityDeliveryStatus.PENDING,
            LiveActivityDeliveryStatus.FAILED,
        )
        const val MAX_JSON_BYTES = 3_000
        const val END_PROVIDER_TTL_SECONDS = 3_600L
        val CONTROL_CHARACTER = Regex("[\\u0000-\\u001f\\u007f]")
        val HEX_COLOR = Regex("#[0-9A-Fa-f]{6}")
        val SUPPORTED_ROUTE_KINDS = setOf(
            "origin", "destination", "walk", "subway", "bus", "drive", "bike", "transfer",
        )
        val ROUTE_COLORS = mapOf(
            "origin" to "#111827",
            "destination" to "#FF4444",
            "walk" to "#9CA3AF",
            "subway" to "#00B140",
            "bus" to "#2979FF",
            "drive" to "#2979FF",
            "bike" to "#22C55E",
            "transfer" to "#9CA3AF",
        )
        val ACTIVE_STATES = setOf(
            ScheduleLiveActivityState.START_PENDING,
            ScheduleLiveActivityState.START_SENT,
            ScheduleLiveActivityState.ACTIVE,
            ScheduleLiveActivityState.TOKEN_PENDING,
            ScheduleLiveActivityState.END_PENDING,
        )
        val FCM_HANDOFF_OWNER_STATES = setOf(
            ScheduleLiveActivityState.START_SENT,
            ScheduleLiveActivityState.ACTIVE,
            ScheduleLiveActivityState.TOKEN_PENDING,
            ScheduleLiveActivityState.END_PENDING,
        )
        val FCM_ORPHAN_RECOVERY_STATES = setOf(
            ScheduleLiveActivityState.START_SENT,
            ScheduleLiveActivityState.ACTIVE,
            ScheduleLiveActivityState.TOKEN_PENDING,
        )
        // END_PENDING may be waiting for a late ActivityKit token after an accepted/ambiguous
        // START. Time alone is never proof of remote termination, and excluding tombstones also
        // prevents the first 100 unresolved rows from starving ordinary expiry work.
        val EXPIRY_SCANNABLE_STATES = setOf(
            ScheduleLiveActivityState.START_PENDING,
            ScheduleLiveActivityState.START_SENT,
            ScheduleLiveActivityState.ACTIVE,
            ScheduleLiveActivityState.TOKEN_PENDING,
        )
        val TERMINAL_OR_ENDING_STATES = setOf(
            ScheduleLiveActivityState.END_PENDING,
            ScheduleLiveActivityState.ENDED,
            ScheduleLiveActivityState.INVALID,
        )
        val OUTSTANDING_END_DELIVERY_STATUSES = setOf(
            LiveActivityDeliveryStatus.PENDING,
            LiveActivityDeliveryStatus.FAILED,
            LiveActivityDeliveryStatus.DISPATCHING,
            LiveActivityDeliveryStatus.UNCERTAIN,
            LiveActivityDeliveryStatus.EXHAUSTED,
        )
        val RETENTION_PROTECTED_DELIVERY_STATUSES = setOf(
            LiveActivityDeliveryStatus.PENDING,
            LiveActivityDeliveryStatus.FAILED,
            LiveActivityDeliveryStatus.DISPATCHING,
            LiveActivityDeliveryStatus.UNCERTAIN,
            LiveActivityDeliveryStatus.EXHAUSTED,
        )
    }
}

internal fun liveActivityEndRetirementReservation(memberId: Long, deviceFingerprint: String): String =
    "la-end:${OpaquePushIdentifier.fingerprint("$memberId:$deviceFingerprint").take(48)}"

internal fun liveActivityDeliveryEventKey(
    liveActivityId: Long,
    operation: LiveActivityDeliveryOperation,
    revision: Long,
    activityTokenFingerprint: String,
    activityTokenGeneration: Long,
    pushDeviceTokenId: Long,
    fcmOwnershipVersion: Long,
): String {
    val routeEpoch = OpaquePushIdentifier.fingerprint(
        "$pushDeviceTokenId:$fcmOwnershipVersion",
    ).take(16)
    return "la:$liveActivityId:${operation.name.lowercase()}:r$revision:g$activityTokenGeneration:" +
        "e$routeEpoch:" +
        activityTokenFingerprint
}
