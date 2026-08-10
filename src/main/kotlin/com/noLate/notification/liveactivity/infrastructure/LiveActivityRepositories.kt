package com.noLate.notification.liveactivity.infrastructure

import com.noLate.notification.liveactivity.domain.LiveActivityDelivery
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryOperation
import com.noLate.notification.liveactivity.domain.LiveActivityDeliveryStatus
import com.noLate.notification.liveactivity.domain.LiveActivityLifecycleIntent
import com.noLate.notification.liveactivity.domain.LiveActivityStartToken
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivity
import com.noLate.notification.liveactivity.domain.ScheduleLiveActivityState
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface LiveActivityLifecycleIntentRepository : JpaRepository<LiveActivityLifecycleIntent, Long> {
    @Query(
        """
        select intent.id from LiveActivityLifecycleIntent intent
        where intent.completedAt is null
          and intent.deadLetteredAt is null
          and intent.availableAt <= :now
        order by intent.availableAt, intent.id
        """
    )
    fun findPendingIds(@Param("now") now: Instant, pageable: Pageable): List<Long>

    @Query(
        """
        select intent.id from LiveActivityLifecycleIntent intent
        where intent.completedAt is not null and intent.completedAt <= :boundary
        order by intent.completedAt, intent.id
        """
    )
    fun findCompletedIds(
        @Param("boundary") boundary: Instant,
        pageable: Pageable,
    ): List<Long>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select intent from LiveActivityLifecycleIntent intent where intent.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): LiveActivityLifecycleIntent?
}

interface LiveActivityStartTokenRepository : JpaRepository<LiveActivityStartToken, Long> {
    @Query(
        """
        select distinct token.memberId
        from LiveActivityStartToken token
        where token.tokenFingerprint = :tokenFingerprint
           or (token.deviceFingerprint = :deviceFingerprint and token.activityType = :activityType)
        order by token.memberId
        """
    )
    fun findRegistrationOwnerMemberIds(
        @Param("tokenFingerprint") tokenFingerprint: String,
        @Param("deviceFingerprint") deviceFingerprint: String,
        @Param("activityType") activityType: String,
    ): List<Long>

    @Query(
        """
        select token.id
        from LiveActivityStartToken token
        where token.tokenFingerprint = :tokenFingerprint
           or (token.deviceFingerprint = :deviceFingerprint and token.activityType = :activityType)
        order by token.id
        """
    )
    fun findRegistrationCandidateIds(
        @Param("tokenFingerprint") tokenFingerprint: String,
        @Param("deviceFingerprint") deviceFingerprint: String,
        @Param("activityType") activityType: String,
    ): List<Long>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from LiveActivityStartToken token where token.id in :ids order by token.id")
    fun findAllByIdsForUpdate(@Param("ids") ids: Collection<Long>): List<LiveActivityStartToken>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from LiveActivityStartToken token where token.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): LiveActivityStartToken?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select token from LiveActivityStartToken token
        where token.memberId = :memberId
          and token.deviceFingerprint = :deviceFingerprint
          and token.activityType = :activityType
        """
    )
    fun findByMemberDeviceTypeForUpdate(
        @Param("memberId") memberId: Long,
        @Param("deviceFingerprint") deviceFingerprint: String,
        @Param("activityType") activityType: String,
    ): LiveActivityStartToken?

    fun findAllByMemberIdAndActivityTypeAndActiveTrueAndRetirementRequestedFalseOrderByIdAsc(
        memberId: Long,
        activityType: String,
    ): List<LiveActivityStartToken>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from LiveActivityStartToken token where token.memberId = :memberId order by token.id")
    fun findAllByMemberIdForUpdate(@Param("memberId") memberId: Long): List<LiveActivityStartToken>

    fun deleteAllByMemberId(memberId: Long)
}

interface ScheduleLiveActivityRepository : JpaRepository<ScheduleLiveActivity, Long> {
    @Query(
        """
        select distinct activity.memberId from ScheduleLiveActivity activity
        where activity.deviceFingerprint = :deviceFingerprint
          and activity.state in :states
        order by activity.memberId
        """
    )
    fun findHandoffOwnerMemberIdsByDeviceFingerprint(
        @Param("deviceFingerprint") deviceFingerprint: String,
        @Param("states") states: Collection<ScheduleLiveActivityState>,
    ): List<Long>

    @Query(
        "select distinct activity.memberId from ScheduleLiveActivity activity " +
            "where activity.scheduleId = :scheduleId order by activity.memberId"
    )
    fun findMemberIdsByScheduleId(@Param("scheduleId") scheduleId: Long): List<Long>

    @Query(
        """
        select distinct activity.memberId from ScheduleLiveActivity activity
        where activity.updateTokenFingerprint = :fingerprint
        order by activity.memberId
        """
    )
    fun findUpdateTokenOwnerMemberIds(@Param("fingerprint") fingerprint: String): List<Long>

    fun findByScheduleIdAndMemberIdAndDeviceFingerprint(
        scheduleId: Long,
        memberId: Long,
        deviceFingerprint: String,
    ): ScheduleLiveActivity?

    fun findByUpdateTokenFingerprint(fingerprint: String): ScheduleLiveActivity?

    fun findByMemberIdAndActivityIdAndDeviceFingerprint(
        memberId: Long,
        activityId: String,
        deviceFingerprint: String,
    ): ScheduleLiveActivity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select activity from ScheduleLiveActivity activity where activity.id in :ids order by activity.id")
    fun findAllByIdsForUpdate(@Param("ids") ids: Collection<Long>): List<ScheduleLiveActivity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select activity from ScheduleLiveActivity activity
        where activity.updateTokenFingerprint = :fingerprint
        """
    )
    fun findByUpdateTokenFingerprintForUpdate(
        @Param("fingerprint") fingerprint: String,
    ): ScheduleLiveActivity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select activity from ScheduleLiveActivity activity where activity.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): ScheduleLiveActivity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select activity from ScheduleLiveActivity activity
        where activity.scheduleId = :scheduleId
          and activity.memberId = :memberId
          and activity.deviceFingerprint = :deviceFingerprint
        """
    )
    fun findByScheduleMemberDeviceForUpdate(
        @Param("scheduleId") scheduleId: Long,
        @Param("memberId") memberId: Long,
        @Param("deviceFingerprint") deviceFingerprint: String,
    ): ScheduleLiveActivity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select activity from ScheduleLiveActivity activity
        where activity.memberId = :memberId
          and activity.activityId = :activityId
          and activity.deviceFingerprint = :deviceFingerprint
        """
    )
    fun findByMemberActivityDeviceForUpdate(
        @Param("memberId") memberId: Long,
        @Param("activityId") activityId: String,
        @Param("deviceFingerprint") deviceFingerprint: String,
    ): ScheduleLiveActivity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select activity from ScheduleLiveActivity activity
        where activity.memberId = :memberId
          and activity.scheduleId = :scheduleId
        order by activity.id
        """
    )
    fun findAllByMemberScheduleForUpdate(
        @Param("memberId") memberId: Long,
        @Param("scheduleId") scheduleId: Long,
    ): List<ScheduleLiveActivity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select activity from ScheduleLiveActivity activity where activity.scheduleId = :scheduleId order by activity.id")
    fun findAllByScheduleIdForUpdate(@Param("scheduleId") scheduleId: Long): List<ScheduleLiveActivity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select activity from ScheduleLiveActivity activity where activity.memberId = :memberId order by activity.id")
    fun findAllByMemberIdForUpdate(@Param("memberId") memberId: Long): List<ScheduleLiveActivity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select activity from ScheduleLiveActivity activity
        where activity.memberId = :memberId
          and activity.deviceFingerprint = :deviceFingerprint
        order by activity.id
        """
    )
    fun findAllByMemberDeviceForUpdate(
        @Param("memberId") memberId: Long,
        @Param("deviceFingerprint") deviceFingerprint: String,
    ): List<ScheduleLiveActivity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select activity from ScheduleLiveActivity activity
        where activity.memberId in :memberIds
          and activity.pushDeviceTokenId in :pushDeviceTokenIds
          and activity.state in :states
        order by activity.pushDeviceTokenId, activity.id
        """
    )
    fun findAllByMembersAndPushTokensAndStatesForUpdate(
        @Param("memberIds") memberIds: Collection<Long>,
        @Param("pushDeviceTokenIds") pushDeviceTokenIds: Collection<Long>,
        @Param("states") states: Collection<ScheduleLiveActivityState>,
    ): List<ScheduleLiveActivity>

    @Query(
        """
        select activity.id from ScheduleLiveActivity activity
        where activity.state in :states and activity.expiresAt <= :now
        order by activity.expiresAt, activity.id
        """
    )
    fun findExpiredCandidateIds(
        @Param("states") states: Collection<ScheduleLiveActivityState>,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<Long>

    @Query(
        """
        select activity.id from ScheduleLiveActivity activity
        where activity.state in :states
          and activity.memberId in (select member.id from Member member where member.deleted = true)
        order by activity.id
        """
    )
    fun findWithdrawnTerminalCandidateIds(
        @Param("states") states: Collection<ScheduleLiveActivityState>,
        pageable: Pageable,
    ): List<Long>

    @Query(
        """
        select activity.id from ScheduleLiveActivity activity
        where activity.state in :states
          and activity.endedAt is not null
          and activity.endedAt <= :boundary
        order by activity.endedAt, activity.id
        """
    )
    fun findTerminalRetentionCandidateIds(
        @Param("states") states: Collection<ScheduleLiveActivityState>,
        @Param("boundary") boundary: Instant,
        pageable: Pageable,
    ): List<Long>

    fun deleteAllByMemberId(memberId: Long)
}

interface LiveActivityDeliveryRepository : JpaRepository<LiveActivityDelivery, Long> {
    @Query(
        """
        select delivery.id from LiveActivityDelivery delivery
        where delivery.status in :statuses and delivery.availableAt <= :now
        order by delivery.availableAt, delivery.id
        """
    )
    fun findDueCandidateIds(
        @Param("statuses") statuses: Collection<LiveActivityDeliveryStatus>,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<Long>

    @Query(
        """
        select delivery.id from LiveActivityDelivery delivery
        where delivery.status = :status and delivery.lastAttemptedAt <= :boundary
        order by delivery.lastAttemptedAt, delivery.id
        """
    )
    fun findStaleDispatchCandidateIds(
        @Param("status") status: LiveActivityDeliveryStatus,
        @Param("boundary") boundary: Instant,
        pageable: Pageable,
    ): List<Long>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delivery from LiveActivityDelivery delivery where delivery.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): LiveActivityDelivery?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delivery from LiveActivityDelivery delivery where delivery.eventKey = :eventKey")
    fun findByEventKeyForUpdate(@Param("eventKey") eventKey: String): LiveActivityDelivery?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select delivery from LiveActivityDelivery delivery
        where delivery.liveActivityId = :liveActivityId
          and delivery.status in :statuses
        order by delivery.id
        """
    )
    fun findAllOpenByActivityForUpdate(
        @Param("liveActivityId") liveActivityId: Long,
        @Param("statuses") statuses: Collection<LiveActivityDeliveryStatus>,
    ): List<LiveActivityDelivery>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select delivery from LiveActivityDelivery delivery
        where delivery.pushDeviceTokenId = :pushDeviceTokenId
          and delivery.operation = :operation
          and delivery.status in :statuses
        order by delivery.id
        """
    )
    fun findAllOpenEndByPushDeviceTokenForUpdate(
        @Param("pushDeviceTokenId") pushDeviceTokenId: Long,
        @Param("operation") operation: LiveActivityDeliveryOperation,
        @Param("statuses") statuses: Collection<LiveActivityDeliveryStatus>,
    ): List<LiveActivityDelivery>

    @Query(
        "select count(delivery) from LiveActivityDelivery delivery " +
            "where delivery.pushDeviceTokenId = :pushDeviceTokenId " +
            "and delivery.operation = :operation and delivery.status in :statuses"
    )
    fun countOpenByPushDeviceToken(
        @Param("pushDeviceTokenId") pushDeviceTokenId: Long,
        @Param("operation") operation: com.noLate.notification.liveactivity.domain.LiveActivityDeliveryOperation,
        @Param("statuses") statuses: Collection<LiveActivityDeliveryStatus>,
    ): Long

    fun deleteAllByLiveActivityId(liveActivityId: Long)

    fun existsByEventKey(eventKey: String): Boolean

    fun deleteAllByMemberId(memberId: Long)
}
