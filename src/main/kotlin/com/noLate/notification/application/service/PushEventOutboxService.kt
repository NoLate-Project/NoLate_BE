package com.noLate.notification.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.AppNotification
import com.noLate.notification.domain.DepartureAlarmPresentationAssignment
import com.noLate.notification.domain.DepartureAlarmPresentationMode
import com.noLate.notification.domain.PushDelivery
import com.noLate.notification.domain.PushLogicalEventKey
import com.noLate.notification.domain.PushManifestState
import com.noLate.notification.domain.PushOutboxDispatchStatus
import com.noLate.notification.domain.withPushAccountBinding
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.DepartureAlarmPresentationAssignmentRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

data class PreparedPushEvent(
    val snapshot: AppNotificationSnapshot?,
    val logicalEventKey: String,
    /** Frozen manifest의 영속 delivery PK. current token 조회 결과가 아니다. */
    val deliveryIds: List<Long>,
    val manifestRecipientCount: Int,
    val inboxCreated: Boolean,
    val fenceAccepted: Boolean,
    val nativeAlarmCoveredRecipientCount: Int = 0,
    /** false이면 withdrawal이 먼저 linearize되어 source/manifest를 만들지 않은 terminal no-op이다. */
    val recipientActive: Boolean = true,
) {
    val emptyManifest: Boolean
        get() = manifestRecipientCount == 0

    val nativeAlarmCovered: Boolean
        get() = nativeAlarmCoveredRecipientCount > 0
}

/**
 * 사용자 push의 immutable payload와 recipient manifest를 만든다.
 *
 * 최초 event transaction만 현재 token snapshot을 읽는다. 이후 같은 logicalEventKey는
 * frozen delivery PK만 반환하며 새 token/device를 절대 추가하지 않는다.
 */
@Service
class PushEventOutboxService(
    private val writer: PushEventOutboxWriter,
) {
    fun prepare(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        deduplicationKey: String?,
        persistInInbox: Boolean,
        fence: PushDispatchFence?,
        sessionFence: AuthenticatedPushSessionFence? = null,
        nativeAlarmCoverageSelector: DepartureAlarmReminderCoverageSelector? = null,
    ): PreparedPushEvent {
        require(persistInInbox) {
            "Durable push events must be persisted; use the explicit ephemeral send path otherwise."
        }
        return retryFreshTransaction {
            writer.prepareInline(
                memberId = memberId,
                title = title,
                body = body,
                data = data,
                deduplicationKey = deduplicationKey,
                fence = fence,
                sessionFence = sessionFence,
                nativeAlarmCoverageSelector = nativeAlarmCoverageSelector,
            )
        }
    }

    /**
     * business transaction의 BEFORE_COMMIT listener에서 호출한다. 외부 provider는 호출하지
     * 않고 payload+manifest+PENDING marker만 현재 transaction에 함께 저장한다.
     */
    fun enqueueDurable(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        deduplicationKey: String,
    ): PreparedPushEvent =
        enqueueDurable(
            memberId = memberId,
            title = title,
            body = body,
            data = data,
            deduplicationKey = deduplicationKey,
            inboxVisible = true,
        )

    fun enqueueDurable(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        deduplicationKey: String,
        inboxVisible: Boolean,
    ): PreparedPushEvent =
        writer.prepareDurable(
            memberId = memberId,
            title = title,
            body = body,
            data = data,
            deduplicationKey = deduplicationKey,
            inboxVisible = inboxVisible,
        )

    /**
     * Provider 전달 내구성은 일반 알림과 공유하되 앱 알림함에는 노출하지 않는 제어 명령이다.
     */
    fun enqueueControl(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        deduplicationKey: String,
    ): PreparedPushEvent =
        enqueueDurable(
            memberId = memberId,
            title = title,
            body = body,
            data = data,
            deduplicationKey = deduplicationKey,
            inboxVisible = false,
        )

    fun loadPersisted(
        memberId: Long,
        logicalEventKey: String,
    ): PreparedPushEvent? =
        writer.load(memberId, logicalEventKey)

    fun findSnapshot(
        memberId: Long,
        deduplicationKey: String,
    ): AppNotificationSnapshot? {
        val normalized = normalizeDeduplicationKey(deduplicationKey) ?: return null
        return writer.findSnapshot(
            memberId,
            PushLogicalEventKey.deterministic(memberId, normalized),
        )
    }

    private fun <T> retryFreshTransaction(block: () -> T): T {
        var last: RuntimeException? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (failure: RuntimeException) {
                if (failure !is DataIntegrityViolationException &&
                    failure !is TransientDataAccessException
                ) {
                    throw failure
                }
                last = failure
                if (attempt == 2) {
                    throw ConcurrencyFailureException(
                        "Push outbox transaction did not converge.",
                    )
                }
            }
        }
        throw ConcurrencyFailureException(
            "Push outbox transaction did not converge.",
            last,
        )
    }
}

@Service
class PushEventOutboxWriter(
    private val appNotificationRepository: AppNotificationRepository,
    private val pushDeliveryRepository: PushDeliveryRepository,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val memberRepository: MemberRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val fenceValidator: PushDispatchFenceValidator? = null,
    private val recipientAuthorizationValidator: PushRecipientAuthorizationValidator? = null,
    private val departureAlarmReminderCoverageService: DepartureAlarmReminderCoverageService? = null,
    private val departureAlarmPresentationAssignmentRepository:
        DepartureAlarmPresentationAssignmentRepository? = null,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun prepareInline(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        deduplicationKey: String?,
        fence: PushDispatchFence?,
        sessionFence: AuthenticatedPushSessionFence? = null,
        nativeAlarmCoverageSelector: DepartureAlarmReminderCoverageSelector? = null,
    ): PreparedPushEvent =
        prepareWithinTransaction(
            memberId = memberId,
            title = title,
            body = body,
            data = data,
            deduplicationKey = deduplicationKey,
            fence = fence,
            durableDispatch = false,
            sessionFence = sessionFence,
            nativeAlarmCoverageSelector = nativeAlarmCoverageSelector,
        )

    @Transactional(propagation = Propagation.MANDATORY)
    fun prepareDurable(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        deduplicationKey: String,
        inboxVisible: Boolean = true,
    ): PreparedPushEvent =
        prepareWithinTransaction(
            memberId = memberId,
            title = title,
            body = body,
            data = data,
            deduplicationKey = deduplicationKey,
            fence = null,
            durableDispatch = true,
            inboxVisible = inboxVisible,
        )

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun load(memberId: Long, logicalEventKey: String): PreparedPushEvent? {
        val notification = appNotificationRepository.findByMemberIdAndLogicalEventKey(
            memberId,
            logicalEventKey.take(100),
        ) ?: return null
        return prepared(notification, inboxCreated = false, fenceAccepted = true)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun findSnapshot(memberId: Long, logicalEventKey: String): AppNotificationSnapshot? =
        appNotificationRepository.findByMemberIdAndLogicalEventKey(memberId, logicalEventKey)
            ?.toSnapshot(objectMapper)

    private fun prepareWithinTransaction(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        deduplicationKey: String?,
        fence: PushDispatchFence?,
        durableDispatch: Boolean,
        sessionFence: AuthenticatedPushSessionFence? = null,
        inboxVisible: Boolean = true,
        nativeAlarmCoverageSelector: DepartureAlarmReminderCoverageSelector? = null,
    ): PreparedPushEvent {
        // Global notification/withdrawal lock order starts with the recipient member. The active
        // check and every source/manifest write below are committed under this same row lock.
        val recipient =
            memberRepository.findActiveNotificationRecipientForUpdate(memberId)
                ?: return inactiveRecipient()
        if (sessionFence != null) {
            sessionFence.requireCurrent(recipient)
        }
        if (
            recipientAuthorizationValidator?.canDispatch(
                memberId = memberId,
                scheduleId = data["scheduleId"]?.toLongOrNull(),
                categoryId = data["categoryId"]?.toLongOrNull(),
                payloadType = data["type"],
                calendarId = data["calendarId"]?.toLongOrNull(),
            ) == false
        ) {
            return inactiveRecipient()
        }
        if (fence != null && fenceValidator?.validate(fence) != true) {
            return PreparedPushEvent(
                snapshot = null,
                logicalEventKey = "",
                deliveryIds = emptyList(),
                manifestRecipientCount = 0,
                inboxCreated = false,
                fenceAccepted = false,
                recipientActive = true,
            )
        }

        val normalizedKey = normalizeDeduplicationKey(deduplicationKey)
        val logicalEventKey = normalizedKey
            ?.let { PushLogicalEventKey.deterministic(memberId, it) }
            ?: PushLogicalEventKey.newEvent()
        val existing = when {
            normalizedKey != null ->
                appNotificationRepository.findByMemberIdAndDeduplicationKeyForUpdate(
                    memberId,
                    normalizedKey,
                )
            else -> null
        }
        if (existing != null) {
            if (existing.manifestState == PushManifestState.OPEN) {
                // OPEN should never commit in the normal writer transaction. If a preview/manual
                // schema left one behind, only rows that were already persisted belong to that
                // historical snapshot. Capturing today's tokens would expand a past event.
                freezeOpenManifest(
                    existing,
                    captureCurrentRecipients = false,
                    nativeAlarmCoverageSelector = null,
                )
            }
            if (durableDispatch &&
                existing.manifestState == PushManifestState.FROZEN &&
                existing.dispatchStatus == PushOutboxDispatchStatus.NOT_REQUIRED
            ) {
                existing.enqueueForDispatch(Instant.now(clock))
            }
            return prepared(existing, inboxCreated = false, fenceAccepted = true)
        }

        val canonicalData = data.withPushAccountBinding(logicalEventKey, memberId)
        val notification = appNotificationRepository.saveAndFlush(
            AppNotification(
                memberId = memberId,
                deduplicationKey = normalizedKey,
                logicalEventKey = logicalEventKey,
                type = canonicalData["type"]?.trim()?.takeIf(String::isNotEmpty)?.take(80)
                    ?: "GENERAL",
                scheduleId = canonicalData["scheduleId"]?.toLongOrNull(),
                categoryId = canonicalData["categoryId"]?.toLongOrNull(),
                calendarId = canonicalData["calendarId"]?.toLongOrNull(),
                title = title.take(200),
                body = body.take(1000),
                dataJson = objectMapper.writeValueAsString(canonicalData),
                createdAt = Instant.now(clock),
                inboxVisible = inboxVisible,
                manifestState = PushManifestState.OPEN,
            )
        )
        freezeOpenManifest(
            notification,
            captureCurrentRecipients = true,
            nativeAlarmCoverageSelector = nativeAlarmCoverageSelector,
        )
        if (durableDispatch) {
            notification.enqueueForDispatch(Instant.now(clock))
        }
        return prepared(notification, inboxCreated = true, fenceAccepted = true)
    }

    /**
     * New event creation and recipient capture are one transaction. A committed OPEN row is
     * therefore abnormal recovery input: its already-persisted delivery rows are frozen as-is,
     * including an explicit zero-row snapshot, and current tokens are never attached later.
     */
    private fun freezeOpenManifest(
        notification: AppNotification,
        captureCurrentRecipients: Boolean,
        nativeAlarmCoverageSelector: DepartureAlarmReminderCoverageSelector?,
    ) {
        val memberId = notification.memberId
        val eventKey = notification.logicalEventKey
        val existing = pushDeliveryRepository
            .findAllByMemberIdAndEventKey(memberId, eventKey)
            .sortedBy { it.id ?: Long.MAX_VALUE }
        var nativeAlarmCoveredRecipientCount = 0
        val deliveries = if (existing.isNotEmpty() || !captureCurrentRecipients) {
            existing
        } else {
            val data = notification.toSnapshot(objectMapper).data
            val activeTokens = tokenRepository
                .findAllByMemberIdAndRetirementRequestedFalse(memberId)
                .distinctBy { it.deliveryDeviceKey() }
            val coverage = nativeAlarmCoverageSelector
                ?.also { selector ->
                    require(selector.memberId == memberId) {
                        "Native alarm coverage selector recipient does not match the event."
                    }
                }
                ?.let { selector ->
                    departureAlarmReminderCoverageService
                        ?.resolveForLockedMember(selector, activeTokens)
                }
                ?: DepartureAlarmReminderCoverage(activeDeviceCount = activeTokens.size)
            val coveredTokenOwnerships = coverage.coveredTokenOwnerships
            nativeAlarmCoveredRecipientCount = coveredTokenOwnerships.size
            if (nativeAlarmCoverageSelector != null && activeTokens.isNotEmpty()) {
                val assignedAt = Instant.now(clock)
                val assignments = activeTokens.map { token ->
                    DepartureAlarmPresentationAssignment(
                        memberId = memberId,
                        logicalEventKey = eventKey,
                        scheduleId = nativeAlarmCoverageSelector.scheduleId,
                        alarmGeneration = coverage.alarmGeneration,
                        occurrenceId = nativeAlarmCoverageSelector.occurrenceId,
                        triggerAt = nativeAlarmCoverageSelector.occurrenceTriggerAt,
                        deviceTokenId = requireNotNull(token.id),
                        tokenOwnershipVersion = token.ownershipVersion,
                        deviceFingerprint = token.deviceFingerprint,
                        platform = token.platform,
                        presentationMode = if (
                            token.alarmOwnership() in coveredTokenOwnerships
                        ) {
                            DepartureAlarmPresentationMode.NATIVE_ALARM
                        } else {
                            DepartureAlarmPresentationMode.VISIBLE_FALLBACK
                        },
                        semanticWarningVisible =
                            nativeAlarmCoverageSelector.semanticWarningVisible,
                        assignedAt = assignedAt,
                    )
                }
                requireNotNull(departureAlarmPresentationAssignmentRepository) {
                    "Native alarm assignment repository is required for fallback measurement."
                }.saveAllAndFlush(assignments)
            }
            val frozen =
                activeTokens
                .filterNot { token ->
                    nativeAlarmCoverageSelector?.semanticWarningVisible != true &&
                        token.alarmOwnership() in coveredTokenOwnerships
                }
                .map { token ->
                    PushDelivery(
                        memberId = memberId,
                        eventKey = eventKey,
                        deviceKey = token.deliveryDeviceKey(),
                        deviceTokenId = token.id,
                        tokenFingerprint = token.tokenFingerprint,
                        tokenOwnershipVersion = token.ownershipVersion,
                        deviceFingerprint = token.deviceFingerprint,
                        platform = token.platform,
                        scheduleId = data["scheduleId"]?.toLongOrNull(),
                        calendarId = data["calendarId"]?.toLongOrNull(),
                        payloadType = data["type"]?.take(80),
                        deliveryAckCapabilityVersion = token.deliveryAckCapabilityVersion,
                    )
                }
            if (frozen.isNotEmpty()) {
                pushDeliveryRepository.saveAllAndFlush(frozen)
            } else {
                emptyList()
            }
        }
        notification.freezeManifest(
            recipientCount = deliveries.size,
            at = Instant.now(clock),
            nativeAlarmCoveredRecipientCount = nativeAlarmCoveredRecipientCount,
        )
        appNotificationRepository.saveAndFlush(notification)
    }

    private fun prepared(
        notification: AppNotification,
        inboxCreated: Boolean,
        fenceAccepted: Boolean,
    ): PreparedPushEvent {
        val deliveries = pushDeliveryRepository
            .findAllByMemberIdAndEventKeyOrderByIdAsc(
                notification.memberId,
                notification.logicalEventKey,
            )
        val expectedCount = when (notification.manifestState) {
            PushManifestState.FROZEN -> notification.manifestRecipientCount
            PushManifestState.INBOX_ONLY -> 0
            PushManifestState.OPEN ->
                throw IllegalStateException("Push manifest remained OPEN after preparation.")
        }
        if (deliveries.size != expectedCount) {
            throw IllegalStateException(
                "Frozen push manifest recipient count does not match persisted deliveries."
            )
        }
        return PreparedPushEvent(
            snapshot = notification.toSnapshot(objectMapper),
            logicalEventKey = notification.logicalEventKey,
            deliveryIds = deliveries.map { requireNotNull(it.id) },
            manifestRecipientCount = expectedCount,
            nativeAlarmCoveredRecipientCount = notification.nativeAlarmCoveredRecipientCount,
            inboxCreated = inboxCreated,
            fenceAccepted = fenceAccepted,
            recipientActive = true,
        )
    }

    private fun inactiveRecipient(): PreparedPushEvent =
        PreparedPushEvent(
            snapshot = null,
            logicalEventKey = "",
            deliveryIds = emptyList(),
            manifestRecipientCount = 0,
            nativeAlarmCoveredRecipientCount = 0,
            inboxCreated = false,
            fenceAccepted = true,
            recipientActive = false,
        )
}

private fun normalizeDeduplicationKey(value: String?): String? =
    value?.trim()?.takeIf(String::isNotEmpty)?.take(180)

private fun com.noLate.notification.domain.NotificationDeviceToken.alarmOwnership():
    DepartureAlarmTokenOwnership? {
    val tokenId = id ?: return null
    val fingerprint = deviceFingerprint ?: return null
    return DepartureAlarmTokenOwnership(
        deviceFingerprint = fingerprint,
        deviceTokenId = tokenId,
        tokenOwnershipVersion = ownershipVersion,
    )
}
