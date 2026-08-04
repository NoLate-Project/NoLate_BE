package com.noLate.notification.application.service

import com.noLate.notification.domain.DepartureAlarmDeliveryMode
import com.noLate.notification.domain.DepartureAlarmGenerationRelation
import com.noLate.notification.domain.DepartureAlarmScheduleOutcome
import com.noLate.notification.domain.DepartureAlarmScheduleReceipt
import com.noLate.notification.domain.DepartureAlarmScheduleSource
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.DepartureAlarmScheduleReceiptRepository
import com.noLate.schedule.application.service.DepartureAlarmPlanFactory
import com.noLate.schedule.domain.DEPARTURE_ALARM_PLAN_SCHEMA_VERSION
import com.noLate.schedule.domain.DepartureAlarmPlanCodec
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import com.noLate.schedule.domain.DepartureAlarmSyncState
import com.noLate.schedule.infrastructure.DepartureAlarmSyncStateRepository
import org.junit.jupiter.api.Assertions.assertEquals
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

@ExtendWith(MockitoExtension::class)
class DepartureAlarmReminderCoverageServiceTest {
    @Mock lateinit var stateRepository: DepartureAlarmSyncStateRepository
    @Mock lateinit var receiptRepository: DepartureAlarmScheduleReceiptRepository

    @Test
    fun `each exact M15 M10 M5 M0 occurrence can cover the current token ownership`() {
        val fixture = fixture()
        fixture.plan.occurrences.forEachIndexed { index, occurrence ->
            whenever(
                receiptRepository.findAllForOccurrenceCoverage(
                    MEMBER_ID,
                    SCHEDULE_ID,
                    0,
                    occurrence.occurrenceId,
                    occurrence.triggerInstant(),
                )
            ).thenReturn(
                listOf(
                    receipt(
                        id = index + 1L,
                        occurrenceId = occurrence.occurrenceId,
                        triggerAt = occurrence.triggerInstant(),
                    )
                )
            )

            val coverage = fixture.service.resolveForLockedMember(
                selector(occurrence.occurrenceId, occurrence.triggerInstant()),
                listOf(token()),
            )

            assertEquals(1, coverage.activeDeviceCount)
            assertEquals(1, coverage.coveredDeviceCount)
        }
    }

    @Test
    fun `higher failed mutation sequence wins even when older scheduled receipt arrives later`() {
        val fixture = fixture()
        val occurrence = fixture.plan.occurrence("M10")!!
        whenever(
            receiptRepository.findAllForOccurrenceCoverage(
                MEMBER_ID, SCHEDULE_ID, 0, "M10", occurrence.triggerInstant(),
            )
        ).thenReturn(
            listOf(
                receipt(
                    id = 20,
                    occurrenceId = "M10",
                    triggerAt = occurrence.triggerInstant(),
                    sequence = 1,
                    outcome = DepartureAlarmScheduleOutcome.SCHEDULED,
                    serverRecordedAt = NOW.minusSeconds(1),
                ),
                receipt(
                    id = 10,
                    occurrenceId = "M10",
                    triggerAt = occurrence.triggerInstant(),
                    sequence = 2,
                    outcome = DepartureAlarmScheduleOutcome.FAILED,
                    serverRecordedAt = NOW.minusSeconds(30),
                ),
            )
        )

        val coverage = fixture.service.resolveForLockedMember(
            selector("M10", occurrence.triggerInstant()),
            listOf(token()),
        )

        assertEquals(0, coverage.coveredDeviceCount)
    }

    @Test
    fun `old ownership receipt cannot suppress a relogged-in token and partial coverage stays per token`() {
        val fixture = fixture()
        val occurrence = fixture.plan.occurrence("M5")!!
        val currentFirst = token(id = 101, deviceId = "device-1")
        val currentSecond = token(id = 202, deviceId = "device-2")
        whenever(
            receiptRepository.findAllForOccurrenceCoverage(
                MEMBER_ID, SCHEDULE_ID, 0, "M5", occurrence.triggerInstant(),
            )
        ).thenReturn(
            listOf(
                receipt(
                    id = 1,
                    occurrenceId = "M5",
                    triggerAt = occurrence.triggerInstant(),
                    token = currentFirst,
                ),
                receipt(
                    id = 2,
                    occurrenceId = "M5",
                    triggerAt = occurrence.triggerInstant(),
                    token = currentSecond,
                    deviceTokenId = 199,
                ),
            )
        )

        val coverage = fixture.service.resolveForLockedMember(
            selector("M5", occurrence.triggerInstant()),
            listOf(currentFirst, currentSecond),
        )

        assertEquals(2, coverage.activeDeviceCount)
        assertEquals(1, coverage.coveredDeviceCount)
        assertTrue(coverage.coveredTokenOwnerships.single().deviceTokenId == 101L)
    }

    @Test
    fun `stale or degraded schedule evidence fails open while freshness boundary is inclusive`() {
        val fixture = fixture(receiptTtlHours = 24)
        val occurrence = fixture.plan.occurrence("M15")!!
        val cutoff = NOW.minusSeconds(24 * 60 * 60L)
        whenever(
            receiptRepository.findAllForOccurrenceCoverage(
                MEMBER_ID, SCHEDULE_ID, 0, "M15", occurrence.triggerInstant(),
            )
        ).thenReturn(
            listOf(
                receipt(
                    id = 1,
                    occurrenceId = "M15",
                    triggerAt = occurrence.triggerInstant(),
                    serverRecordedAt = cutoff,
                )
            ),
            listOf(
                receipt(
                    id = 2,
                    occurrenceId = "M15",
                    triggerAt = occurrence.triggerInstant(),
                    serverRecordedAt = cutoff.minusMillis(1),
                )
            ),
            listOf(
                receipt(
                    id = 3,
                    occurrenceId = "M15",
                    triggerAt = occurrence.triggerInstant(),
                    serverRecordedAt = NOW,
                    deliveryMode = DepartureAlarmDeliveryMode.ANDROID_INEXACT,
                )
            ),
            listOf(
                receipt(
                    id = 4,
                    occurrenceId = "M15",
                    triggerAt = occurrence.triggerInstant(),
                    serverRecordedAt = NOW,
                    failureReason = "SOUND_DISABLED",
                )
            ),
        )

        val selector = selector("M15", occurrence.triggerInstant())
        assertEquals(1, fixture.service.resolveForLockedMember(selector, listOf(token())).coveredDeviceCount)
        assertEquals(0, fixture.service.resolveForLockedMember(selector, listOf(token())).coveredDeviceCount)
        assertEquals(0, fixture.service.resolveForLockedMember(selector, listOf(token())).coveredDeviceCount)
        assertEquals(0, fixture.service.resolveForLockedMember(selector, listOf(token())).coveredDeviceCount)
    }

    @Test
    fun `generation anchor or occurrence trigger mismatch never reads receipts`() {
        val fixture = fixture()
        val occurrence = fixture.plan.occurrence("M5")!!

        val coverage = fixture.service.resolveForLockedMember(
            selector("M5", occurrence.triggerInstant().plusMillis(1)),
            listOf(token()),
        )

        assertEquals(0, coverage.coveredDeviceCount)
        verify(receiptRepository, never()).findAllForOccurrenceCoverage(any(), any(), any(), any(), any())
    }

    @Test
    fun `lost same-generation revalidation keeps the prior fresh receipt authoritative`() {
        val fixture = fixture()
        val occurrence = fixture.plan.occurrence("M10")!!
        whenever(
            receiptRepository.findAllForOccurrenceCoverage(
                MEMBER_ID, SCHEDULE_ID, 0, "M10", occurrence.triggerInstant(),
            )
        ).thenReturn(
            listOf(
                receipt(
                    id = 1,
                    occurrenceId = "M10",
                    triggerAt = occurrence.triggerInstant(),
                    sequence = 1,
                )
            )
        )

        fixture.state.reissueValidation(NOW)
        val coverage = fixture.service.resolveForLockedMember(
            selector("M10", occurrence.triggerInstant()),
            listOf(token()),
        )

        assertEquals(0L, fixture.state.generation)
        assertEquals(1L, fixture.state.validationRevision)
        assertEquals(1, coverage.coveredDeviceCount)
    }

    private fun fixture(receiptTtlHours: Long = 24): Fixture {
        val plan = DepartureAlarmPlanFactory().create(
            MEMBER_ID,
            SCHEDULE_ID,
            DEPARTURE_AT,
            "회의",
        )
        val state = DepartureAlarmSyncState.createUpsert(
            memberId = MEMBER_ID,
            scheduleId = SCHEDULE_ID,
            triggerAt = plan.departureOccurrence().triggerInstant(),
            title = plan.departureOccurrence().title,
            snoozeMinutes = 5,
            alarmPlanSchemaVersion = DEPARTURE_ALARM_PLAN_SCHEMA_VERSION,
            alarmOccurrencesJson = DepartureAlarmPlanCodec.encode(plan),
        )
        whenever(stateRepository.findByMemberIdAndScheduleIdForUpdate(MEMBER_ID, SCHEDULE_ID))
            .thenReturn(state)
        return Fixture(
            plan = plan,
            state = state,
            service = DepartureAlarmReminderCoverageService(
                syncStateRepository = stateRepository,
                receiptRepository = receiptRepository,
                clock = Clock.fixed(NOW, ZoneOffset.UTC),
                receiptTtlHours = receiptTtlHours,
            ),
        )
    }

    private fun selector(occurrenceId: String, triggerAt: Instant) =
        DepartureAlarmReminderCoverageSelector(
            memberId = MEMBER_ID,
            scheduleId = SCHEDULE_ID,
            recommendedDepartureAt = DEPARTURE_AT,
            occurrenceId = occurrenceId,
            occurrenceTriggerAt = triggerAt,
        )

    private fun token(
        id: Long = 101,
        deviceId: String = "device-1",
    ) = NotificationDeviceToken(
        id = id,
        memberId = MEMBER_ID,
        deviceId = deviceId,
        platform = PushPlatform.ANDROID,
        token = "token-$id",
    )

    private fun receipt(
        id: Long,
        occurrenceId: String,
        triggerAt: Instant,
        token: NotificationDeviceToken = token(),
        deviceTokenId: Long = requireNotNull(token.id),
        sequence: Long = 1,
        outcome: DepartureAlarmScheduleOutcome = DepartureAlarmScheduleOutcome.SCHEDULED,
        serverRecordedAt: Instant = NOW.minusSeconds(5),
        deliveryMode: DepartureAlarmDeliveryMode = DepartureAlarmDeliveryMode.ANDROID_EXACT,
        failureReason: String? = null,
    ) = DepartureAlarmScheduleReceipt(
        id = id,
        memberId = MEMBER_ID,
        clientReceiptId = "00000000-0000-0000-0000-${id.toString().padStart(12, '0')}",
        deviceFingerprint = requireNotNull(token.deviceFingerprint),
        deviceTokenId = deviceTokenId,
        tokenOwnershipVersion = token.ownershipVersion,
        commandReceiptKey = id.toString().padStart(64, '0'),
        alarmId = "schedule:$SCHEDULE_ID:member:$MEMBER_ID",
        scheduleId = SCHEDULE_ID,
        generation = 0,
        desiredGenerationAtReceipt = 0,
        desiredOperationAtReceipt = DepartureAlarmSyncOperation.UPSERT,
        generationRelation = DepartureAlarmGenerationRelation.CURRENT,
        operation = DepartureAlarmSyncOperation.UPSERT,
        triggerAt = triggerAt,
        occurrenceId = occurrenceId,
        mutationSequence = sequence,
        outcome = outcome,
        applied = outcome == DepartureAlarmScheduleOutcome.SCHEDULED,
        scheduled = outcome == DepartureAlarmScheduleOutcome.SCHEDULED,
        platform = token.platform,
        deliveryMode = deliveryMode,
        source = DepartureAlarmScheduleSource.SNAPSHOT,
        failureReason = when (outcome) {
            DepartureAlarmScheduleOutcome.FAILED -> failureReason ?: "NATIVE_APPLY_FAILED"
            else -> failureReason
        },
        clientOccurredAt = NOW.minusSeconds(10),
        serverRecordedAt = serverRecordedAt,
    )

    private data class Fixture(
        val plan: com.noLate.schedule.domain.DepartureAlarmPlan,
        val state: DepartureAlarmSyncState,
        val service: DepartureAlarmReminderCoverageService,
    )

    private companion object {
        const val MEMBER_ID = 17L
        const val SCHEDULE_ID = 41L
        val NOW: Instant = Instant.parse("2026-08-04T03:00:00Z")
        val DEPARTURE_AT: Instant = NOW.plusSeconds(15 * 60L)
    }
}
