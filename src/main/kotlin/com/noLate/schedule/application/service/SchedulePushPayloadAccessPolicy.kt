package com.noLate.schedule.application.service

/**
 * Schedule-bound notification payloads use the same access rule at dispatch and cleanup time.
 *
 * A participant who still has schedule-only access may keep navigation/general schedule
 * notifications, while every travel-derived payload requires the stronger travel grant.
 */
internal object SchedulePushPayloadAccessPolicy {
    const val SCHEDULE_PUSH_PAYLOAD_TYPE = "SCHEDULE_PUSH"

    private val travelRequiredPayloadTypes = setOf(
        SCHEDULE_PUSH_PAYLOAD_TYPE,
        "ROUTE_SETUP_REMINDER",
        "SCHEDULE_PARTICIPANT_DEPARTED",
        "SCHEDULE_DEPARTURE_NUDGE",
        "SCHEDULE_DEPARTURE_REMINDER",
        "SCHEDULE_TRAFFIC",
        "DEPARTURE_ADVANCE_NOTICE",
        "DEPARTURE_NOW",
        "DEPARTURE_REMINDER",
        "TRAFFIC_CHANGE",
    )

    fun canDispatch(
        access: ScheduleAccessDecision,
        payloadType: String?,
    ): Boolean =
        access.canView &&
            (payloadType !in travelRequiredPayloadTypes || access.travelEnabled)

    fun shouldDelete(
        access: ScheduleAccessDecision,
        payloadType: String?,
    ): Boolean = !canDispatch(access, payloadType)
}
