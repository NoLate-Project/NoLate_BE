package com.noLate.member.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.JwtTokenProvider
import com.noLate.member.application.useCase.MemberUseCase
import com.noLate.member.domain.consent.SignupConsentCommand
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.MemberDto
import com.noLate.member.domain.profile.MemberProfileDto
import com.noLate.member.infrastructure.MemberProfileRepository
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.schedule.application.service.ScheduleCalendarService
import com.noLate.schedule.application.service.ScheduleCalendarMutationFenceObserver
import com.noLate.schedule.application.service.ScheduleShareMutationFenceObserver
import com.noLate.schedule.application.service.ScheduleShareService
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCalendarStatus
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.ScheduleRouteSetupReminder
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Security filter가 g1을 승인한 뒤 request handler 진입이 지연되는 account-transition 회귀다.
 *
 * public logout이 같은 member row에서 g2를 먼저 commit하면 재개된 g1 mutation은 실제
 * write transaction에서 generation mismatch를 관측하고 어떤 상태도 쓰지 않아야 한다.
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:member-authenticated-mutation-fence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.push.enabled=false",
        "notification.push-outbox.enabled=false",
    ],
)
@Import(MemberAuthenticatedMutationFenceTestConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MemberAuthenticatedMutationSessionFenceIntegrationTest @Autowired constructor(
    private val memberUseCase: MemberUseCase,
    private val memberRepository: MemberRepository,
    private val memberProfileRepository: MemberProfileRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val passwordEncoder: PasswordEncoder,
    private val calendarService: ScheduleCalendarService,
    private val shareService: ScheduleShareService,
    private val notificationTokenService: NotificationTokenService,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val categoryRepository: ScheduleCategoryRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val scheduleRepository: ScheduleRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val routeSetupReminderRepository: ScheduleRouteSetupReminderRepository,
    private val appNotificationRepository: AppNotificationRepository,
    private val pushDeliveryRepository: PushDeliveryRepository,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val calendarMutationFenceObserver: BlockingCalendarTargetPreviewObserver,
    private val shareMutationFenceObserver: BlockingShareTargetPreviewObserver,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val signupConsents = SignupConsentCommand(
        termsVersion = "2026.07.16",
        privacyCollectionVersion = "2026.07.16",
        termsAgreed = true,
        privacyCollectionAgreed = true,
    )

    @Test
    fun `curation g1 request resumed after logout g2 writes no completion state`() {
        val fixture = activeMember("curation", "CurationPass1!")

        val failure = invokeAfterLogout(fixture) {
            memberUseCase.completeCuration(
                memberId = fixture.memberId,
                presentedSessionGeneration = fixture.sessionGeneration,
            )
        }

        assertInvalidToken(failure)
        assertFalse(
            memberRepository.findById(fixture.memberId).orElseThrow().curationCompleted,
        )
    }

    @Test
    fun `profile update g1 request resumed after logout g2 preserves current profile`() {
        val fixture = activeMember("profile-update", "ProfilePass1!")
        transactions.executeWithoutResult {
            val profile = memberProfileRepository.findByMemberId(fixture.memberId)
                ?: error("default profile missing")
            profile.nickname = "before"
            profile.imgId = 11L
            profile.intro = "before-intro"
            memberProfileRepository.saveAndFlush(profile)
        }

        val failure = invokeAfterLogout(fixture) {
            memberUseCase.updateMyProfile(
                memberId = fixture.memberId,
                dto = MemberProfileDto(
                    memberId = fixture.memberId,
                    nickname = "after",
                    imgId = 99L,
                    intro = "after-intro",
                ),
                presentedSessionGeneration = fixture.sessionGeneration,
            )
        }

        assertInvalidToken(failure)
        val persisted = memberProfileRepository.findByMemberId(fixture.memberId)
            ?: error("profile disappeared")
        assertEquals("before", persisted.nickname)
        assertEquals(11L, persisted.imgId)
        assertEquals("before-intro", persisted.intro)
    }

    @Test
    fun `lazy profile GET g1 request resumed after logout g2 creates no profile row`() {
        val fixture = activeMember("profile-lazy", "ProfileLazy1!")
        transactions.executeWithoutResult {
            memberProfileRepository.deleteByMemberId(fixture.memberId)
            memberProfileRepository.flush()
        }
        assertNull(memberProfileRepository.findByMemberId(fixture.memberId))

        val failure = invokeAfterLogout(fixture) {
            memberUseCase.getMyProfile(
                memberId = fixture.memberId,
                presentedSessionGeneration = fixture.sessionGeneration,
            )
        }

        assertInvalidToken(failure)
        assertNull(memberProfileRepository.findByMemberId(fixture.memberId))
    }

    @Test
    fun `password g1 request resumed after logout g2 preserves the existing password`() {
        val oldPassword = "PasswordOld1!"
        val newPassword = "PasswordNew1!"
        val fixture = activeMember("password", oldPassword)

        val failure = invokeAfterLogout(fixture) {
            memberUseCase.changePassword(
                memberId = fixture.memberId,
                currentPassword = oldPassword,
                newPassword = newPassword,
                presentedSessionGeneration = fixture.sessionGeneration,
            )
        }

        assertInvalidToken(failure)
        val encoded = memberRepository.findById(fixture.memberId).orElseThrow().password
            ?: error("encoded password missing")
        assertTrue(passwordEncoder.matches(oldPassword, encoded))
        assertFalse(passwordEncoder.matches(newPassword, encoded))
    }

    @Test
    fun `active calendar owner must archive before withdrawal and archived membership leaves with account`() {
        val owner = activeMember("calendar-owner-policy", "CalendarOwner1!")
        val calendar = calendarService.createCalendar(
            ownerMemberId = owner.memberId,
            title = "active shared calendar",
            color = "#135724",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            presentedSessionGeneration = owner.sessionGeneration,
        )

        val rejected = runCatching {
            memberUseCase.withdraw(
                memberId = owner.memberId,
                presentedSessionGeneration = owner.sessionGeneration,
                passwordForCheck = owner.password,
            )
        }.exceptionOrNull()

        assertBusinessError(rejected, ErrorCode.INVALID_STATE)
        assertFalse(memberRepository.findById(owner.memberId).orElseThrow().deleted)
        assertEquals(
            ScheduleCalendarStatus.ACTIVE,
            calendarRepository.findById(calendar.id).orElseThrow().status,
        )
        assertEquals(
            ScheduleCalendarMemberStatus.ACTIVE,
            calendarMemberRepository
                .findByCalendarIdAndMemberId(calendar.id, owner.memberId)
                ?.status,
        )

        calendarService.archiveCalendar(
            ownerMemberId = owner.memberId,
            calendarId = calendar.id,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        memberUseCase.withdraw(
            memberId = owner.memberId,
            presentedSessionGeneration = owner.sessionGeneration,
            passwordForCheck = owner.password,
        )

        assertTrue(memberRepository.findById(owner.memberId).orElseThrow().deleted)
        val archivedMembership = calendarMemberRepository
            .findByCalendarIdAndMemberId(calendar.id, owner.memberId)
            ?: error("archived calendar membership disappeared")
        assertEquals(ScheduleCalendarMemberStatus.LEFT, archivedMembership.status)
        assertTrue(archivedMembership.deleted)
    }

    @Test
    fun `participant withdrawal leaves membership and removes member travel and push artifacts`() {
        val owner = activeMember("calendar-participant-owner", "CalendarOwner2!")
        val participant = activeMember("calendar-participant", "CalPart1!")
        val calendar = calendarService.createCalendar(
            ownerMemberId = owner.memberId,
            title = "participant calendar",
            color = "#246813",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        registerPushToken(participant, "participant")
        calendarService.addMember(
            ownerMemberId = owner.memberId,
            calendarId = calendar.id,
            targetEmail = null,
            targetAppId = participant.memberId,
            role = ScheduleCalendarRole.VIEWER,
            authenticatedActorMemberId = owner.memberId,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        val scheduleId = createParticipantTravelArtifacts(
            ownerMemberId = owner.memberId,
            participantMemberId = participant.memberId,
            calendarId = calendar.id,
        )
        assertTrue(appNotificationRepository.findAllByMemberIdOrderByIdDesc(participant.memberId).isNotEmpty())
        assertTrue(pushDeliveryRepository.findAll().any { it.memberId == participant.memberId })

        memberUseCase.withdraw(
            memberId = participant.memberId,
            presentedSessionGeneration = participant.sessionGeneration,
            passwordForCheck = participant.password,
        )

        assertTrue(memberRepository.findById(participant.memberId).orElseThrow().deleted)
        assertFalse(memberRepository.findById(owner.memberId).orElseThrow().deleted)
        assertEquals(
            ScheduleCalendarStatus.ACTIVE,
            calendarRepository.findById(calendar.id).orElseThrow().status,
        )
        val membership = calendarMemberRepository
            .findByCalendarIdAndMemberId(calendar.id, participant.memberId)
            ?: error("participant membership disappeared")
        assertEquals(ScheduleCalendarMemberStatus.LEFT, membership.status)
        assertTrue(membership.deleted)
        assertTrue(scheduleRepository.findById(scheduleId).isPresent)
        assertTrue(pushJobRepository.findAll().none { it.memberId == participant.memberId })
        assertTrue(travelPlanRepository.findAll().none { it.memberId == participant.memberId })
        assertTrue(routeSetupReminderRepository.findAll().none { it.memberId == participant.memberId })
        assertTrue(appNotificationRepository.findAllByMemberIdOrderByIdDesc(participant.memberId).isEmpty())
        assertTrue(pushDeliveryRepository.findAll().none { it.memberId == participant.memberId })
        assertTrue(tokenRepository.findAllByMemberId(participant.memberId).isEmpty())
    }

    @Test
    fun `empty shared category withdrawal removes recipient source manifest and inbox`() {
        val owner = activeMember("empty-category-owner", "EmptyCategory1!")
        val target = activeMember("empty-category-target", "EmptyCategory2!")
        val categoryId = transactions.execute {
            requireNotNull(
                categoryRepository.saveAndFlush(
                    ScheduleCategory(
                        memberId = owner.memberId,
                        title = "empty shared category",
                        color = "#778899",
                    ),
                ).id,
            )
        } ?: error("category fixture was not created")
        registerPushToken(target, "empty-category")

        shareService.shareCategory(
            ownerMemberId = owner.memberId,
            categoryId = categoryId,
            targetEmail = null,
            targetAppId = target.memberId,
            permission = ScheduleSharePermission.VIEWER,
            presentedSessionGeneration = owner.sessionGeneration,
        )

        val frozenSources = appNotificationRepository
            .findAllByMemberIdOrderByIdDesc(target.memberId)
            .filter { it.categoryId == categoryId && it.type == "CATEGORY_SHARE_RECEIVED" }
        assertEquals(1, frozenSources.size)
        val eventKey = frozenSources.single().logicalEventKey
        assertEquals(
            1,
            pushDeliveryRepository
                .findAllByMemberIdAndEventKeyOrderByIdAsc(target.memberId, eventKey)
                .size,
        )

        memberUseCase.withdraw(
            memberId = owner.memberId,
            presentedSessionGeneration = owner.sessionGeneration,
            passwordForCheck = owner.password,
        )

        assertTrue(memberRepository.findById(owner.memberId).orElseThrow().deleted)
        assertFalse(memberRepository.findById(target.memberId).orElseThrow().deleted)
        assertTrue(categoryRepository.findById(categoryId).isEmpty)
        assertTrue(categoryShareRepository.findAllByCategoryIdAndDeletedFalse(categoryId).isEmpty())
        assertTrue(
            appNotificationRepository.findAllByMemberIdOrderByIdDesc(target.memberId)
                .none { it.categoryId == categoryId },
        )
        assertTrue(
            pushDeliveryRepository
                .findAllByMemberIdAndEventKeyOrderByIdAsc(target.memberId, eventKey)
                .isEmpty(),
        )
    }

    @Test
    @Timeout(20)
    fun `calendar archive and owner withdrawal serialize without deadlock`() {
        val owner = activeMember("calendar-owner-race", "CalendarRace1!")
        val calendar = calendarService.createCalendar(
            ownerMemberId = owner.memberId,
            title = "archive race",
            color = "#334455",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_ONLY,
            presentedSessionGeneration = owner.sessionGeneration,
        )

        val (archiveFailure, withdrawalFailure) = runConcurrently(
            first = {
                calendarService.archiveCalendar(
                    ownerMemberId = owner.memberId,
                    calendarId = calendar.id,
                    presentedSessionGeneration = owner.sessionGeneration,
                )
            },
            second = {
                memberUseCase.withdraw(
                    memberId = owner.memberId,
                    presentedSessionGeneration = owner.sessionGeneration,
                    passwordForCheck = owner.password,
                )
            },
        )

        assertNull(archiveFailure, archiveFailure?.stackTraceToString())
        if (withdrawalFailure != null) {
            assertBusinessError(withdrawalFailure, ErrorCode.INVALID_STATE)
        }
        assertEquals(
            ScheduleCalendarStatus.ARCHIVED,
            calendarRepository.findById(calendar.id).orElseThrow().status,
        )
        assertEquals(
            withdrawalFailure == null,
            memberRepository.findById(owner.memberId).orElseThrow().deleted,
        )
    }

    @Test
    @Timeout(20)
    fun `calendar ownership transfer and old owner withdrawal keep an active owner`() {
        val owner = activeMember("calendar-transfer-owner", "TransferRace1!")
        val successor = activeMember("calendar-transfer-successor", "TransferRace2!")
        val calendar = calendarService.createCalendar(
            ownerMemberId = owner.memberId,
            title = "ownership transfer race",
            color = "#556677",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        calendarService.addMember(
            ownerMemberId = owner.memberId,
            calendarId = calendar.id,
            targetEmail = null,
            targetAppId = successor.memberId,
            role = ScheduleCalendarRole.EDITOR,
            authenticatedActorMemberId = owner.memberId,
            presentedSessionGeneration = owner.sessionGeneration,
        )

        val (transferFailure, withdrawalFailure) = runConcurrently(
            first = {
                calendarService.transferOwnership(
                    ownerMemberId = owner.memberId,
                    calendarId = calendar.id,
                    targetMemberId = successor.memberId,
                    presentedSessionGeneration = owner.sessionGeneration,
                )
            },
            second = {
                memberUseCase.withdraw(
                    memberId = owner.memberId,
                    presentedSessionGeneration = owner.sessionGeneration,
                    passwordForCheck = owner.password,
                )
            },
        )

        assertNull(transferFailure, transferFailure?.stackTraceToString())
        if (withdrawalFailure != null) {
            assertBusinessError(withdrawalFailure, ErrorCode.INVALID_STATE)
        }
        val persistedCalendar = calendarRepository.findById(calendar.id).orElseThrow()
        assertEquals(successor.memberId, persistedCalendar.ownerMemberId)
        assertFalse(memberRepository.findById(successor.memberId).orElseThrow().deleted)
        val successorMembership = calendarMemberRepository
            .findByCalendarIdAndMemberId(calendar.id, successor.memberId)
            ?: error("successor membership disappeared")
        assertEquals(ScheduleCalendarMemberStatus.ACTIVE, successorMembership.status)
        assertEquals(ScheduleCalendarRole.OWNER, successorMembership.role)
        assertEquals(
            withdrawalFailure == null,
            memberRepository.findById(owner.memberId).orElseThrow().deleted,
        )
    }

    @Test
    @Timeout(20)
    fun `calendar add and target withdrawal leave no active membership or notification`() {
        val owner = activeMember("calendar-add-owner", "AddRace1!")
        val target = activeMember("calendar-add-target", "AddRace2!")
        val calendar = calendarService.createCalendar(
            ownerMemberId = owner.memberId,
            title = "member add race",
            color = "#667788",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            presentedSessionGeneration = owner.sessionGeneration,
        )

        val gate = calendarMutationFenceObserver.arm(calendar.id)
        val executor = Executors.newSingleThreadExecutor()
        val addFuture = executor.submit<Throwable?> {
            runCatching {
                calendarService.addMember(
                    ownerMemberId = owner.memberId,
                    calendarId = calendar.id,
                    targetEmail = null,
                    targetAppId = target.memberId,
                    role = ScheduleCalendarRole.VIEWER,
                    authenticatedActorMemberId = owner.memberId,
                    presentedSessionGeneration = owner.sessionGeneration,
                )
            }.exceptionOrNull()
        }
        val addFailure: Throwable?
        val withdrawalFailure: Throwable?
        try {
            assertTrue(gate.previewed.await(10, TimeUnit.SECONDS))
            withdrawalFailure = runCatching {
                memberUseCase.withdraw(
                    memberId = target.memberId,
                    presentedSessionGeneration = target.sessionGeneration,
                    passwordForCheck = target.password,
                )
            }.exceptionOrNull()
            gate.resume.countDown()
            addFailure = addFuture.get(10, TimeUnit.SECONDS)
        } finally {
            gate.resume.countDown()
            calendarMutationFenceObserver.reset()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }

        assertNull(withdrawalFailure, withdrawalFailure?.stackTraceToString())
        assertBusinessError(addFailure, ErrorCode.MEMBER_NOT_FOUND)
        assertTrue(memberRepository.findById(target.memberId).orElseThrow().deleted)
        calendarMemberRepository
            .findByCalendarIdAndMemberId(calendar.id, target.memberId)
            ?.let { membership ->
                assertTrue(membership.deleted)
                assertTrue(membership.status != ScheduleCalendarMemberStatus.ACTIVE)
            }
        assertTrue(appNotificationRepository.findAllByMemberIdOrderByIdDesc(target.memberId).isEmpty())
        assertTrue(pushDeliveryRepository.findAll().none { it.memberId == target.memberId })
    }

    @Test
    @Timeout(20)
    fun `schedule share target withdrawal after scalar preview creates no active grant or notification`() {
        val owner = activeMember("schedule-share-race-owner", "ShareRace1!")
        val target = activeMember("schedule-share-race-target", "ShareRace2!")
        val scheduleId = transactions.execute {
            requireNotNull(
                scheduleRepository.saveAndFlush(
                    Schedule(
                        memberId = owner.memberId,
                        title = "schedule share race",
                        startAt = Instant.parse("2099-08-04T01:00:00Z"),
                        endAt = Instant.parse("2099-08-04T02:00:00Z"),
                    ),
                ).id,
            )
        } ?: error("schedule fixture was not created")

        val gate = shareMutationFenceObserver.arm(
            resourceType = ScheduleShareResourceType.SCHEDULE,
            resourceId = scheduleId,
            targetMemberId = target.memberId,
        )
        val executor = Executors.newSingleThreadExecutor()
        val shareFuture = executor.submit<Throwable?> {
            runCatching {
                shareService.shareSchedule(
                    ownerMemberId = owner.memberId,
                    scheduleId = scheduleId,
                    targetEmail = null,
                    targetAppId = target.memberId,
                    permission = ScheduleSharePermission.VIEWER,
                    contentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
                    presentedSessionGeneration = owner.sessionGeneration,
                )
            }.exceptionOrNull()
        }
        val shareFailure: Throwable?
        val withdrawalFailure: Throwable?
        try {
            assertTrue(gate.previewed.await(10, TimeUnit.SECONDS))
            withdrawalFailure = runCatching {
                memberUseCase.withdraw(
                    memberId = target.memberId,
                    presentedSessionGeneration = target.sessionGeneration,
                    passwordForCheck = target.password,
                )
            }.exceptionOrNull()
            gate.resume.countDown()
            shareFailure = shareFuture.get(10, TimeUnit.SECONDS)
        } finally {
            gate.resume.countDown()
            shareMutationFenceObserver.reset()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }

        assertNull(withdrawalFailure, withdrawalFailure?.stackTraceToString())
        assertBusinessError(shareFailure, ErrorCode.MEMBER_NOT_FOUND)
        assertTrue(memberRepository.findById(target.memberId).orElseThrow().deleted)
        assertNull(scheduleShareRepository.findByScheduleIdAndTargetMemberId(scheduleId, target.memberId))
        assertTrue(appNotificationRepository.findAllByMemberIdOrderByIdDesc(target.memberId).isEmpty())
        assertTrue(pushDeliveryRepository.findAll().none { it.memberId == target.memberId })
    }

    @Test
    @Timeout(20)
    fun `category share target withdrawal after scalar preview creates no active grant or notification`() {
        val owner = activeMember("category-share-race-owner", "CategoryRace1!")
        val target = activeMember("category-share-race-target", "CategoryRace2!")
        val targetEmail = memberRepository.findById(target.memberId).orElseThrow().email
        val categoryId = transactions.execute {
            requireNotNull(
                categoryRepository.saveAndFlush(
                    ScheduleCategory(
                        memberId = owner.memberId,
                        title = "category share race",
                        color = "#778899",
                    ),
                ).id,
            )
        } ?: error("category fixture was not created")

        val gate = shareMutationFenceObserver.arm(
            resourceType = ScheduleShareResourceType.CATEGORY,
            resourceId = categoryId,
            targetMemberId = target.memberId,
        )
        val executor = Executors.newSingleThreadExecutor()
        val shareFuture = executor.submit<Throwable?> {
            runCatching {
                shareService.shareCategory(
                    ownerMemberId = owner.memberId,
                    categoryId = categoryId,
                    targetEmail = targetEmail,
                    targetAppId = null,
                    permission = ScheduleSharePermission.VIEWER,
                    presentedSessionGeneration = owner.sessionGeneration,
                )
            }.exceptionOrNull()
        }
        val shareFailure: Throwable?
        val withdrawalFailure: Throwable?
        try {
            assertTrue(gate.previewed.await(10, TimeUnit.SECONDS))
            withdrawalFailure = runCatching {
                memberUseCase.withdraw(
                    memberId = target.memberId,
                    presentedSessionGeneration = target.sessionGeneration,
                    passwordForCheck = target.password,
                )
            }.exceptionOrNull()
            gate.resume.countDown()
            shareFailure = shareFuture.get(10, TimeUnit.SECONDS)
        } finally {
            gate.resume.countDown()
            shareMutationFenceObserver.reset()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }

        assertNull(withdrawalFailure, withdrawalFailure?.stackTraceToString())
        assertBusinessError(shareFailure, ErrorCode.MEMBER_NOT_FOUND)
        assertTrue(memberRepository.findById(target.memberId).orElseThrow().deleted)
        assertNull(categoryShareRepository.findByCategoryIdAndTargetMemberId(categoryId, target.memberId))
        assertTrue(appNotificationRepository.findAllByMemberIdOrderByIdDesc(target.memberId).isEmpty())
        assertTrue(pushDeliveryRepository.findAll().none { it.memberId == target.memberId })
    }

    @Test
    @Timeout(20)
    fun `participant remove and withdrawal serialize to one inactive membership`() {
        val owner = activeMember("calendar-remove-owner", "CalendarRace2!")
        val participant = activeMember("calendar-remove-participant", "CalendarRace3!")
        val calendar = calendarService.createCalendar(
            ownerMemberId = owner.memberId,
            title = "participant remove race",
            color = "#445566",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            presentedSessionGeneration = owner.sessionGeneration,
        )
        calendarService.addMember(
            ownerMemberId = owner.memberId,
            calendarId = calendar.id,
            targetEmail = null,
            targetAppId = participant.memberId,
            role = ScheduleCalendarRole.EDITOR,
            authenticatedActorMemberId = owner.memberId,
            presentedSessionGeneration = owner.sessionGeneration,
        )

        val (removeFailure, withdrawalFailure) = runConcurrently(
            first = {
                calendarService.removeMember(
                    ownerMemberId = owner.memberId,
                    calendarId = calendar.id,
                    targetMemberId = participant.memberId,
                    presentedSessionGeneration = owner.sessionGeneration,
                )
            },
            second = {
                memberUseCase.withdraw(
                    memberId = participant.memberId,
                    presentedSessionGeneration = participant.sessionGeneration,
                    passwordForCheck = participant.password,
                )
            },
        )

        assertNull(withdrawalFailure, withdrawalFailure?.stackTraceToString())
        if (removeFailure != null) {
            assertTrue(removeFailure is BusinessException, removeFailure.stackTraceToString())
            assertTrue(
                (removeFailure as BusinessException).errorCode == ErrorCode.MEMBER_NOT_FOUND ||
                    removeFailure.errorCode == ErrorCode.SCHEDULE_CALENDAR_MEMBER_NOT_FOUND,
            )
        }
        assertTrue(memberRepository.findById(participant.memberId).orElseThrow().deleted)
        val membership = calendarMemberRepository
            .findByCalendarIdAndMemberId(calendar.id, participant.memberId)
            ?: error("participant membership disappeared")
        assertTrue(membership.deleted)
        assertTrue(
            membership.status == ScheduleCalendarMemberStatus.LEFT ||
                membership.status == ScheduleCalendarMemberStatus.REMOVED,
        )
    }

    private fun activeMember(label: String, password: String): ActiveMemberFixture {
        val email = "$label-${UUID.randomUUID()}@example.com"
        val signed = memberUseCase.signUp(
            MemberDto(
                email = email,
                password = password,
                name = label,
                loginType = LoginType.COMMON,
            ),
            signupConsents,
        )
        val login = memberUseCase.login(
            MemberDto(
                email = email,
                password = password,
                loginType = LoginType.COMMON,
            ),
        )
        return ActiveMemberFixture(
            memberId = requireNotNull(signed.id),
            sessionGeneration = jwtTokenProvider.getSessionGeneration(
                requireNotNull(login.accessToken),
            ),
            refreshToken = requireNotNull(login.refreshToken),
            password = password,
        )
    }

    private fun registerPushToken(
        fixture: ActiveMemberFixture,
        label: String,
    ) {
        val nonce = UUID.randomUUID().toString()
        notificationTokenService.registerToken(
            memberId = fixture.memberId,
            deviceId = "$label-device-$nonce",
            platform = PushPlatform.ANDROID,
            token = "$label-token-$nonce",
            accessTokenIssuedAt = Instant.now(),
            accessTokenSessionGeneration = fixture.sessionGeneration,
        )
    }

    private fun createParticipantTravelArtifacts(
        ownerMemberId: Long,
        participantMemberId: Long,
        calendarId: Long,
    ): Long {
        val startAt = Instant.parse("2099-07-25T01:00:00Z")
        return transactions.execute {
            val scheduleId = requireNotNull(
                scheduleRepository.saveAndFlush(
                    Schedule(
                        memberId = ownerMemberId,
                        calendarId = calendarId,
                        title = "participant travel",
                        startAt = startAt,
                        endAt = startAt.plusSeconds(3_600),
                    ),
                ).id,
            )
            travelPlanRepository.saveAndFlush(
                ScheduleTravelPlan(
                    scheduleId = scheduleId,
                    memberId = participantMemberId,
                    travelMinutes = 30,
                    notificationEnabled = true,
                    scheduleFingerprint = "f".repeat(64),
                ),
            )
            pushJobRepository.saveAndFlush(
                SchedulePushJob.create(
                    memberId = participantMemberId,
                    scheduleId = scheduleId,
                    scheduleAt = startAt,
                    departureAt = startAt.minusSeconds(1_800),
                    monitorStartAt = startAt.minusSeconds(3_600),
                    intervalMinutes = 20,
                ),
            )
            routeSetupReminderRepository.saveAndFlush(
                ScheduleRouteSetupReminder(
                    scheduleId = scheduleId,
                    memberId = participantMemberId,
                    scheduleFingerprint = "f".repeat(64),
                    nextAttemptAt = Instant.parse("2026-07-25T00:00:00Z"),
                ),
            )
            scheduleId
        } ?: error("participant travel fixture was not created")
    }

    private fun runConcurrently(
        first: () -> Unit,
        second: () -> Unit,
    ): Pair<Throwable?, Throwable?> {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val firstFuture = executor.submit<Throwable?> {
            ready.countDown()
            check(start.await(10, TimeUnit.SECONDS))
            runCatching(first).exceptionOrNull()
        }
        val secondFuture = executor.submit<Throwable?> {
            ready.countDown()
            check(start.await(10, TimeUnit.SECONDS))
            runCatching(second).exceptionOrNull()
        }
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return firstFuture.get(10, TimeUnit.SECONDS) to
                secondFuture.get(10, TimeUnit.SECONDS)
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun invokeAfterLogout(
        fixture: ActiveMemberFixture,
        mutation: () -> Unit,
    ): Throwable? {
        val filterAuthenticated = CountDownLatch(1)
        val resumeRequest = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit {
            // The request has already captured a signed g1 principal at the security filter.
            filterAuthenticated.countDown()
            check(resumeRequest.await(10, TimeUnit.SECONDS))
            failure.set(runCatching(mutation).exceptionOrNull())
        }
        try {
            assertTrue(filterAuthenticated.await(10, TimeUnit.SECONDS))
            memberUseCase.logout(fixture.refreshToken)
            assertEquals(
                fixture.sessionGeneration + 1,
                memberRepository.findById(fixture.memberId).orElseThrow().sessionGeneration,
            )
            resumeRequest.countDown()
            future.get(10, TimeUnit.SECONDS)
            return failure.get()
        } finally {
            resumeRequest.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun assertInvalidToken(failure: Throwable?) {
        assertBusinessError(failure, ErrorCode.INVALID_TOKEN)
    }

    private fun assertBusinessError(failure: Throwable?, errorCode: ErrorCode) {
        assertTrue(failure is BusinessException, failure?.stackTraceToString())
        assertEquals(errorCode, (failure as BusinessException).errorCode)
    }

    private data class ActiveMemberFixture(
        val memberId: Long,
        val sessionGeneration: Long,
        val refreshToken: String,
        val password: String,
    )
}

@TestConfiguration
class MemberAuthenticatedMutationFenceTestConfig {
    @Bean
    fun blockingCalendarTargetPreviewObserver() = BlockingCalendarTargetPreviewObserver()

    @Bean
    fun blockingShareTargetPreviewObserver() = BlockingShareTargetPreviewObserver()
}

class BlockingCalendarTargetPreviewObserver : ScheduleCalendarMutationFenceObserver {
    private val armed = AtomicReference<CalendarTargetPreviewGate?>()

    fun arm(calendarId: Long): CalendarTargetPreviewGate =
        CalendarTargetPreviewGate(calendarId).also {
            check(armed.compareAndSet(null, it))
        }

    fun reset() {
        armed.getAndSet(null)?.resume?.countDown()
    }

    override fun afterMembershipPreview(calendarId: Long) {
        val gate = armed.get() ?: return
        if (gate.calendarId != calendarId || !armed.compareAndSet(gate, null)) return
        gate.previewed.countDown()
        check(gate.resume.await(10, TimeUnit.SECONDS))
    }
}

class CalendarTargetPreviewGate(
    val calendarId: Long,
    val previewed: CountDownLatch = CountDownLatch(1),
    val resume: CountDownLatch = CountDownLatch(1),
)

class BlockingShareTargetPreviewObserver : ScheduleShareMutationFenceObserver {
    private val armed = AtomicReference<ShareTargetPreviewGate?>()

    fun arm(
        resourceType: ScheduleShareResourceType,
        resourceId: Long,
        targetMemberId: Long,
    ): ShareTargetPreviewGate =
        ShareTargetPreviewGate(resourceType, resourceId, targetMemberId).also {
            check(armed.compareAndSet(null, it))
        }

    fun reset() {
        armed.getAndSet(null)?.resume?.countDown()
    }

    override fun afterTargetPreview(
        resourceType: ScheduleShareResourceType,
        resourceId: Long,
        targetMemberId: Long,
    ) {
        val gate = armed.get() ?: return
        if (
            gate.resourceType != resourceType ||
            gate.resourceId != resourceId ||
            gate.targetMemberId != targetMemberId ||
            !armed.compareAndSet(gate, null)
        ) {
            return
        }
        gate.previewed.countDown()
        check(gate.resume.await(10, TimeUnit.SECONDS))
    }
}

class ShareTargetPreviewGate(
    val resourceType: ScheduleShareResourceType,
    val resourceId: Long,
    val targetMemberId: Long,
    val previewed: CountDownLatch = CountDownLatch(1),
    val resume: CountDownLatch = CountDownLatch(1),
)
