package com.noLate.notification.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.liveactivity.application.LiveActivityDispatchCoordinator
import com.noLate.notification.liveactivity.application.LiveActivityTokenWriter
import com.noLate.notification.liveactivity.application.NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE
import com.noLate.notification.liveactivity.application.NoLateDepartureContentStateWire
import com.noLate.notification.liveactivity.application.ScheduleLiveActivityEtaProjection
import com.noLate.notification.liveactivity.application.ScheduleLiveActivityService
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryOperation
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryStatus
import com.noLate.notification.liveactivity.domain.LiveActivityStartToken
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivity
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivityState
import com.noLate.notification.liveactivity.infrastructure.LiveActivityDeliveryRepository
import com.noLate.notification.liveactivity.infrastructure.LiveActivityStartTokenRepository
import com.noLate.notification.liveactivity.infrastructure.ScheduleLiveActivityRepository
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@Import(
    NotificationTokenService::class,
    NotificationTokenRetirementService::class,
    NotificationTokenWriter::class,
    ScheduleLiveActivityService::class,
    LiveActivityDispatchCoordinator::class,
    LiveActivityTokenWriter::class,
    NotificationTokenLiveActivityHandoffTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:notification-token-live-activity-handoff;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "notification.push-token.dispatch-lease-wait-millis=5000",
        "notification.push-token.dispatch-lease-poll-millis=5",
        "notification.push-token.provider-max-call-seconds=1",
    ]
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationTokenLiveActivityHandoffIntegrationTest @Autowired constructor(
    private val tokenService: NotificationTokenService,
    private val retirementService: NotificationTokenRetirementService,
    private val liveActivityService: ScheduleLiveActivityService,
    private val dispatchCoordinator: LiveActivityDispatchCoordinator,
    private val liveActivityTokenWriter: LiveActivityTokenWriter,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val startTokenRepository: LiveActivityStartTokenRepository,
    private val activityRepository: ScheduleLiveActivityRepository,
    private val deliveryRepository: LiveActivityDeliveryRepository,
    private val memberRepository: MemberRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val objectMapper: ObjectMapper,
) {
    private val now = Instant.parse("2026-08-06T00:10:00Z")

    @BeforeEach
    fun clean() {
        pushJobRepository.deleteAll()
        deliveryRepository.deleteAll()
        activityRepository.deleteAll()
        startTokenRepository.deleteAll()
        tokenRepository.deleteAll()
        memberRepository.deleteAll()
    }

    @Test
    fun `UNREGISTERED END retirement followed by same-device registration replans on new epoch`() {
        val memberId = requireNotNull(
            memberRepository.saveAndFlush(
                Member(
                    name = "member",
                    password = "Password1!",
                    email = "live-activity-handoff@example.com",
                    loginType = LoginType.COMMON,
                )
            ).id
        )
        val deviceId = "ios-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "old-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val oldFcm = tokenRepository.findAllByMemberId(memberId).single()
        val oldFcmId = requireNotNull(oldFcm.id)
        val oldFcmFingerprint = oldFcm.tokenFingerprint
        val oldOwnershipVersion = oldFcm.ownershipVersion
        val content = NoLateDepartureContentStateWire(
            revision = 1,
            travelMinutes = 40,
            firstWaitMinutes = 20,
            predictedArrivalEpochSeconds = now.plusSeconds(3_600).epochSecond,
            recommendedDepartureEpochSeconds = now.epochSecond,
            updatedAtEpochSeconds = now.epochSecond,
            actionExpiresAtEpochSeconds = now.plusSeconds(3_600).epochSecond,
            status = "tracking",
            routeSegments = emptyList(),
        )
        val activity = ScheduleLiveActivity(
            memberId = memberId,
            scheduleId = 41,
            pushDeviceTokenId = oldFcmId,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 1,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = objectMapper.writeValueAsString(content),
            expiresAt = now.plusSeconds(3_600),
        )
        activity.bindUpdateToken("activity-41", "activity-update-token")
        activityRepository.saveAndFlush(activity)

        liveActivityService.endForMemberSchedule(memberId, 41, "EXPIRED")
        val oldEnd = deliveryRepository.findAll().single()
        assertThat(oldEnd.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(oldEnd.status).isEqualTo(LiveActivityDeliveryStatus.PENDING)

        val lease = requireNotNull(dispatchCoordinator.claimNext(now))
        dispatchCoordinator.completeInvalidFcmRoute(lease, now)
        assertThat(
            tokenService.removeTokenByOwnership(
                memberId = memberId,
                tokenId = oldFcmId,
                tokenFingerprint = oldFcmFingerprint,
                ownershipVersion = oldOwnershipVersion,
            )
        ).isFalse()
        val retired = tokenRepository.findById(oldFcmId).orElseThrow()
        assertThat(retired.retirementRequested).isTrue()
        assertThat(retired.dispatchLeaseId).isNull()
        assertThat(activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(deliveryRepository.findById(requireNotNull(oldEnd.id)).orElseThrow().status)
            .isEqualTo(LiveActivityDeliveryStatus.INVALID)

        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "replacement-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )

        val replacement = tokenRepository.findAllByMemberId(memberId).single()
        assertThat(replacement.id).isEqualTo(oldFcmId)
        assertThat(replacement.token).isEqualTo("replacement-fcm")
        assertThat(replacement.retirementRequested).isFalse()
        assertThat(replacement.ownershipVersion).isEqualTo(oldOwnershipVersion + 1)
        val rebound = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(rebound.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(rebound.pushDeviceTokenId).isEqualTo(oldFcmId)
        val deliveries = deliveryRepository.findAll()
        assertThat(deliveries).hasSize(2)
        assertThat(deliveries.single { it.id == oldEnd.id }.status)
            .isEqualTo(LiveActivityDeliveryStatus.INVALID)
        val replacementEnd = deliveries.single { it.id != oldEnd.id }
        assertThat(replacementEnd.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(replacementEnd.status).isEqualTo(LiveActivityDeliveryStatus.PENDING)
        assertThat(replacementEnd.fcmTokenFingerprint)
            .isEqualTo(OpaquePushIdentifier.fingerprint("replacement-fcm"))
        assertThat(replacementEnd.fcmOwnershipVersion)
            .isEqualTo(oldOwnershipVersion + 1)

        // The new epoch remains a normal active route and is no longer pinned by the old END.
        retirementService.reapExpiredRetirements()
        assertThat(tokenRepository.findById(oldFcmId)).isPresent
    }

    @Test
    fun `crash after INVALID completion still leaves a retired route that registration can handoff`() {
        val memberId = createMember("invalid-completion-crash@example.com")
        val deviceId = "invalid-completion-crash-ios-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "crash-old-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val oldFcm = tokenRepository.findAllByMemberId(memberId).single()
        val oldFcmId = requireNotNull(oldFcm.id)
        val oldOwnershipVersion = oldFcm.ownershipVersion
        val activity = activeActivity(memberId, oldFcmId, deviceFingerprint)
        activityRepository.saveAndFlush(activity)
        liveActivityService.endForMemberSchedule(memberId, 41, "EXPIRED")
        val oldEnd = deliveryRepository.findAll().single()

        val lease = requireNotNull(dispatchCoordinator.claimNext(now))
        dispatchCoordinator.completeInvalidFcmRoute(lease, now)
        // Deliberately do not call removeTokenByOwnership: this is the worker crash point between
        // its two REQUIRES_NEW calls. The first commit alone must now be recoverable.
        val afterInvalidCommit = tokenRepository.findById(oldFcmId).orElseThrow()
        assertThat(afterInvalidCommit.retirementRequested).isTrue()
        assertThat(afterInvalidCommit.dispatchLeaseId).isNull()
        assertThat(afterInvalidCommit.dispatchLeaseUntil).isNull()
        assertThat(deliveryRepository.findById(requireNotNull(oldEnd.id)).orElseThrow().status)
            .isEqualTo(LiveActivityDeliveryStatus.INVALID)
        assertThat(activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.END_PENDING)

        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "crash-replacement-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )

        val replacement = tokenRepository.findAllByMemberId(memberId).single()
        assertThat(replacement.id).isEqualTo(oldFcmId)
        assertThat(replacement.retirementRequested).isFalse()
        assertThat(replacement.ownershipVersion).isEqualTo(oldOwnershipVersion + 1)
        val rebound = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(rebound.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(rebound.pushDeviceTokenId).isEqualTo(oldFcmId)
        val deliveries = deliveryRepository.findAll()
        assertThat(deliveries).hasSize(2)
        assertThat(deliveries.single { it.id == oldEnd.id }.status)
            .isEqualTo(LiveActivityDeliveryStatus.INVALID)
        val replacementEnd = deliveries.single { it.id != oldEnd.id }
        assertThat(replacementEnd.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(replacementEnd.status).isEqualTo(LiveActivityDeliveryStatus.PENDING)
        assertThat(replacementEnd.fcmTokenFingerprint).isEqualTo(replacement.tokenFingerprint)
        assertThat(replacementEnd.fcmOwnershipVersion).isEqualTo(replacement.ownershipVersion)
    }

    @Test
    fun `account switch keeps new FCM on old owner until invalid END succeeds`() {
        val oldMemberId = createMember("foreign-end-old-owner@example.com")
        val newMemberId = createMember("foreign-end-new-owner@example.com")
        val deviceId = "foreign-end-account-switch-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val replacementToken = "foreign-end-replacement-fcm"
        val replacementFingerprint = OpaquePushIdentifier.fingerprint(replacementToken)
        tokenService.registerToken(
            memberId = oldMemberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "foreign-end-invalid-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val oldFcm = tokenRepository.findAllByMemberId(oldMemberId).single()
        val oldFcmId = requireNotNull(oldFcm.id)
        val oldOwnershipVersion = oldFcm.ownershipVersion
        val activity = activeActivity(oldMemberId, oldFcmId, deviceFingerprint)
        activityRepository.saveAndFlush(activity)
        liveActivityService.endForMemberSchedule(oldMemberId, 41, "ACCOUNT_SWITCH")
        val invalidEnd = deliveryRepository.findAll().single()
        val invalidLease = requireNotNull(dispatchCoordinator.claimNext(now))
        dispatchCoordinator.completeInvalidFcmRoute(invalidLease, now)
        // Crash before removeTokenByOwnership: exact invalid completion alone leaves the old route
        // retired without a usable END reservation.
        val strandedRoute = tokenRepository.findById(oldFcmId).orElseThrow()
        assertThat(strandedRoute.memberId).isEqualTo(oldMemberId)
        assertThat(strandedRoute.retirementRequested).isTrue()
        assertThat(strandedRoute.dispatchLeaseId).isNull()
        assertThat(deliveryRepository.findById(requireNotNull(invalidEnd.id)).orElseThrow().status)
            .isEqualTo(LiveActivityDeliveryStatus.INVALID)

        val executor = Executors.newSingleThreadExecutor()
        val registration = executor.submit {
            tokenService.registerToken(
                memberId = newMemberId,
                deviceId = deviceId,
                platform = PushPlatform.IOS,
                token = replacementToken,
                accessTokenIssuedAt = now,
                accessTokenSessionGeneration = 0,
            )
        }
        try {
            awaitCondition("old-owner END-only replacement epoch") {
                deliveryRepository.findAll().any {
                    it.memberId == oldMemberId &&
                        it.operation == LiveActivityDeliveryOperation.END &&
                        it.status == LiveActivityDeliveryStatus.PENDING &&
                        it.fcmTokenFingerprint == replacementFingerprint
                }
            }

            val endOnlyRoute = tokenRepository.findById(oldFcmId).orElseThrow()
            assertThat(endOnlyRoute.memberId).isEqualTo(oldMemberId)
            assertThat(endOnlyRoute.tokenFingerprint).isEqualTo(replacementFingerprint)
            assertThat(endOnlyRoute.ownershipVersion).isEqualTo(oldOwnershipVersion + 1)
            assertThat(endOnlyRoute.retirementRequested).isTrue()
            assertThat(endOnlyRoute.dispatchLeaseId).startsWith("la-end:")
            assertThat(tokenRepository.findAllByMemberId(newMemberId)).isEmpty()
            assertThat(registration.isDone).isFalse()
            val replacementEnd = deliveryRepository.findAll().single {
                it.status == LiveActivityDeliveryStatus.PENDING
            }
            assertThat(replacementEnd.memberId).isEqualTo(oldMemberId)
            assertThat(replacementEnd.pushDeviceTokenId).isEqualTo(oldFcmId)
            assertThat(replacementEnd.fcmOwnershipVersion)
                .isEqualTo(oldOwnershipVersion + 1)

            val endLease = requireNotNull(dispatchCoordinator.claimNext(now))
            assertThat(endLease.deliveryId).isEqualTo(replacementEnd.id)
            assertThat(endLease.memberId).isEqualTo(oldMemberId)
            dispatchCoordinator.completeSuccess(endLease, "end-success", now)

            registration.get(5, TimeUnit.SECONDS)
            val newOwnerRoute = tokenRepository.findAllByMemberId(newMemberId).single()
            assertThat(newOwnerRoute.tokenFingerprint).isEqualTo(replacementFingerprint)
            assertThat(tokenRepository.findAllByMemberId(oldMemberId)).isEmpty()
            assertThat(
                activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state
            ).isEqualTo(ScheduleLiveActivityState.ENDED)
            val terminalReplacementEnd =
                deliveryRepository.findById(requireNotNull(replacementEnd.id)).orElseThrow()
            assertThat(terminalReplacementEnd.status)
                .isEqualTo(LiveActivityDeliveryStatus.SUCCESS)
            assertThat(terminalReplacementEnd.memberId).isEqualTo(oldMemberId)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `account switch recreates a deleted foreign route only for END before new ownership`() {
        val oldMemberId = createMember("foreign-orphan-old-owner@example.com")
        val newMemberId = createMember("foreign-orphan-new-owner@example.com")
        val deviceId = "foreign-orphan-account-switch-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val replacementToken = "foreign-orphan-replacement-fcm"
        val replacementFingerprint = OpaquePushIdentifier.fingerprint(replacementToken)
        tokenService.registerToken(
            memberId = oldMemberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "foreign-orphan-invalid-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val oldFcm = tokenRepository.findAllByMemberId(oldMemberId).single()
        val oldFcmId = requireNotNull(oldFcm.id)
        val activity = activeActivity(oldMemberId, oldFcmId, deviceFingerprint)
        activityRepository.saveAndFlush(activity)
        liveActivityService.enqueueCurrentUpdate(requireNotNull(activity.id))
        val invalidUpdate = deliveryRepository.findAll().single()
        assertThat(invalidUpdate.operation).isEqualTo(LiveActivityDeliveryOperation.UPDATE)
        val updateLease = requireNotNull(dispatchCoordinator.claimNext(now))
        dispatchCoordinator.completeInvalidFcmRoute(updateLease, now)
        assertThat(
            tokenService.removeTokenByOwnership(
                memberId = oldMemberId,
                tokenId = oldFcmId,
                tokenFingerprint = oldFcm.tokenFingerprint,
                ownershipVersion = oldFcm.ownershipVersion,
            )
        ).isTrue()
        assertThat(tokenRepository.findById(oldFcmId)).isEmpty

        liveActivityService.endForMemberSchedule(oldMemberId, 41, "ACCOUNT_SWITCH")
        val orphan = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(orphan.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(orphan.pushDeviceTokenId).isEqualTo(oldFcmId)
        assertThat(deliveryRepository.findAll().none {
            it.operation == LiveActivityDeliveryOperation.END
        }).isTrue()

        val executor = Executors.newSingleThreadExecutor()
        val registration = executor.submit {
            tokenService.registerToken(
                memberId = newMemberId,
                deviceId = deviceId,
                platform = PushPlatform.IOS,
                token = replacementToken,
                accessTokenIssuedAt = now,
                accessTokenSessionGeneration = 0,
            )
        }
        try {
            awaitCondition("foreign orphan END-only route") {
                deliveryRepository.findAll().any {
                    it.memberId == oldMemberId &&
                        it.operation == LiveActivityDeliveryOperation.END &&
                        it.status == LiveActivityDeliveryStatus.PENDING &&
                        it.fcmTokenFingerprint == replacementFingerprint
                }
            }
            val endOnlyRoute = tokenRepository.findAllByMemberId(oldMemberId).single()
            assertThat(endOnlyRoute.tokenFingerprint).isEqualTo(replacementFingerprint)
            assertThat(endOnlyRoute.retirementRequested).isTrue()
            assertThat(endOnlyRoute.dispatchLeaseId).startsWith("la-end:")
            assertThat(tokenRepository.findAllByMemberId(newMemberId)).isEmpty()
            assertThat(registration.isDone).isFalse()
            val replacementEnd = deliveryRepository.findAll().single {
                it.operation == LiveActivityDeliveryOperation.END
            }
            assertThat(replacementEnd.memberId).isEqualTo(oldMemberId)

            val endLease = requireNotNull(dispatchCoordinator.claimNext(now))
            assertThat(endLease.deliveryId).isEqualTo(replacementEnd.id)
            dispatchCoordinator.completeSuccess(endLease, "foreign-orphan-end-success", now)
            registration.get(5, TimeUnit.SECONDS)

            val newOwnerRoute = tokenRepository.findAllByMemberId(newMemberId).single()
            assertThat(newOwnerRoute.tokenFingerprint).isEqualTo(replacementFingerprint)
            assertThat(tokenRepository.findAllByMemberId(oldMemberId)).isEmpty()
            assertThat(
                activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state
            ).isEqualTo(ScheduleLiveActivityState.ENDED)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `account switch ends foreign ACTIVE orphan when replacement registers before cancel`() {
        val oldMemberId = createMember("foreign-active-orphan-old-owner@example.com")
        val newMemberId = createMember("foreign-active-orphan-new-owner@example.com")
        val deviceId = "foreign-active-orphan-account-switch-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val replacementToken = "foreign-active-orphan-replacement-fcm"
        val replacementFingerprint = OpaquePushIdentifier.fingerprint(replacementToken)
        tokenService.registerToken(
            memberId = oldMemberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "foreign-active-orphan-invalid-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val oldFcm = tokenRepository.findAllByMemberId(oldMemberId).single()
        val oldFcmId = requireNotNull(oldFcm.id)
        val activity = activeActivity(oldMemberId, oldFcmId, deviceFingerprint)
        activityRepository.saveAndFlush(activity)
        liveActivityService.enqueueCurrentUpdate(requireNotNull(activity.id))
        val updateLease = requireNotNull(dispatchCoordinator.claimNext(now))
        dispatchCoordinator.completeInvalidFcmRoute(updateLease, now)
        assertThat(
            tokenService.removeTokenByOwnership(
                memberId = oldMemberId,
                tokenId = oldFcmId,
                tokenFingerprint = oldFcm.tokenFingerprint,
                ownershipVersion = oldFcm.ownershipVersion,
            )
        ).isTrue()
        assertThat(tokenRepository.findById(oldFcmId)).isEmpty
        assertThat(activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.ACTIVE)

        val executor = Executors.newSingleThreadExecutor()
        val registration = executor.submit {
            tokenService.registerToken(
                memberId = newMemberId,
                deviceId = deviceId,
                platform = PushPlatform.IOS,
                token = replacementToken,
                accessTokenIssuedAt = now,
                accessTokenSessionGeneration = 0,
            )
        }
        try {
            awaitCondition("foreign ACTIVE orphan END-only route") {
                deliveryRepository.findAll().any {
                    it.memberId == oldMemberId &&
                        it.operation == LiveActivityDeliveryOperation.END &&
                        it.status == LiveActivityDeliveryStatus.PENDING &&
                        it.fcmTokenFingerprint == replacementFingerprint
                }
            }
            val ending = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
            assertThat(ending.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
            assertThat(ending.endReason).isEqualTo("FOREIGN_FCM_ORPHAN_HANDOFF")
            val endOnlyRoute = tokenRepository.findAllByMemberId(oldMemberId).single()
            assertThat(endOnlyRoute.retirementRequested).isTrue()
            assertThat(endOnlyRoute.dispatchLeaseId).startsWith("la-end:")
            assertThat(tokenRepository.findAllByMemberId(newMemberId)).isEmpty()
            assertThat(registration.isDone).isFalse()
            val replacementEnd = deliveryRepository.findAll().single {
                it.operation == LiveActivityDeliveryOperation.END
            }
            assertThat(replacementEnd.memberId).isEqualTo(oldMemberId)

            val endLease = requireNotNull(dispatchCoordinator.claimNext(now))
            assertThat(endLease.deliveryId).isEqualTo(replacementEnd.id)
            dispatchCoordinator.completeSuccess(endLease, "foreign-active-end-success", now)
            registration.get(5, TimeUnit.SECONDS)

            assertThat(tokenRepository.findAllByMemberId(oldMemberId)).isEmpty()
            assertThat(tokenRepository.findAllByMemberId(newMemberId).single().tokenFingerprint)
                .isEqualTo(replacementFingerprint)
            assertThat(
                activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state
            ).isEqualTo(ScheduleLiveActivityState.ENDED)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `registration rebinds and replans an ACTIVE END tombstone whose old FCM row is gone`() {
        val memberId = createMember("orphan-active-handoff@example.com")
        val deviceId = "orphan-active-ios-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "old-active-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val oldFcmId = requireNotNull(tokenRepository.findAllByMemberId(memberId).single().id)
        val activity = activeActivity(memberId, oldFcmId, deviceFingerprint)
        activityRepository.saveAndFlush(activity)
        tokenRepository.deleteById(oldFcmId)
        tokenRepository.flush()

        liveActivityService.endForMemberSchedule(memberId, 41, "EXPIRED")
        assertThat(activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(deliveryRepository.findAll()).isEmpty()

        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "replacement-active-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )

        val replacement = tokenRepository.findAllByMemberId(memberId).single()
        val rebound = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(rebound.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(rebound.pushDeviceTokenId).isEqualTo(replacement.id)
        val replacementEnd = deliveryRepository.findAll().single()
        assertThat(replacementEnd.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(replacementEnd.pushDeviceTokenId).isEqualTo(replacement.id)
        assertThat(replacementEnd.fcmOwnershipVersion).isEqualTo(replacement.ownershipVersion)
        assertThat(replacementEnd.fcmTokenFingerprint).isEqualTo(replacement.tokenFingerprint)
    }

    @Test
    fun `cancellation rebinds ACTIVE activity when replacement FCM registered before END`() {
        val memberId = createMember("replacement-before-cancel@example.com")
        val deviceId = "replacement-before-cancel-ios-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "fcm-a",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val fcmAId = requireNotNull(tokenRepository.findAllByMemberId(memberId).single().id)
        val activity = activeActivity(memberId, fcmAId, deviceFingerprint)
        activityRepository.saveAndFlush(activity)

        // Exact production ordering: A is provider-confirmed invalid/deleted while the Activity
        // remains ACTIVE, then B registers before any ETA projection, token callback, or cancel.
        tokenRepository.deleteById(fcmAId)
        tokenRepository.flush()
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "fcm-b",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val fcmB = tokenRepository.findAllByMemberId(memberId).single()
        val beforeCancel = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(beforeCancel.state).isEqualTo(ScheduleLiveActivityState.ACTIVE)
        assertThat(beforeCancel.pushDeviceTokenId).isEqualTo(fcmB.id)
        assertThat(beforeCancel.boundFcmOwnershipVersion).isEqualTo(fcmB.ownershipVersion)
        val replacementUpdate = deliveryRepository.findAll().single()
        assertThat(replacementUpdate.operation).isEqualTo(LiveActivityDeliveryOperation.UPDATE)
        assertThat(replacementUpdate.pushDeviceTokenId).isEqualTo(fcmB.id)

        liveActivityService.endForMemberSchedule(memberId, 41, "SCHEDULE_CANCELLED")

        val ended = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(ended.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(ended.pushDeviceTokenId).isEqualTo(fcmB.id)
        assertThat(
            deliveryRepository.findById(requireNotNull(replacementUpdate.id)).orElseThrow().status
        ).isEqualTo(LiveActivityDeliveryStatus.SUPERSEDED)
        val end = deliveryRepository.findAll().single {
            it.operation == LiveActivityDeliveryOperation.END
        }
        assertThat(end.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(end.status).isEqualTo(LiveActivityDeliveryStatus.PENDING)
        assertThat(end.pushDeviceTokenId).isEqualTo(fcmB.id)
        assertThat(end.fcmTokenFingerprint).isEqualTo(fcmB.tokenFingerprint)
        assertThat(end.fcmOwnershipVersion).isEqualTo(fcmB.ownershipVersion)
    }

    @Test
    fun `missing exact FCM persists current-session update credential only for recoverable states`() {
        val cases = listOf(
            ScheduleLiveActivityState.START_SENT,
            ScheduleLiveActivityState.ACTIVE,
            ScheduleLiveActivityState.TOKEN_PENDING,
        )

        cases.forEachIndexed { index, initialState ->
            val memberId = createMember(
                "missing-fcm-${initialState.name.lowercase()}@example.com",
            )
            val deviceId = "missing-fcm-${initialState.name.lowercase()}-$index"
            val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
            tokenService.registerToken(
                memberId = memberId,
                deviceId = deviceId,
                platform = PushPlatform.IOS,
                token = "missing-fcm-route-$index",
                accessTokenIssuedAt = now,
                accessTokenSessionGeneration = 0,
            )
            val fcmId = requireNotNull(tokenRepository.findAllByMemberId(memberId).single().id)
            val activity = when (initialState) {
                ScheduleLiveActivityState.START_SENT ->
                    unboundActivity(memberId, fcmId, deviceFingerprint).also {
                        it.markStartAccepted(it.contentRevision)
                    }

                ScheduleLiveActivityState.ACTIVE ->
                    activeActivity(memberId, fcmId, deviceFingerprint)

                ScheduleLiveActivityState.TOKEN_PENDING ->
                    activeActivity(memberId, fcmId, deviceFingerprint).also {
                        assertThat(
                            it.invalidateUpdateCredential(
                                requireNotNull(it.updateTokenFingerprint),
                                it.updateTokenGeneration,
                            )
                        ).isTrue()
                    }

                else -> error("unsupported test state: $initialState")
            }
            val activityRowId = requireNotNull(activityRepository.saveAndFlush(activity).id)

            tokenRepository.deleteById(fcmId)
            tokenRepository.flush()
            assertThat(tokenRepository.findById(fcmId)).isEmpty()

            val rotatedToken = "credential-only-${initialState.name.lowercase()}-$index"
            val rotatedFingerprint = OpaquePushIdentifier.fingerprint(rotatedToken)
            liveActivityTokenWriter.registerUpdateToken(
                memberId = memberId,
                deviceFingerprint = deviceFingerprint,
                scheduleId = 41,
                activityId = "activity-41",
                updateToken = rotatedToken,
                updateTokenFingerprint = rotatedFingerprint,
                generation = 3,
                presentedSessionGeneration = 0,
            )

            val rebound = activityRepository.findById(activityRowId).orElseThrow()
            assertThat(rebound.state).isEqualTo(ScheduleLiveActivityState.ACTIVE)
            assertThat(rebound.pushDeviceTokenId).isEqualTo(fcmId)
            assertThat(rebound.activityId).isEqualTo("activity-41")
            assertThat(rebound.updateTokenFingerprint).isEqualTo(rotatedFingerprint)
            assertThat(deliveryRepository.findAll().none { it.liveActivityId == activityRowId })
                .isTrue()

            tokenService.registerToken(
                memberId = memberId,
                deviceId = deviceId,
                platform = PushPlatform.IOS,
                token = "credential-only-replacement-fcm-$index",
                accessTokenIssuedAt = now,
                accessTokenSessionGeneration = 0,
            )
            val replacement = tokenRepository.findAllByMemberId(memberId).single()
            val recovered = activityRepository.findById(activityRowId).orElseThrow()
            assertThat(recovered.pushDeviceTokenId).isEqualTo(replacement.id)
            assertThat(recovered.boundFcmOwnershipVersion)
                .isEqualTo(replacement.ownershipVersion)
            val update = deliveryRepository.findAll().filter {
                it.liveActivityId == activityRowId
            }.single()
            assertThat(update.operation).isEqualTo(LiveActivityDeliveryOperation.UPDATE)
            assertThat(update.pushDeviceTokenId).isEqualTo(replacement.id)
            assertThat(update.activityTokenFingerprint).isEqualTo(rotatedFingerprint)
        }
    }

    @Test
    fun `FCM-first orphan rebind waits for callback then emits one exact UPDATE`() {
        val cases = listOf(
            ScheduleLiveActivityState.START_SENT,
            ScheduleLiveActivityState.TOKEN_PENDING,
        )
        cases.forEachIndexed { index, initialState ->
            val memberId = createMember("fcm-first-${initialState.name.lowercase()}@example.com")
            val deviceId = "fcm-first-${initialState.name.lowercase()}-$index"
            val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
            tokenService.registerToken(
                memberId = memberId,
                deviceId = deviceId,
                platform = PushPlatform.IOS,
                token = "fcm-first-old-$index",
                accessTokenIssuedAt = now,
                accessTokenSessionGeneration = 0,
            )
            val oldFcmId = requireNotNull(tokenRepository.findAllByMemberId(memberId).single().id)
            val activity = when (initialState) {
                ScheduleLiveActivityState.START_SENT ->
                    unboundActivity(memberId, oldFcmId, deviceFingerprint).also {
                        it.markStartAccepted(it.contentRevision)
                    }
                ScheduleLiveActivityState.TOKEN_PENDING ->
                    activeActivity(memberId, oldFcmId, deviceFingerprint).also {
                        assertThat(
                            it.invalidateUpdateCredential(
                                requireNotNull(it.updateTokenFingerprint),
                                it.updateTokenGeneration,
                            )
                        ).isTrue()
                    }
                else -> error("unsupported test state: $initialState")
            }
            val activityRowId = requireNotNull(activityRepository.saveAndFlush(activity).id)
            tokenRepository.deleteById(oldFcmId)
            tokenRepository.flush()

            tokenService.registerToken(
                memberId = memberId,
                deviceId = deviceId,
                platform = PushPlatform.IOS,
                token = "fcm-first-replacement-$index",
                accessTokenIssuedAt = now,
                accessTokenSessionGeneration = 0,
            )
            val replacement = tokenRepository.findAllByMemberId(memberId).single()
            val waiting = activityRepository.findById(activityRowId).orElseThrow()
            assertThat(waiting.pushDeviceTokenId).isEqualTo(replacement.id)
            assertThat(waiting.state).isEqualTo(initialState)
            assertThat(deliveryRepository.findAll().none { it.liveActivityId == activityRowId })
                .isTrue()

            val updateToken = "fcm-first-update-token-${initialState.name.lowercase()}"
            val updateFingerprint = OpaquePushIdentifier.fingerprint(updateToken)
            liveActivityTokenWriter.registerUpdateToken(
                memberId = memberId,
                deviceFingerprint = deviceFingerprint,
                scheduleId = 41,
                activityId = "activity-41",
                updateToken = updateToken,
                updateTokenFingerprint = updateFingerprint,
                generation = 3,
                presentedSessionGeneration = 0,
            )

            val recovered = activityRepository.findById(activityRowId).orElseThrow()
            assertThat(recovered.state).isEqualTo(ScheduleLiveActivityState.ACTIVE)
            assertThat(recovered.pushDeviceTokenId).isEqualTo(replacement.id)
            val update = deliveryRepository.findAll().filter {
                it.liveActivityId == activityRowId
            }.single()
            assertThat(update.operation).isEqualTo(LiveActivityDeliveryOperation.UPDATE)
            assertThat(update.activityTokenFingerprint).isEqualTo(updateFingerprint)
            assertThat(update.pushDeviceTokenId).isEqualTo(replacement.id)
        }
    }

    @Test
    fun `foreign existing exact FCM blocks current-session credential-only callback`() {
        val ownerMemberId = createMember("foreign-exact-route-owner@example.com")
        val activityMemberId = createMember("foreign-exact-route-activity@example.com")
        val deviceId = "foreign-exact-route-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        tokenService.registerToken(
            memberId = ownerMemberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "foreign-exact-route-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val foreignFcmId = requireNotNull(
            tokenRepository.findAllByMemberId(ownerMemberId).single().id,
        )
        val activity = activityRepository.saveAndFlush(
            activeActivity(activityMemberId, foreignFcmId, deviceFingerprint),
        )
        val originalFingerprint = requireNotNull(activity.updateTokenFingerprint)

        assertThatThrownBy {
            val rotatedToken = "foreign-exact-route-rotated-update-token"
            liveActivityTokenWriter.registerUpdateToken(
                memberId = activityMemberId,
                deviceFingerprint = deviceFingerprint,
                scheduleId = 41,
                activityId = "activity-41",
                updateToken = rotatedToken,
                updateTokenFingerprint = OpaquePushIdentifier.fingerprint(rotatedToken),
                generation = 3,
                presentedSessionGeneration = 0,
            )
        }.isInstanceOf(BusinessException::class.java)

        val unchanged = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(unchanged.updateTokenFingerprint).isEqualTo(originalFingerprint)
        assertThat(unchanged.pushDeviceTokenId).isEqualTo(foreignFcmId)
        assertThat(deliveryRepository.findAll()).isEmpty()
    }

    @Test
    fun `provider-invalid END keeps an exact retired route available for prior-session ended ACK`() {
        val fixture = providerInvalidEndThenLogout("exact-retired-route")

        liveActivityTokenWriter.reportEnded(
            memberId = fixture.memberId,
            deviceFingerprint = fixture.deviceFingerprint,
            scheduleId = 41,
            activityId = "activity-41",
            presentedSessionGeneration = 0,
        )

        assertThat(activityRepository.findById(fixture.activityRowId).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.ENDED)
        assertThat(deliveryRepository.findById(fixture.endDeliveryId).orElseThrow().status)
            .isEqualTo(LiveActivityDeliveryStatus.SUPERSEDED)
        val retainedRoute = tokenRepository.findById(fixture.fcmId).orElseThrow()
        assertThat(retainedRoute.retirementRequested).isTrue()
        assertThat(retainedRoute.ownershipVersion).isEqualTo(fixture.fcmOwnershipVersion)
        assertThat(retainedRoute.dispatchLeaseId).isNull()
        assertThat(retainedRoute.dispatchLeaseUntil).isNull()
    }

    @Test
    fun `prior-session ended ACK rejects a retired same-row replacement ownership epoch`() {
        val fixture = providerInvalidEndThenLogout("replacement-epoch")
        val replacementToken = "replacement-epoch-fcm"
        val route = tokenRepository.findById(fixture.fcmId).orElseThrow()
        assertThat(
            route.replaceRetiredSameDeviceOwnershipEpoch(
                memberId = fixture.memberId,
                deviceId = fixture.deviceId,
                platform = PushPlatform.IOS,
                token = replacementToken,
                tokenFingerprint = OpaquePushIdentifier.fingerprint(replacementToken),
                deviceFingerprint = fixture.deviceFingerprint,
            )
        ).isTrue()
        assertThat(route.requestRetirement(now)).isTrue()
        tokenRepository.saveAndFlush(route)
        assertThat(route.retirementRequested).isTrue()
        assertThat(route.ownershipVersion).isEqualTo(fixture.fcmOwnershipVersion + 1)
        assertThat(route.dispatchLeaseId).isNull()
        assertThat(route.dispatchLeaseUntil).isNull()

        assertThatThrownBy {
            liveActivityTokenWriter.reportEnded(
                memberId = fixture.memberId,
                deviceFingerprint = fixture.deviceFingerprint,
                scheduleId = 41,
                activityId = "activity-41",
                presentedSessionGeneration = 0,
            )
        }.isInstanceOf(BusinessException::class.java)

        assertThat(activityRepository.findById(fixture.activityRowId).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(deliveryRepository.findById(fixture.endDeliveryId).orElseThrow().status)
            .isEqualTo(LiveActivityDeliveryStatus.INVALID)
    }

    @Test
    fun `prior-session ended ACK rejects an exact row reowned by another member`() {
        val fixture = providerInvalidEndThenLogout("foreign-reownership")
        val foreignMemberId = createMember("provider-invalid-foreign-owner@example.com")
        val foreignToken = "provider-invalid-foreign-fcm"
        val route = tokenRepository.findById(fixture.fcmId).orElseThrow()
        route.replaceOwnership(
            memberId = foreignMemberId,
            deviceId = fixture.deviceId,
            platform = PushPlatform.IOS,
            token = foreignToken,
            tokenFingerprint = OpaquePushIdentifier.fingerprint(foreignToken),
            deviceFingerprint = fixture.deviceFingerprint,
        )
        tokenRepository.saveAndFlush(route)
        assertThat(route.retirementRequested).isTrue()
        assertThat(route.ownershipVersion).isEqualTo(fixture.fcmOwnershipVersion + 1)

        assertThatThrownBy {
            liveActivityTokenWriter.reportEnded(
                memberId = fixture.memberId,
                deviceFingerprint = fixture.deviceFingerprint,
                scheduleId = 41,
                activityId = "activity-41",
                presentedSessionGeneration = 0,
            )
        }.isInstanceOf(BusinessException::class.java)

        assertThat(activityRepository.findById(fixture.activityRowId).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(tokenRepository.findById(fixture.fcmId).orElseThrow().memberId)
            .isEqualTo(foreignMemberId)
    }

    @Test
    fun `prior-session ended ACK rejects a retired route with a half-cleared lease`() {
        val fixture = providerInvalidEndThenLogout("half-cleared-lease")
        val route = tokenRepository.findById(fixture.fcmId).orElseThrow()
        route.dispatchLeaseUntil = now.plusSeconds(60)
        tokenRepository.saveAndFlush(route)
        assertThat(route.dispatchLeaseId).isNull()
        assertThat(route.dispatchLeaseUntil).isNotNull()

        assertThatThrownBy {
            liveActivityTokenWriter.reportEnded(
                memberId = fixture.memberId,
                deviceFingerprint = fixture.deviceFingerprint,
                scheduleId = 41,
                activityId = "activity-41",
                presentedSessionGeneration = 0,
            )
        }.isInstanceOf(BusinessException::class.java)

        assertThat(activityRepository.findById(fixture.activityRowId).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(deliveryRepository.findById(fixture.endDeliveryId).orElseThrow().status)
            .isEqualTo(LiveActivityDeliveryStatus.INVALID)
    }

    @Test
    fun `logout proof accepts immediately prior reportEnded after exact FCM deletion`() {
        val memberId = createMember("missing-fcm-logout-proof@example.com")
        val deviceId = "missing-fcm-logout-proof-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "missing-fcm-logout-proof-route",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val fcmId = requireNotNull(tokenRepository.findAllByMemberId(memberId).single().id)
        val activity = activityRepository.saveAndFlush(
            activeActivity(memberId, fcmId, deviceFingerprint),
        )
        tokenRepository.deleteById(fcmId)
        tokenRepository.flush()

        val member = memberRepository.findById(memberId).orElseThrow()
        member.sessionGeneration = 1
        memberRepository.saveAndFlush(member)
        liveActivityService.prepareEndAllForMemberBeforeTokenRetirement(memberId, "LOGOUT")

        val tombstone = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(tombstone.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(tombstone.lateEndAckSessionGeneration).isEqualTo(0)
        assertThat(tombstone.lateEndAckPushDeviceTokenId).isEqualTo(fcmId)
        assertThat(tombstone.lateEndAckFcmOwnershipVersion)
            .isEqualTo(tombstone.boundFcmOwnershipVersion)
        assertThat(deliveryRepository.findAll()).isEmpty()

        liveActivityTokenWriter.reportEnded(
            memberId = memberId,
            deviceFingerprint = deviceFingerprint,
            scheduleId = 41,
            activityId = "activity-41",
            presentedSessionGeneration = 0,
        )

        assertThat(activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.ENDED)
        assertThat(deliveryRepository.findAll()).isEmpty()
    }

    @Test
    fun `missing FCM reportEnded rejects a non-immediate generation despite logout proof`() {
        val memberId = createMember("missing-fcm-wrong-generation@example.com")
        val deviceId = "missing-fcm-wrong-generation-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val member = memberRepository.findById(memberId).orElseThrow()
        member.sessionGeneration = 5
        memberRepository.saveAndFlush(member)
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "missing-fcm-wrong-generation-route",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 5,
        )
        val fcmId = requireNotNull(tokenRepository.findAllByMemberId(memberId).single().id)
        val activity = activityRepository.saveAndFlush(
            activeActivity(memberId, fcmId, deviceFingerprint),
        )
        tokenRepository.deleteById(fcmId)
        tokenRepository.flush()

        val loggingOut = memberRepository.findById(memberId).orElseThrow()
        loggingOut.sessionGeneration = 6
        memberRepository.saveAndFlush(loggingOut)
        liveActivityService.prepareEndAllForMemberBeforeTokenRetirement(memberId, "LOGOUT")
        assertThat(
            activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
                .lateEndAckSessionGeneration
        ).isEqualTo(5)

        assertThatThrownBy {
            liveActivityTokenWriter.reportEnded(
                memberId = memberId,
                deviceFingerprint = deviceFingerprint,
                scheduleId = 41,
                activityId = "activity-41",
                presentedSessionGeneration = 4,
            )
        }.isInstanceOf(BusinessException::class.java)

        assertThat(activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.END_PENDING)
    }

    @Test
    fun `missing FCM reportEnded rejects immediately prior session without logout proof`() {
        val memberId = createMember("missing-fcm-no-proof@example.com")
        val deviceId = "missing-fcm-no-proof-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "missing-fcm-no-proof-route",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val fcmId = requireNotNull(tokenRepository.findAllByMemberId(memberId).single().id)
        val activity = activityRepository.saveAndFlush(
            activeActivity(memberId, fcmId, deviceFingerprint),
        )
        tokenRepository.deleteById(fcmId)
        tokenRepository.flush()
        liveActivityService.endForMemberSchedule(memberId, 41, "SCHEDULE_CANCELLED")
        val tombstone = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(tombstone.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(tombstone.lateEndAckSessionGeneration).isNull()
        assertThat(tombstone.lateEndAckPushDeviceTokenId).isNull()
        assertThat(tombstone.lateEndAckFcmOwnershipVersion).isNull()

        val member = memberRepository.findById(memberId).orElseThrow()
        member.sessionGeneration = 1
        memberRepository.saveAndFlush(member)

        assertThatThrownBy {
            liveActivityTokenWriter.reportEnded(
                memberId = memberId,
                deviceFingerprint = deviceFingerprint,
                scheduleId = 41,
                activityId = "activity-41",
                presentedSessionGeneration = 0,
            )
        }.isInstanceOf(BusinessException::class.java)

        assertThat(activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.END_PENDING)
    }

    @Test
    fun `rotated update-token callback completes a TOKEN_PENDING orphan handoff with END`() {
        val memberId = createMember("orphan-token-pending-handoff@example.com")
        val deviceId = "orphan-token-pending-ios-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "old-token-pending-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val oldFcmId = requireNotNull(tokenRepository.findAllByMemberId(memberId).single().id)
        val activity = activeActivity(memberId, oldFcmId, deviceFingerprint)
        assertThat(
            activity.invalidateUpdateCredential(
                requireNotNull(activity.updateTokenFingerprint),
                activity.updateTokenGeneration,
            )
        ).isTrue()
        activityRepository.saveAndFlush(activity)
        tokenRepository.deleteById(oldFcmId)
        tokenRepository.flush()

        liveActivityService.endForMemberSchedule(memberId, 41, "EXPIRED")
        assertThat(activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.END_PENDING)
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "replacement-token-pending-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        assertThat(deliveryRepository.findAll()).isEmpty()
        val replacement = tokenRepository.findAllByMemberId(memberId).single()
        val reboundWithoutCredential =
            activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(reboundWithoutCredential.pushDeviceTokenId).isEqualTo(replacement.id)
        assertThat(reboundWithoutCredential.state)
            .isEqualTo(ScheduleLiveActivityState.END_PENDING)

        val rotatedToken = "rotated-activity-update-token"
        val rotatedFingerprint = OpaquePushIdentifier.fingerprint(rotatedToken)
        liveActivityTokenWriter.registerUpdateToken(
            memberId = memberId,
            deviceFingerprint = deviceFingerprint,
            scheduleId = 41,
            activityId = "activity-41",
            updateToken = rotatedToken,
            updateTokenFingerprint = rotatedFingerprint,
            generation = 3,
            presentedSessionGeneration = 0,
        )

        val rebound = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(rebound.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(rebound.updateTokenFingerprint).isEqualTo(rotatedFingerprint)
        val end = deliveryRepository.findAll().single()
        assertThat(end.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(end.activityTokenFingerprint).isEqualTo(rotatedFingerprint)
        assertThat(end.pushDeviceTokenId).isEqualTo(replacement.id)
        assertThat(end.fcmOwnershipVersion).isEqualTo(replacement.ownershipVersion)
    }

    @Test
    fun `rotated END callback before replacement FCM is persisted then materialized`() {
        val memberId = createMember("callback-before-fcm-handoff@example.com")
        val deviceId = "callback-before-fcm-ios-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "callback-before-fcm-old-route",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val oldFcmId = requireNotNull(tokenRepository.findAllByMemberId(memberId).single().id)
        val activity = activeActivity(memberId, oldFcmId, deviceFingerprint)
        assertThat(
            activity.invalidateUpdateCredential(
                requireNotNull(activity.updateTokenFingerprint),
                activity.updateTokenGeneration,
            )
        ).isTrue()
        activityRepository.saveAndFlush(activity)
        tokenRepository.deleteById(oldFcmId)
        tokenRepository.flush()

        liveActivityService.endForMemberSchedule(memberId, 41, "EXPIRED")
        assertThat(activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(deliveryRepository.findAll()).isEmpty()

        // ActivityKit may rotate and upload its END credential before Firebase finishes issuing
        // the replacement route. Persist the authenticated exact-generation credential without
        // reviving the tombstone or fabricating an unroutable provider delivery.
        val rotatedToken = "callback-before-fcm-rotated-update-token"
        val rotatedFingerprint = OpaquePushIdentifier.fingerprint(rotatedToken)
        liveActivityTokenWriter.registerUpdateToken(
            memberId = memberId,
            deviceFingerprint = deviceFingerprint,
            scheduleId = 41,
            activityId = "activity-41",
            updateToken = rotatedToken,
            updateTokenFingerprint = rotatedFingerprint,
            generation = 3,
            presentedSessionGeneration = 0,
        )

        val credentialOnly = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(credentialOnly.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(credentialOnly.pushDeviceTokenId).isEqualTo(oldFcmId)
        assertThat(credentialOnly.updateTokenFingerprint).isEqualTo(rotatedFingerprint)
        assertThat(deliveryRepository.findAll()).isEmpty()

        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "callback-before-fcm-replacement-route",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )

        val replacement = tokenRepository.findAllByMemberId(memberId).single()
        val rebound = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(rebound.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(rebound.pushDeviceTokenId).isEqualTo(replacement.id)
        assertThat(rebound.updateTokenFingerprint).isEqualTo(rotatedFingerprint)
        val end = deliveryRepository.findAll().single()
        assertThat(end.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(end.status).isEqualTo(LiveActivityDeliveryStatus.PENDING)
        assertThat(end.pushDeviceTokenId).isEqualTo(replacement.id)
        assertThat(end.fcmTokenFingerprint).isEqualTo(replacement.tokenFingerprint)
        assertThat(end.fcmOwnershipVersion).isEqualTo(replacement.ownershipVersion)
        assertThat(end.activityTokenFingerprint).isEqualTo(rotatedFingerprint)
    }

    @Test
    fun `same-owner identical FCM releases reservation-only END then accepts callback`() {
        val memberId = createMember("same-owner-reservation-only@example.com")
        val deviceId = "same-owner-reservation-only-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val unchangedFcmToken = "same-owner-reservation-fcm"
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = unchangedFcmToken,
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val oldFcm = tokenRepository.findAllByMemberId(memberId).single()
        val oldFcmId = requireNotNull(oldFcm.id)
        val oldEpoch = oldFcm.ownershipVersion
        val activity = activeActivity(memberId, oldFcmId, deviceFingerprint)
        assertThat(
            activity.invalidateUpdateCredential(
                requireNotNull(activity.updateTokenFingerprint),
                activity.updateTokenGeneration,
            )
        ).isTrue()
        activityRepository.saveAndFlush(activity)

        liveActivityService.prepareEndAllForMemberBeforeTokenRetirement(memberId, "LOGOUT")
        retirementService.retireAllByMember(memberId)
        val reserved = tokenRepository.findById(oldFcmId).orElseThrow()
        assertThat(reserved.retirementRequested).isTrue()
        assertThat(reserved.dispatchLeaseId).startsWith("la-end:")
        assertThat(activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(deliveryRepository.findAll()).isEmpty()

        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = unchangedFcmToken,
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )

        val replacement = tokenRepository.findAllByMemberId(memberId).single()
        assertThat(replacement.id).isEqualTo(oldFcmId)
        assertThat(replacement.ownershipVersion).isEqualTo(oldEpoch + 1)
        assertThat(replacement.retirementRequested).isFalse()
        assertThat(replacement.dispatchLeaseId).isNull()
        assertThat(deliveryRepository.findAll()).isEmpty()

        val rotatedToken = "same-owner-reservation-rotated-update-token"
        val rotatedFingerprint = OpaquePushIdentifier.fingerprint(rotatedToken)
        liveActivityTokenWriter.registerUpdateToken(
            memberId = memberId,
            deviceFingerprint = deviceFingerprint,
            scheduleId = 41,
            activityId = "activity-41",
            updateToken = rotatedToken,
            updateTokenFingerprint = rotatedFingerprint,
            generation = 3,
            presentedSessionGeneration = 0,
        )

        val end = deliveryRepository.findAll().single()
        assertThat(end.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(end.pushDeviceTokenId).isEqualTo(replacement.id)
        assertThat(end.fcmOwnershipVersion).isEqualTo(replacement.ownershipVersion)
        assertThat(end.activityTokenFingerprint).isEqualTo(rotatedFingerprint)
    }

    @Test
    fun `foreign registration keeps reservation-only END on old owner until callback succeeds`() {
        val oldMemberId = createMember("foreign-reservation-old-owner@example.com")
        val newMemberId = createMember("foreign-reservation-new-owner@example.com")
        val deviceId = "foreign-reservation-only-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        tokenService.registerToken(
            memberId = oldMemberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "foreign-reservation-old-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val oldFcmId = requireNotNull(tokenRepository.findAllByMemberId(oldMemberId).single().id)
        val activity = activeActivity(oldMemberId, oldFcmId, deviceFingerprint)
        assertThat(
            activity.invalidateUpdateCredential(
                requireNotNull(activity.updateTokenFingerprint),
                activity.updateTokenGeneration,
            )
        ).isTrue()
        activityRepository.saveAndFlush(activity)
        liveActivityService.prepareEndAllForMemberBeforeTokenRetirement(oldMemberId, "LOGOUT")
        retirementService.retireAllByMember(oldMemberId)
        assertThat(tokenRepository.findById(oldFcmId).orElseThrow().dispatchLeaseId)
            .startsWith("la-end:")
        assertThat(deliveryRepository.findAll()).isEmpty()

        val replacementToken = "foreign-reservation-new-fcm"
        val replacementFingerprint = OpaquePushIdentifier.fingerprint(replacementToken)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val registration = executor.submit {
                tokenService.registerToken(
                    memberId = newMemberId,
                    deviceId = deviceId,
                    platform = PushPlatform.IOS,
                    token = replacementToken,
                    accessTokenIssuedAt = now,
                    accessTokenSessionGeneration = 0,
                )
            }
            awaitCondition("replacement route is retained for the old owner's END") {
                tokenRepository.findAllByMemberId(oldMemberId).singleOrNull()?.let {
                    it.tokenFingerprint == replacementFingerprint &&
                        it.retirementRequested &&
                        it.dispatchLeaseId?.startsWith("la-end:") == true
                } == true
            }
            assertThat(tokenRepository.findAllByMemberId(newMemberId)).isEmpty()
            assertThat(registration.isDone).isFalse()
            assertThat(deliveryRepository.findAll()).isEmpty()

            val rotatedToken = "foreign-reservation-rotated-update-token"
            val rotatedFingerprint = OpaquePushIdentifier.fingerprint(rotatedToken)
            liveActivityTokenWriter.registerUpdateToken(
                memberId = oldMemberId,
                deviceFingerprint = deviceFingerprint,
                scheduleId = 41,
                activityId = "activity-41",
                updateToken = rotatedToken,
                updateTokenFingerprint = rotatedFingerprint,
                generation = 3,
                presentedSessionGeneration = 0,
            )
            val end = deliveryRepository.findAll().single()
            assertThat(end.operation).isEqualTo(LiveActivityDeliveryOperation.END)
            assertThat(end.memberId).isEqualTo(oldMemberId)
            assertThat(end.fcmTokenFingerprint).isEqualTo(replacementFingerprint)
            assertThat(end.activityTokenFingerprint).isEqualTo(rotatedFingerprint)

            val endLease = requireNotNull(dispatchCoordinator.claimNext(now))
            assertThat(endLease.deliveryId).isEqualTo(end.id)
            dispatchCoordinator.completeSuccess(endLease, "foreign-reservation-end-success", now)
            registration.get(5, TimeUnit.SECONDS)

            assertThat(tokenRepository.findAllByMemberId(oldMemberId)).isEmpty()
            assertThat(tokenRepository.findAllByMemberId(newMemberId).single().tokenFingerprint)
                .isEqualTo(replacementFingerprint)
            assertThat(activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state)
                .isEqualTo(ScheduleLiveActivityState.ENDED)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `foreign replacement supersedes exhausted old END and transfers after new END success`() {
        val oldMemberId = createMember("foreign-exhausted-old-owner@example.com")
        val newMemberId = createMember("foreign-exhausted-new-owner@example.com")
        val deviceId = "foreign-exhausted-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        tokenService.registerToken(
            memberId = oldMemberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "foreign-exhausted-old-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val oldFcmId = requireNotNull(tokenRepository.findAllByMemberId(oldMemberId).single().id)
        val activity = activityRepository.saveAndFlush(
            activeActivity(oldMemberId, oldFcmId, deviceFingerprint),
        )
        liveActivityService.endForMemberSchedule(oldMemberId, 41, "EXPIRED")
        val exhaustedEnd = deliveryRepository.findAll().single()
        exhaustedEnd.markExhausted(now)
        deliveryRepository.saveAndFlush(exhaustedEnd)
        assertThat(exhaustedEnd.status).isEqualTo(LiveActivityDeliveryStatus.EXHAUSTED)

        val replacementToken = "foreign-exhausted-replacement-fcm"
        val replacementFingerprint = OpaquePushIdentifier.fingerprint(replacementToken)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val registration = executor.submit {
                tokenService.registerToken(
                    memberId = newMemberId,
                    deviceId = deviceId,
                    platform = PushPlatform.IOS,
                    token = replacementToken,
                    accessTokenIssuedAt = now,
                    accessTokenSessionGeneration = 0,
                )
            }
            awaitCondition("exhausted END is replaced on a new old-owner route epoch") {
                val deliveries = deliveryRepository.findAll()
                deliveries.any {
                    it.id == exhaustedEnd.id &&
                        it.status == LiveActivityDeliveryStatus.SUPERSEDED
                } && deliveries.any {
                    it.id != exhaustedEnd.id &&
                        it.operation == LiveActivityDeliveryOperation.END &&
                        it.status == LiveActivityDeliveryStatus.PENDING &&
                        it.fcmTokenFingerprint == replacementFingerprint
                }
            }
            assertThat(registration.isDone).isFalse()
            assertThat(tokenRepository.findAllByMemberId(newMemberId)).isEmpty()
            val replacementEnd = deliveryRepository.findAll().single {
                it.id != exhaustedEnd.id &&
                    it.operation == LiveActivityDeliveryOperation.END &&
                    it.status == LiveActivityDeliveryStatus.PENDING
            }
            val endLease = requireNotNull(dispatchCoordinator.claimNext(now))
            assertThat(endLease.deliveryId).isEqualTo(replacementEnd.id)
            dispatchCoordinator.completeSuccess(endLease, "foreign-exhausted-end-success", now)
            registration.get(5, TimeUnit.SECONDS)

            assertThat(tokenRepository.findAllByMemberId(oldMemberId)).isEmpty()
            assertThat(tokenRepository.findAllByMemberId(newMemberId).single().tokenFingerprint)
                .isEqualTo(replacementFingerprint)
            assertThat(deliveryRepository.findById(requireNotNull(exhaustedEnd.id)).orElseThrow().status)
                .isEqualTo(LiveActivityDeliveryStatus.SUPERSEDED)
            assertThat(activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state)
                .isEqualTo(ScheduleLiveActivityState.ENDED)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `same-owner active FCM advances epoch for TOKEN_PENDING END before rotated callback`() {
        val memberId = createMember("same-owner-token-pending-handoff@example.com")
        val deviceId = "same-owner-token-pending-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "same-owner-fcm-a",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val fcmA = tokenRepository.findAllByMemberId(memberId).single()
        val fcmAEpoch = fcmA.ownershipVersion
        val activity = activeActivity(memberId, requireNotNull(fcmA.id), deviceFingerprint)
        assertThat(
            activity.invalidateUpdateCredential(
                requireNotNull(activity.updateTokenFingerprint),
                activity.updateTokenGeneration,
            )
        ).isTrue()
        activityRepository.saveAndFlush(activity)
        liveActivityService.endForMemberSchedule(memberId, 41, "EXPIRED")
        assertThat(activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(deliveryRepository.findAll()).isEmpty()

        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "same-owner-fcm-b",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val fcmB = tokenRepository.findAllByMemberId(memberId).single()
        assertThat(fcmB.id).isEqualTo(fcmA.id)
        assertThat(fcmB.ownershipVersion).isEqualTo(fcmAEpoch + 1)
        assertThat(fcmB.retirementRequested).isFalse()
        assertThat(deliveryRepository.findAll()).isEmpty()

        val rotatedToken = "same-owner-rotated-update-token"
        val rotatedFingerprint = OpaquePushIdentifier.fingerprint(rotatedToken)
        liveActivityTokenWriter.registerUpdateToken(
            memberId = memberId,
            deviceFingerprint = deviceFingerprint,
            scheduleId = 41,
            activityId = "activity-41",
            updateToken = rotatedToken,
            updateTokenFingerprint = rotatedFingerprint,
            generation = 3,
            presentedSessionGeneration = 0,
        )

        val end = deliveryRepository.findAll().single()
        assertThat(end.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(end.fcmTokenFingerprint).isEqualTo(fcmB.tokenFingerprint)
        assertThat(end.fcmOwnershipVersion).isEqualTo(fcmB.ownershipVersion)
        assertThat(end.activityTokenFingerprint).isEqualTo(rotatedFingerprint)
        assertThat(activityRepository.findById(requireNotNull(activity.id)).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.END_PENDING)
    }

    @Test
    fun `same-owner identical FCM registration does not block late END token callback`() {
        val memberId = createMember("same-owner-identical-token-handoff@example.com")
        val deviceId = "same-owner-identical-token-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val fcmToken = "same-owner-identical-fcm"
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = fcmToken,
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val originalFcm = tokenRepository.findAllByMemberId(memberId).single()
        val originalEpoch = originalFcm.ownershipVersion
        val activity = activeActivity(
            memberId,
            requireNotNull(originalFcm.id),
            deviceFingerprint,
        )
        assertThat(
            activity.invalidateUpdateCredential(
                requireNotNull(activity.updateTokenFingerprint),
                activity.updateTokenGeneration,
            )
        ).isTrue()
        activityRepository.saveAndFlush(activity)
        liveActivityService.endForMemberSchedule(memberId, 41, "EXPIRED")
        assertThat(deliveryRepository.findAll()).isEmpty()

        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = fcmToken,
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val idempotentFcm = tokenRepository.findAllByMemberId(memberId).single()
        assertThat(idempotentFcm.id).isEqualTo(originalFcm.id)
        assertThat(idempotentFcm.ownershipVersion).isEqualTo(originalEpoch)
        assertThat(deliveryRepository.findAll()).isEmpty()

        val rotatedToken = "same-owner-identical-rotated-update-token"
        val rotatedFingerprint = OpaquePushIdentifier.fingerprint(rotatedToken)
        liveActivityTokenWriter.registerUpdateToken(
            memberId = memberId,
            deviceFingerprint = deviceFingerprint,
            scheduleId = 41,
            activityId = "activity-41",
            updateToken = rotatedToken,
            updateTokenFingerprint = rotatedFingerprint,
            generation = 3,
            presentedSessionGeneration = 0,
        )

        val end = deliveryRepository.findAll().single()
        assertThat(end.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(end.fcmTokenFingerprint).isEqualTo(idempotentFcm.tokenFingerprint)
        assertThat(end.fcmOwnershipVersion).isEqualTo(originalEpoch)
        assertThat(end.activityTokenFingerprint).isEqualTo(rotatedFingerprint)
    }

    @Test
    fun `generation replacement keeps old instance END reserved across logout`() {
        val fixture = generationFixture("generation-logout@example.com")

        liveActivityService.projectEta(generationProjection(fixture))

        val ending = activityRepository.findById(fixture.activityRowId).orElseThrow()
        assertThat(ending.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(ending.sourceNotificationGeneration).isEqualTo(3)
        assertThat(ending.activityId).isEqualTo("activity-41")
        val immutableOldEnd = deliveryRepository.findAll().single()
        assertThat(immutableOldEnd.operation).isEqualTo(LiveActivityDeliveryOperation.END)

        liveActivityService.prepareEndAllForMemberBeforeTokenRetirement(
            fixture.memberId,
            "LOGOUT",
        )
        retirementService.retireAllByMember(fixture.memberId)

        val reserved = tokenRepository.findById(fixture.fcmId).orElseThrow()
        assertThat(reserved.retirementRequested).isTrue()
        assertThat(reserved.dispatchLeaseId).startsWith("la-end:")
        assertThat(deliveryRepository.findAll().none {
            it.operation == LiveActivityDeliveryOperation.START
        }).isTrue()

        val endLease = requireNotNull(dispatchCoordinator.claimNext(now))
        assertThat(endLease.deliveryId).isEqualTo(immutableOldEnd.id)
        dispatchCoordinator.completeSuccess(endLease, "generation-logout-end-success", now)
        assertThat(activityRepository.findById(fixture.activityRowId).orElseThrow().state)
            .isEqualTo(ScheduleLiveActivityState.ENDED)
        assertThat(deliveryRepository.findById(requireNotNull(immutableOldEnd.id)).orElseThrow().status)
            .isEqualTo(LiveActivityDeliveryStatus.SUCCESS)
    }

    @Test
    fun `generation replacement recovers UNREGISTERED old END before starting new generation`() {
        val fixture = generationFixture("generation-unregistered@example.com")
        liveActivityService.projectEta(generationProjection(fixture))
        val oldEnd = deliveryRepository.findAll().single()

        val oldEndLease = requireNotNull(dispatchCoordinator.claimNext(now))
        dispatchCoordinator.completeInvalidFcmRoute(oldEndLease, now)
        assertThat(deliveryRepository.findById(requireNotNull(oldEnd.id)).orElseThrow().status)
            .isEqualTo(LiveActivityDeliveryStatus.INVALID)
        assertThat(
            tokenService.removeTokenByOwnership(
                memberId = fixture.memberId,
                tokenId = fixture.fcmId,
                tokenFingerprint = fixture.fcmFingerprint,
                ownershipVersion = fixture.fcmOwnershipVersion,
            )
        ).isFalse()

        tokenService.registerToken(
            memberId = fixture.memberId,
            deviceId = fixture.deviceId,
            platform = PushPlatform.IOS,
            token = "generation-unregistered-replacement-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val replacementEnd = deliveryRepository.findAll().single {
            it.operation == LiveActivityDeliveryOperation.END &&
                it.status == LiveActivityDeliveryStatus.PENDING
        }
        val recovering = activityRepository.findById(fixture.activityRowId).orElseThrow()
        assertThat(recovering.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(recovering.sourceNotificationGeneration).isEqualTo(3)
        assertThat(recovering.activityId).isEqualTo("activity-41")

        val replacementLease = requireNotNull(dispatchCoordinator.claimNext(now))
        assertThat(replacementLease.deliveryId).isEqualTo(replacementEnd.id)
        dispatchCoordinator.completeSuccess(
            replacementLease,
            "generation-unregistered-end-success",
            now,
        )

        liveActivityService.projectEta(generationProjection(fixture))
        val restarted = activityRepository.findById(fixture.activityRowId).orElseThrow()
        assertThat(restarted.state).isEqualTo(ScheduleLiveActivityState.START_PENDING)
        assertThat(restarted.sourceNotificationGeneration).isEqualTo(4)
        assertThat(restarted.activityId).isNull()
        assertThat(restarted.updateToken).isNull()
        assertThat(deliveryRepository.findAll().count {
            it.operation == LiveActivityDeliveryOperation.START &&
                it.status == LiveActivityDeliveryStatus.PENDING
        }).isEqualTo(1)
    }

    @Test
    fun `generation replacement accepts rotated old token after invalid END before new START`() {
        val fixture = generationFixture("generation-rotated-end@example.com")
        liveActivityService.projectEta(generationProjection(fixture))
        val oldEnd = deliveryRepository.findAll().single()

        val oldEndLease = requireNotNull(dispatchCoordinator.claimNext(now))
        dispatchCoordinator.completeInvalidActivityRoute(oldEndLease, now)
        assertThat(deliveryRepository.findById(requireNotNull(oldEnd.id)).orElseThrow().status)
            .isEqualTo(LiveActivityDeliveryStatus.INVALID)
        val stillRecoverable = activityRepository.findById(fixture.activityRowId).orElseThrow()
        assertThat(stillRecoverable.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(stillRecoverable.sourceNotificationGeneration).isEqualTo(3)
        assertThat(stillRecoverable.activityId).isEqualTo("activity-41")

        val rotatedToken = "generation-replacement-rotated-old-token"
        val rotatedFingerprint = OpaquePushIdentifier.fingerprint(rotatedToken)
        liveActivityTokenWriter.registerUpdateToken(
            memberId = fixture.memberId,
            deviceFingerprint = fixture.deviceFingerprint,
            scheduleId = 41,
            activityId = "activity-41",
            updateToken = rotatedToken,
            updateTokenFingerprint = rotatedFingerprint,
            generation = 3,
            presentedSessionGeneration = 0,
        )
        val rotatedEnd = deliveryRepository.findAll().single {
            it.operation == LiveActivityDeliveryOperation.END &&
                it.status == LiveActivityDeliveryStatus.PENDING
        }
        assertThat(rotatedEnd.activityTokenFingerprint).isEqualTo(rotatedFingerprint)

        val rotatedLease = requireNotNull(dispatchCoordinator.claimNext(now))
        assertThat(rotatedLease.deliveryId).isEqualTo(rotatedEnd.id)
        dispatchCoordinator.completeSuccess(rotatedLease, "generation-rotated-end-success", now)

        liveActivityService.projectEta(generationProjection(fixture))
        val restarted = activityRepository.findById(fixture.activityRowId).orElseThrow()
        assertThat(restarted.state).isEqualTo(ScheduleLiveActivityState.START_PENDING)
        assertThat(restarted.sourceNotificationGeneration).isEqualTo(4)
        assertThat(restarted.activityId).isNull()
        assertThat(deliveryRepository.findAll().count {
            it.operation == LiveActivityDeliveryOperation.START &&
                it.status == LiveActivityDeliveryStatus.PENDING
        }).isEqualTo(1)
    }

    @Test
    fun `generation END rebinds replacement FCM before immediate logout reservation`() {
        val fixture = generationFixture("generation-replacement-route@example.com")
        tokenRepository.deleteById(fixture.fcmId)
        tokenRepository.flush()
        tokenService.registerToken(
            memberId = fixture.memberId,
            deviceId = fixture.deviceId,
            platform = PushPlatform.IOS,
            token = "generation-current-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val replacementFcm = tokenRepository.findAllByMemberId(fixture.memberId).single()
        assertThat(replacementFcm.id).isNotEqualTo(fixture.fcmId)
        val startToken = startTokenRepository
            .findAllByMemberIdAndActivityTypeAndActiveTrueAndRetirementRequestedFalseOrderByIdAsc(
                fixture.memberId,
                NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            ).single()
        val replacementStartToken = "generation-current-start-token"
        assertThat(
            startToken.rotate(
                memberId = fixture.memberId,
                pushDeviceTokenId = requireNotNull(replacementFcm.id),
                deviceFingerprint = fixture.deviceFingerprint,
                token = replacementStartToken,
                tokenFingerprint = OpaquePushIdentifier.fingerprint(replacementStartToken),
                seenAt = now,
            )
        ).isTrue()
        startTokenRepository.saveAndFlush(startToken)
        val reboundBeforeGenerationEnd = activityRepository.findById(
            fixture.activityRowId,
        ).orElseThrow()
        assertThat(reboundBeforeGenerationEnd.pushDeviceTokenId).isEqualTo(replacementFcm.id)
        assertThat(reboundBeforeGenerationEnd.boundFcmOwnershipVersion)
            .isEqualTo(replacementFcm.ownershipVersion)
        val replacementUpdate = deliveryRepository.findAll().single()
        assertThat(replacementUpdate.operation).isEqualTo(LiveActivityDeliveryOperation.UPDATE)

        liveActivityService.projectEta(generationProjection(fixture))

        val ending = activityRepository.findById(fixture.activityRowId).orElseThrow()
        assertThat(ending.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(ending.pushDeviceTokenId).isEqualTo(replacementFcm.id)
        assertThat(
            deliveryRepository.findById(requireNotNull(replacementUpdate.id)).orElseThrow().status
        ).isEqualTo(LiveActivityDeliveryStatus.SUPERSEDED)
        val oldInstanceEnd = deliveryRepository.findAll().single {
            it.operation == LiveActivityDeliveryOperation.END
        }
        assertThat(oldInstanceEnd.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        assertThat(oldInstanceEnd.pushDeviceTokenId).isEqualTo(replacementFcm.id)
        assertThat(oldInstanceEnd.fcmOwnershipVersion)
            .isEqualTo(replacementFcm.ownershipVersion)

        liveActivityService.prepareEndAllForMemberBeforeTokenRetirement(
            fixture.memberId,
            "LOGOUT",
        )
        retirementService.retireAllByMember(fixture.memberId)
        val reserved = tokenRepository.findById(requireNotNull(replacementFcm.id)).orElseThrow()
        assertThat(reserved.retirementRequested).isTrue()
        assertThat(reserved.dispatchLeaseId).startsWith("la-end:")
    }

    private data class GenerationFixture(
        val memberId: Long,
        val deviceId: String,
        val deviceFingerprint: String,
        val fcmId: Long,
        val fcmFingerprint: String,
        val fcmOwnershipVersion: Long,
        val activityRowId: Long,
    )

    private fun generationFixture(email: String): GenerationFixture {
        val memberId = createMember(email)
        val deviceId = "generation-${email.substringBefore('@')}"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "fcm-$deviceId",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val fcm = tokenRepository.findAllByMemberId(memberId).single()
        val fcmId = requireNotNull(fcm.id)
        val activity = activityRepository.saveAndFlush(
            activeActivity(memberId, fcmId, deviceFingerprint),
        )
        startTokenRepository.saveAndFlush(
            LiveActivityStartToken(
                memberId = memberId,
                pushDeviceTokenId = fcmId,
                deviceFingerprint = deviceFingerprint,
                activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
                pushToStartToken = "start-$deviceId",
                lastSeenAt = now,
            ),
        )
        val scheduleAt = now.plusSeconds(7_200)
        val departureAt = now.plusSeconds(3_600)
        val job = SchedulePushJob.create(
            memberId = memberId,
            scheduleId = 41,
            scheduleAt = scheduleAt,
            departureAt = departureAt,
            monitorStartAt = now,
            intervalMinutes = 20,
            notificationInputFingerprint = OpaquePushIdentifier.fingerprint("initial-$deviceId"),
        )
        (1..4).forEach { generation ->
            assertThat(
                job.changeSchedule(
                    scheduleAt = scheduleAt,
                    departureAt = departureAt,
                    monitorStartAt = now,
                    intervalMinutes = 20,
                    notificationInputFingerprint = OpaquePushIdentifier.fingerprint(
                        "$generation-$deviceId",
                    ),
                )
            ).isTrue()
        }
        pushJobRepository.saveAndFlush(job)
        assertThat(job.notificationGeneration).isEqualTo(4)
        return GenerationFixture(
            memberId = memberId,
            deviceId = deviceId,
            deviceFingerprint = deviceFingerprint,
            fcmId = fcmId,
            fcmFingerprint = fcm.tokenFingerprint,
            fcmOwnershipVersion = fcm.ownershipVersion,
            activityRowId = requireNotNull(activity.id),
        )
    }

    private fun generationProjection(fixture: GenerationFixture) =
        ScheduleLiveActivityEtaProjection(
            memberId = fixture.memberId,
            scheduleId = 41,
            notificationGeneration = 4,
            checkCount = 0,
            evaluatedAt = now,
            scheduleTitle = "세대 교체 일정",
            destinationName = "강남역",
            scheduleStartAt = now.plusSeconds(7_200),
            travelMinutes = 40,
            recommendedDepartureAt = now.plusSeconds(3_600),
            predictedArrivalAt = now.plusSeconds(6_000),
            firstBoardingWaitMinutes = 20,
            etaSource = TrafficSource.LIVE_PROVIDER,
            etaStale = false,
            routeSegments = emptyList(),
            staleAt = now.plusSeconds(600),
        )

    private fun createMember(email: String): Long = requireNotNull(
        memberRepository.saveAndFlush(
            Member(
                name = "member",
                password = "Password1!",
                email = email,
                loginType = LoginType.COMMON,
            )
        ).id
    )

    private data class ProviderInvalidEndLogoutFixture(
        val memberId: Long,
        val deviceId: String,
        val deviceFingerprint: String,
        val fcmId: Long,
        val fcmOwnershipVersion: Long,
        val activityRowId: Long,
        val endDeliveryId: Long,
    )

    private fun providerInvalidEndThenLogout(suffix: String): ProviderInvalidEndLogoutFixture {
        val memberId = createMember("provider-invalid-late-ack-$suffix@example.com")
        val deviceId = "provider-invalid-late-ack-$suffix-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "provider-invalid-late-ack-$suffix-fcm",
            accessTokenIssuedAt = now,
            accessTokenSessionGeneration = 0,
        )
        val fcm = tokenRepository.findAllByMemberId(memberId).single()
        val fcmId = requireNotNull(fcm.id)
        val activity = activityRepository.saveAndFlush(
            activeActivity(memberId, fcmId, deviceFingerprint),
        )
        liveActivityService.endForMemberSchedule(memberId, 41, "EXPIRED")
        val endDelivery = deliveryRepository.findAll().single()
        assertThat(endDelivery.operation).isEqualTo(LiveActivityDeliveryOperation.END)
        val lease = requireNotNull(dispatchCoordinator.claimNext(now))
        dispatchCoordinator.completeInvalidFcmRoute(lease, now)

        val invalidRoute = tokenRepository.findById(fcmId).orElseThrow()
        assertThat(invalidRoute.retirementRequested).isTrue()
        assertThat(invalidRoute.ownershipVersion).isEqualTo(fcm.ownershipVersion)
        assertThat(invalidRoute.dispatchLeaseId).isNull()
        assertThat(invalidRoute.dispatchLeaseUntil).isNull()
        assertThat(deliveryRepository.findById(requireNotNull(endDelivery.id)).orElseThrow().status)
            .isEqualTo(LiveActivityDeliveryStatus.INVALID)

        val member = memberRepository.findById(memberId).orElseThrow()
        member.sessionGeneration = 1
        memberRepository.saveAndFlush(member)
        liveActivityService.prepareEndAllForMemberBeforeTokenRetirement(memberId, "LOGOUT")

        val tombstone = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(tombstone.state).isEqualTo(ScheduleLiveActivityState.END_PENDING)
        assertThat(tombstone.lateEndAckSessionGeneration).isEqualTo(0)
        assertThat(tombstone.lateEndAckPushDeviceTokenId).isEqualTo(fcmId)
        assertThat(tombstone.lateEndAckFcmOwnershipVersion).isEqualTo(fcm.ownershipVersion)
        val unreservedRoute = tokenRepository.findById(fcmId).orElseThrow()
        assertThat(unreservedRoute.retirementRequested).isTrue()
        assertThat(unreservedRoute.dispatchLeaseId).isNull()
        assertThat(unreservedRoute.dispatchLeaseUntil).isNull()

        return ProviderInvalidEndLogoutFixture(
            memberId = memberId,
            deviceId = deviceId,
            deviceFingerprint = deviceFingerprint,
            fcmId = fcmId,
            fcmOwnershipVersion = fcm.ownershipVersion,
            activityRowId = requireNotNull(activity.id),
            endDeliveryId = requireNotNull(endDelivery.id),
        )
    }

    private fun activeActivity(
        memberId: Long,
        pushDeviceTokenId: Long,
        deviceFingerprint: String,
    ): ScheduleLiveActivity =
        unboundActivity(memberId, pushDeviceTokenId, deviceFingerprint).also {
            it.bindUpdateToken("activity-41", "activity-update-token")
        }

    private fun unboundActivity(
        memberId: Long,
        pushDeviceTokenId: Long,
        deviceFingerprint: String,
    ): ScheduleLiveActivity {
        val content = NoLateDepartureContentStateWire(
            revision = 1,
            travelMinutes = 40,
            firstWaitMinutes = 20,
            predictedArrivalEpochSeconds = now.plusSeconds(3_600).epochSecond,
            recommendedDepartureEpochSeconds = now.epochSecond,
            updatedAtEpochSeconds = now.epochSecond,
            actionExpiresAtEpochSeconds = now.plusSeconds(3_600).epochSecond,
            status = "tracking",
            routeSegments = emptyList(),
        )
        return ScheduleLiveActivity(
            memberId = memberId,
            scheduleId = 41,
            pushDeviceTokenId = pushDeviceTokenId,
            deviceFingerprint = deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 1,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = objectMapper.writeValueAsString(content),
            expiresAt = now.plusSeconds(3_600),
        )
    }

    private fun awaitCondition(
        description: String,
        timeoutMillis: Long = 2_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        var satisfied = false
        while (!satisfied && System.nanoTime() < deadline) {
            satisfied = runCatching(condition).getOrDefault(false)
            if (!satisfied) Thread.sleep(10)
        }
        assertThat(satisfied).describedAs(description).isTrue()
    }
}

@TestConfiguration
class NotificationTokenLiveActivityHandoffTestConfig {
    @Bean
    fun liveActivityHandoffClock(): Clock =
        Clock.fixed(Instant.parse("2026-08-06T00:10:00Z"), ZoneOffset.UTC)

    @Bean
    fun liveActivityHandoffObjectMapper(): ObjectMapper = jacksonObjectMapper()
}
