package com.noLate.schedule.domain

import jakarta.validation.constraints.Min
import java.time.Instant

data class SchedulePushJobCreateRequest(

    @field:Min(1)
    val memberId: Long,

    @field:Min(1)
    val scheduleId: Long,

    val scheduleAt: Instant,

    val departureAt: Instant,

    val monitorStartAt: Instant,

    val intervalMinutes: Int
) {
    fun toEntity(): SchedulePushJob {
        return SchedulePushJob.create(
            memberId = memberId,
            scheduleId = scheduleId,
            scheduleAt = scheduleAt,
            departureAt = departureAt,
            monitorStartAt = monitorStartAt,
            intervalMinutes = intervalMinutes
        )
    }
}

data class SchedulePushJobDto(
    val id: Long?,
    val memberId: Long,
    val scheduleId: Long,
    val scheduleAt: Instant,
    val departureAt: Instant,
    val monitorStartAt: Instant,
    val intervalMinutes: Int,
    val status: SchedulePushJobStatus,
    val nextCheckAt: Instant,
    val lastTravelMinutes: Int?,
    val lastRecommendedDepartureAt: Instant?,
    val lastNotifiedDepartureAt: Instant?,
    val lastCheckedAt: Instant?,
    val lastPushedAt: Instant?,
    val departureNoticeSentAt: Instant?,
    val lastDepartureReminderStage: String?,
    val lastDepartureReminderBoundaryAt: Instant?,
    val snoozedUntil: Instant?,
    val checkCount: Int,
    val retryCount: Int,
    val failureReason: String?,
    val version: Long?
) {
    companion object {
        fun fromEntity(entity: SchedulePushJob): SchedulePushJobDto {
            return SchedulePushJobDto(
                id = entity.id,
                memberId = entity.memberId,
                scheduleId = entity.scheduleId,
                scheduleAt = entity.scheduleAt,
                departureAt = entity.departureAt,
                monitorStartAt = entity.monitorStartAt,
                intervalMinutes = entity.intervalMinutes,
                status = entity.status,
                nextCheckAt = entity.nextCheckAt,
                lastTravelMinutes = entity.lastTravelMinutes,
                lastRecommendedDepartureAt = entity.lastRecommendedDepartureAt,
                lastNotifiedDepartureAt = entity.lastNotifiedDepartureAt,
                lastCheckedAt = entity.lastCheckedAt,
                lastPushedAt = entity.lastPushedAt,
                departureNoticeSentAt = entity.departureNoticeSentAt,
                lastDepartureReminderStage = entity.lastDepartureReminderStage,
                lastDepartureReminderBoundaryAt = entity.lastDepartureReminderBoundaryAt,
                snoozedUntil = entity.snoozedUntil,
                checkCount = entity.checkCount,
                retryCount = entity.retryCount,
                failureReason = entity.failureReason,
                version = entity.version
            )
        }
    }
}
