package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.PushClient
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushManifestState
import com.noLate.notification.domain.PushOutboxDispatchStatus
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleShareInvitationStatus
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.domain.ScheduleType
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-ancillary-session-fence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.push.enabled=false",
        "notification.push-outbox.enabled=false",
    ],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduleAncillaryMutationSessionFenceIntegrationTest @Autowired constructor(
    private val categoryService: ScheduleCategoryService,
    private val shareService: ScheduleShareService,
    private val departureNotificationService: ScheduleDepartureNotificationService,
    private val memberRepository: MemberRepository,
    private val categoryRepository: ScheduleCategoryRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val scheduleRepository: ScheduleRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val invitationRepository: ScheduleShareInvitationRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val appNotificationRepository: AppNotificationRepository,
    private val pushDeliveryRepository: PushDeliveryRepository,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val objectMapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @MockitoBean
    private lateinit var pushClient: PushClient

    @Test
    fun `category mutations captured under g1 resume after g2 and change no category`() {
        val actor = member("category-fence", sessionGeneration = 1L)
        val actorId = requireNotNull(actor.id)
        val category = transactions.execute {
            categoryRepository.saveAndFlush(
                ScheduleCategory(memberId = actorId, title = "기존", color = "#123456"),
            )
        } ?: error("category fixture was not created")
        val categoryId = requireNotNull(category.id)
        val beforeCount = categoryRepository.findAllByMemberId(actorId).size

        val firstFailure = invokeAfterGenerationAdvance(actorId) {
            categoryService.getCategories(actorId, presentedSessionGeneration = 1L)
        }
        val failures = listOf(
            firstFailure,
            runCatching {
                categoryService.createCategory(actorId, "신규", null, null, null, 1L)
            }.exceptionOrNull(),
            runCatching {
                categoryService.updateCategory(actorId, categoryId, "수정", null, null, null, 1L)
            }.exceptionOrNull(),
            runCatching {
                categoryService.reorderCategories(
                    actorId,
                    listOf(ScheduleCategoryReorderItem(categoryId, 9)),
                    1L,
                )
            }.exceptionOrNull(),
            runCatching {
                categoryService.deleteCategory(actorId, categoryId, 1L)
            }.exceptionOrNull(),
        )

        failures.forEach(::assertInvalidToken)
        val persisted = categoryRepository.findById(categoryId).orElseThrow()
        assertEquals("기존", persisted.title)
        assertEquals(0, persisted.sortOrder)
        assertFalse(persisted.deleted)
        assertEquals(beforeCount, categoryRepository.findAllByMemberId(actorId).size)
    }

    @Test
    fun `share invitation revoke and accept captured under old generations mutate no row or outbox`() {
        val owner = member("share-owner", sessionGeneration = 3L)
        val target = member("share-target", sessionGeneration = 5L)
        val fixture = scheduleFixture(requireNotNull(owner.id), "share-fence")
        val share = shareService.shareSchedule(
            ownerMemberId = requireNotNull(owner.id),
            scheduleId = fixture.scheduleId,
            targetEmail = null,
            targetAppId = requireNotNull(target.id),
            permission = ScheduleSharePermission.VIEWER,
            presentedSessionGeneration = 3L,
        )
        val categoryShare = shareService.shareCategory(
            ownerMemberId = requireNotNull(owner.id),
            categoryId = fixture.categoryId,
            targetEmail = null,
            targetAppId = requireNotNull(target.id),
            permission = ScheduleSharePermission.VIEWER,
            presentedSessionGeneration = 3L,
        )
        val invitation = shareService.createScheduleInvitation(
            ownerMemberId = requireNotNull(owner.id),
            scheduleId = fixture.scheduleId,
            permission = ScheduleSharePermission.VIEWER,
            ttlHours = 24L,
            maxAcceptCount = 1,
            presentedSessionGeneration = 3L,
        )
        val beforeOutboxCount = appNotificationRepository.count()
        val beforeJobCount = pushJobRepository.count()

        val updateFailure = invokeAfterGenerationAdvance(requireNotNull(owner.id)) {
            shareService.updateScheduleShare(
                ownerMemberId = requireNotNull(owner.id),
                scheduleId = fixture.scheduleId,
                shareId = share.id.toLong(),
                permission = ScheduleSharePermission.EDITOR,
                presentedSessionGeneration = 3L,
            )
        }
        val ownerFailures = listOf(
            updateFailure,
            runCatching {
                shareService.revokeScheduleShare(
                    requireNotNull(owner.id),
                    fixture.scheduleId,
                    share.id.toLong(),
                    3L,
                )
            }.exceptionOrNull(),
            runCatching {
                shareService.createScheduleInvitation(
                    requireNotNull(owner.id),
                    fixture.scheduleId,
                    ScheduleSharePermission.EDITOR,
                    ttlHours = 12L,
                    maxAcceptCount = 2,
                    presentedSessionGeneration = 3L,
                )
            }.exceptionOrNull(),
            runCatching {
                shareService.revokeInvitation(
                    ownerMemberId = requireNotNull(owner.id),
                    resourceType = com.noLate.schedule.domain.ScheduleShareResourceType.SCHEDULE,
                    resourceId = fixture.scheduleId,
                    invitationId = invitation.id.toLong(),
                    presentedSessionGeneration = 3L,
                )
            }.exceptionOrNull(),
            runCatching {
                shareService.updateCategoryShare(
                    ownerMemberId = requireNotNull(owner.id),
                    categoryId = fixture.categoryId,
                    shareId = categoryShare.id.toLong(),
                    permission = ScheduleSharePermission.EDITOR,
                    presentedSessionGeneration = 3L,
                )
            }.exceptionOrNull(),
            runCatching {
                shareService.revokeCategoryShare(
                    ownerMemberId = requireNotNull(owner.id),
                    categoryId = fixture.categoryId,
                    shareId = categoryShare.id.toLong(),
                    presentedSessionGeneration = 3L,
                )
            }.exceptionOrNull(),
        )
        ownerFailures.forEach(::assertInvalidToken)

        val acceptFailure = invokeAfterGenerationAdvance(requireNotNull(target.id)) {
            shareService.acceptInvitation(
                currentMemberId = requireNotNull(target.id),
                token = invitation.token,
                presentedSessionGeneration = 5L,
            )
        }
        assertInvalidToken(acceptFailure)

        val persistedShare = scheduleShareRepository
            .findByIdAndScheduleIdAndDeletedFalse(share.id.toLong(), fixture.scheduleId)
            ?: error("share disappeared")
        assertEquals(ScheduleSharePermission.VIEWER, persistedShare.permission)
        assertEquals(ScheduleShareStatus.ACTIVE, persistedShare.status)
        val persistedCategoryShare = categoryShareRepository
            .findByIdAndCategoryIdAndDeletedFalse(
                categoryShare.id.toLong(),
                fixture.categoryId,
            ) ?: error("category share disappeared")
        assertEquals(ScheduleSharePermission.VIEWER, persistedCategoryShare.permission)
        assertEquals(ScheduleShareStatus.ACTIVE, persistedCategoryShare.status)
        val persistedInvitation = invitationRepository.findById(invitation.id.toLong()).orElseThrow()
        assertEquals(ScheduleShareInvitationStatus.PENDING, persistedInvitation.status)
        assertEquals(0, persistedInvitation.acceptedCount)
        assertEquals(beforeOutboxCount, appNotificationRepository.count())
        assertEquals(beforeJobCount, pushJobRepository.count())
    }

    @Test
    fun `departure nudge captured under g1 resumes after g2 without an outbox write`() {
        val owner = member("nudge-owner", sessionGeneration = 8L)
        val target = member("nudge-target", sessionGeneration = 2L)
        val fixture = scheduleFixture(requireNotNull(owner.id), "nudge-fence")
        shareService.shareSchedule(
            ownerMemberId = requireNotNull(owner.id),
            scheduleId = fixture.scheduleId,
            targetEmail = null,
            targetAppId = requireNotNull(target.id),
            permission = ScheduleSharePermission.VIEWER,
            presentedSessionGeneration = 8L,
        )
        val beforeOutboxCount = appNotificationRepository.count()
        val beforeJobCount = pushJobRepository.count()

        val failure = invokeAfterGenerationAdvance(requireNotNull(owner.id)) {
            departureNotificationService.sendDepartureNudge(
                ownerMemberId = requireNotNull(owner.id),
                scheduleId = fixture.scheduleId,
                targetMemberId = requireNotNull(target.id),
                presentedSessionGeneration = 8L,
            )
        }

        assertInvalidToken(failure)
        assertEquals(beforeOutboxCount, appNotificationRepository.count())
        assertEquals(beforeJobCount, pushJobRepository.count())
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `current generation departure nudge freezes a pending manifest without provider IO or self deadlock`() {
        val owner = member("nudge-current-owner", sessionGeneration = 8L)
        val target = member("nudge-current-target", sessionGeneration = 0L)
        val ownerId = requireNotNull(owner.id)
        val targetId = requireNotNull(target.id)
        val fixture = scheduleFixture(
            ownerMemberId = ownerId,
            label = "nudge-current",
            scheduleType = ScheduleType.ROUTE,
        )
        shareService.shareSchedule(
            ownerMemberId = ownerId,
            scheduleId = fixture.scheduleId,
            targetEmail = null,
            targetAppId = targetId,
            permission = ScheduleSharePermission.VIEWER,
            presentedSessionGeneration = 8L,
        )
        val tokenSuffix = System.nanoTime()
        transactions.executeWithoutResult {
            tokenRepository.saveAndFlush(
                NotificationDeviceToken(
                    memberId = targetId,
                    deviceId = "nudge-device-$tokenSuffix",
                    platform = PushPlatform.ANDROID,
                    token = "nudge-token-$tokenSuffix",
                ),
            )
        }
        clearInvocations(pushClient)

        val result = departureNotificationService.sendDepartureNudge(
            ownerMemberId = ownerId,
            scheduleId = fixture.scheduleId,
            targetMemberId = targetId,
            presentedSessionGeneration = 8L,
        )

        assertEquals(1, result.requestedCount)
        assertEquals(0, result.attemptedCount)
        assertEquals(0, result.sentCount)
        val outbox = appNotificationRepository
            .findAllByMemberIdOrderByIdDesc(targetId)
            .single { it.type == "SCHEDULE_DEPARTURE_NUDGE" }
        assertEquals(PushManifestState.FROZEN, outbox.manifestState)
        assertEquals(1, outbox.manifestRecipientCount)
        assertEquals(PushOutboxDispatchStatus.PENDING, outbox.dispatchStatus)
        assertEquals(outbox.logicalEventKey, result.eventSnapshot?.logicalEventKey)
        assertTrue(
            outbox.deduplicationKey
                ?.startsWith("schedule-departure-nudge:${fixture.scheduleId}:$ownerId:$targetId:")
                == true,
        )
        val canonicalData: Map<String, String> = objectMapper.readValue(outbox.dataJson)
        assertEquals("SCHEDULE_DEPARTURE_NUDGE", canonicalData["type"])
        assertEquals(fixture.scheduleId.toString(), canonicalData["scheduleId"])
        assertEquals(ownerId.toString(), canonicalData["requestedByMemberId"])
        assertEquals(outbox.logicalEventKey, canonicalData["logicalEventKey"])
        assertEquals(targetId.toString(), canonicalData["recipientMemberId"])
        val deliveries = pushDeliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(
            targetId,
            outbox.logicalEventKey,
        )
        assertEquals(1, deliveries.size)
        assertEquals(targetId, deliveries.single().memberId)

        // The endpoint transaction only commits the source+manifest. A provider call here would
        // either re-lock target in REQUIRES_NEW (self-deadlock) or perform I/O under DB locks.
        verifyNoInteractions(pushClient)
    }

    private fun invokeAfterGenerationAdvance(
        memberId: Long,
        mutation: () -> Unit,
    ): Throwable? {
        val authenticated = CountDownLatch(1)
        val resumeMutation = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit {
            authenticated.countDown()
            check(resumeMutation.await(10, TimeUnit.SECONDS))
            failure.set(runCatching(mutation).exceptionOrNull())
        }
        try {
            assertTrue(authenticated.await(10, TimeUnit.SECONDS))
            transactions.executeWithoutResult {
                val member = memberRepository.findByIdForUpdate(memberId)
                    ?: error("fixture member disappeared")
                member.sessionGeneration = Math.addExact(member.sessionGeneration, 1L)
            }
            resumeMutation.countDown()
            future.get(10, TimeUnit.SECONDS)
            return failure.get()
        } finally {
            resumeMutation.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun member(label: String, sessionGeneration: Long): Member =
        transactions.execute {
            memberRepository.saveAndFlush(
                Member(
                    name = label,
                    password = "Password1!",
                    email = "$label-${System.nanoTime()}@example.com",
                    loginType = LoginType.COMMON,
                    sessionGeneration = sessionGeneration,
                ),
            )
        } ?: error("member fixture was not created")

    private fun scheduleFixture(
        ownerMemberId: Long,
        label: String,
        scheduleType: ScheduleType = ScheduleType.NORMAL,
    ): ScheduleFixture =
        transactions.execute {
            val category = categoryRepository.saveAndFlush(
                ScheduleCategory(memberId = ownerMemberId, title = label, color = "#123456"),
            )
            val schedule = scheduleRepository.saveAndFlush(
                Schedule(
                    memberId = ownerMemberId,
                    categoryId = requireNotNull(category.id),
                    scheduleType = scheduleType,
                    title = label,
                    startAt = Instant.parse("2099-07-24T05:00:00Z"),
                    endAt = Instant.parse("2099-07-24T06:00:00Z"),
                ),
            )
            ScheduleFixture(
                scheduleId = requireNotNull(schedule.id),
                categoryId = requireNotNull(category.id),
            )
        } ?: error("schedule fixture was not created")

    private fun assertInvalidToken(failure: Throwable?) {
        assertTrue(failure is BusinessException, failure?.stackTraceToString())
        assertEquals(ErrorCode.INVALID_TOKEN, (failure as BusinessException).errorCode)
    }

    private data class ScheduleFixture(
        val scheduleId: Long,
        val categoryId: Long,
    )
}
