package com.noLate.notification.liveactivity.application

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.FirebasePushTimingInvariant
import com.noLate.notification.liveactivity.domain.LiveActivityDelivery
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryOperation
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryStatus
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivity
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivityState
import com.noLate.notification.liveactivity.infrastructure.LiveActivityDeliveryRepository
import com.noLate.notification.liveactivity.infrastructure.LiveActivityStartTokenRepository
import com.noLate.notification.liveactivity.infrastructure.ScheduleLiveActivityRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

class LiveActivityDispatchLease(
    val deliveryId: Long,
    val leaseId: String,
    val memberId: Long,
    val fcmTokenId: Long,
    val fcmTokenFingerprint: String,
    val fcmOwnershipVersion: Long,
    val fcmRegistrationToken: String,
    val activityToken: String,
    val operation: LiveActivityDeliveryOperation,
    val timestamp: Long,
    val activityType: String,
    val attributesJson: String?,
    val contentStateJson: String,
    val alertTitle: String?,
    val alertBody: String?,
    val staleAt: Instant?,
    val dismissalAt: Instant?,
    val expirationAt: Instant,
) {
    override fun toString(): String =
        "LiveActivityDispatchLease(deliveryId=$deliveryId, operation=$operation)"
}

data class LiveActivityStaleRecoveryResult(
    val recoveredCount: Int,
    val retryTimes: Set<Instant> = emptySet(),
) {
    val nextRetryAt: Instant?
        get() = retryTimes.minOrNull()
}

data class LiveActivityDispatchRunResult(
    val processedCount: Int,
    val batchExhausted: Boolean,
    val retryTimes: Set<Instant> = emptySet(),
) {
    val nextRetryAt: Instant?
        get() = retryTimes.minOrNull()
}

@Service
class LiveActivityDispatchCoordinator(
    private val memberRepository: MemberRepository,
    private val deviceTokenRepository: NotificationDeviceTokenRepository,
    private val startTokenRepository: LiveActivityStartTokenRepository,
    private val activityRepository: ScheduleLiveActivityRepository,
    private val deliveryRepository: LiveActivityDeliveryRepository,
    @Value("\${notification.live-activity.dispatch-lease-seconds:600}")
    private val dispatchLeaseSeconds: Long = 600,
    @Value("\${notification.live-activity.max-attempts:3}")
    private val maxAttempts: Int = 3,
    @Value("\${notification.live-activity.retry-base-seconds:30}")
    private val retryBaseSeconds: Long = 30,
    private val firebasePushTimingInvariant: FirebasePushTimingInvariant? = null,
) {
    init {
        require(dispatchLeaseSeconds in 61..3_600)
        require(maxAttempts in 1..10)
        require(retryBaseSeconds in 1..3_600)
        firebasePushTimingInvariant?.let { invariant ->
            require(invariant.firebaseWorstCaseCallMillis < dispatchLeaseSeconds * 1_000) {
                "notification.live-activity.dispatch-lease-seconds must be strictly longer " +
                    "than the Firebase Admin worst-case request/retry budget."
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimNext(now: Instant): LiveActivityDispatchLease? {
        val candidateIds = deliveryRepository.findDueCandidateIds(
            setOf(LiveActivityDeliveryStatus.PENDING, LiveActivityDeliveryStatus.FAILED),
            now,
            PageRequest.of(0, 20),
        )
        val snapshots = candidateIds.mapNotNull { deliveryRepository.findById(it).orElse(null) }
        val lockedMembers = if (snapshots.isEmpty()) emptyList() else {
            memberRepository.findAllByIdsForUpdate(
                snapshots.map { it.memberId }.distinct().sorted(),
            )
        }
        val membersById = lockedMembers.associateBy { requireNotNull(it.id) }
        for (snapshot in snapshots) {
            val candidateId = requireNotNull(snapshot.id)
            val member = membersById[snapshot.memberId]
            if (
                member == null ||
                (member.deleted && snapshot.operation != LiveActivityDeliveryOperation.END)
            ) {
                deliveryRepository.findByIdForUpdate(candidateId)
                    ?.markSuperseded(now, "RECIPIENT_INACTIVE")
                continue
            }
            // Canonical live-activity lock order after sorted member fencing:
            // activity -> ordinary FCM route -> start credential -> immutable delivery.
            val activity = activityRepository.findByIdForUpdate(snapshot.liveActivityId)
            val fcm = deviceTokenRepository.findByIdForUpdate(snapshot.pushDeviceTokenId)
            var startToken: com.noLate.notification.liveactivity.domain.LiveActivityStartToken? = null
            if (snapshot.operation == LiveActivityDeliveryOperation.START) {
                startToken = snapshot.startTokenId?.let(startTokenRepository::findByIdForUpdate)
            }
            val delivery = deliveryRepository.findByIdForUpdate(candidateId) ?: continue
            if (
                delivery.status !in setOf(
                    LiveActivityDeliveryStatus.PENDING,
                    LiveActivityDeliveryStatus.FAILED,
                ) || delivery.availableAt.isAfter(now)
            ) continue
            if (delivery.attemptCount >= maxAttempts) {
                if (
                    delivery.operation == LiveActivityDeliveryOperation.END &&
                    now.isBefore(delivery.expirationAt)
                ) {
                    // END is idempotent. Renew bounded attempt counters while its frozen APNs TTL
                    // remains valid instead of permanently stranding the Activity after 3 tries.
                    delivery.resetAttemptCycleForEndRetry()
                } else {
                    delivery.markExhausted(now)
                    when (delivery.operation) {
                        // Exhaustion is not provider acceptance. Keep END_PENDING observable for
                        // operations/manual replay instead of reporting a false remote termination.
                        LiveActivityDeliveryOperation.END -> Unit
                        LiveActivityDeliveryOperation.START -> if (
                            activity != null && deliveryStillTargetsCurrentInstance(delivery, activity)
                        ) {
                            activity.markInvalid(now, "START_ATTEMPT_LIMIT_REACHED")
                        }
                        LiveActivityDeliveryOperation.UPDATE -> Unit
                    }
                    continue
                }
            }
            if (activity == null || !deliveryMatchesDesiredState(delivery, activity)) {
                delivery.markSuperseded(now, "DESIRED_STATE_CHANGED")
                continue
            }
            if (
                delivery.operation != LiveActivityDeliveryOperation.END &&
                !now.isBefore(activity.expiresAt)
            ) {
                delivery.markSuperseded(now, "ACTIVITY_EXPIRED_BEFORE_PROVIDER_CLAIM")
                if (
                    delivery.operation == LiveActivityDeliveryOperation.START &&
                    deliveryStillTargetsCurrentInstance(delivery, activity)
                ) {
                    activity.markEnded(now, "START_DELIVERY_EXPIRED")
                }
                continue
            }
            val reservedRetirementEnd =
                delivery.operation == LiveActivityDeliveryOperation.END &&
                    fcm?.retirementRequested == true &&
                    fcm.dispatchLeaseId == liveActivityEndRetirementReservation(
                        delivery.memberId,
                        delivery.deviceFingerprint,
                    )
            if (member.deleted && !reservedRetirementEnd) {
                delivery.markSuperseded(now, "WITHDRAWN_END_ROUTE_NOT_RESERVED")
                continue
            }
            if (
                fcm == null || fcm.memberId != delivery.memberId ||
                fcm.tokenFingerprint != delivery.fcmTokenFingerprint ||
                fcm.ownershipVersion != delivery.fcmOwnershipVersion ||
                fcm.deviceFingerprint != delivery.deviceFingerprint ||
                fcm.platform != PushPlatform.IOS ||
                (fcm.retirementRequested && !reservedRetirementEnd)
            ) {
                delivery.markSuperseded(now, "FCM_OWNERSHIP_CHANGED")
                continue
            }
            if (delivery.operation == LiveActivityDeliveryOperation.START) {
                startToken = startToken?.takeIf {
                        it.memberId == delivery.memberId &&
                            it.deviceFingerprint == delivery.deviceFingerprint &&
                            it.tokenFingerprint == delivery.activityTokenFingerprint &&
                            it.tokenGeneration == delivery.startTokenGeneration &&
                            it.active && !it.retirementRequested
                    }
                if (startToken == null) {
                    delivery.markSuperseded(now, "START_TOKEN_ROTATED")
                    continue
                }
            }
            val leaseId = "la-${UUID.randomUUID()}"
            val leaseUntil = now.plusSeconds(dispatchLeaseSeconds)
            if (!activity.acquireDispatchLease(leaseId, now, leaseUntil)) continue
            val fcmLeaseAcquired = if (reservedRetirementEnd) {
                fcm.acquireReservedRetirementDispatchLease(
                    liveActivityEndRetirementReservation(
                        delivery.memberId,
                        delivery.deviceFingerprint,
                    ),
                    leaseId,
                    leaseUntil,
                )
            } else {
                fcm.acquireDispatchLease(leaseId, now, leaseUntil)
            }
            if (!fcmLeaseAcquired) {
                activity.releaseDispatchLease(leaseId)
                continue
            }
            if (startToken != null && !startToken.acquireDispatchLease(leaseId, now, leaseUntil)) {
                fcm.releaseDispatchLease(leaseId)
                activity.releaseDispatchLease(leaseId)
                continue
            }
            delivery.beginDispatch(now, leaseId, leaseUntil)
            deliveryRepository.flush()
            activityRepository.flush()
            deviceTokenRepository.flush()
            startTokenRepository.flush()
            return LiveActivityDispatchLease(
                deliveryId = requireNotNull(delivery.id),
                leaseId = leaseId,
                memberId = delivery.memberId,
                fcmTokenId = requireNotNull(fcm.id),
                fcmTokenFingerprint = fcm.tokenFingerprint,
                fcmOwnershipVersion = fcm.ownershipVersion,
                fcmRegistrationToken = fcm.token,
                activityToken = delivery.activityToken,
                operation = delivery.operation,
                timestamp = delivery.apnsTimestamp,
                activityType = delivery.activityType,
                attributesJson = delivery.attributesJson,
                contentStateJson = delivery.contentStateJson,
                alertTitle = delivery.alertTitle,
                alertBody = delivery.alertBody,
                staleAt = delivery.staleAt,
                dismissalAt = delivery.dismissalAt,
                expirationAt = delivery.expirationAt,
            )
        }
        return null
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeSuccess(lease: LiveActivityDispatchLease, messageId: String, now: Instant) {
        withLockedLease(lease) { delivery, activity, _ ->
            delivery.markSuccess(now, messageId)
            when (delivery.operation) {
                LiveActivityDeliveryOperation.START -> activity.markStartAccepted(delivery.revision)
                LiveActivityDeliveryOperation.UPDATE -> Unit
                LiveActivityDeliveryOperation.END ->
                    activity.markEndAccepted(delivery.revision, delivery.targetActivityId, now)
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeRetryableFailure(
        lease: LiveActivityDispatchLease,
        code: String,
        now: Instant,
    ): Instant? {
        var nextRetryAt: Instant? = null
        withLockedLease(lease) { delivery, activity, _ ->
            val canceledUnacceptedStart =
                isExactCanceledInFlightStart(delivery, activity, lease.leaseId)
            if (delivery.attemptCount >= maxAttempts) {
                if (
                    delivery.operation == LiveActivityDeliveryOperation.END &&
                    now.isBefore(delivery.expirationAt)
                ) {
                    delivery.markConfirmedFailure(
                        now,
                        code,
                        null,
                        minOf(retryAt(delivery, now), delivery.expirationAt),
                    )
                } else {
                    delivery.markExhausted(now)
                }
                if (
                    delivery.operation == LiveActivityDeliveryOperation.START &&
                    deliveryStillTargetsCurrentInstance(delivery, activity)
                ) {
                    activity.markInvalid(now, "START_ATTEMPT_LIMIT_REACHED")
                }
            } else {
                val multiplier = 1L shl (delivery.attemptCount - 1).coerceIn(0, 10)
                val delay = (retryBaseSeconds * multiplier).coerceAtMost(3_600)
                delivery.markConfirmedFailure(now, code, null, now.plusSeconds(delay))
            }
            if (canceledUnacceptedStart) {
                // QUOTA_EXCEEDED and other retryable classifications are explicit nonacceptance
                // for this attempt. Cancellation removed the desire to retry START, so no remote
                // card can exist and the tokenless tombstone may terminate immediately.
                delivery.markClientReconciled(now, "CANCELLED_START_CONFIRMED_UNACCEPTED")
                activity.markEnded(now)
            }
            nextRetryAt = delivery.availableAt.takeIf {
                delivery.status == LiveActivityDeliveryStatus.FAILED
            }
        }
        return nextRetryAt
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completePermanentFailure(lease: LiveActivityDispatchLease, code: String, now: Instant) {
        withLockedLease(lease) { delivery, activity, _ ->
            val canceledUnacceptedStart =
                isExactCanceledInFlightStart(delivery, activity, lease.leaseId)
            delivery.markInvalid(now, code)
            // A confirmed provider rejection is not proof that a remote Activity disappeared.
            // Keep END_PENDING and its frozen ownership route fail-closed for client ACK/manual
            // recovery; otherwise account transfer could orphan old-owner lock-screen PII.
            when (delivery.operation) {
                LiveActivityDeliveryOperation.START -> when {
                    canceledUnacceptedStart -> activity.markEnded(now)
                    deliveryStillTargetsCurrentInstance(delivery, activity) ->
                        activity.markInvalid(now, code)
                }
                // A rejected UPDATE did not destroy the already-running remote Activity. Preserve
                // its identity/credential so a later cancellation can still enqueue exact END.
                LiveActivityDeliveryOperation.UPDATE,
                LiveActivityDeliveryOperation.END,
                -> Unit
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeInvalidActivityRoute(lease: LiveActivityDispatchLease, now: Instant) {
        withLockedLease(lease) { delivery, activity, startToken ->
            val canceledUnacceptedStart =
                isExactCanceledInFlightStart(delivery, activity, lease.leaseId)
            delivery.markInvalid(now, "INVALID_ACTIVITY_ROUTE")
            val stillTargetsCurrent = deliveryStillTargetsCurrentInstance(delivery, activity)
            val exactStartToken = startToken?.takeIf {
                it.id == delivery.startTokenId &&
                    it.tokenGeneration == delivery.startTokenGeneration &&
                    it.tokenFingerprint == delivery.activityTokenFingerprint
            }
            when (delivery.operation) {
                LiveActivityDeliveryOperation.START -> when {
                    canceledUnacceptedStart -> activity.markEnded(now)
                    stillTargetsCurrent -> activity.markInvalid(now, "INVALID_ACTIVITY_ROUTE")
                }
                LiveActivityDeliveryOperation.UPDATE -> if (stillTargetsCurrent) {
                    activity.invalidateUpdateCredential(
                        delivery.activityTokenFingerprint,
                        requireNotNull(delivery.updateTokenGeneration),
                    )
                }
                // A failed END is not proof that the Activity disappeared. Keep its tombstone and
                // accept a later rotated token callback for a new exact END attempt.
                LiveActivityDeliveryOperation.END -> Unit
            }
            if (
                delivery.operation == LiveActivityDeliveryOperation.START &&
                (stillTargetsCurrent || canceledUnacceptedStart) &&
                exactStartToken != null
            ) {
                exactStartToken.requestRetirement(now)
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeInvalidFcmRoute(lease: LiveActivityDispatchLease, now: Instant) {
        // The INVALID fact and retirement marker must share one commit. The worker's later
        // removeTokenByOwnership call is only eager cleanup; a process death between the two can
        // no longer leave an active-looking FCM row pinning an END tombstone forever.
        withLockedLease(lease, retireExactFcmRouteAt = now) { delivery, activity, _ ->
            val canceledUnacceptedStart =
                isExactCanceledInFlightStart(delivery, activity, lease.leaseId)
            delivery.markInvalid(now, "INVALID_FCM_ROUTE")
            if (canceledUnacceptedStart) activity.markEnded(now)
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeAmbiguous(
        lease: LiveActivityDispatchLease,
        code: String,
        now: Instant,
    ): Instant? {
        var nextRetryAt: Instant? = null
        withLockedLease(lease) { delivery, activity, _ ->
            when (delivery.operation) {
                // Suppress duplicate remote START while waiting for client token reconciliation.
                LiveActivityDeliveryOperation.START -> {
                    delivery.markAmbiguous(now, code)
                    activity.markStartAccepted(delivery.revision)
                }
                // Repeating the same immutable END is idempotent and safer than claiming the
                // Activity disappeared when provider acceptance is unknown.
                LiveActivityDeliveryOperation.END -> {
                    val retryAt = minOf(retryAt(delivery, now), delivery.expirationAt)
                    delivery.markRetryableAfterAmbiguous(now, code, retryAt)
                    nextRetryAt = retryAt
                }
                LiveActivityDeliveryOperation.UPDATE -> delivery.markAmbiguous(now, code)
            }
        }
        return nextRetryAt
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recoverStaleDispatches(now: Instant, batchSize: Int): LiveActivityStaleRecoveryResult {
        val boundary = now.minusSeconds(dispatchLeaseSeconds)
        val ids = deliveryRepository.findStaleDispatchCandidateIds(
            LiveActivityDeliveryStatus.DISPATCHING,
            boundary,
            PageRequest.of(0, batchSize.coerceIn(1, 200)),
        )
        val snapshots = ids.mapNotNull { deliveryRepository.findById(it).orElse(null) }
        if (snapshots.isNotEmpty()) {
            memberRepository.findAllByIdsForUpdate(
                snapshots.map { it.memberId }.distinct().sorted(),
            )
        }
        var recovered = 0
        val retryTimes = mutableSetOf<Instant>()
        snapshots.forEach { snapshot ->
            val id = requireNotNull(snapshot.id)
            val activity = activityRepository.findByIdForUpdate(snapshot.liveActivityId)
            val fcm = deviceTokenRepository.findByIdForUpdate(snapshot.pushDeviceTokenId)
            val startToken = snapshot.startTokenId?.let(startTokenRepository::findByIdForUpdate)
            val delivery = deliveryRepository.findByIdForUpdate(id)
                ?.takeIf {
                    it.status == LiveActivityDeliveryStatus.DISPATCHING &&
                        it.lastAttemptedAt?.isAfter(boundary) == false
                } ?: return@forEach
            val leaseId = delivery.dispatchLeaseId
            var releasedFcmLease = false
            if (leaseId != null) {
                activity?.releaseDispatchLease(leaseId)
                releasedFcmLease = fcm?.releaseDispatchLease(leaseId) == true
                if (startToken?.releaseDispatchLease(leaseId) == true && startToken.retirementRequested) {
                    startTokenRepository.delete(startToken)
                }
            }
            when (delivery.operation) {
                // Claim-before-send and send-before-ACK are indistinguishable after a process
                // crash. START is fail-closed to avoid creating a duplicate remote Activity.
                LiveActivityDeliveryOperation.START -> {
                    delivery.markAmbiguous(now, "PROCESS_DIED_AFTER_PROVIDER_BOUNDARY")
                    activity?.markStartAccepted(delivery.revision)
                }
                LiveActivityDeliveryOperation.END -> {
                    val retryAt = minOf(retryAt(delivery, now), delivery.expirationAt)
                    delivery.markRetryableAfterAmbiguous(
                        now,
                        "PROCESS_DIED_AFTER_PROVIDER_BOUNDARY",
                        retryAt,
                    )
                    retryTimes += retryAt
                }
                LiveActivityDeliveryOperation.UPDATE -> delivery.markAmbiguous(
                    now,
                    "PROCESS_DIED_AFTER_PROVIDER_BOUNDARY",
                )
            }
            // END must become FAILED before checking open rows, otherwise the last retired FCM
            // reservation could be dropped between stale-lease recovery and its exact retry.
            if (releasedFcmLease && fcm != null) {
                restoreRetirementEndReservation(fcm, now)
            }
            recovered += 1
        }
        return LiveActivityStaleRecoveryResult(
            recoveredCount = recovered,
            retryTimes = retryTimes,
        )
    }

    private fun withLockedLease(
        lease: LiveActivityDispatchLease,
        retireExactFcmRouteAt: Instant? = null,
        block: (
            LiveActivityDelivery,
            ScheduleLiveActivity,
            com.noLate.notification.liveactivity.domain.LiveActivityStartToken?,
        ) -> Unit,
    ) {
        memberRepository.findByIdForUpdate(lease.memberId)
        val snapshot = deliveryRepository.findById(lease.deliveryId).orElse(null) ?: return
        val activity = activityRepository.findByIdForUpdate(snapshot.liveActivityId)
        val fcm = deviceTokenRepository.findByIdForUpdate(snapshot.pushDeviceTokenId)
        val startToken = snapshot.startTokenId?.let(startTokenRepository::findByIdForUpdate)
        val delivery = deliveryRepository.findByIdForUpdate(lease.deliveryId)
            ?.takeIf { it.status == LiveActivityDeliveryStatus.DISPATCHING && it.ownsLease(lease.leaseId) }
            ?: return
        if (activity == null) {
            delivery.markAmbiguous(Instant.now(), "ACTIVITY_STATE_MISSING")
            if (fcm?.releaseDispatchLease(lease.leaseId) == true) {
                restoreRetirementEndReservation(fcm, Instant.now())
            }
            if (startToken?.releaseDispatchLease(lease.leaseId) == true && startToken.retirementRequested) {
                startTokenRepository.delete(startToken)
            }
            return
        }
        block(delivery, activity, startToken)
        val retiredExactFcmRoute = retireExactFcmRouteAt != null && fcm != null &&
            fcm.id == delivery.pushDeviceTokenId &&
            fcm.memberId == delivery.memberId &&
            fcm.deviceFingerprint == delivery.deviceFingerprint &&
            fcm.tokenFingerprint == delivery.fcmTokenFingerprint &&
            fcm.ownershipVersion == delivery.fcmOwnershipVersion &&
            fcm.platform == PushPlatform.IOS &&
            fcm.dispatchLeaseId == lease.leaseId
        if (retiredExactFcmRoute) {
            // requestRetirement intentionally observes the still-active provider lease and keeps
            // the canonical row. release below clears that lease, while registration can then
            // prove and advance the retired same-device epoch in one transaction.
            requireNotNull(fcm).requestRetirement(requireNotNull(retireExactFcmRouteAt))
        }
        activity.releaseDispatchLease(lease.leaseId)
        if (fcm?.releaseDispatchLease(lease.leaseId) == true) {
            // Never reserve a route just confirmed invalid by FCM. Other retirement causes still
            // retain the END-only reservation contract used by logout/account transfer.
            if (!retiredExactFcmRoute) restoreRetirementEndReservation(fcm, Instant.now())
        }
        if (startToken?.releaseDispatchLease(lease.leaseId) == true && startToken.retirementRequested) {
            startTokenRepository.delete(startToken)
        }
    }

    private fun restoreRetirementEndReservation(
        fcm: com.noLate.notification.domain.NotificationDeviceToken,
        now: Instant,
    ) {
        val fcmId = fcm.id ?: return
        if (!fcm.retirementRequested) return
        val hasOpenEnd = deliveryRepository.countOpenByPushDeviceToken(
            fcmId,
            LiveActivityDeliveryOperation.END,
            RETIREMENT_PROTECTED_END_STATUSES,
        ) > 0 || deviceTokenRepository.countEndPendingLiveActivities(fcmId) > 0
        if (hasOpenEnd) {
            fcm.restoreRetirementDispatchReservation(
                liveActivityEndRetirementReservation(
                    fcm.memberId,
                    requireNotNull(fcm.deviceFingerprint),
                ),
                now.plusSeconds(dispatchLeaseSeconds),
            )
        }
    }

    private fun retryAt(delivery: LiveActivityDelivery, now: Instant): Instant {
        val multiplier = 1L shl (delivery.attemptCount - 1).coerceIn(0, 10)
        return now.plusSeconds((retryBaseSeconds * multiplier).coerceAtMost(3_600))
    }

    /**
     * Cancellation may advance the aggregate from START_PENDING to a tokenless END_PENDING while
     * the exact provider START remains in flight. This proof deliberately ignores desired-state
     * equality but retains every immutable instance/route/lease fence. It is used only for an
     * explicit provider nonacceptance; success and ambiguous outcomes never call it.
     */
    private fun isExactCanceledInFlightStart(
        delivery: LiveActivityDelivery,
        activity: ScheduleLiveActivity,
        leaseId: String,
    ): Boolean =
        delivery.operation == LiveActivityDeliveryOperation.START &&
            delivery.ownsLease(leaseId) &&
            activity.id == delivery.liveActivityId &&
            activity.state == ScheduleLiveActivityState.END_PENDING &&
            activity.dispatchLeaseId == leaseId &&
            activity.activityId == null &&
            activity.updateToken == null &&
            activity.updateTokenFingerprint == null &&
            activity.sourceNotificationGeneration == delivery.sourceNotificationGeneration &&
            activity.contentRevision == delivery.revision + 1 &&
            activity.pushDeviceTokenId == delivery.pushDeviceTokenId &&
            activity.startTokenId == delivery.startTokenId &&
            activity.startTokenGeneration == delivery.startTokenGeneration &&
            activity.startTokenFingerprint == delivery.activityTokenFingerprint

    private fun deliveryMatchesDesiredState(
        delivery: LiveActivityDelivery,
        activity: ScheduleLiveActivity,
    ): Boolean = when (delivery.operation) {
        LiveActivityDeliveryOperation.START ->
            activity.contentRevision == delivery.revision &&
                activity.state == ScheduleLiveActivityState.START_PENDING &&
                activity.startTokenId == delivery.startTokenId &&
                activity.startTokenFingerprint == delivery.activityTokenFingerprint &&
                activity.startTokenGeneration == delivery.startTokenGeneration
        LiveActivityDeliveryOperation.UPDATE ->
            activity.contentRevision == delivery.revision &&
                activity.state == ScheduleLiveActivityState.ACTIVE &&
                activity.activityId == delivery.targetActivityId &&
                activity.updateTokenFingerprint == delivery.activityTokenFingerprint &&
                activity.updateTokenGeneration == delivery.updateTokenGeneration
        LiveActivityDeliveryOperation.END -> {
            val stillCurrent = activity.activityId == delivery.targetActivityId
            if (stillCurrent) {
                activity.contentRevision == delivery.revision &&
                    activity.state == ScheduleLiveActivityState.END_PENDING &&
                    activity.updateTokenFingerprint == delivery.activityTokenFingerprint &&
                    activity.updateTokenGeneration == delivery.updateTokenGeneration
            } else {
                // Generation replacement reset the unique row, but this immutable END still owns
                // the old ActivityKit instance token and must be delivered before/alongside START.
                delivery.targetActivityId != null &&
                    delivery.sourceNotificationGeneration < activity.sourceNotificationGeneration
            }
        }
    }

    private fun deliveryStillTargetsCurrentInstance(
        delivery: LiveActivityDelivery,
        activity: ScheduleLiveActivity,
    ): Boolean = when (delivery.operation) {
        LiveActivityDeliveryOperation.START ->
            activity.contentRevision == delivery.revision &&
                activity.state == ScheduleLiveActivityState.START_PENDING &&
                activity.startTokenId == delivery.startTokenId &&
                activity.startTokenGeneration == delivery.startTokenGeneration &&
                activity.startTokenFingerprint == delivery.activityTokenFingerprint
        LiveActivityDeliveryOperation.UPDATE,
        LiveActivityDeliveryOperation.END,
        -> activity.contentRevision == delivery.revision &&
            activity.activityId == delivery.targetActivityId &&
            activity.updateTokenFingerprint == delivery.activityTokenFingerprint &&
            activity.updateTokenGeneration == delivery.updateTokenGeneration
    }

    private companion object {
        val RETIREMENT_PROTECTED_END_STATUSES = setOf(
            LiveActivityDeliveryStatus.PENDING,
            LiveActivityDeliveryStatus.FAILED,
            LiveActivityDeliveryStatus.DISPATCHING,
            LiveActivityDeliveryStatus.UNCERTAIN,
            LiveActivityDeliveryStatus.EXHAUSTED,
        )
    }
}

@Component
class LiveActivityDispatchWorker(
    private val clientProvider: ObjectProvider<LiveActivityPushClient>,
    private val coordinator: LiveActivityDispatchCoordinator,
    private val notificationTokenService: NotificationTokenService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${notification.live-activity.dispatch-enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${notification.live-activity.batch-size:50}")
    private val batchSize: Int = 50,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun dispatchDue(): LiveActivityDispatchRunResult {
        if (!enabled) return LiveActivityDispatchRunResult(0, false)
        val client = clientProvider.getIfAvailable()
            ?: return LiveActivityDispatchRunResult(0, false)
        val limit = batchSize.coerceIn(1, 200)
        var processed = 0
        val retryTimes = mutableSetOf<Instant>()
        repeat(limit) {
            val lease = coordinator.claimNext(Instant.now(clock))
                ?: return LiveActivityDispatchRunResult(processed, false, retryTimes)
            processed += 1
            dispatch(client, lease)?.let(retryTimes::add)
        }
        return LiveActivityDispatchRunResult(processed, true, retryTimes)
    }

    fun recoverStaleDispatches(): LiveActivityStaleRecoveryResult {
        if (!enabled || clientProvider.getIfAvailable() == null) {
            return LiveActivityStaleRecoveryResult(0)
        }
        return coordinator.recoverStaleDispatches(Instant.now(clock), batchSize)
    }

    private fun dispatch(
        client: LiveActivityPushClient,
        lease: LiveActivityDispatchLease,
    ): Instant? {
        val request = runCatching { lease.toRequest() }.getOrElse {
            coordinator.completePermanentFailure(
                lease,
                "INVALID_FROZEN_PAYLOAD",
                Instant.now(clock),
            )
            return null
        }
        return try {
            val result = client.send(request)
            coordinator.completeSuccess(lease, result.messageId, Instant.now(clock))
            null
        } catch (_: InvalidLiveActivityRouteException) {
            coordinator.completeInvalidActivityRoute(lease, Instant.now(clock))
            null
        } catch (_: InvalidLiveActivityFcmTokenException) {
            coordinator.completeInvalidFcmRoute(lease, Instant.now(clock))
            // The immutable FCM ownership snapshot, not the provider exception body, authorizes
            // retirement. ActivityKit-route failures never enter this branch.
            notificationTokenService.removeTokenByOwnership(
                memberId = lease.memberId,
                tokenId = lease.fcmTokenId,
                tokenFingerprint = lease.fcmTokenFingerprint,
                ownershipVersion = lease.fcmOwnershipVersion,
            )
            null
        } catch (_: RetryableLiveActivityDeliveryException) {
            coordinator.completeRetryableFailure(lease, "PROVIDER_THROTTLED", Instant.now(clock))
        } catch (_: ConfirmedLiveActivityDeliveryException) {
            coordinator.completePermanentFailure(lease, "PROVIDER_REJECTED", Instant.now(clock))
            null
        } catch (_: IllegalArgumentException) {
            coordinator.completePermanentFailure(lease, "PAYLOAD_REJECTED", Instant.now(clock))
            null
        } catch (failure: Exception) {
            // Firebase INTERNAL/UNAVAILABLE after SDK retries can be acceptance-ambiguous. A blind
            // START replay risks a duplicate Activity, so freeze it for client reconciliation.
            val retryAt = coordinator.completeAmbiguous(
                lease,
                failure.javaClass.simpleName.take(120),
                Instant.now(clock),
            )
            log.warn(
                "Live Activity provider result ambiguous. deliveryId={}, operation={}, errorCode={}",
                lease.deliveryId,
                lease.operation,
                failure.javaClass.simpleName,
            )
            retryAt
        }
    }

    private fun LiveActivityDispatchLease.toRequest(): LiveActivityPushRequest =
        LiveActivityPushRequest(
            fcmRegistrationToken = fcmRegistrationToken,
            liveActivityToken = activityToken,
            event = when (operation) {
                LiveActivityDeliveryOperation.START -> LiveActivityPushEvent.START
                LiveActivityDeliveryOperation.UPDATE -> LiveActivityPushEvent.UPDATE
                LiveActivityDeliveryOperation.END -> LiveActivityPushEvent.END
            },
            timestamp = timestamp,
            contentState = readMap(contentStateJson),
            attributesType = activityType.takeIf { operation == LiveActivityDeliveryOperation.START },
            attributes = attributesJson?.let(::readMap),
            alertTitle = alertTitle,
            alertBody = alertBody,
            staleAt = staleAt,
            dismissalAt = dismissalAt,
            expirationAt = expirationAt,
        )

    private fun readMap(json: String): Map<String, Any?> = objectMapper.readValue(
        json,
        object : TypeReference<Map<String, Any?>>() {},
    )
}
