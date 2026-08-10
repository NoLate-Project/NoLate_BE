package com.noLate.notification.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.domain.CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryOperation
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryStatus
import com.noLate.notification.liveactivity.infrastructure.LiveActivityDeliveryRepository
import com.noLate.notification.liveactivity.application.ScheduleLiveActivityService
import com.noLate.notification.liveactivity.application.liveActivityEndRetirementReservation
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.TransientDataAccessException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class NotificationTokenService (
    private val notificationRepository: NotificationDeviceTokenRepository,
    private val writer: NotificationTokenWriter,
    private val retirementService: NotificationTokenRetirementService,
    @Value("\${notification.push-token.dispatch-lease-wait-millis:70000}")
    private val dispatchLeaseWaitMillis: Long = 70_000,
    @Value("\${notification.push-token.dispatch-lease-poll-millis:25}")
    private val dispatchLeasePollMillis: Long = 25,
    @Value("\${notification.push-token.provider-max-call-seconds:60}")
    private val providerMaxCallSeconds: Long = 60,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(dispatchLeaseWaitMillis > providerMaxCallSeconds * 1_000) {
            "Push token ownership wait must outlive the bounded provider call."
        }
    }

    /**
     * FCM/APNs/Expo 등에서 받은 토큰 등록/갱신
     *
     * - 같은 (memberId + deviceId) 조합이 있으면 → token / platform 갱신
     * - 같은 token 또는 deviceId의 중복 row는 현재 등록 한 건으로 수렴
     * - 같은 token/device가 다른 회원에게 남아 있으면 이전 소유권 제거
     * - deviceId가 없어도 동일 token은 한 row만 유지
     */
    fun registerToken(
        memberId: Long,
        deviceId: String?,
        platform: PushPlatform,
        token: String,
        accessTokenIssuedAt: Instant,
        accessTokenSessionGeneration: Long,
        deliveryAckCapabilityVersion: Int? = null,
    ) {
        // deviceId는 대소문자와 앞뒤 공백까지 UTF-8 byte identity의 일부인 opaque 값이다.
        // 빈 문자열만 optional null과 동일하게 보고, 그 외 값을 trim/case-fold하지 않는다.
        val normalizedDeviceId = deviceId?.takeIf { it.isNotEmpty() }
        if (
            deliveryAckCapabilityVersion != null &&
            deliveryAckCapabilityVersion != CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION
        ) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "지원하지 않는 푸시 수신 확인 capability 버전입니다.",
            )
        }
        if (
            deliveryAckCapabilityVersion != null &&
            (normalizedDeviceId == null || normalizedDeviceId.length > 100)
        ) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "푸시 수신 확인 capability에는 유효한 기기 식별자가 필요합니다.",
            )
        }
        val tokenFingerprint = OpaquePushIdentifier.fingerprint(token)
        val deviceFingerprint = normalizedDeviceId?.let(OpaquePushIdentifier::fingerprint)
        var concurrencyAttempt = 0
        val leaseWaitDeadline =
            System.nanoTime() + dispatchLeaseWaitMillis.coerceAtLeast(0) * 1_000_000
        while (true) {
            try {
                val result = writer.register(
                    memberId = memberId,
                    deviceId = normalizedDeviceId,
                    deviceFingerprint = deviceFingerprint,
                    platform = platform,
                    token = token,
                    tokenFingerprint = tokenFingerprint,
                    accessTokenIssuedAt = accessTokenIssuedAt,
                    accessTokenSessionGeneration = accessTokenSessionGeneration,
                    deliveryAckCapabilityVersion = deliveryAckCapabilityVersion,
                )
                if (result.retryAfterCommit) {
                    if (System.nanoTime() >= leaseWaitDeadline) {
                        throw ConcurrencyFailureException(
                            "Push token ownership transfer is waiting for Live Activity END.",
                        )
                    }
                    try {
                        Thread.sleep(dispatchLeasePollMillis.coerceIn(1, 250))
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw ConcurrencyFailureException(
                            "Push token ownership transfer was interrupted.",
                        )
                    }
                    continue
                }
                logRegistration(
                    memberId,
                    normalizedDeviceId,
                    platform,
                    result.removedOwnershipCount,
                    result.result,
                )
                return
            } catch (ex: PushTokenDispatchLeaseActiveException) {
                if (System.nanoTime() >= leaseWaitDeadline) {
                    throw ConcurrencyFailureException(
                        "Push token ownership transfer timed out waiting for active dispatch.",
                    )
                }
                try {
                    Thread.sleep(dispatchLeasePollMillis.coerceIn(1, 250))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ConcurrencyFailureException(
                        "Push token ownership transfer was interrupted.",
                    )
                }
            } catch (ex: RuntimeException) {
                if (!ex.isExpectedTokenFingerprintCollision() &&
                    ex !is TransientDataAccessException
                ) {
                    throw ex
                }
                concurrencyAttempt += 1
                // 빈 key-range에 대한 동시 insert는 fingerprint unique에서 한 caller가 진다.
                // deadlock/lock-timeout도 실패 transaction을 버린 뒤 fresh REQUIRES_NEW에서
                // canonical row를 다시 잠근다. raw token/device는 오류에 포함하지 않는다.
                log.warn(
                    "Push token registration concurrency retry. attempt={}, failureType={}",
                    concurrencyAttempt,
                    ex.javaClass.simpleName,
                )
                if (concurrencyAttempt >= 3) {
                    throw ConcurrencyFailureException(
                        "Push token registration did not converge after bounded retries.",
                    )
                }
            }
        }
    }

    /**
     * 특정 기기(deviceId)의 토큰 제거 (로그아웃 시 이 기기만 로그아웃 같은 용도)
     */
    @Transactional
    fun removeToken(memberId: Long, deviceId: String) {
        retirementService.retireByDeviceFingerprint(
            memberId,
            OpaquePushIdentifier.fingerprint(deviceId),
        )
    }

    /**
     * 회원의 모든 기기 토큰 제거 (회원 탈퇴, 강제 로그아웃 등)
     */
    @Transactional
    fun removeAllTokensByMember(memberId: Long) {
        retirementService.retireAllByMember(memberId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun removeTokenValue(memberId: Long, token: String) {
        retirementService.retireByTokenFingerprint(
            memberId,
            OpaquePushIdentifier.fingerprint(token),
        )
    }

    /**
     * 무효 응답은 provider 호출 때 검증한 ownership snapshot과 여전히 같을 때만 삭제한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun removeTokenByOwnership(
        memberId: Long,
        tokenId: Long,
        tokenFingerprint: String,
        ownershipVersion: Long,
    ): Boolean =
        retirementService.retireByOwnership(
            memberId = memberId,
            tokenId = tokenId,
            tokenFingerprint = tokenFingerprint,
            ownershipVersion = ownershipVersion,
        )

    /**
     * 해당 회원의 모든 기기 토큰 조회
     */
    @Transactional(readOnly = true)
    fun getTokensByMember(memberId: Long): List<NotificationDeviceToken> {
        return notificationRepository.findAllByMemberIdAndRetirementRequestedFalse(memberId)
    }

    private fun logRegistration(
        memberId: Long,
        deviceId: String?,
        platform: PushPlatform,
        removedOwnershipCount: Int,
        result: String,
    ) {
        // FCM 토큰 자체는 인증 정보이므로 로그에 남기지 않는다. 계정/기기 매핑 여부만으로
        // 실기기 등록 누락과 계정 전환 시 소유권 이동을 추적할 수 있게 한다.
        log.info(
            "Push token registered. memberId={}, deviceIdPresent={}, platform={}, removedOwnershipCount={}, result={}",
            memberId,
            !deviceId.isNullOrBlank(),
            platform,
            removedOwnershipCount,
            result,
        )
    }
}

private fun RuntimeException.isExpectedTokenFingerprintCollision(): Boolean {
    if (this is DuplicateKeyException) return true

    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        val constraintName = (current as? ConstraintViolationException)?.constraintName
        if (constraintName != null &&
            TOKEN_FINGERPRINT_UNIQUE_CONSTRAINTS.any {
                constraintName.contains(it, ignoreCase = true)
            }
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

private val TOKEN_FINGERPRINT_UNIQUE_CONSTRAINTS = setOf(
    "uk_push_device_token_token_fingerprint",
    "uk_push_device_token_device_fingerprint",
)

data class NotificationTokenRegistrationResult(
    val removedOwnershipCount: Int,
    val result: String,
    val retryAfterCommit: Boolean = false,
)

@Service
class NotificationTokenWriter(
    private val notificationRepository: NotificationDeviceTokenRepository,
    private val memberRepository: MemberRepository,
    private val registrationObserver: NotificationTokenRegistrationObserver? = null,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    private val liveActivityDeliveryRepository: LiveActivityDeliveryRepository? = null,
    private val scheduleLiveActivityService: ScheduleLiveActivityService? = null,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun register(
        memberId: Long,
        deviceId: String?,
        deviceFingerprint: String?,
        platform: PushPlatform,
        token: String,
        tokenFingerprint: String,
        accessTokenIssuedAt: Instant,
        accessTokenSessionGeneration: Long,
        deliveryAckCapabilityVersion: Int? = null,
    ): NotificationTokenRegistrationResult {
        /*
         * 후보 ownership을 non-locking snapshot으로 먼저 읽고 모든 관련 member row를 ID
         * 오름차순으로 잠근다. logout과 registration 모두 member -> token 순서다.
         * snapshot 이후 새 owner가 나타나면 token row를 수정하지 않고 transient retry한다.
         */
        val snapshotOwnerIds =
            notificationRepository.findRegistrationCandidateOwnerMemberIds(
                tokenFingerprint,
                deviceFingerprint,
            )
        val liveActivityHandoffOwnerIds = if (
            platform == PushPlatform.IOS &&
            deviceFingerprint != null &&
            scheduleLiveActivityService != null
        ) {
            scheduleLiveActivityService.findLiveActivityHandoffOwnerMemberIdsForRegistration(
                deviceFingerprint,
            )
        } else {
            emptyList()
        }
        val memberFenceIds = (
            snapshotOwnerIds + liveActivityHandoffOwnerIds +
                listOf(memberId)
            )
            .distinct()
            .sorted()
        val lockedMembers = if (memberFenceIds.isEmpty()) {
            emptyList()
        } else {
            memberRepository.findAllByIdsForUpdate(memberFenceIds)
        }

        val member = lockedMembers.firstOrNull { it.id == memberId }
            ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        if (member.deleted ||
            member.sessionGeneration != accessTokenSessionGeneration
        ) {
            throw BusinessException(ErrorCode.INVALID_TOKEN)
        }
        // iat는 서명된 요청 provenance로 전달되지만 초 단위 정밀도 때문에 순서 판정에는 쓰지
        // 않는다. signed session generation이 logout과의 단조 fence다.
        // 테스트 hook도 member row lock을 획득한 뒤에만 실행된다. 운영 bean이 없으면 no-op이다.
        registrationObserver?.afterMemberSessionFence(memberId)
        if (
            platform == PushPlatform.IOS &&
            deviceFingerprint != null &&
            scheduleLiveActivityService != null
        ) {
            val currentHandoffOwnerIds =
                scheduleLiveActivityService.findLiveActivityHandoffOwnerMemberIdsForRegistration(
                    deviceFingerprint,
                )
            if (currentHandoffOwnerIds.any { it !in memberFenceIds }) {
                throw CannotAcquireLockException(
                    "Live Activity handoff owner appeared after member fencing.",
                )
            }
        }

        // 전역 lock order: member row -> token fingerprint/device rows.
        val candidateIds = notificationRepository.findRegistrationCandidateIds(
            tokenFingerprint,
            deviceFingerprint,
        )
        val candidateSnapshots = if (candidateIds.isEmpty()) emptyList()
        else notificationRepository.findAllById(candidateIds)
        if (candidateSnapshots.any { it.memberId !in memberFenceIds }) {
            throw CannotAcquireLockException(
                "Push token ownership changed during registration fencing.",
            )
        }
        val oldOwnerCandidates = candidateSnapshots.filter { it.memberId != memberId }
        val ownershipEndRouteIds = scheduleLiveActivityService
            ?.prepareEndBeforeFcmOwnershipTransfer(
                oldOwnerCandidates.map { it.memberId }.distinct().sorted(),
                oldOwnerCandidates.mapNotNull { it.id }.distinct().sorted(),
            )
            .orEmpty()
        val foreignRetiredEndRouteSnapshots = if (
            ownershipEndRouteIds.isNotEmpty() &&
            platform == PushPlatform.IOS &&
            deviceFingerprint != null
        ) {
            candidateSnapshots.filter {
                it.id in ownershipEndRouteIds &&
                    it.memberId != memberId &&
                    it.deviceFingerprint == deviceFingerprint &&
                    it.platform == PushPlatform.IOS &&
                    it.retirementRequested &&
                    (
                        (it.dispatchLeaseId == null && it.dispatchLeaseUntil == null) ||
                            it.dispatchLeaseId == liveActivityEndRetirementReservation(
                                it.memberId,
                                deviceFingerprint,
                            )
                        ) &&
                    it.tokenFingerprint != tokenFingerprint
            }
        } else {
            emptyList()
        }
        val foreignRetiredEndOwnerIds = foreignRetiredEndRouteSnapshots
            .map { it.memberId }
            .distinct()
        val foreignRetiredEndHandoffPlan = if (
            deviceFingerprint != null &&
            foreignRetiredEndRouteSnapshots.isNotEmpty() &&
            foreignRetiredEndOwnerIds.size == 1
        ) {
            scheduleLiveActivityService?.prepareSameDeviceFcmEndHandoff(
                foreignRetiredEndOwnerIds.single(),
                deviceFingerprint,
                foreignRetiredEndRouteSnapshots.mapNotNull { it.id }.distinct().sorted(),
            )
        } else {
            null
        }
        val foreignOrphanEndOwnerIds = liveActivityHandoffOwnerIds
            .filter { it != memberId }
            .distinct()
            .sorted()
        val foreignOrphanEndHandoffPlan = if (
            platform == PushPlatform.IOS &&
            deviceFingerprint != null &&
            candidateSnapshots.isEmpty() &&
            ownershipEndRouteIds.isEmpty() &&
            foreignOrphanEndOwnerIds.size == 1
        ) {
            scheduleLiveActivityService?.prepareForeignOrphanFcmEndHandoff(
                foreignOrphanEndOwnerIds.single(),
                deviceFingerprint,
            )?.takeIf { it.replaceableExistingPushDeviceTokenIds.isEmpty() }
        } else {
            null
        }
        val sameDeviceEndHandoffRouteIds = if (
            ownershipEndRouteIds.isEmpty() &&
            platform == PushPlatform.IOS &&
            deviceFingerprint != null
        ) {
            candidateSnapshots.filter {
                    it.memberId == memberId &&
                    it.deviceFingerprint == deviceFingerprint &&
                    it.platform == PushPlatform.IOS &&
                    (
                        !it.retirementRequested ||
                            it.tokenFingerprint != tokenFingerprint ||
                            it.dispatchLeaseId == liveActivityEndRetirementReservation(
                                memberId,
                                deviceFingerprint,
                            )
                        )
            }.mapNotNull { it.id }.distinct().sorted()
        } else {
            emptyList()
        }
        // Lock END tombstones before the FCM rows. applySameDeviceFcmEndHandoff runs only after
        // the exact retired route is validated and rotated below, in this same transaction.
        val sameDeviceEndHandoffPlan = if (
            ownershipEndRouteIds.isEmpty() &&
            platform == PushPlatform.IOS &&
            deviceFingerprint != null
        ) {
            scheduleLiveActivityService?.prepareSameDeviceFcmEndHandoff(
                memberId,
                deviceFingerprint,
                sameDeviceEndHandoffRouteIds,
            )
        } else {
            null
        }
        val sameDeviceOrphanRecoveryPlan = if (
            ownershipEndRouteIds.isEmpty() &&
            platform == PushPlatform.IOS &&
            deviceFingerprint != null
        ) {
            scheduleLiveActivityService?.prepareSameDeviceFcmOrphanRecovery(
                memberId,
                deviceFingerprint,
            )
        } else {
            null
        }
        val candidates = if (candidateIds.isEmpty()) {
            emptyList()
        } else {
            notificationRepository.findAllByIdsForUpdate(candidateIds)
        }
        if (candidates.any { it.memberId !in memberFenceIds }) {
            throw CannotAcquireLockException(
                "Push token ownership changed during registration fencing.",
            )
        }
        if (
            scheduleLiveActivityService != null &&
            candidates.any { candidate ->
                candidate.memberId != memberId &&
                    oldOwnerCandidates.none { it.id == candidate.id }
            }
        ) {
            throw CannotAcquireLockException(
                "Push token candidate appeared after Live Activity ownership fencing.",
            )
        }
        if (foreignOrphanEndHandoffPlan != null) {
            if (
                candidates.isNotEmpty() ||
                foreignOrphanEndHandoffPlan.memberId !in memberFenceIds ||
                foreignOrphanEndHandoffPlan.memberId == memberId ||
                foreignOrphanEndHandoffPlan.deviceFingerprint != deviceFingerprint ||
                foreignOrphanEndHandoffPlan.replaceableExistingPushDeviceTokenIds.isNotEmpty()
            ) {
                throw CannotAcquireLockException(
                    "Foreign orphan Live Activity END changed during registration fencing.",
                )
            }
            val endOnlyRoute = NotificationDeviceToken(
                memberId = foreignOrphanEndHandoffPlan.memberId,
                deviceId = deviceId,
                platform = platform,
                token = token,
                tokenFingerprint = tokenFingerprint,
                deviceFingerprint = deviceFingerprint,
                deliveryAckCapabilityVersion = deliveryAckCapabilityVersion,
            )
            notificationRepository.saveAndFlush(endOnlyRoute)
            scheduleLiveActivityService?.applySameDeviceFcmEndHandoff(
                foreignOrphanEndHandoffPlan,
                endOnlyRoute,
                retireForEndOnly = true,
            ) ?: throw CannotAcquireLockException(
                "Live Activity foreign orphan END handoff service became unavailable.",
            )
            return NotificationTokenRegistrationResult(
                removedOwnershipCount = 0,
                result = "foreign_orphan_live_activity_end_handoff",
                retryAfterCommit = true,
            )
        }
        if (ownershipEndRouteIds.isNotEmpty()) {
            val correctlyReserved = ownershipEndRouteIds.all { routeId ->
                candidates.any {
                    it.id == routeId &&
                        it.memberId != memberId &&
                        it.retirementRequested
                }
            }
            if (!correctlyReserved) {
                throw CannotAcquireLockException(
                    "Live Activity END route changed during ownership fencing.",
                )
            }
            val foreignEndOnlyCandidateAndReservation =
                foreignRetiredEndHandoffPlan?.let { plan ->
                val epochMatches = candidates.filter {
                    it.id in plan.replaceableExistingPushDeviceTokenIds &&
                        it.memberId == plan.memberId &&
                        it.memberId != memberId &&
                        it.deviceFingerprint == deviceFingerprint &&
                        it.platform == PushPlatform.IOS &&
                        platform == PushPlatform.IOS &&
                        it.retirementRequested &&
                        it.tokenFingerprint != tokenFingerprint
                }
                if (
                    plan.deviceFingerprint != deviceFingerprint ||
                    plan.replaceableExistingPushDeviceTokenIds.size != 1 ||
                    epochMatches.mapNotNull { it.id }.toSet() !=
                    plan.replaceableExistingPushDeviceTokenIds
                ) {
                    throw CannotAcquireLockException(
                        "Foreign retired FCM route changed during END-only handoff.",
                    )
                }
                val reservationReleased = epochMatches.any {
                    scheduleLiveActivityService?.releaseFcmEndReservationForHandoff(plan, it)
                        ?: throw CannotAcquireLockException(
                            "Live Activity foreign END reservation service became unavailable.",
                        )
                }
                if (epochMatches.any {
                        it.dispatchLeaseId != null || it.dispatchLeaseUntil != null
                    }
                ) {
                    throw PushTokenDispatchLeaseActiveException()
                }
                epochMatches.single() to reservationReleased
            }
            if (foreignEndOnlyCandidateAndReservation != null) {
                val (foreignEndOnlyCandidate, reservationReleased) =
                    foreignEndOnlyCandidateAndReservation
                val oldOwnerId = foreignEndOnlyCandidate.memberId
                val replaced = foreignEndOnlyCandidate.replaceRetiredSameDeviceOwnershipEpoch(
                    memberId = oldOwnerId,
                    deviceId = deviceId,
                    platform = platform,
                    token = token,
                    tokenFingerprint = tokenFingerprint,
                    deviceFingerprint = requireNotNull(deviceFingerprint),
                    deliveryAckCapabilityVersion = deliveryAckCapabilityVersion,
                    allowUnchangedTokenAfterEndReservation = reservationReleased,
                )
                if (!replaced) {
                    throw CannotAcquireLockException(
                        "Foreign retired FCM route could not advance to an END-only epoch.",
                    )
                }
                notificationRepository.saveAndFlush(foreignEndOnlyCandidate)
                scheduleLiveActivityService?.applySameDeviceFcmEndHandoff(
                    requireNotNull(foreignRetiredEndHandoffPlan),
                    foreignEndOnlyCandidate,
                    retireForEndOnly = true,
                ) ?: throw CannotAcquireLockException(
                    "Live Activity foreign END handoff service became unavailable.",
                )
                // The row intentionally remains owned by the old member and END-reserved. Only a
                // later registration transaction after terminal END may transfer it to memberId.
                return NotificationTokenRegistrationResult(
                    removedOwnershipCount = 0,
                    result = "foreign_live_activity_end_handoff",
                    retryAfterCommit = true,
                )
            }
            // Returning commits the old-owner END and route reservation. The public service then
            // retries in a fresh transaction and may transfer only after the END is terminal.
            return NotificationTokenRegistrationResult(
                removedOwnershipCount = 0,
                result = "live_activity_end_pending",
                retryAfterCommit = true,
            )
        }
        val sameDeviceEndHandoffCandidateAndReservation = sameDeviceEndHandoffPlan?.let { plan ->
            val epochMatches = candidates.filter {
                it.id in plan.replaceableExistingPushDeviceTokenIds &&
                    it.memberId == memberId &&
                    it.deviceFingerprint == deviceFingerprint &&
                    it.platform == PushPlatform.IOS &&
                    platform == PushPlatform.IOS &&
                    (
                        !it.retirementRequested ||
                            it.tokenFingerprint != tokenFingerprint ||
                            it.dispatchLeaseId == liveActivityEndRetirementReservation(
                                memberId,
                                requireNotNull(deviceFingerprint),
                            )
                        )
            }
            if (
                plan.memberId != memberId ||
                plan.deviceFingerprint != deviceFingerprint ||
                plan.replaceableExistingPushDeviceTokenIds.size > 1 ||
                epochMatches.mapNotNull { it.id }.toSet() !=
                    plan.replaceableExistingPushDeviceTokenIds
            ) {
                throw CannotAcquireLockException(
                    "Retired FCM route changed during same-device END handoff.",
                )
            }
            val reservationReleased = epochMatches.any {
                scheduleLiveActivityService?.releaseFcmEndReservationForHandoff(plan, it)
                    ?: throw CannotAcquireLockException(
                        "Live Activity END reservation service became unavailable.",
                    )
            }
            if (epochMatches.any { it.dispatchLeaseId != null || it.dispatchLeaseUntil != null }) {
                throw PushTokenDispatchLeaseActiveException()
            }
            epochMatches.singleOrNull()?.let { it to reservationReleased }
        }
        val sameDeviceEndHandoffCandidate = sameDeviceEndHandoffCandidateAndReservation?.first
        val sameDeviceEndReservationReleased =
            sameDeviceEndHandoffCandidateAndReservation?.second == true
        val sameDeviceEndHandoffCandidateId = sameDeviceEndHandoffCandidate?.id
        val now = Instant.now(clock)
        val expiredRetired = candidates.filter {
            (sameDeviceEndHandoffCandidateId == null || it.id != sameDeviceEndHandoffCandidateId) &&
                it.retirementRequested && !it.hasActiveDispatchLease(now) &&
                !it.hasOutstandingLiveActivityEnd()
        }
        if (expiredRetired.isNotEmpty()) {
            notificationRepository.deleteAll(expiredRetired)
            notificationRepository.flush()
        }
        val liveCandidates = candidates.filterNot { it in expiredRetired }
        if (liveCandidates.any {
                (sameDeviceEndHandoffCandidateId == null ||
                    it.id != sameDeviceEndHandoffCandidateId) &&
                    (it.hasActiveDispatchLease(now) || it.hasOutstandingLiveActivityEnd())
            }
        ) {
            throw PushTokenDispatchLeaseActiveException()
        }
        val tokenMatches = liveCandidates.filter { it.tokenFingerprint == tokenFingerprint }
        val deviceMatches = deviceFingerprint
            ?.let { fingerprint ->
                liveCandidates.filter { it.deviceFingerprint == fingerprint }
            }
            .orEmpty()
        val preferred = sameDeviceEndHandoffCandidate ?: if (deviceFingerprint != null) {
            deviceMatches.maxByOrNull { it.id ?: Long.MIN_VALUE }
                ?: tokenMatches.maxByOrNull { it.id ?: Long.MIN_VALUE }
        } else {
            tokenMatches
                .filter { it.memberId == memberId }
                .maxByOrNull { it.id ?: Long.MIN_VALUE }
                ?: tokenMatches.maxByOrNull { it.id ?: Long.MIN_VALUE }
        }
        val duplicates = (tokenMatches + deviceMatches)
            .distinctBy { it.id ?: System.identityHashCode(it).toLong() }
            .filterNot { it === preferred || (it.id != null && it.id == preferred?.id) }

        if (duplicates.isNotEmpty()) {
            notificationRepository.deleteAll(duplicates)
            notificationRepository.flush()
        }

        if (preferred != null) {
            if (
                sameDeviceEndHandoffCandidate != null &&
                preferred.id == sameDeviceEndHandoffCandidateId
            ) {
                val replaced = if (preferred.retirementRequested) {
                    preferred.replaceRetiredSameDeviceOwnershipEpoch(
                        memberId = memberId,
                        deviceId = deviceId,
                        platform = platform,
                        token = token,
                        tokenFingerprint = tokenFingerprint,
                        deviceFingerprint = requireNotNull(deviceFingerprint),
                        deliveryAckCapabilityVersion = deliveryAckCapabilityVersion,
                        allowUnchangedTokenAfterEndReservation =
                            sameDeviceEndReservationReleased,
                    )
                } else {
                    preferred.replaceOwnership(
                        memberId = memberId,
                        deviceId = deviceId,
                        platform = platform,
                        token = token,
                        tokenFingerprint = tokenFingerprint,
                        deviceFingerprint = deviceFingerprint,
                        deliveryAckCapabilityVersion = deliveryAckCapabilityVersion,
                    )
                    true
                }
                if (!replaced) {
                    throw CannotAcquireLockException(
                        "Retired FCM route could not advance to a replacement epoch.",
                    )
                }
                notificationRepository.saveAndFlush(preferred)
                scheduleLiveActivityService?.applySameDeviceFcmEndHandoff(
                    requireNotNull(sameDeviceEndHandoffPlan),
                    preferred,
                ) ?: throw CannotAcquireLockException(
                    "Live Activity END handoff service became unavailable.",
                )
                if (sameDeviceOrphanRecoveryPlan != null) {
                    scheduleLiveActivityService.applySameDeviceFcmOrphanRecovery(
                        sameDeviceOrphanRecoveryPlan,
                        preferred,
                    )
                }
                return NotificationTokenRegistrationResult(
                    duplicates.size,
                    "updated_live_activity_end_handoff",
                )
            }
            preferred.replaceOwnership(
                memberId = memberId,
                deviceId = deviceId,
                platform = platform,
                token = token,
                tokenFingerprint = tokenFingerprint,
                deviceFingerprint = deviceFingerprint,
                deliveryAckCapabilityVersion = deliveryAckCapabilityVersion,
            )
            notificationRepository.saveAndFlush(preferred)
            if (sameDeviceEndHandoffPlan != null) {
                scheduleLiveActivityService?.applySameDeviceFcmEndHandoff(
                    sameDeviceEndHandoffPlan,
                    preferred,
                ) ?: throw CannotAcquireLockException(
                    "Live Activity END handoff service became unavailable.",
                )
            }
            if (sameDeviceOrphanRecoveryPlan != null) {
                scheduleLiveActivityService?.applySameDeviceFcmOrphanRecovery(
                    sameDeviceOrphanRecoveryPlan,
                    preferred,
                ) ?: throw CannotAcquireLockException(
                    "Live Activity orphan recovery service became unavailable.",
                )
            }
            return NotificationTokenRegistrationResult(
                duplicates.size,
                if (sameDeviceEndHandoffPlan == null) {
                    "updated"
                } else {
                    "updated_live_activity_end_handoff"
                },
            )
        }

        val entity = NotificationDeviceToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = platform,
            token = token,
            tokenFingerprint = tokenFingerprint,
            deviceFingerprint = deviceFingerprint,
            deliveryAckCapabilityVersion = deliveryAckCapabilityVersion,
        )
        notificationRepository.saveAndFlush(entity)
        if (sameDeviceEndHandoffPlan != null) {
            scheduleLiveActivityService?.applySameDeviceFcmEndHandoff(
                sameDeviceEndHandoffPlan,
                entity,
            ) ?: throw CannotAcquireLockException(
                "Live Activity END handoff service became unavailable.",
            )
        }
        if (sameDeviceOrphanRecoveryPlan != null) {
            scheduleLiveActivityService?.applySameDeviceFcmOrphanRecovery(
                sameDeviceOrphanRecoveryPlan,
                entity,
            ) ?: throw CannotAcquireLockException(
                "Live Activity orphan recovery service became unavailable.",
            )
        }
        return NotificationTokenRegistrationResult(
            duplicates.size,
            if (sameDeviceEndHandoffPlan == null) {
                "created"
            } else {
                "created_live_activity_end_handoff"
            },
        )
    }

    private fun NotificationDeviceToken.hasOutstandingLiveActivityEnd(): Boolean {
        val tokenId = id ?: return false
        val hasDelivery = liveActivityDeliveryRepository?.countOpenByPushDeviceToken(
            tokenId,
            LiveActivityDeliveryOperation.END,
            OUTSTANDING_LIVE_ACTIVITY_END_STATUSES,
        )?.let { it > 0 } == true
        return hasDelivery || notificationRepository.countEndPendingLiveActivities(tokenId) > 0
    }

    private companion object {
        val OUTSTANDING_LIVE_ACTIVITY_END_STATUSES = setOf(
            LiveActivityDeliveryStatus.PENDING,
            LiveActivityDeliveryStatus.FAILED,
            LiveActivityDeliveryStatus.DISPATCHING,
            LiveActivityDeliveryStatus.UNCERTAIN,
            LiveActivityDeliveryStatus.EXHAUSTED,
        )
    }
}

class PushTokenDispatchLeaseActiveException :
    TransientDataAccessException("Push token ownership is leased by an active provider dispatch.")

/**
 * 보안 필터 통과 후 DB write가 지연되는 경합을 결정적으로 검증하기 위한 관찰 지점.
 * 운영 환경에는 구현 bean이 없으며 raw deviceId/token을 전달하지 않는다.
 */
fun interface NotificationTokenRegistrationObserver {
    fun afterMemberSessionFence(memberId: Long)
}
