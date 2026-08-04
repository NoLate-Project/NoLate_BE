package com.noLate.notification.application.service

import com.noLate.notification.domain.DepartureAlarmScheduleOutcome
import com.noLate.notification.domain.DepartureAlarmDeliveryMode
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.infrastructure.DepartureAlarmScheduleReceiptRepository
import com.noLate.schedule.domain.DEPARTURE_ALARM_PLAN_SCHEMA_VERSION
import com.noLate.schedule.domain.DepartureAlarmPlanCodec
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import com.noLate.schedule.infrastructure.DepartureAlarmSyncStateRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit.HOURS
import java.time.temporal.ChronoUnit

/** Immutable source selector evaluated only after the outbox writer owns the member lock. */
data class DepartureAlarmReminderCoverageSelector(
    val memberId: Long,
    val scheduleId: Long,
    val recommendedDepartureAt: Instant,
    val occurrenceId: String,
    val occurrenceTriggerAt: Instant,
    /**
     * A safety/traffic warning is independent information, not the ordinary boundary reminder.
     * Keep its visible delivery even when a native alarm covers the reminder itself.
     */
    val semanticWarningVisible: Boolean = false,
)

data class DepartureAlarmTokenOwnership(
    val deviceFingerprint: String,
    val deviceTokenId: Long,
    val tokenOwnershipVersion: Long,
)

data class DepartureAlarmReminderCoverage(
    val coveredTokenOwnerships: Set<DepartureAlarmTokenOwnership> = emptySet(),
    val activeDeviceCount: Int = 0,
    val alarmGeneration: Long? = null,
) {
    val coveredDeviceCount: Int
        get() = coveredTokenOwnerships.size
}

/**
 * Resolves occurrence-level native scheduling evidence inside the outbox member-lock transaction.
 *
 * The highest client mutation sequence for the exact token ownership wins, so transport reordering
 * cannot resurrect an older SCHEDULED result after a newer FAILED result. At the same sequence a
 * failure wins deterministically. Legacy rows without ownership/sequence and aggregate delivery
 * ACKs never suppress a visible fallback.
 */
@Service
class DepartureAlarmReminderCoverageService(
    private val syncStateRepository: DepartureAlarmSyncStateRepository,
    private val receiptRepository: DepartureAlarmScheduleReceiptRepository,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${schedule.push.departure-alarm-coverage-receipt-ttl-hours:24}")
    private val receiptTtlHours: Long = 24,
) {
    init {
        require(receiptTtlHours in 1..MAX_RECEIPT_TTL_HOURS) {
            "Native alarm coverage receipt TTL must be between 1 and $MAX_RECEIPT_TTL_HOURS hours."
        }
    }

    fun resolveForLockedMember(
        selector: DepartureAlarmReminderCoverageSelector,
        activeTokens: List<NotificationDeviceToken>,
    ): DepartureAlarmReminderCoverage {
        val canonicalTokens = activeTokens
            .filterNot { it.retirementRequested }
            .distinctBy { it.deliveryDeviceKey() }
        if (canonicalTokens.isEmpty()) return DepartureAlarmReminderCoverage()

        val lockedState = syncStateRepository.findByMemberIdAndScheduleIdForUpdate(
            selector.memberId,
            selector.scheduleId,
        )
        val state = lockedState?.takeIf {
            it.operation == DepartureAlarmSyncOperation.UPSERT &&
                it.alarmPlanSchemaVersion == DEPARTURE_ALARM_PLAN_SCHEMA_VERSION &&
                it.triggerAt == selector.recommendedDepartureAt.truncatedTo(ChronoUnit.MILLIS)
        } ?: return uncovered(canonicalTokens.size, lockedState?.generation)
        val encodedPlan = state.alarmOccurrencesJson
            ?: return uncovered(canonicalTokens.size, state.generation)
        val occurrence = runCatching {
            DepartureAlarmPlanCodec.decode(encodedPlan).occurrence(selector.occurrenceId)
        }.getOrNull()
            ?.takeIf {
                it.triggerInstant() == selector.occurrenceTriggerAt.truncatedTo(ChronoUnit.MILLIS)
            }
            ?: return uncovered(canonicalTokens.size, state.generation)

        val latestByOwnership = receiptRepository.findAllForOccurrenceCoverage(
            memberId = selector.memberId,
            scheduleId = selector.scheduleId,
            generation = state.generation,
            occurrenceId = occurrence.occurrenceId,
            triggerAt = occurrence.triggerInstant(),
        ).mapNotNull { receipt ->
            val tokenId = receipt.deviceTokenId ?: return@mapNotNull null
            val ownershipVersion = receipt.tokenOwnershipVersion ?: return@mapNotNull null
            receipt.mutationSequence ?: return@mapNotNull null
            DepartureAlarmTokenOwnership(
                deviceFingerprint = receipt.deviceFingerprint,
                deviceTokenId = tokenId,
                tokenOwnershipVersion = ownershipVersion,
            ) to receipt
        }.sortedWith(
            compareByDescending<Pair<DepartureAlarmTokenOwnership, com.noLate.notification.domain.DepartureAlarmScheduleReceipt>> {
                requireNotNull(it.second.mutationSequence)
            }.thenByDescending {
                if (it.second.outcome == DepartureAlarmScheduleOutcome.SCHEDULED) 0 else 1
            }.thenByDescending { it.second.clientOccurredAt }
                .thenByDescending { it.second.serverRecordedAt }
                .thenByDescending { it.second.id ?: Long.MIN_VALUE }
        ).distinctBy { it.first }
            .toMap()

        val freshnessCutoff = Instant.now(clock).minus(receiptTtlHours, HOURS)
        val covered = canonicalTokens.mapNotNull { token ->
            val tokenId = token.id ?: return@mapNotNull null
            val deviceFingerprint = token.deviceFingerprint ?: return@mapNotNull null
            val ownership = DepartureAlarmTokenOwnership(
                deviceFingerprint = deviceFingerprint,
                deviceTokenId = tokenId,
                tokenOwnershipVersion = token.ownershipVersion,
            )
            val latest = latestByOwnership[ownership] ?: return@mapNotNull null
            ownership.takeIf {
                latest.platform == token.platform &&
                    latest.outcome == DepartureAlarmScheduleOutcome.SCHEDULED &&
                    latest.scheduled &&
                    latest.deliveryMode in STRONG_DELIVERY_MODES &&
                    !latest.failureReason.isStrongCoverageFailure() &&
                    !latest.serverRecordedAt.isBefore(freshnessCutoff) &&
                    !latest.clientOccurredAt.isBefore(freshnessCutoff) &&
                    latest.clientOccurredAt.isBefore(occurrence.triggerInstant())
            }
        }.toSet()
        return DepartureAlarmReminderCoverage(
            coveredTokenOwnerships = covered,
            activeDeviceCount = canonicalTokens.size,
            alarmGeneration = state.generation,
        )
    }

    private fun uncovered(
        activeDeviceCount: Int,
        alarmGeneration: Long? = null,
    ) = DepartureAlarmReminderCoverage(
        activeDeviceCount = activeDeviceCount,
        alarmGeneration = alarmGeneration,
    )

    private fun String?.isStrongCoverageFailure(): Boolean {
        val reason = this ?: return false
        return STRONG_COVERAGE_FAILURE_MARKERS.any(reason::contains)
    }

    private companion object {
        const val MAX_RECEIPT_TTL_HOURS = 24L * 7
        val STRONG_DELIVERY_MODES = setOf(
            DepartureAlarmDeliveryMode.ANDROID_EXACT,
            DepartureAlarmDeliveryMode.IOS_ALARM_KIT,
            DepartureAlarmDeliveryMode.IOS_TIME_SENSITIVE,
        )
        val STRONG_COVERAGE_FAILURE_MARKERS = setOf(
            "DISABLED",
            "DENIED",
            "UNAVAILABLE",
            "UNSUPPORTED",
            "PROVISIONAL",
            "QUOTA",
            "SILENT",
        )
    }
}
