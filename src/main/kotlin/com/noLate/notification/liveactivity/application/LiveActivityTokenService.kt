package com.noLate.notification.liveactivity.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryOperation
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryStatus
import com.noLate.notification.liveactivity.domain.LiveActivityAppearance
import com.noLate.notification.liveactivity.domain.LiveActivityStartToken
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivity
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivityState
import com.noLate.notification.liveactivity.infrastructure.LiveActivityDeliveryRepository
import com.noLate.notification.liveactivity.infrastructure.LiveActivityStartTokenRepository
import com.noLate.notification.liveactivity.infrastructure.ScheduleLiveActivityRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class LiveActivityTokenService(
    private val writer: LiveActivityTokenWriter,
) {
    fun registerStartToken(
        memberId: Long,
        deviceId: String,
        activityType: String,
        pushToStartToken: String,
        schemaVersion: Int,
        presentedSessionGeneration: Long,
        appearance: String? = null,
    ) {
        validateCommon(deviceId, activityType, schemaVersion)
        validateActivityToken(pushToStartToken)
        val parsedAppearance = parseAppearance(appearance)
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val tokenFingerprint = OpaquePushIdentifier.fingerprint(pushToStartToken)
        retryUniqueInsertRace {
            writer.registerStartToken(
                memberId = memberId,
                deviceFingerprint = deviceFingerprint,
                activityType = activityType,
                token = pushToStartToken,
                tokenFingerprint = tokenFingerprint,
                presentedSessionGeneration = presentedSessionGeneration,
                appearance = parsedAppearance,
            )
        }
    }

    fun retireStartToken(
        memberId: Long,
        deviceId: String,
        activityType: String,
        presentedSessionGeneration: Long,
    ) {
        validateCommon(deviceId, activityType, NOLATE_LIVE_ACTIVITY_SCHEMA_VERSION)
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        // Fence the signed session before any lifecycle mutation. The start-token retirement does
        // not retire the ordinary FCM route, so END can still be durably enqueued afterwards.
        writer.retireStartToken(
            memberId,
            deviceFingerprint,
            activityType,
            presentedSessionGeneration,
        )
    }

    fun registerUpdateToken(
        memberId: Long,
        deviceId: String,
        scheduleId: Long,
        activityId: String,
        updateToken: String,
        generation: Long,
        schemaVersion: Int,
        presentedSessionGeneration: Long,
    ) {
        validateCommon(deviceId, NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE, schemaVersion)
        validateActivityId(activityId)
        validateActivityToken(updateToken)
        if (scheduleId <= 0) throw BusinessException(ErrorCode.INVALID_INPUT)
        if (generation < 0) throw BusinessException(ErrorCode.INVALID_INPUT)
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val updateTokenFingerprint = OpaquePushIdentifier.fingerprint(updateToken)
        retryUniqueInsertRace {
            writer.registerUpdateToken(
                memberId = memberId,
                deviceFingerprint = deviceFingerprint,
                scheduleId = scheduleId,
                activityId = activityId,
                updateToken = updateToken,
                updateTokenFingerprint = updateTokenFingerprint,
                generation = generation,
                presentedSessionGeneration = presentedSessionGeneration,
            )
        }
    }

    fun reportEnded(
        memberId: Long,
        deviceId: String,
        scheduleId: Long,
        activityId: String,
        presentedSessionGeneration: Long,
    ) {
        validateDeviceId(deviceId)
        validateActivityId(activityId)
        if (scheduleId <= 0) throw BusinessException(ErrorCode.INVALID_INPUT)
        writer.reportEnded(
            memberId,
            OpaquePushIdentifier.fingerprint(deviceId),
            scheduleId,
            activityId,
            presentedSessionGeneration,
        )
    }

    private fun validateCommon(deviceId: String, activityType: String, schemaVersion: Int) {
        validateDeviceId(deviceId)
        if (
            activityType != NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE ||
            schemaVersion != NOLATE_LIVE_ACTIVITY_SCHEMA_VERSION
        ) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "지원하지 않는 Live Activity 계약입니다.")
        }
    }

    private fun validateDeviceId(deviceId: String) {
        if (deviceId.length !in 1..100 || CONTROL_CHARACTER.containsMatchIn(deviceId)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "유효한 기기 식별자가 필요합니다.")
        }
    }

    private fun validateActivityId(activityId: String) {
        if (!SAFE_ACTIVITY_ID.matches(activityId)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "유효한 Live Activity 식별자가 필요합니다.")
        }
    }

    private fun validateActivityToken(token: String) {
        if (token.length !in 64..500 || token.length % 2 != 0 || !LOWER_HEX.matches(token)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "유효한 ActivityKit 토큰이 필요합니다.")
        }
    }

    private fun parseAppearance(value: String?): LiveActivityAppearance? {
        if (value == null) return null
        return LiveActivityAppearance.fromWireValue(value)
            ?: throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "지원하지 않는 Live Activity 화면 모드입니다.",
            )
    }

    private fun <T> retryUniqueInsertRace(block: () -> T): T = try {
        block()
    } catch (_: DataIntegrityViolationException) {
        // A no-row unique-key race cannot be pessimistically locked. The committed winner now
        // exists, so one fresh transaction acquires the canonical sorted ownership fence.
        block()
    } catch (_: ConcurrencyFailureException) {
        // The candidate owner set may grow in the no-row window. One fresh transaction re-reads
        // and locks the complete sorted member fence; a genuinely busy provider lease still
        // fails closed on this second attempt.
        block()
    }

    private companion object {
        val LOWER_HEX = Regex("[0-9a-f]+")
        val SAFE_ACTIVITY_ID = Regex("[A-Za-z0-9._:-]{1,200}")
        val CONTROL_CHARACTER = Regex("[\\u0000-\\u001f\\u007f]")
    }
}

@Service
class LiveActivityTokenWriter(
    private val memberRepository: MemberRepository,
    private val deviceTokenRepository: NotificationDeviceTokenRepository,
    private val startTokenRepository: LiveActivityStartTokenRepository,
    private val activityRepository: ScheduleLiveActivityRepository,
    private val deliveryRepository: LiveActivityDeliveryRepository,
    private val projectionService: ScheduleLiveActivityService,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${notification.live-activity.retirement-end-hold-seconds:600}")
    private val retirementEndHoldSeconds: Long = 600,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun registerStartToken(
        memberId: Long,
        deviceFingerprint: String,
        activityType: String,
        token: String,
        tokenFingerprint: String,
        presentedSessionGeneration: Long,
        appearance: LiveActivityAppearance? = null,
    ) {
        val fencedMemberIds = (
            startTokenRepository.findRegistrationOwnerMemberIds(
                tokenFingerprint,
                deviceFingerprint,
                activityType,
            ) + memberId
            ).distinct().sorted()
        val members = memberRepository.findAllByIdsForUpdate(fencedMemberIds)
        requireCurrentSession(members.firstOrNull { it.id == memberId }, presentedSessionGeneration)
        val candidateIds = startTokenRepository.findRegistrationCandidateIds(
            tokenFingerprint,
            deviceFingerprint,
            activityType,
        )
        // Candidate snapshots are stable after their sorted member rows are locked. Acquire every
        // affected activity before any FCM/start-token row to match provider completion order.
        val candidateSnapshots = if (candidateIds.isEmpty()) emptyList()
        else startTokenRepository.findAllById(candidateIds)
        if (candidateSnapshots.any { it.memberId !in fencedMemberIds }) {
            // A no-row unique race introduced a new owner after the member-fence snapshot. Never
            // lock that owner's activity out of order; abort so the public writer retry starts
            // with a fresh, sorted owner set.
            throw ConcurrencyFailureException("Live Activity start-token ownership changed.")
        }
        (candidateSnapshots.map { it.memberId to it.deviceFingerprint } +
            (memberId to deviceFingerprint))
            .distinct()
            .sortedWith(compareBy<Pair<Long, String>> { it.first }.thenBy { it.second })
            .forEach { (ownerId, ownerDevice) ->
                activityRepository.findAllByMemberDeviceForUpdate(ownerId, ownerDevice)
            }
        val fcmToken = requireOwnedIosDevice(memberId, deviceFingerprint)
        val candidates = if (candidateIds.isEmpty()) emptyList()
        else startTokenRepository.findAllByIdsForUpdate(candidateIds)
        if (candidates.any { it.memberId !in fencedMemberIds }) {
            throw ConcurrencyFailureException("Live Activity start-token ownership changed.")
        }
        val now = Instant.now(clock)
        // A provider completion may arrive after the lease TTL. Do not rotate the credential
        // until stale-dispatch recovery has cleared the old lease identity and delivery state.
        if (candidates.any { it.dispatchLeaseId != null }) {
            throw ConcurrencyFailureException("Live Activity start-token dispatch is in progress.")
        }
        val preferred = candidates.firstOrNull {
            it.memberId == memberId &&
                it.deviceFingerprint == deviceFingerprint &&
                it.activityType == activityType
        } ?: candidates.firstOrNull { it.tokenFingerprint == tokenFingerprint }
        val duplicates = candidates.filterNot { it.id == preferred?.id }
        if (duplicates.isNotEmpty()) {
            startTokenRepository.deleteAll(duplicates)
            startTokenRepository.flush()
        }
        if (preferred == null) {
            startTokenRepository.saveAndFlush(
                LiveActivityStartToken(
                    memberId = memberId,
                    pushDeviceTokenId = requireNotNull(fcmToken.id),
                    deviceFingerprint = deviceFingerprint,
                    activityType = activityType,
                    pushToStartToken = token,
                    tokenFingerprint = tokenFingerprint,
                    lastSeenAt = now,
                    appearance = appearance,
                )
            )
        } else {
            preferred.rotate(
                memberId = memberId,
                pushDeviceTokenId = requireNotNull(fcmToken.id),
                deviceFingerprint = deviceFingerprint,
                token = token,
                tokenFingerprint = tokenFingerprint,
                seenAt = now,
                appearance = appearance,
            )
            startTokenRepository.saveAndFlush(preferred)
        }
        // Reconcile even when the ActivityKit credential is identical: the same FCM row may have
        // advanced its token fingerprint/ownership epoch. The projection itself is exact-event
        // idempotent, so an unchanged route cannot reset provider backoff or attempt limits.
        projectionService.reconcileStartToken(memberId, deviceFingerprint)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun retireStartToken(
        memberId: Long,
        deviceFingerprint: String,
        activityType: String,
        presentedSessionGeneration: Long,
    ) {
        requireCurrentSession(
            memberRepository.findByIdForUpdate(memberId),
            presentedSessionGeneration,
        )
        val activities = activityRepository.findAllByMemberDeviceForUpdate(
            memberId,
            deviceFingerprint,
        )
        activities.map { it.pushDeviceTokenId }.distinct().sorted()
            .forEach(deviceTokenRepository::findByIdForUpdate)
        val token = startTokenRepository.findByMemberDeviceTypeForUpdate(
            memberId,
            deviceFingerprint,
            activityType,
        )
        if (token != null) {
            if (token.requestRetirement(Instant.now(clock))) {
                startTokenRepository.delete(token)
            } else {
                startTokenRepository.save(token)
            }
            startTokenRepository.flush()
        }
        // The credential retirement and every device-scoped END delivery commit together. A
        // process stop cannot leave the start token retired while its lock-screen cards survive.
        projectionService.endForMemberDevice(
            memberId,
            deviceFingerprint,
            "START_TOKEN_RETIRED",
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun registerUpdateToken(
        memberId: Long,
        deviceFingerprint: String,
        scheduleId: Long,
        activityId: String,
        updateToken: String,
        updateTokenFingerprint: String,
        generation: Long,
        presentedSessionGeneration: Long,
    ): Long {
        val fencedMemberIds = (
            activityRepository.findUpdateTokenOwnerMemberIds(updateTokenFingerprint) + memberId
            ).distinct().sorted()
        val members = memberRepository.findAllByIdsForUpdate(fencedMemberIds)
        val member = members.firstOrNull { it.id == memberId }
            ?: throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        val currentSession = !member.deleted &&
            member.sessionGeneration == presentedSessionGeneration
        val immediatelyRetiredSession = member.sessionGeneration > 0 &&
            presentedSessionGeneration == member.sessionGeneration - 1
        if (!currentSession && !immediatelyRetiredSession) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        }
        val targetSnapshot = activityRepository.findByScheduleIdAndMemberIdAndDeviceFingerprint(
            scheduleId,
            memberId,
            deviceFingerprint,
        ) ?: throw BusinessException(ErrorCode.INVALID_STATE, "시작 대기 중인 Live Activity가 없습니다.")
        val collisionSnapshot = activityRepository.findByUpdateTokenFingerprint(updateTokenFingerprint)
        if (collisionSnapshot != null && collisionSnapshot.memberId !in fencedMemberIds) {
            // Never acquire a foreign activity lock unless its member row participated in the
            // initial sorted fence. A fresh writer transaction will include the concurrent owner.
            throw ConcurrencyFailureException("Live Activity update-token ownership changed.")
        }
        val lockedActivities = activityRepository.findAllByIdsForUpdate(
            listOfNotNull(targetSnapshot.id, collisionSnapshot?.id).distinct().sorted(),
        )
        val activity = lockedActivities.firstOrNull { it.id == targetSnapshot.id }
            ?: throw ConcurrencyFailureException("Live Activity desired state changed.")
        val collision = lockedActivities.firstOrNull { it.id == collisionSnapshot?.id }
        val exactEndCleanup =
            activity.state == ScheduleLiveActivityState.END_PENDING &&
            activity.sourceNotificationGeneration == generation
        val lateEndCleanup = !currentSession && exactEndCleanup &&
            activity.hasLateEndAckAuthorization(presentedSessionGeneration)
        if (!currentSession && !lateEndCleanup) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        }
        if (collision != null && collision.id != activity.id) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "ActivityKit 토큰 소유권이 일치하지 않습니다.")
        }
        if (activity.activityId != null && activity.activityId != activityId) {
            throw BusinessException(ErrorCode.INVALID_STATE, "Live Activity 인스턴스가 일치하지 않습니다.")
        }
        if (activity.sourceNotificationGeneration != generation) {
            throw BusinessException(
                ErrorCode.CONCURRENT_MODIFICATION,
                "Live Activity 세대가 변경되었습니다.",
            )
        }
        if (
            activity.state == ScheduleLiveActivityState.ENDED ||
            activity.state == ScheduleLiveActivityState.INVALID
        ) {
            throw BusinessException(ErrorCode.INVALID_STATE, "종료된 Live Activity입니다.")
        }
        val inFlightLeaseId = activity.dispatchLeaseId
        val currentFcm = if (inFlightLeaseId != null) {
            // The provider call freezes this exact route. A same-device replacement cannot be
            // accepted until the immutable START attempt is reconciled or recovered.
            deviceTokenRepository.findByIdForUpdate(activity.pushDeviceTokenId)
                ?.takeIf {
                    it.memberId == memberId &&
                        it.deviceFingerprint == deviceFingerprint &&
                        it.platform == PushPlatform.IOS &&
                        (!it.retirementRequested || exactEndCleanup) &&
                        (!lateEndCleanup ||
                            (it.id == activity.lateEndAckPushDeviceTokenId &&
                                it.ownershipVersion ==
                                activity.lateEndAckFcmOwnershipVersion))
                } ?: throw BusinessException(
                ErrorCode.FORBIDDEN,
                "현재 계정에 등록된 iOS 푸시 기기가 아닙니다.",
            )
        } else {
            val deviceRoutes = deviceTokenRepository
                .findAllByMemberIdAndDeviceFingerprintForUpdate(memberId, deviceFingerprint)
            val usableRoute = deviceRoutes.singleOrNull {
                it.platform == PushPlatform.IOS &&
                    (!lateEndCleanup ||
                        (it.id == activity.lateEndAckPushDeviceTokenId &&
                            it.ownershipVersion == activity.lateEndAckFcmOwnershipVersion)) &&
                    (
                        !it.retirementRequested ||
                            (
                                exactEndCleanup &&
                                    it.id == activity.pushDeviceTokenId &&
                                    it.dispatchLeaseId == liveActivityEndRetirementReservation(
                                        memberId,
                                        deviceFingerprint,
                                    )
                                )
                        )
            }
            if (usableRoute != null) {
                usableRoute
            } else {
                // An authenticated exact-generation ActivityKit credential may arrive after its
                // provider-invalid FCM row was physically deleted. First prove that the exact row
                // is truly absent (an empty member-scoped lookup must not mistake a foreign-owned
                // row for deletion), then persist only the credential until a replacement route
                // is registered. No provider delivery is fabricated without an FCM epoch.
                val exactRoute = deviceTokenRepository.findByIdForUpdate(
                    activity.pushDeviceTokenId,
                )
                val globalDeviceRouteIds = deviceTokenRepository.findIdsByDeviceFingerprint(
                    deviceFingerprint,
                )
                val exactRetiredUnreservedRoute = deviceRoutes.singleOrNull {
                    it.id == activity.pushDeviceTokenId &&
                        it.memberId == memberId &&
                        it.deviceFingerprint == deviceFingerprint &&
                        it.platform == PushPlatform.IOS &&
                        it.retirementRequested &&
                        it.dispatchLeaseId == null &&
                        it.dispatchLeaseUntil == null
                }
                val durableEndCredentialOnly = exactEndCleanup &&
                    (
                        (deviceRoutes.isEmpty() && exactRoute == null &&
                            globalDeviceRouteIds.isEmpty()) ||
                            exactRetiredUnreservedRoute != null
                        )
                val durableCurrentCredentialOnly = currentSession &&
                    deviceRoutes.isEmpty() &&
                    exactRoute == null &&
                    globalDeviceRouteIds.isEmpty() &&
                    activity.state in setOf(
                        ScheduleLiveActivityState.START_SENT,
                        ScheduleLiveActivityState.ACTIVE,
                        ScheduleLiveActivityState.TOKEN_PENDING,
                    )
                if (!durableEndCredentialOnly && !durableCurrentCredentialOnly) {
                    throw BusinessException(
                        ErrorCode.FORBIDDEN,
                        "현재 계정에 등록된 iOS 푸시 기기가 아닙니다.",
                    )
                }
                null
            }
        }
        if (inFlightLeaseId != null) {
            val now = Instant.now(clock)
            val exactInFlightFcm = requireNotNull(currentFcm)
            if (activity.updateToken == null) {
                reconcileExactInFlightStart(
                    activity = activity,
                    fcm = exactInFlightFcm,
                    leaseId = inFlightLeaseId,
                    generation = generation,
                    now = now,
                )
            } else {
                reconcileExactInFlightUpdateOrEnd(
                    activity = activity,
                    fcm = exactInFlightFcm,
                    leaseId = inFlightLeaseId,
                    generation = generation,
                    incomingTokenFingerprint = updateTokenFingerprint,
                    now = now,
                )
            }
        }
        // Re-registering an unchanged token is an idempotent success. Rotation only advances the
        // token generation when ActivityKit actually supplied a new credential.
        // The ordinary FCM row can be replaced after UNREGISTERED. This authenticated exact
        // ActivityKit callback rebinds the same-device route before materializing the current
        // ownership-epoch UPDATE/END delivery.
        if (currentFcm != null) {
            activity.bindFcmRoute(
                requireNotNull(currentFcm.id),
                currentFcm.ownershipVersion,
            )
        }
        activity.bindUpdateToken(activityId, updateToken)
        val rowId = requireNotNull(activityRepository.saveAndFlush(activity).id)
        // Binding a late token and materializing its UPDATE/END outbox row are one transaction.
        // In particular, END_PENDING must never become a durable tombstone without a dispatch.
        if (currentFcm != null) projectionService.enqueueCurrentUpdate(rowId)
        return rowId
    }

    /**
     * An authenticated ActivityKit update token proves that the remote START reached this exact
     * member/device/schedule/generation even when Firebase has not returned yet. Reconcile only a
     * fully matching immutable START lease; every partial or foreign proof remains fail-closed.
     *
     * Marking the delivery first makes a later provider completion a no-op. All three provider
     * leases and the subsequent ACTIVE/END_PENDING token bind commit in this writer transaction.
     */
    private fun reconcileExactInFlightStart(
        activity: ScheduleLiveActivity,
        fcm: NotificationDeviceToken,
        leaseId: String,
        generation: Long,
        now: Instant,
    ) {
        val rowId = requireNotNull(activity.id)
        val startTokenId = activity.startTokenId
            ?: throw ConcurrencyFailureException("Live Activity START dispatch proof is incomplete.")
        val startToken = startTokenRepository.findByIdForUpdate(startTokenId)
            ?: throw ConcurrencyFailureException("Live Activity START credential disappeared.")
        val delivery = deliveryRepository.findAllOpenByActivityForUpdate(
            rowId,
            setOf(LiveActivityDeliveryStatus.DISPATCHING),
        ).singleOrNull {
            it.operation == LiveActivityDeliveryOperation.START && it.ownsLease(leaseId)
        } ?: throw ConcurrencyFailureException("Live Activity START dispatch proof is ambiguous.")

        val stateAcceptsClientProof =
            activity.state == ScheduleLiveActivityState.START_PENDING ||
                activity.state == ScheduleLiveActivityState.END_PENDING
        val revisionMatches =
            activity.state == ScheduleLiveActivityState.END_PENDING ||
                activity.contentRevision == delivery.revision
        val exactProof = stateAcceptsClientProof && revisionMatches &&
            activity.dispatchLeaseId == leaseId &&
            activity.sourceNotificationGeneration == generation &&
            delivery.liveActivityId == rowId &&
            delivery.memberId == activity.memberId &&
            delivery.scheduleId == activity.scheduleId &&
            delivery.deviceFingerprint == activity.deviceFingerprint &&
            delivery.sourceNotificationGeneration == generation &&
            delivery.pushDeviceTokenId == fcm.id &&
            delivery.fcmTokenFingerprint == fcm.tokenFingerprint &&
            delivery.fcmOwnershipVersion == fcm.ownershipVersion &&
            activity.boundFcmOwnershipVersion == delivery.fcmOwnershipVersion &&
            fcm.dispatchLeaseId == leaseId &&
            delivery.startTokenId == startToken.id &&
            delivery.startTokenId == activity.startTokenId &&
            delivery.startTokenGeneration == startToken.tokenGeneration &&
            delivery.startTokenGeneration == activity.startTokenGeneration &&
            delivery.activityTokenFingerprint == startToken.tokenFingerprint &&
            delivery.activityTokenFingerprint == activity.startTokenFingerprint &&
            startToken.memberId == activity.memberId &&
            startToken.deviceFingerprint == activity.deviceFingerprint &&
            startToken.pushDeviceTokenId == fcm.id &&
            startToken.dispatchLeaseId == leaseId
        if (!exactProof) {
            throw ConcurrencyFailureException("Live Activity START dispatch proof does not match.")
        }

        delivery.markClientReconciled(now, "CLIENT_CONFIRMED_START")
        activity.releaseDispatchLease(leaseId)
        check(fcm.releaseDispatchLease(leaseId))
        check(startToken.releaseDispatchLease(leaseId))
        if (startToken.retirementRequested) {
            startTokenRepository.delete(startToken)
            startTokenRepository.flush()
        }
        if (fcm.retirementRequested) {
            check(activity.state == ScheduleLiveActivityState.END_PENDING)
            check(
                fcm.restoreRetirementDispatchReservation(
                    liveActivityEndRetirementReservation(
                        activity.memberId,
                        activity.deviceFingerprint,
                    ),
                    now.plusSeconds(retirementEndHoldSeconds),
                )
            )
        }
    }

    /** A rotated ActivityKit credential supersedes only its exact in-flight UPDATE/END epoch. */
    private fun reconcileExactInFlightUpdateOrEnd(
        activity: ScheduleLiveActivity,
        fcm: NotificationDeviceToken,
        leaseId: String,
        generation: Long,
        incomingTokenFingerprint: String,
        now: Instant,
    ) {
        val rowId = requireNotNull(activity.id)
        val currentTokenFingerprint = activity.updateTokenFingerprint
            ?: throw ConcurrencyFailureException("Live Activity UPDATE credential disappeared.")
        if (currentTokenFingerprint == incomingTokenFingerprint) {
            // A repeated credential does not prove whether the old provider UPDATE was accepted.
            // Only a genuine ActivityKit rotation creates a new immutable route for replanning.
            throw ConcurrencyFailureException("Live Activity UPDATE dispatch is still in progress.")
        }
        val delivery = deliveryRepository.findAllOpenByActivityForUpdate(
            rowId,
            setOf(LiveActivityDeliveryStatus.DISPATCHING),
        ).singleOrNull {
            it.operation in setOf(
                LiveActivityDeliveryOperation.UPDATE,
                LiveActivityDeliveryOperation.END,
            ) && it.ownsLease(leaseId)
        } ?: throw ConcurrencyFailureException(
            "Live Activity UPDATE/END dispatch proof is ambiguous.",
        )

        val stateAcceptsRotation = when (delivery.operation) {
            LiveActivityDeliveryOperation.UPDATE ->
                activity.state == ScheduleLiveActivityState.ACTIVE ||
                    activity.state == ScheduleLiveActivityState.END_PENDING
            LiveActivityDeliveryOperation.END ->
                activity.state == ScheduleLiveActivityState.END_PENDING
            LiveActivityDeliveryOperation.START -> false
        }
        val revisionMatches = when (delivery.operation) {
            // Cancellation can advance terminal content while an UPDATE is in flight. A genuine
            // token rotation still proves the old credential epoch and replans the desired END.
            LiveActivityDeliveryOperation.UPDATE ->
                activity.state == ScheduleLiveActivityState.END_PENDING ||
                    activity.contentRevision == delivery.revision
            LiveActivityDeliveryOperation.END -> activity.contentRevision == delivery.revision
            LiveActivityDeliveryOperation.START -> false
        }
        val exactProof = stateAcceptsRotation && revisionMatches &&
            activity.dispatchLeaseId == leaseId &&
            activity.sourceNotificationGeneration == generation &&
            delivery.liveActivityId == rowId &&
            delivery.memberId == activity.memberId &&
            delivery.scheduleId == activity.scheduleId &&
            delivery.deviceFingerprint == activity.deviceFingerprint &&
            delivery.sourceNotificationGeneration == generation &&
            delivery.pushDeviceTokenId == fcm.id &&
            delivery.fcmTokenFingerprint == fcm.tokenFingerprint &&
            delivery.fcmOwnershipVersion == fcm.ownershipVersion &&
            activity.boundFcmOwnershipVersion == delivery.fcmOwnershipVersion &&
            fcm.dispatchLeaseId == leaseId &&
            delivery.targetActivityId == activity.activityId &&
            delivery.activityTokenFingerprint == currentTokenFingerprint &&
            delivery.updateTokenGeneration == activity.updateTokenGeneration
        if (!exactProof) {
            throw ConcurrencyFailureException(
                "Live Activity UPDATE/END dispatch proof does not match.",
            )
        }

        delivery.markClientReconciled(
            now,
            if (delivery.operation == LiveActivityDeliveryOperation.END) {
                "CLIENT_ROTATED_END_TOKEN"
            } else {
                "CLIENT_ROTATED_UPDATE_TOKEN"
            },
        )
        activity.releaseDispatchLease(leaseId)
        check(fcm.releaseDispatchLease(leaseId))
        if (fcm.retirementRequested) {
            check(activity.state == ScheduleLiveActivityState.END_PENDING)
            check(
                fcm.restoreRetirementDispatchReservation(
                    liveActivityEndRetirementReservation(
                        activity.memberId,
                        activity.deviceFingerprint,
                    ),
                    now.plusSeconds(retirementEndHoldSeconds),
                )
            )
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reportEnded(
        memberId: Long,
        deviceFingerprint: String,
        scheduleId: Long,
        activityId: String,
        presentedSessionGeneration: Long,
    ) {
        val member = memberRepository.findByIdForUpdate(memberId)
            ?: throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        val currentSession = !member.deleted &&
            member.sessionGeneration == presentedSessionGeneration
        val immediatelyRetiredSession = member.sessionGeneration > 0 &&
            presentedSessionGeneration == member.sessionGeneration - 1
        if (!currentSession && !immediatelyRetiredSession) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        }
        val snapshot = activityRepository.findByMemberIdAndActivityIdAndDeviceFingerprint(
            memberId,
            activityId,
            deviceFingerprint,
        )?.takeIf { it.scheduleId == scheduleId }
            ?: return
        val activity = snapshot.id?.let(activityRepository::findByIdForUpdate)
            ?.takeIf { it.scheduleId == scheduleId && it.activityId == activityId }
            ?: return
        if (!currentSession && activity.state != ScheduleLiveActivityState.END_PENDING) {
            // A request authenticated before the immediately preceding session retirement may
            // finish only the exact tombstone it already observed; it cannot mutate live state.
            if (activity.state == ScheduleLiveActivityState.ENDED) return
            throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        }
        val now = Instant.now(clock)
        val activeLeaseId = activity.dispatchLeaseId
        val exactRouteRow = deviceTokenRepository.findByIdForUpdate(activity.pushDeviceTokenId)
        val fcm = exactRouteRow?.takeIf {
                it.memberId == memberId &&
                    it.deviceFingerprint == deviceFingerprint &&
                    it.platform == PushPlatform.IOS
            }
        if (!currentSession) {
            val reservation = liveActivityEndRetirementReservation(memberId, deviceFingerprint)
            val durableFrozenProof = activity.hasLateEndAckAuthorization(
                presentedSessionGeneration,
            )
            val exactRetiredEndRoute = fcm != null &&
                fcm.id == activity.lateEndAckPushDeviceTokenId &&
                fcm.ownershipVersion == activity.lateEndAckFcmOwnershipVersion &&
                fcm.retirementRequested &&
                (
                    fcm.dispatchLeaseId == reservation ||
                        (activeLeaseId != null && fcm.dispatchLeaseId == activeLeaseId) ||
                        (
                            activeLeaseId == null &&
                                fcm.dispatchLeaseId == null &&
                                fcm.dispatchLeaseUntil == null
                        )
                )
            val durableMissingRouteSessionProof = exactRouteRow == null && durableFrozenProof
            if (
                !durableFrozenProof ||
                (!exactRetiredEndRoute && !durableMissingRouteSessionProof)
            ) {
                throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
            }
        }
        activity.markEnded(now, "CLIENT_ENDED")
        activity.id?.let { rowId ->
            deliveryRepository.findAllOpenByActivityForUpdate(
                rowId,
                CLIENT_RECONCILABLE_DELIVERY_STATUSES,
            ).forEach { it.markClientReconciled(now, "CLIENT_ENDED") }
        }
        if (fcm != null) {
            val reservation = liveActivityEndRetirementReservation(memberId, deviceFingerprint)
            when {
                activeLeaseId != null && fcm.dispatchLeaseId == activeLeaseId ->
                    fcm.releaseDispatchLease(activeLeaseId)
                fcm.retirementRequested && fcm.dispatchLeaseId == reservation ->
                    fcm.releaseDispatchLease(reservation)
            }
        }
        activityRepository.flush()
        deliveryRepository.flush()
        deviceTokenRepository.flush()
    }

    private fun requireCurrentSession(
        member: com.noLate.member.domain.member.Member?,
        presentedSessionGeneration: Long,
    ) {
        if (
            member == null || member.deleted ||
            member.sessionGeneration != presentedSessionGeneration
        ) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        }
    }

    private fun requireOwnedIosDevice(
        memberId: Long,
        deviceFingerprint: String,
        allowRetiredTokenId: Long? = null,
    ) =
        deviceTokenRepository.findAllByMemberIdAndDeviceFingerprintForUpdate(
            memberId,
            deviceFingerprint,
        ).singleOrNull {
            it.platform == PushPlatform.IOS &&
                (
                    !it.retirementRequested ||
                        (
                            allowRetiredTokenId != null &&
                                it.id == allowRetiredTokenId &&
                                it.dispatchLeaseId == liveActivityEndRetirementReservation(
                                    memberId,
                                    deviceFingerprint,
                                )
                            )
                    )
        } ?: throw BusinessException(
            ErrorCode.FORBIDDEN,
            "현재 계정에 등록된 iOS 푸시 기기가 아닙니다.",
        )

    private companion object {
        val CLIENT_RECONCILABLE_DELIVERY_STATUSES = setOf(
            LiveActivityDeliveryStatus.PENDING,
            LiveActivityDeliveryStatus.DISPATCHING,
            LiveActivityDeliveryStatus.FAILED,
            LiveActivityDeliveryStatus.UNCERTAIN,
            LiveActivityDeliveryStatus.INVALID,
            LiveActivityDeliveryStatus.EXHAUSTED,
        )
    }
}
