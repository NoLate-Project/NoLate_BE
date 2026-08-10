package com.noLate.notification.liveactivity.application

import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.liveactivity.domain.LiveActivityDelivery
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryOperation
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryStatus
import com.noLate.notification.liveactivity.domain.LiveActivityAppearance
import com.noLate.notification.liveactivity.domain.LiveActivityStartToken
import com.noLate.notification.liveactivity.domain.LiveActivityLifecycleIntent
import com.noLate.notification.liveactivity.domain.LiveActivityLifecycleScopeType
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivity
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivityState
import com.noLate.notification.liveactivity.infrastructure.LiveActivityDeliveryRepository
import com.noLate.notification.liveactivity.infrastructure.LiveActivityStartTokenRepository
import com.noLate.notification.liveactivity.infrastructure.LiveActivityLifecycleIntentRepository
import com.noLate.notification.liveactivity.infrastructure.ScheduleLiveActivityRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:live-activity-token-atomicity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "spring.task.scheduling.enabled=false",
        "schedule.push.enabled=false",
        "notification.push-outbox.enabled=false",
    ],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LiveActivityTokenWriterAtomicityIntegrationTest @Autowired constructor(
    private val writer: LiveActivityTokenWriter,
    private val memberRepository: MemberRepository,
    private val deviceRepository: NotificationDeviceTokenRepository,
    private val startTokenRepository: LiveActivityStartTokenRepository,
    private val activityRepository: ScheduleLiveActivityRepository,
    private val deliveryRepository: LiveActivityDeliveryRepository,
    private val lifecycleIntentRepository: LiveActivityLifecycleIntentRepository,
    private val lifecycleRecoveryWorker: LiveActivityLifecycleRecoveryWorker,
) {
    @MockitoBean
    private lateinit var projectionService: ScheduleLiveActivityService

    @BeforeEach
    fun clean() {
        lifecycleIntentRepository.deleteAll()
        deliveryRepository.deleteAll()
        activityRepository.deleteAll()
        startTokenRepository.deleteAll()
        deviceRepository.deleteAll()
        memberRepository.deleteAll()
    }

    @Test
    fun `poison lifecycle row backs off so the 101st newer END is not starved`() {
        val poison = lifecycleIntentRepository.saveAndFlush(
            LiveActivityLifecycleIntent(
                scopeType = LiveActivityLifecycleScopeType.SCHEDULE,
                scheduleId = 1,
                reason = "POISON",
            ),
        )
        val healthy = lifecycleIntentRepository.saveAllAndFlush(
            (2L..101L).map { scheduleId ->
                LiveActivityLifecycleIntent(
                    scopeType = LiveActivityLifecycleScopeType.SCHEDULE,
                    scheduleId = scheduleId,
                    reason = "HEALTHY",
                )
            },
        )
        doThrow(IllegalStateException("corrupt projection"))
            .`when`(projectionService).endAllForSchedule(1, "POISON")

        lifecycleRecoveryWorker.recoverPending()
        lifecycleRecoveryWorker.recoverPending()

        val persistedPoison = lifecycleIntentRepository
            .findById(requireNotNull(poison.id)).orElseThrow()
        val tail = lifecycleIntentRepository
            .findById(requireNotNull(healthy.last().id)).orElseThrow()
        assertThat(persistedPoison.completedAt).isNull()
        assertThat(persistedPoison.attemptCount).isEqualTo(1)
        assertThat(persistedPoison.availableAt).isAfter(Instant.EPOCH)
        assertThat(tail.completedAt).isNotNull()
    }

    @Test
    fun `update token binding rolls back when delivery projection cannot commit`() {
        val fixture = fixture("update-rollback")
        val activity = activityRepository.saveAndFlush(
            ScheduleLiveActivity(
                memberId = fixture.memberId,
                scheduleId = 41,
                pushDeviceTokenId = fixture.deviceTokenId,
                deviceFingerprint = fixture.deviceFingerprint,
                activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
                sourceNotificationGeneration = 3,
                sourceCheckCount = 4,
                contentFingerprint = "b".repeat(64),
                attributesJson = "{}",
                contentStateJson = "{}",
                expiresAt = Instant.parse("2026-08-07T00:00:00Z"),
            )
        )
        doThrow(IllegalStateException("projection unavailable"))
            .`when`(projectionService).enqueueCurrentUpdate(any())

        assertThatThrownBy {
            writer.registerUpdateToken(
                memberId = fixture.memberId,
                deviceFingerprint = fixture.deviceFingerprint,
                scheduleId = 41,
                activityId = "activity-41",
                updateToken = "c".repeat(64),
                updateTokenFingerprint = OpaquePushIdentifier.fingerprint("c".repeat(64)),
                generation = 3,
                presentedSessionGeneration = 9,
            )
        }.isInstanceOf(IllegalStateException::class.java)

        val persisted = activityRepository.findById(requireNotNull(activity.id)).orElseThrow()
        assertThat(persisted.updateToken).isNull()
        assertThat(persisted.updateTokenFingerprint).isNull()
        assertThat(persisted.activityId).isNull()
        assertThat(persisted.state).isEqualTo(ScheduleLiveActivityState.START_PENDING)
    }

    @Test
    fun `in-flight START client reconciliation and all lease releases roll back with projection`() {
        val fixture = fixture("inflight-start-rollback")
        val now = Instant.parse("2026-08-06T00:10:00Z")
        val leaseId = "la-inflight-rollback"
        val leaseUntil = now.plusSeconds(600)
        val startTokenValue = "a".repeat(64)
        val startTokenFingerprint = OpaquePushIdentifier.fingerprint(startTokenValue)
        val startToken = LiveActivityStartToken(
            memberId = fixture.memberId,
            pushDeviceTokenId = fixture.deviceTokenId,
            deviceFingerprint = fixture.deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            pushToStartToken = startTokenValue,
            tokenFingerprint = startTokenFingerprint,
            tokenGeneration = 1,
            lastSeenAt = now,
        )
        assertThat(startToken.acquireDispatchLease(leaseId, now, leaseUntil)).isTrue()
        val startTokenId = requireNotNull(startTokenRepository.saveAndFlush(startToken).id)
        val activity = ScheduleLiveActivity(
            memberId = fixture.memberId,
            scheduleId = 41,
            pushDeviceTokenId = fixture.deviceTokenId,
            deviceFingerprint = fixture.deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            startTokenId = startTokenId,
            startTokenFingerprint = startTokenFingerprint,
            startTokenGeneration = 1,
            sourceNotificationGeneration = 3,
            sourceCheckCount = 4,
            contentRevision = 1,
            contentFingerprint = "b".repeat(64),
            attributesJson = "{}",
            contentStateJson = "{}",
            expiresAt = now.plusSeconds(3_600),
        )
        assertThat(activity.acquireDispatchLease(leaseId, now, leaseUntil)).isTrue()
        val activityId = requireNotNull(activityRepository.saveAndFlush(activity).id)
        val fcm = deviceRepository.findById(fixture.deviceTokenId).orElseThrow()
        assertThat(fcm.acquireDispatchLease(leaseId, now, leaseUntil)).isTrue()
        deviceRepository.saveAndFlush(fcm)
        val delivery = LiveActivityDelivery(
            eventKey = "inflight-start-rollback",
            liveActivityId = activityId,
            memberId = fixture.memberId,
            scheduleId = 41,
            pushDeviceTokenId = fixture.deviceTokenId,
            fcmTokenFingerprint = fcm.tokenFingerprint,
            fcmOwnershipVersion = fcm.ownershipVersion,
            deviceFingerprint = fixture.deviceFingerprint,
            activityToken = startTokenValue,
            activityTokenFingerprint = startTokenFingerprint,
            startTokenId = startTokenId,
            startTokenGeneration = 1,
            operation = LiveActivityDeliveryOperation.START,
            revision = 1,
            sourceNotificationGeneration = 3,
            apnsTimestamp = now.epochSecond,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            attributesJson = "{}",
            contentStateJson = "{}",
            expirationAt = now.plusSeconds(3_600),
            availableAt = now,
        )
        delivery.beginDispatch(now, leaseId, leaseUntil)
        val deliveryId = requireNotNull(deliveryRepository.saveAndFlush(delivery).id)
        doThrow(IllegalStateException("projection unavailable"))
            .`when`(projectionService).enqueueCurrentUpdate(any())

        assertThatThrownBy {
            val updateToken = "c".repeat(64)
            writer.registerUpdateToken(
                memberId = fixture.memberId,
                deviceFingerprint = fixture.deviceFingerprint,
                scheduleId = 41,
                activityId = "activity-41",
                updateToken = updateToken,
                updateTokenFingerprint = OpaquePushIdentifier.fingerprint(updateToken),
                generation = 3,
                presentedSessionGeneration = 9,
            )
        }.isInstanceOf(IllegalStateException::class.java)

        val persistedActivity = activityRepository.findById(activityId).orElseThrow()
        val persistedFcm = deviceRepository.findById(fixture.deviceTokenId).orElseThrow()
        val persistedStart = startTokenRepository.findById(startTokenId).orElseThrow()
        val persistedDelivery = deliveryRepository.findById(deliveryId).orElseThrow()
        assertThat(persistedActivity.state).isEqualTo(ScheduleLiveActivityState.START_PENDING)
        assertThat(persistedActivity.activityId).isNull()
        assertThat(persistedActivity.dispatchLeaseId).isEqualTo(leaseId)
        assertThat(persistedFcm.dispatchLeaseId).isEqualTo(leaseId)
        assertThat(persistedStart.dispatchLeaseId).isEqualTo(leaseId)
        assertThat(persistedDelivery.status).isEqualTo(LiveActivityDeliveryStatus.DISPATCHING)
        assertThat(persistedDelivery.dispatchLeaseId).isEqualTo(leaseId)
    }

    @Test
    fun `start credential registration rolls back when reconciliation cannot commit`() {
        val fixture = fixture("start-rollback")
        doThrow(IllegalStateException("reconcile unavailable"))
            .`when`(projectionService).reconcileStartToken(
                fixture.memberId,
                fixture.deviceFingerprint,
            )

        assertThatThrownBy {
            writer.registerStartToken(
                memberId = fixture.memberId,
                deviceFingerprint = fixture.deviceFingerprint,
                activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
                token = "a".repeat(64),
                tokenFingerprint = OpaquePushIdentifier.fingerprint("a".repeat(64)),
                presentedSessionGeneration = 9,
            )
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(startTokenRepository.findAll()).isEmpty()
    }

    @Test
    fun `identical start credential registration delegates exact-route idempotent reconciliation`() {
        val fixture = fixture("idempotent-start")
        val token = "a".repeat(64)
        repeat(2) {
            writer.registerStartToken(
                memberId = fixture.memberId,
                deviceFingerprint = fixture.deviceFingerprint,
                activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
                token = token,
                tokenFingerprint = OpaquePushIdentifier.fingerprint(token),
                presentedSessionGeneration = 9,
            )
        }

        assertThat(startTokenRepository.findAll()).hasSize(1)
        verify(projectionService, times(2)).reconcileStartToken(
            fixture.memberId,
            fixture.deviceFingerprint,
        )
    }

    @Test
    fun `appearance registration persists per device while legacy omission preserves the last snapshot`() {
        val fixture = fixture("appearance-preservation")
        val token = "a".repeat(64)
        val tokenFingerprint = OpaquePushIdentifier.fingerprint(token)

        writer.registerStartToken(
            memberId = fixture.memberId,
            deviceFingerprint = fixture.deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            token = token,
            tokenFingerprint = tokenFingerprint,
            presentedSessionGeneration = 9,
            appearance = LiveActivityAppearance.DARK,
        )
        val created = startTokenRepository.findAll().single()
        assertThat(created.appearance).isEqualTo(LiveActivityAppearance.DARK)
        assertThat(created.tokenGeneration).isEqualTo(1)

        writer.registerStartToken(
            memberId = fixture.memberId,
            deviceFingerprint = fixture.deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            token = token,
            tokenFingerprint = tokenFingerprint,
            presentedSessionGeneration = 9,
        )
        val afterLegacyRegistration = startTokenRepository.findAll().single()
        assertThat(afterLegacyRegistration.appearance).isEqualTo(LiveActivityAppearance.DARK)
        assertThat(afterLegacyRegistration.tokenGeneration).isEqualTo(1)

        writer.registerStartToken(
            memberId = fixture.memberId,
            deviceFingerprint = fixture.deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            token = token,
            tokenFingerprint = tokenFingerprint,
            presentedSessionGeneration = 9,
            appearance = LiveActivityAppearance.LIGHT,
        )
        val afterAppearanceChange = startTokenRepository.findAll().single()
        assertThat(afterAppearanceChange.appearance).isEqualTo(LiveActivityAppearance.LIGHT)
        assertThat(afterAppearanceChange.tokenGeneration).isEqualTo(1)
    }

    @Test
    fun `expired dispatch lease still blocks start credential rotation until stale recovery`() {
        val fixture = fixture("expired-start-lease")
        val oldToken = "a".repeat(64)
        val startToken = LiveActivityStartToken(
            memberId = fixture.memberId,
            pushDeviceTokenId = fixture.deviceTokenId,
            deviceFingerprint = fixture.deviceFingerprint,
            activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
            pushToStartToken = oldToken,
            lastSeenAt = Instant.EPOCH,
        )
        assertThat(
            startToken.acquireDispatchLease(
                "expired-provider-attempt",
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
            ),
        ).isTrue()
        val startTokenId = requireNotNull(startTokenRepository.saveAndFlush(startToken).id)

        assertThatThrownBy {
            val newToken = "b".repeat(64)
            writer.registerStartToken(
                memberId = fixture.memberId,
                deviceFingerprint = fixture.deviceFingerprint,
                activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
                token = newToken,
                tokenFingerprint = OpaquePushIdentifier.fingerprint(newToken),
                presentedSessionGeneration = 9,
            )
        }.isInstanceOf(ConcurrencyFailureException::class.java)

        val persisted = startTokenRepository.findById(startTokenId).orElseThrow()
        assertThat(persisted.tokenFingerprint)
            .isEqualTo(OpaquePushIdentifier.fingerprint(oldToken))
        assertThat(persisted.dispatchLeaseId).isEqualTo("expired-provider-attempt")
    }

    @Test
    fun `start token retirement rolls back when device END projection cannot commit`() {
        val fixture = fixture("retire-rollback")
        val startToken = startTokenRepository.saveAndFlush(
            LiveActivityStartToken(
                memberId = fixture.memberId,
                pushDeviceTokenId = fixture.deviceTokenId,
                deviceFingerprint = fixture.deviceFingerprint,
                activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
                pushToStartToken = "a".repeat(64),
                lastSeenAt = Instant.parse("2026-08-06T00:00:00Z"),
            )
        )
        doThrow(IllegalStateException("end unavailable"))
            .`when`(projectionService).endForMemberDevice(
                fixture.memberId,
                fixture.deviceFingerprint,
                "START_TOKEN_RETIRED",
            )

        assertThatThrownBy {
            writer.retireStartToken(
                memberId = fixture.memberId,
                deviceFingerprint = fixture.deviceFingerprint,
                activityType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
                presentedSessionGeneration = 9,
            )
        }.isInstanceOf(IllegalStateException::class.java)

        val persisted = startTokenRepository.findById(requireNotNull(startToken.id)).orElseThrow()
        assertThat(persisted.active).isTrue()
        assertThat(persisted.retirementRequested).isFalse()
    }

    private fun fixture(key: String): Fixture {
        val member = memberRepository.saveAndFlush(
            Member(
                name = key,
                password = "Password1!",
                email = "$key@nolate.test",
                loginType = LoginType.COMMON,
                sessionGeneration = 9,
            )
        )
        val memberId = requireNotNull(member.id)
        val deviceFingerprint = OpaquePushIdentifier.fingerprint("device-$key")
        val device = deviceRepository.saveAndFlush(
            NotificationDeviceToken(
                memberId = memberId,
                deviceId = "device-$key",
                platform = PushPlatform.IOS,
                token = "fcm-$key",
                deviceFingerprint = deviceFingerprint,
            )
        )
        return Fixture(memberId, requireNotNull(device.id), deviceFingerprint)
    }

    private data class Fixture(
        val memberId: Long,
        val deviceTokenId: Long,
        val deviceFingerprint: String,
    )
}
