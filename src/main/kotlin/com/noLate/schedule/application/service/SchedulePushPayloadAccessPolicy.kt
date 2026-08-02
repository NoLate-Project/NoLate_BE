package com.noLate.schedule.application.service

import com.noLate.schedule.domain.DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE

/**
 * Schedule-bound notification payloads use the same access rule at dispatch and cleanup time.
 *
 * A participant who still has schedule-only access may keep only explicitly allow-listed
 * navigation/general schedule notifications. Legacy null and unknown payload types fail closed:
 * those rows can contain old travel details and therefore require the stronger travel grant.
 */
internal object SchedulePushPayloadAccessPolicy {
    const val SCHEDULE_PUSH_PAYLOAD_TYPE = "SCHEDULE_PUSH"

    private val viewSafePayloadTypes = setOf(
        "SCHEDULE_SHARE_RECEIVED",
        "SCHEDULE_DETAIL",
    )

    fun canDispatch(
        access: ScheduleAccessDecision,
        payloadType: String?,
    ): Boolean =
        access.canView &&
            (payloadType in viewSafePayloadTypes || access.travelEnabled)

    fun shouldDelete(
        access: ScheduleAccessDecision,
        payloadType: String?,
    ): Boolean =
        payloadType != DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE &&
            !canDispatch(access, payloadType)
}
