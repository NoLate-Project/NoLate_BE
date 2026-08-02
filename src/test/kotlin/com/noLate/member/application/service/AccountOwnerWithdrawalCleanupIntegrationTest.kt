package com.noLate.member.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.service.NotificationTokenRetirementService
import com.noLate.notification.domain.AppNotification
import com.noLate.notification.domain.DepartureAlarmFireEvent
import com.noLate.notification.domain.DepartureAlarmGenerationRelation
import com.noLate.notification.domain.DepartureAlarmDeliveryMode
import com.noLate.notification.domain.DepartureAlarmScheduleOutcome
import com.noLate.notification.domain.DepartureAlarmScheduleReceipt
import com.noLate.notification.domain.DepartureAlarmScheduleSource
import com.noLate.notification.domain.PushDelivery
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.domain.PushSendHistory
import com.noLate.notification.domain.PushSendStatus
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.DepartureAlarmFireEventRepository
import com.noLate.notification.infrastructure.DepartureAlarmScheduleReceiptRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import com.noLate.schedule.application.service.ScheduleAccessPolicy
import com.noLate.schedule.application.cache.ScheduleCalendarCacheInvalidationEvent
import com.noLate.schedule.application.service.ScheduleSharingAvailabilityPolicy
import com.noLate.schedule.application.service.ScheduleTravelAccessCleanupService
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleArrivalObservationSource
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCalendarStatus
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleDepartureStatus
import com.noLate.schedule.domain.ScheduleEtaAccuracyObservation
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import com.noLate.schedule.domain.EtaPredictionBasis
import com.noLate.schedule.domain.EtaAlgorithmVersion
import com.noLate.schedule.domain.EtaAccuracyEligibilityPolicyVersion
import com.noLate.schedule.domain.EtaAccuracyEligibilityReason
import com.noLate.schedule.domain.EtaOnTimeOutcome
import com.noLate.schedule.domain.EtaProviderId
import com.noLate.schedule.domain.ScheduleArrivalObservationVerification
import com.noLate.schedule.domain.ScheduleNotificationActionReceipt
import com.noLate.schedule.domain.ScheduleNotificationActionType
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.ScheduleRouteSetupReminder
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.ScheduleEtaAccuracyObservationRepository
import com.noLate.schedule.infrastructure.ScheduleNotificationActionReceiptRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.transaction.support.TransactionTemplate

@DataJpaTest
@Import(
    AccountCleanupService::class,
    NotificationTokenRetirementService::class,
    ScheduleAccessPolicy::class,
    ScheduleSharingAvailabilityPolicy::class,
    ScheduleTravelAccessCleanupService::class,
    AccountOwnerWithdrawalCleanupTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:owner-withdrawal-cleanup;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountOwnerWithdrawalCleanupIntegrationTest @Autowired constructor(
    private val cleanupService: AccountCleanupService,
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val jobRepository: SchedulePushJobRepository,
    private val planRepository: ScheduleTravelPlanRepository,
    private val markerRepository: ScheduleRouteSetupReminderRepository,
    private val notificationRepository: AppNotificationRepository,
    private val deliveryRepository: PushDeliveryRepository,
    private val historyRepository: PushSendHistoryRepository,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val categoryRepository: ScheduleCategoryRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val departureStatusRepository: ScheduleDepartureStatusRepository,
    private val etaAccuracyObservationRepository: ScheduleEtaAccuracyObservationRepository,
    private val departureAlarmFireEventRepository: DepartureAlarmFireEventRepository,
    private val departureAlarmScheduleReceiptRepository: DepartureAlarmScheduleReceiptRepository,
    private val actionReceiptRepository: ScheduleNotificationActionReceiptRepository,
    private val transactionManager: PlatformTransactionManager,
    private val invalidationRecorder: AccountCleanupInvalidationRecorder,
) {

    @Test
    fun `owner withdrawal removes lower-id participant notification state before schedule`() {
        // Participant is deliberately inserted first so its row ID is lower than the owner ID.
        val participant = memberRepository.saveAndFlush(
            Member(
                name = "participant",
                password = "Password1!",
                email = "participant-withdrawal@example.com",
            )
        )
        val owner = memberRepository.saveAndFlush(
            Member(
                name = "owner",
                password = "Password1!",
                email = "owner-withdrawal@example.com",
                sessionGeneration = 4L,
            )
        )
        val participantId = requireNotNull(participant.id)
        val ownerId = requireNotNull(owner.id)
        val startAt = Instant.parse("2099-07-25T01:00:00Z")
        val schedule = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = ownerId,
                title = "owner schedule",
                startAt = startAt,
                endAt = startAt.plusSeconds(3_600),
            )
        )
        val scheduleId = requireNotNull(schedule.id)
        planRepository.saveAndFlush(
            ScheduleTravelPlan(
                scheduleId = scheduleId,
                memberId = participantId,
                travelMinutes = 30,
                notificationEnabled = true,
                scheduleFingerprint = "f".repeat(64),
            )
        )
        jobRepository.saveAndFlush(
            SchedulePushJob.create(
                memberId = participantId,
                scheduleId = scheduleId,
                scheduleAt = startAt,
                departureAt = startAt.minusSeconds(1_800),
                monitorStartAt = startAt.minusSeconds(3_600),
                intervalMinutes = 20,
            )
        )
        markerRepository.saveAndFlush(
            ScheduleRouteSetupReminder(
                scheduleId = scheduleId,
                memberId = participantId,
                scheduleFingerprint = "f".repeat(64),
                nextAttemptAt = Instant.parse("2026-07-24T00:00:00Z"),
            )
        )
        val source = notificationRepository.saveAndFlush(
            AppNotification(
                memberId = participantId,
                logicalEventKey = "logical:owner-withdrawal",
                type = "SCHEDULE_DEPARTURE_REMINDER",
                scheduleId = scheduleId,
                title = "private title",
                body = "private body",
                dataJson = "{}",
                createdAt = Instant.parse("2026-07-24T00:00:00Z"),
            )
        )
        deliveryRepository.saveAndFlush(
            PushDelivery(
                memberId = participantId,
                eventKey = source.logicalEventKey,
                deviceKey = "device-sha256:test",
                tokenFingerprint = "a".repeat(64),
                tokenOwnershipVersion = 1L,
                platform = PushPlatform.ANDROID,
                scheduleId = scheduleId,
            )
        )
        historyRepository.saveAndFlush(
            PushSendHistory(
                memberId = participantId,
                scheduleId = scheduleId,
                title = "private title",
                body = "private body",
                dataJson = "{}",
                status = PushSendStatus.FAILED,
                sentAt = Instant.parse("2026-07-24T00:00:00Z"),
            )
        )
        departureAlarmFireEventRepository.saveAndFlush(
            DepartureAlarmFireEvent(
                memberId = participantId,
                clientEventId = "550e8400-e29b-41d4-a716-446655440000",
                deviceFingerprint = "f".repeat(64),
                alarmId = "schedule:$scheduleId:member:$participantId",
                scheduleId = scheduleId,
                generation = 2,
                desiredGenerationAtReceipt = 2,
                desiredOperationAtReceipt = DepartureAlarmSyncOperation.UPSERT,
                generationRelation = DepartureAlarmGenerationRelation.CURRENT,
                scheduledFor = startAt.minusSeconds(1_800),
                sourceTriggerAt = startAt.minusSeconds(1_800),
                clientOccurredAt = startAt.minusSeconds(1_797),
                fireDelaySeconds = 3,
                serverRecordedAt = Instant.parse("2026-07-24T00:00:00Z"),
            )
        )
        departureAlarmScheduleReceiptRepository.saveAndFlush(
            DepartureAlarmScheduleReceipt(
                memberId = participantId,
                clientReceiptId = "550e8400-e29b-41d4-a716-446655440100",
                deviceFingerprint = "f".repeat(64),
                commandReceiptKey = "e".repeat(64),
                alarmId = "schedule:$scheduleId:member:$participantId",
                scheduleId = scheduleId,
                generation = 2,
                desiredGenerationAtReceipt = 2,
                desiredOperationAtReceipt = DepartureAlarmSyncOperation.UPSERT,
                generationRelation = DepartureAlarmGenerationRelation.CURRENT,
                operation = DepartureAlarmSyncOperation.UPSERT,
                triggerAt = startAt.minusSeconds(1_800),
                outcome = DepartureAlarmScheduleOutcome.SCHEDULED,
                applied = true,
                scheduled = true,
                platform = PushPlatform.ANDROID,
                deliveryMode = DepartureAlarmDeliveryMode.ANDROID_EXACT,
                source = DepartureAlarmScheduleSource.SNAPSHOT,
                failureReason = null,
                clientOccurredAt = Instant.parse("2026-07-24T00:00:00Z"),
                serverRecordedAt = Instant.parse("2026-07-24T00:00:01Z"),
            )
        )

        cleanupService.withdraw(owner)

        assertFalse(memberRepository.findById(participantId).orElseThrow().deleted)
        assertTrue(memberRepository.findById(ownerId).orElseThrow().deleted)
        assertTrue(scheduleRepository.findById(scheduleId).isEmpty)
        assertTrue(jobRepository.findAllByScheduleId(scheduleId).isEmpty())
        assertTrue(planRepository.findAllByScheduleIdAndDeletedFalse(scheduleId).isEmpty())
        assertTrue(markerRepository.findAll().none { it.scheduleId == scheduleId })
        assertTrue(notificationRepository.findAll().none { it.scheduleId == scheduleId })
        assertTrue(deliveryRepository.findAll().none { it.scheduleId == scheduleId })
        assertTrue(departureAlarmFireEventRepository.findAll().none { it.scheduleId == scheduleId })
        assertTrue(
            departureAlarmScheduleReceiptRepository.findAll().none { it.scheduleId == scheduleId }
        )
        assertTrue(
            historyRepository.findAllByScheduleIdOrderBySentAtDesc(
                scheduleId,
                org.springframework.data.domain.PageRequest.of(0, 10),
            ).isEmpty()
        )
        assertTrue(
            invalidationRecorder.events.any {
                it.reason == "account-withdrawn" &&
                    it.memberIds == setOf(ownerId, participantId)
            }
        )
    }

    @Test
    fun `owner withdrawal purges participant artifacts for a previously soft deleted shared schedule`() {
        val participant = member("soft-deleted-schedule-participant")
        val owner = member("soft-deleted-schedule-owner", sessionGeneration = 10L)
        val participantId = requireNotNull(participant.id)
        val ownerId = requireNotNull(owner.id)
        val startAt = Instant.parse("2099-08-03T01:00:00Z")
        val schedule = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = ownerId,
                title = "withdrawal-private-soft-deleted-schedule",
                startAt = startAt,
                endAt = startAt.plusSeconds(3_600),
            ),
        )
        val scheduleId = requireNotNull(schedule.id)
        scheduleShareRepository.saveAndFlush(
            ScheduleShare(
                scheduleId = scheduleId,
                ownerMemberId = ownerId,
                targetMemberId = participantId,
                permission = ScheduleSharePermission.VIEWER,
                status = ScheduleShareStatus.ACTIVE,
            ),
        )
        seedScheduleArtifacts(
            scheduleId = scheduleId,
            memberId = participantId,
            key = "soft-deleted-owner-withdrawal",
            startAt = startAt,
        )
        departureStatusRepository.saveAndFlush(
            ScheduleDepartureStatus(
                scheduleId = scheduleId,
                memberId = participantId,
                departedAt = Instant.parse("2026-07-24T00:00:00Z"),
            ),
        )
        etaAccuracyObservationRepository.saveAndFlush(
            ScheduleEtaAccuracyObservation(
                scheduleId = scheduleId,
                memberId = participantId,
                pushJobId = jobRepository
                    .findByScheduleIdAndMemberId(scheduleId, participantId)
                    ?.id,
                departedAt = Instant.parse("2026-07-24T00:00:00Z"),
                predictionEvaluatedAt = Instant.parse("2026-07-23T23:59:00Z"),
                predictedArrivalAt = Instant.parse("2026-07-24T00:30:00Z"),
                recommendedDepartureAt = Instant.parse("2026-07-24T00:00:00Z"),
                targetArrivalAt = Instant.parse("2026-07-24T00:30:00Z"),
                actualArrivalAt = Instant.parse("2026-07-24T00:35:00Z"),
                observationSource = ScheduleArrivalObservationSource.USER_NOW,
                observationVerification = ScheduleArrivalObservationVerification.UNVERIFIED_CLIENT,
                precisionSeconds = 30,
                adjustmentSeconds = null,
                clientAppVersion = null,
                clientBuildVersion = null,
                backendCohortVersion = "integration-test",
                eligibilityPolicyVersion = EtaAccuracyEligibilityPolicyVersion.SELF_REPORT_DIAGNOSTIC_V2,
                etaSource = TrafficSource.LIVE_PROVIDER,
                etaStale = false,
                travelMinutes = 30,
                predictionBasis = EtaPredictionBasis.PROVIDER_ABSOLUTE,
                travelMode = ScheduleTravelMode.TRANSIT,
                providerId = EtaProviderId.ODSAY_TRANSIT,
                algorithmVersion = EtaAlgorithmVersion.TRANSIT_REALTIME_V2,
                providerFetchedAt = Instant.parse("2026-07-23T23:59:00Z"),
                predictedOnTime = true,
                actualOnTime = false,
                onTimeOutcome = EtaOnTimeOutcome.PREDICTED_ON_TIME_ACTUAL_LATE,
                departureOffsetSeconds = 0,
                actualTravelSeconds = 35 * 60,
                reportDelaySeconds = 1,
                accuracyEligible = false,
                accuracyEligibilityReason = EtaAccuracyEligibilityReason.UNVERIFIED_USER_NOW,
                signedErrorSeconds = 300,
                absoluteErrorSeconds = 300,
                recordedAt = Instant.parse("2026-07-24T00:35:01Z"),
            ),
        )
        actionReceiptRepository.saveAndFlush(
            ScheduleNotificationActionReceipt(
                keyFingerprint = "e".repeat(64),
                memberId = participantId,
                scheduleId = scheduleId,
                actionType = ScheduleNotificationActionType.DEPART_NOW,
                resultDepartedAt = Instant.parse("2026-07-24T00:00:00Z"),
                completedAt = Instant.parse("2026-07-24T00:00:00Z"),
                createdAt = Instant.parse("2026-07-24T00:00:00Z"),
            ),
        )

        schedule.softDelete()
        scheduleRepository.saveAndFlush(schedule)
        jobRepository.findByScheduleIdAndMemberId(scheduleId, participantId)
            ?.also {
                it.cancel()
                jobRepository.saveAndFlush(it)
            }
            ?: error("participant push job missing before withdrawal")

        cleanupService.withdraw(owner)

        assertTrue(memberRepository.findById(ownerId).orElseThrow().deleted)
        assertFalse(memberRepository.findById(participantId).orElseThrow().deleted)
        assertTrue(scheduleRepository.findById(scheduleId).isEmpty)
        assertTrue(
            scheduleShareRepository.findByScheduleIdAndTargetMemberId(scheduleId, participantId) == null,
        )
        assertNoScheduleArtifacts(scheduleId, participantId)
        assertTrue(
            departureStatusRepository.findAllByScheduleIdAndDeletedFalse(scheduleId).isEmpty(),
        )
        assertTrue(
            etaAccuracyObservationRepository
                .findByScheduleIdAndMemberId(scheduleId, participantId) == null,
        )
        assertTrue(
            actionReceiptRepository.findAll()
                .none { it.scheduleId == scheduleId && it.memberId == participantId },
        )
        assertTrue(
            notificationRepository.findAll()
                .none {
                    it.scheduleId == scheduleId &&
                        (
                            it.title.contains("private") ||
                                it.body.contains("private") ||
                                it.dataJson.contains("private")
                            )
                },
        )
        assertTrue(
            historyRepository.findAll()
                .none {
                    it.scheduleId == scheduleId &&
                        (
                            it.title.contains("private") ||
                                it.body.contains("private") ||
                                it.dataJson.contains("private")
                            )
                },
        )
    }

    @Test
    fun `active calendar owner must transfer or archive before withdrawal`() {
        val owner = member("active-calendar-owner", sessionGeneration = 8L)
        val ownerId = requireNotNull(owner.id)
        val calendar = calendar(ownerId, "active-owner")
        val calendarId = requireNotNull(calendar.id)

        val failure = assertThrows<BusinessException> {
            cleanupService.withdraw(owner)
        }

        assertEquals(ErrorCode.INVALID_STATE, failure.errorCode)
        assertFalse(memberRepository.findById(ownerId).orElseThrow().deleted)
        assertEquals(
            ScheduleCalendarStatus.ACTIVE,
            calendarRepository.findById(calendarId).orElseThrow().status,
        )
        val membership = calendarMemberRepository
            .findByCalendarIdAndMemberId(calendarId, ownerId)
            ?: error("owner membership missing")
        assertEquals(ScheduleCalendarRole.OWNER, membership.role)
        assertEquals(ScheduleCalendarMemberStatus.ACTIVE, membership.status)
        assertFalse(membership.deleted)
    }

    @Test
    fun `participant withdrawal leaves an auditable inactive membership and clears personal artifacts`() {
        val owner = member("participant-calendar-owner")
        val participant = member("participant-calendar-member", sessionGeneration = 3L)
        val ownerId = requireNotNull(owner.id)
        val participantId = requireNotNull(participant.id)
        val calendar = calendar(ownerId, "participant")
        val calendarId = requireNotNull(calendar.id)
        calendarMemberRepository.saveAndFlush(
            ScheduleCalendarMember(
                calendarId = calendarId,
                memberId = participantId,
                role = ScheduleCalendarRole.VIEWER,
            ),
        )
        val startAt = Instant.parse("2099-08-01T01:00:00Z")
        val schedule = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = ownerId,
                calendarId = calendarId,
                title = "calendar owner schedule",
                startAt = startAt,
                endAt = startAt.plusSeconds(3_600),
            ),
        )
        val scheduleId = requireNotNull(schedule.id)
        seedScheduleArtifacts(scheduleId, participantId, "participant-withdrawal", startAt)

        cleanupService.withdraw(participant)

        assertTrue(memberRepository.findById(participantId).orElseThrow().deleted)
        assertFalse(memberRepository.findById(ownerId).orElseThrow().deleted)
        assertEquals(
            ScheduleCalendarStatus.ACTIVE,
            calendarRepository.findById(calendarId).orElseThrow().status,
        )
        val membership = calendarMemberRepository
            .findByCalendarIdAndMemberId(calendarId, participantId)
            ?: error("participant membership audit row missing")
        assertEquals(ScheduleCalendarMemberStatus.LEFT, membership.status)
        assertTrue(membership.deleted)
        assertTrue(scheduleRepository.findById(scheduleId).isPresent)
        assertNoScheduleArtifacts(scheduleId, participantId)
    }

    @Test
    fun `empty owned category withdrawal removes recipient inbox source and delivery`() {
        val owner = member("empty-category-owner", sessionGeneration = 5L)
        val target = member("empty-category-target")
        val ownerId = requireNotNull(owner.id)
        val targetId = requireNotNull(target.id)
        val category = categoryRepository.saveAndFlush(
            ScheduleCategory(
                memberId = ownerId,
                title = "empty shared category",
                color = "#778899",
            ),
        )
        val categoryId = requireNotNull(category.id)
        categoryShareRepository.saveAndFlush(
            ScheduleCategoryShare(
                categoryId = categoryId,
                ownerMemberId = ownerId,
                targetMemberId = targetId,
                permission = ScheduleSharePermission.VIEWER,
                status = ScheduleShareStatus.ACTIVE,
            ),
        )
        val source = notificationRepository.saveAndFlush(
            AppNotification(
                memberId = targetId,
                logicalEventKey = "logical:empty-category-$categoryId",
                type = "CATEGORY_SHARE_RECEIVED",
                categoryId = categoryId,
                title = "shared category",
                body = "category invitation accepted",
                dataJson = """{"categoryId":$categoryId}""",
                createdAt = Instant.parse("2026-07-24T00:00:00Z"),
            ),
        )
        deliveryRepository.saveAndFlush(
            PushDelivery(
                memberId = targetId,
                eventKey = source.logicalEventKey,
                deviceKey = "device-sha256:empty-category-$targetId",
                tokenFingerprint = "b".repeat(64),
                tokenOwnershipVersion = 1L,
                platform = PushPlatform.ANDROID,
                payloadType = "CATEGORY_SHARE_RECEIVED",
            ),
        )

        cleanupService.withdraw(owner)

        assertTrue(memberRepository.findById(ownerId).orElseThrow().deleted)
        assertFalse(memberRepository.findById(targetId).orElseThrow().deleted)
        assertTrue(categoryRepository.findById(categoryId).isEmpty)
        assertTrue(categoryShareRepository.findAllByCategoryIdAndDeletedFalse(categoryId).isEmpty())
        assertTrue(
            notificationRepository.findAllByMemberIdOrderByIdDesc(targetId)
                .none { it.categoryId == categoryId },
        )
        assertTrue(
            deliveryRepository
                .findAllByMemberIdAndEventKeyOrderByIdAsc(targetId, source.logicalEventKey)
                .isEmpty(),
        )
    }

    @Test
    fun `editor owned schedule survives owned category withdrawal without travel or push artifacts`() {
        val categoryOwner = member("editor-category-owner", sessionGeneration = 6L)
        val editor = member("editor-category-editor")
        val viewer = member("editor-category-viewer")
        val categoryOwnerId = requireNotNull(categoryOwner.id)
        val editorId = requireNotNull(editor.id)
        val viewerId = requireNotNull(viewer.id)
        val category = categoryRepository.saveAndFlush(
            ScheduleCategory(
                memberId = categoryOwnerId,
                title = "shared work",
                color = "#123456",
            ),
        )
        val categoryId = requireNotNull(category.id)
        categoryShareRepository.saveAllAndFlush(
            listOf(
                ScheduleCategoryShare(
                    categoryId = categoryId,
                    ownerMemberId = categoryOwnerId,
                    targetMemberId = editorId,
                    permission = ScheduleSharePermission.EDITOR,
                    status = ScheduleShareStatus.ACTIVE,
                ),
                ScheduleCategoryShare(
                    categoryId = categoryId,
                    ownerMemberId = categoryOwnerId,
                    targetMemberId = viewerId,
                    permission = ScheduleSharePermission.VIEWER,
                    status = ScheduleShareStatus.ACTIVE,
                ),
            ),
        )
        val startAt = Instant.parse("2099-08-02T01:00:00Z")
        val editorSchedule = Schedule(
            memberId = editorId,
            categoryId = categoryId,
            title = "editor-owned category schedule",
            startAt = startAt,
            endAt = startAt.plusSeconds(3_600),
        ).apply {
            updateCategorySnapshot(categoryId.toString(), category.title, category.color)
        }
        val scheduleId = requireNotNull(scheduleRepository.saveAndFlush(editorSchedule).id)
        seedScheduleArtifacts(scheduleId, editorId, "editor-owner", startAt)
        seedScheduleArtifacts(scheduleId, viewerId, "editor-viewer", startAt)

        cleanupService.withdraw(categoryOwner)

        val retained = scheduleRepository.findById(scheduleId).orElseThrow()
        assertFalse(retained.deleted)
        assertEquals(editorId, retained.memberId)
        assertEquals(null, retained.categoryId)
        assertTrue(categoryRepository.findById(categoryId).isEmpty)
        assertTrue(categoryShareRepository.findAllByCategoryIdAndDeletedFalse(categoryId).isEmpty())
        assertTrue(scheduleRepository.findScheduleDetail(scheduleId, editorId) != null)
        assertTrue(scheduleRepository.findScheduleDetail(scheduleId, viewerId) == null)
        assertNoScheduleArtifacts(scheduleId, editorId)
        assertNoScheduleArtifacts(scheduleId, viewerId)
    }

    @Test
    @Timeout(20)
    fun `calendar creation that wins the member fence makes withdrawal fail closed`() {
        val owner = member("calendar-create-race-owner", sessionGeneration = 9L)
        val ownerId = requireNotNull(owner.id)
        val calendarPersisted = CountDownLatch(1)
        val allowCalendarCommit = CountDownLatch(1)
        val calendarId = AtomicReference<Long>()
        val executor = Executors.newFixedThreadPool(2)

        val creation = executor.submit {
            TransactionTemplate(transactionManager).executeWithoutResult {
                memberRepository.findByIdForUpdate(ownerId)
                    ?: error("owner disappeared before calendar creation")
                val created = calendarRepository.saveAndFlush(
                    ScheduleCalendar(
                        ownerMemberId = ownerId,
                        title = "calendar-create-race",
                    ),
                )
                val createdId = requireNotNull(created.id)
                calendarMemberRepository.saveAndFlush(
                    ScheduleCalendarMember(
                        calendarId = createdId,
                        memberId = ownerId,
                        role = ScheduleCalendarRole.OWNER,
                    ),
                )
                calendarId.set(createdId)
                calendarPersisted.countDown()
                check(allowCalendarCommit.await(10, TimeUnit.SECONDS)) {
                    "calendar commit gate timed out"
                }
            }
        }

        try {
            assertTrue(calendarPersisted.await(10, TimeUnit.SECONDS))
            val withdrawal = executor.submit<Throwable?> {
                runCatching { cleanupService.withdraw(owner) }.exceptionOrNull()
            }

            assertThrows<TimeoutException> {
                withdrawal.get(300, TimeUnit.MILLISECONDS)
            }

            allowCalendarCommit.countDown()
            creation.get(10, TimeUnit.SECONDS)
            val failure = withdrawal.get(10, TimeUnit.SECONDS)
            assertTrue(failure is BusinessException, failure?.stackTraceToString())
            assertEquals(ErrorCode.INVALID_STATE, (failure as BusinessException).errorCode)
            assertFalse(memberRepository.findById(ownerId).orElseThrow().deleted)
            assertEquals(
                ScheduleCalendarStatus.ACTIVE,
                calendarRepository.findById(requireNotNull(calendarId.get())).orElseThrow().status,
            )
        } finally {
            allowCalendarCommit.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun member(
        label: String,
        sessionGeneration: Long = 1L,
    ): Member = memberRepository.saveAndFlush(
        Member(
            name = label,
            password = "Password1!",
            email = "$label@example.com",
            sessionGeneration = sessionGeneration,
        ),
    )

    private fun calendar(ownerMemberId: Long, label: String): ScheduleCalendar {
        val calendar = calendarRepository.saveAndFlush(
            ScheduleCalendar(
                ownerMemberId = ownerMemberId,
                title = "$label calendar",
            ),
        )
        calendarMemberRepository.saveAndFlush(
            ScheduleCalendarMember(
                calendarId = requireNotNull(calendar.id),
                memberId = ownerMemberId,
                role = ScheduleCalendarRole.OWNER,
            ),
        )
        return calendar
    }

    private fun seedScheduleArtifacts(
        scheduleId: Long,
        memberId: Long,
        key: String,
        startAt: Instant,
    ) {
        planRepository.saveAndFlush(
            ScheduleTravelPlan(
                scheduleId = scheduleId,
                memberId = memberId,
                travelMinutes = 30,
                notificationEnabled = true,
                scheduleFingerprint = "c".repeat(64),
            ),
        )
        jobRepository.saveAndFlush(
            SchedulePushJob.create(
                memberId = memberId,
                scheduleId = scheduleId,
                scheduleAt = startAt,
                departureAt = startAt.minusSeconds(1_800),
                monitorStartAt = startAt.minusSeconds(3_600),
                intervalMinutes = 20,
            ),
        )
        markerRepository.saveAndFlush(
            ScheduleRouteSetupReminder(
                scheduleId = scheduleId,
                memberId = memberId,
                scheduleFingerprint = "c".repeat(64),
                nextAttemptAt = Instant.parse("2026-07-24T00:00:00Z"),
            ),
        )
        val source = notificationRepository.saveAndFlush(
            AppNotification(
                memberId = memberId,
                logicalEventKey = "logical:$key",
                type = "SCHEDULE_DEPARTURE_REMINDER",
                scheduleId = scheduleId,
                title = "private title",
                body = "private body",
                dataJson = "{}",
                createdAt = Instant.parse("2026-07-24T00:00:00Z"),
            ),
        )
        deliveryRepository.saveAndFlush(
            PushDelivery(
                memberId = memberId,
                eventKey = source.logicalEventKey,
                deviceKey = "device-sha256:$key",
                tokenFingerprint = "d".repeat(64),
                tokenOwnershipVersion = 1L,
                platform = PushPlatform.ANDROID,
                scheduleId = scheduleId,
            ),
        )
        historyRepository.saveAndFlush(
            PushSendHistory(
                memberId = memberId,
                scheduleId = scheduleId,
                title = "private title",
                body = "private body",
                dataJson = "{}",
                status = PushSendStatus.FAILED,
                sentAt = Instant.parse("2026-07-24T00:00:00Z"),
            ),
        )
    }

    private fun assertNoScheduleArtifacts(scheduleId: Long, memberId: Long) {
        assertTrue(
            jobRepository.findAllByScheduleId(scheduleId)
                .none { it.memberId == memberId },
        )
        assertTrue(
            planRepository.findAllByScheduleIdAndDeletedFalse(scheduleId)
                .none { it.memberId == memberId },
        )
        assertTrue(
            markerRepository.findAll()
                .none { it.scheduleId == scheduleId && it.memberId == memberId },
        )
        assertTrue(
            notificationRepository.findAll()
                .none { it.scheduleId == scheduleId && it.memberId == memberId },
        )
        assertTrue(
            deliveryRepository.findAll()
                .none { it.scheduleId == scheduleId && it.memberId == memberId },
        )
        assertTrue(
            historyRepository.findAllByScheduleIdOrderBySentAtDesc(
                scheduleId,
                org.springframework.data.domain.PageRequest.of(0, 100),
            ).none { it.memberId == memberId },
        )
    }
}

@TestConfiguration
class AccountOwnerWithdrawalCleanupTestConfig {
    @Bean
    fun accountOwnerWithdrawalCleanupClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC)

    @Bean
    fun accountCleanupInvalidationRecorder(): AccountCleanupInvalidationRecorder =
        AccountCleanupInvalidationRecorder()
}

class AccountCleanupInvalidationRecorder {
    val events = CopyOnWriteArrayList<ScheduleCalendarCacheInvalidationEvent>()

    @EventListener
    fun onInvalidated(event: ScheduleCalendarCacheInvalidationEvent) {
        events += event
    }
}
