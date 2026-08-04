package com.noLate.notification.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.notification.domain.DepartureAlarmDeliveryMode
import com.noLate.notification.domain.DepartureAlarmGenerationRelation
import com.noLate.notification.domain.DepartureAlarmPresentationMode
import com.noLate.notification.domain.DepartureAlarmScheduleOutcome
import com.noLate.notification.domain.DepartureAlarmScheduleReceipt
import com.noLate.notification.domain.DepartureAlarmScheduleSource
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.DepartureAlarmPresentationAssignmentRepository
import com.noLate.notification.infrastructure.DepartureAlarmScheduleReceiptRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.notification.support.AllowAllPushRecipientAuthorizationTestConfig
import com.noLate.notification.support.ensureActivePushMember
import com.noLate.schedule.application.service.DepartureAlarmPlanFactory
import com.noLate.schedule.domain.DEPARTURE_ALARM_PLAN_SCHEMA_VERSION
import com.noLate.schedule.domain.DepartureAlarmPlan
import com.noLate.schedule.domain.DepartureAlarmPlanCodec
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import com.noLate.schedule.domain.DepartureAlarmSyncState
import com.noLate.schedule.infrastructure.DepartureAlarmSyncStateRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@DataJpaTest
@Import(
    PushEventOutboxService::class,
    PushEventOutboxWriter::class,
    DepartureAlarmReminderCoverageService::class,
    DepartureAlarmPresentationAssignmentTestConfig::class,
    AllowAllPushRecipientAuthorizationTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:departure-alarm-assignment;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.push.departure-alarm-coverage-receipt-ttl-hours=24",
    ],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DepartureAlarmPresentationAssignmentIntegrationTest @Autowired constructor(
    private val outboxService: PushEventOutboxService,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val stateRepository: DepartureAlarmSyncStateRepository,
    private val receiptRepository: DepartureAlarmScheduleReceiptRepository,
    private val deliveryRepository: PushDeliveryRepository,
    private val notificationRepository: AppNotificationRepository,
    private val assignmentRepository: DepartureAlarmPresentationAssignmentRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    @BeforeEach
    fun clean() {
        assignmentRepository.deleteAll()
        deliveryRepository.deleteAll()
        notificationRepository.deleteAll()
        receiptRepository.deleteAll()
        stateRepository.deleteAll()
        tokenRepository.deleteAll()
        ensureActivePushMember(jdbcTemplate, MEMBER_ID)
    }

    @Test
    fun `partial native coverage sends only the uncovered ownership and freezes assignments`() {
        val first = tokenRepository.saveAndFlush(token("device-a", "token-a"))
        val second = tokenRepository.saveAndFlush(token("device-b", "token-b"))
        val fixture = planFixture()
        receiptRepository.saveAndFlush(receipt(fixture, first, sequence = 1))

        val prepared = prepare(fixture, "partial-fallback")

        assertThat(prepared.manifestRecipientCount).isEqualTo(1)
        assertThat(prepared.nativeAlarmCoveredRecipientCount).isEqualTo(1)
        val deliveries = deliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(
            MEMBER_ID,
            prepared.logicalEventKey,
        )
        assertThat(deliveries.map { it.deviceTokenId }).containsExactly(requireNotNull(second.id))
        assertThat(assignmentModes(prepared.logicalEventKey)).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                requireNotNull(first.id) to DepartureAlarmPresentationMode.NATIVE_ALARM,
                requireNotNull(second.id) to DepartureAlarmPresentationMode.VISIBLE_FALLBACK,
            )
        )

        receiptRepository.saveAndFlush(
            receipt(
                fixture = fixture,
                token = first,
                sequence = 2,
                outcome = DepartureAlarmScheduleOutcome.FAILED,
            )
        )
        tokenRepository.saveAndFlush(token("device-late", "token-late"))

        val retried = prepare(fixture, "partial-fallback")

        assertThat(retried.deliveryIds).containsExactlyElementsOf(prepared.deliveryIds)
        assertThat(retried.nativeAlarmCoveredRecipientCount).isEqualTo(1)
        assertThat(assignmentRepository.findAllByMemberIdAndLogicalEventKeyOrderByIdAsc(
            MEMBER_ID,
            prepared.logicalEventKey,
        )).hasSize(2)
    }

    @Test
    fun `all covered ownerships freeze an empty visible manifest without losing measurement`() {
        val first = tokenRepository.saveAndFlush(token("device-a", "token-a"))
        val second = tokenRepository.saveAndFlush(token("device-b", "token-b"))
        val fixture = planFixture()
        receiptRepository.saveAllAndFlush(
            listOf(
                receipt(fixture, first, sequence = 1),
                receipt(fixture, second, sequence = 1),
            )
        )

        val prepared = prepare(fixture, "all-native")

        assertThat(prepared.emptyManifest).isTrue()
        assertThat(prepared.nativeAlarmCoveredRecipientCount).isEqualTo(2)
        assertThat(prepared.snapshot).isNotNull
        assertThat(deliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(
            MEMBER_ID,
            prepared.logicalEventKey,
        )).isEmpty()
        assertThat(assignmentModes(prepared.logicalEventKey).values)
            .containsOnly(DepartureAlarmPresentationMode.NATIVE_ALARM)
    }

    @Test
    fun `semantic warning remains visible for covered ownerships and is frozen as intentional dual presentation`() {
        val first = tokenRepository.saveAndFlush(token("device-a", "token-a"))
        val second = tokenRepository.saveAndFlush(token("device-b", "token-b"))
        val fixture = planFixture()
        receiptRepository.saveAllAndFlush(
            listOf(
                receipt(fixture, first, sequence = 1),
                receipt(fixture, second, sequence = 1),
            )
        )

        val prepared = prepare(
            fixture = fixture,
            key = "native-plus-semantic-warning",
            semanticWarningVisible = true,
        )

        assertThat(prepared.manifestRecipientCount).isEqualTo(2)
        assertThat(prepared.nativeAlarmCoveredRecipientCount).isEqualTo(2)
        assertThat(
            deliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(
                MEMBER_ID,
                prepared.logicalEventKey,
            ).map { it.deviceTokenId }
        ).containsExactlyInAnyOrder(requireNotNull(first.id), requireNotNull(second.id))
        val assignments = assignmentRepository
            .findAllByMemberIdAndLogicalEventKeyOrderByIdAsc(MEMBER_ID, prepared.logicalEventKey)
        assertThat(assignments).allSatisfy {
            assertThat(it.platform).isEqualTo(PushPlatform.ANDROID)
            assertThat(it.presentationMode).isEqualTo(DepartureAlarmPresentationMode.NATIVE_ALARM)
            assertThat(it.semanticWarningVisible).isTrue()
        }
    }

    @Test
    fun `receipt from a prior token ownership cannot suppress fallback after relogin`() {
        val token = tokenRepository.saveAndFlush(token("device-old", "token-old"))
        val fixture = planFixture()
        receiptRepository.saveAndFlush(receipt(fixture, token, sequence = 1))
        token.replaceOwnership(
            memberId = MEMBER_ID,
            deviceId = "device-new",
            platform = PushPlatform.ANDROID,
            token = "token-new",
            tokenFingerprint = com.noLate.notification.domain.OpaquePushIdentifier.fingerprint("token-new"),
            deviceFingerprint = com.noLate.notification.domain.OpaquePushIdentifier.fingerprint("device-new"),
        )
        tokenRepository.saveAndFlush(token)

        val prepared = prepare(fixture, "relogin-fallback")

        assertThat(prepared.manifestRecipientCount).isEqualTo(1)
        assertThat(prepared.nativeAlarmCoveredRecipientCount).isZero()
        val assignment = assignmentRepository
            .findAllByMemberIdAndLogicalEventKeyOrderByIdAsc(MEMBER_ID, prepared.logicalEventKey)
            .single()
        assertThat(assignment.tokenOwnershipVersion).isEqualTo(1L)
        assertThat(assignment.presentationMode)
            .isEqualTo(DepartureAlarmPresentationMode.VISIBLE_FALLBACK)
    }

    private fun prepare(
        fixture: PlanFixture,
        key: String,
        semanticWarningVisible: Boolean = false,
    ): PreparedPushEvent =
        outboxService.prepare(
            memberId = MEMBER_ID,
            title = fixture.occurrence.title,
            body = fixture.occurrence.body,
            data = mapOf(
                "type" to "SCHEDULE_DEPARTURE_REMINDER",
                "scheduleId" to SCHEDULE_ID.toString(),
                "occurrenceId" to fixture.occurrence.occurrenceId,
                "decision" to fixture.occurrence.decision,
                "minutesBeforeDeparture" to fixture.occurrence.minutesBeforeDeparture.toString(),
            ),
            deduplicationKey = key,
            persistInInbox = true,
            fence = null,
            nativeAlarmCoverageSelector = DepartureAlarmReminderCoverageSelector(
                memberId = MEMBER_ID,
                scheduleId = SCHEDULE_ID,
                recommendedDepartureAt = DEPARTURE_AT,
                occurrenceId = fixture.occurrence.occurrenceId,
                occurrenceTriggerAt = fixture.occurrence.triggerInstant(),
                semanticWarningVisible = semanticWarningVisible,
            ),
        )

    private fun planFixture(): PlanFixture {
        val plan = DepartureAlarmPlanFactory().create(
            memberId = MEMBER_ID,
            scheduleId = SCHEDULE_ID,
            recommendedDepartureAt = DEPARTURE_AT,
            scheduleTitle = "회의",
        )
        val state = stateRepository.saveAndFlush(
            DepartureAlarmSyncState.createUpsert(
                memberId = MEMBER_ID,
                scheduleId = SCHEDULE_ID,
                triggerAt = plan.departureOccurrence().triggerInstant(),
                title = plan.departureOccurrence().title,
                snoozeMinutes = 5,
                alarmPlanSchemaVersion = DEPARTURE_ALARM_PLAN_SCHEMA_VERSION,
                alarmOccurrencesJson = DepartureAlarmPlanCodec.encode(plan),
                validationRequestedAt = NOW,
            )
        )
        return PlanFixture(state, plan, plan.occurrence("M15")!!)
    }

    private fun receipt(
        fixture: PlanFixture,
        token: NotificationDeviceToken,
        sequence: Long,
        outcome: DepartureAlarmScheduleOutcome = DepartureAlarmScheduleOutcome.SCHEDULED,
    ) = DepartureAlarmScheduleReceipt(
        memberId = MEMBER_ID,
        clientReceiptId = UUID.randomUUID().toString(),
        deviceFingerprint = requireNotNull(token.deviceFingerprint),
        deviceTokenId = requireNotNull(token.id),
        tokenOwnershipVersion = token.ownershipVersion,
        commandReceiptKey = UUID.randomUUID().toString().replace("-", "").repeat(2),
        alarmId = fixture.state.alarmId,
        scheduleId = SCHEDULE_ID,
        generation = fixture.state.generation,
        desiredGenerationAtReceipt = fixture.state.generation,
        desiredOperationAtReceipt = DepartureAlarmSyncOperation.UPSERT,
        generationRelation = DepartureAlarmGenerationRelation.CURRENT,
        operation = DepartureAlarmSyncOperation.UPSERT,
        triggerAt = fixture.occurrence.triggerInstant(),
        occurrenceId = fixture.occurrence.occurrenceId,
        mutationSequence = sequence,
        outcome = outcome,
        applied = outcome == DepartureAlarmScheduleOutcome.SCHEDULED,
        scheduled = outcome == DepartureAlarmScheduleOutcome.SCHEDULED,
        platform = token.platform,
        deliveryMode = DepartureAlarmDeliveryMode.ANDROID_EXACT,
        source = DepartureAlarmScheduleSource.PUSH,
        failureReason = if (outcome == DepartureAlarmScheduleOutcome.FAILED) {
            "EXACT_ALARM_PERMISSION_DENIED"
        } else {
            null
        },
        clientOccurredAt = NOW.minusSeconds(30),
        serverRecordedAt = NOW,
    )

    private fun token(deviceId: String, providerToken: String) = NotificationDeviceToken(
        memberId = MEMBER_ID,
        deviceId = deviceId,
        platform = PushPlatform.ANDROID,
        token = providerToken,
    )

    private fun assignmentModes(logicalEventKey: String) = assignmentRepository
        .findAllByMemberIdAndLogicalEventKeyOrderByIdAsc(MEMBER_ID, logicalEventKey)
        .associate { it.deviceTokenId to it.presentationMode }

    private data class PlanFixture(
        val state: DepartureAlarmSyncState,
        val plan: DepartureAlarmPlan,
        val occurrence: com.noLate.schedule.domain.DepartureAlarmOccurrence,
    )

    private companion object {
        const val MEMBER_ID = 117L
        const val SCHEDULE_ID = 9041L
        val NOW: Instant = Instant.parse("2026-08-04T03:00:00Z")
        val DEPARTURE_AT: Instant = Instant.parse("2026-08-04T05:00:00Z")
    }
}

@TestConfiguration
class DepartureAlarmPresentationAssignmentTestConfig {
    @Bean
    fun departureAlarmAssignmentClock(): Clock = Clock.fixed(
        Instant.parse("2026-08-04T03:00:00Z"),
        ZoneOffset.UTC,
    )

    @Bean
    fun departureAlarmAssignmentObjectMapper(): ObjectMapper = jacksonObjectMapper()
}
