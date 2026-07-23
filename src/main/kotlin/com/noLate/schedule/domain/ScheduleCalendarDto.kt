package com.noLate.schedule.domain

data class ScheduleCalendarDto(
    val id: Long,
    val title: String,
    val color: String,
    val defaultContentMode: ScheduleShareContentMode,
    val status: ScheduleCalendarStatus,
    val ownerMemberId: Long,
    val myRole: ScheduleCalendarRole,
    val memberCount: Int,
    val routeReminderEnabled: Boolean,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

data class ScheduleCalendarMemberDto(
    val id: Long,
    val calendarId: Long,
    val memberId: Long,
    val name: String? = null,
    val email: String? = null,
    val role: ScheduleCalendarRole,
    val status: ScheduleCalendarMemberStatus,
    val routeReminderEnabled: Boolean,
    val joinedAt: String? = null,
    val updatedAt: String? = null,
)
